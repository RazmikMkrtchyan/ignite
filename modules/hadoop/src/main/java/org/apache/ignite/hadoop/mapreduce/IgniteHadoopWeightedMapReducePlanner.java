/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.hadoop.mapreduce;

import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.igfs.IgfsBlockLocation;
import org.apache.ignite.igfs.IgfsPath;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.hadoop.HadoopFileBlock;
import org.apache.ignite.internal.processors.hadoop.HadoopInputSplit;
import org.apache.ignite.internal.processors.hadoop.HadoopJob;
import org.apache.ignite.internal.processors.hadoop.HadoopMapReducePlan;
import org.apache.ignite.internal.processors.hadoop.HadoopUtils;
import org.apache.ignite.internal.processors.hadoop.igfs.HadoopIgfsEndpoint;
import org.apache.ignite.internal.processors.hadoop.planner.HadoopAbstractMapReducePlanner;
import org.apache.ignite.internal.processors.hadoop.planner.HadoopDefaultMapReducePlan;
import org.apache.ignite.internal.processors.hadoop.planner.HadoopMapReducePlanGroup;
import org.apache.ignite.internal.processors.hadoop.planner.HadoopMapReducePlanTopology;
import org.apache.ignite.internal.processors.igfs.IgfsEx;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.ignite.IgniteFileSystem.IGFS_SCHEME;

/**
 * Map-reduce planner which tries to assign map jobs to affinity nodes.
 */
// TODO: Docs.
public class IgniteHadoopWeightedMapReducePlanner extends HadoopAbstractMapReducePlanner {
    /** Default local mapper weight. */
    public static final int DFLT_LOC_MAPPER_WEIGHT = 100;

    /** Default remote mapper weight. */
    public static final int DFLT_RMT_MAPPER_WEIGHT = 120;

    /** Default local reducer weight. */
    public static final int DFLT_LOC_REDUCER_WEIGHT = 100;

    /** Default remote reducer weight. */
    public static final int DFLT_RMT_REDUCER_WEIGHT = 120;

    /** Default reducer migration threshold weight. */
    public static final int DFLT_REDUCER_MIGRATION_THRESHOLD_WEIGHT = 1000;

    /** Local mapper weight. */
    private int locMapperWeight = DFLT_LOC_MAPPER_WEIGHT;

    /** Remote mapper weight. */
    private int rmtMapperWeight = DFLT_RMT_MAPPER_WEIGHT;

    /** Local reducer weight. */
    private int locReducerWeight = DFLT_LOC_REDUCER_WEIGHT;

    /** Remote reducer weight. */
    private int rmtReducerWeight = DFLT_RMT_REDUCER_WEIGHT;

    /** Reducer migration threshold weight. */
    private int reducerMigrationThresholdWeight = DFLT_REDUCER_MIGRATION_THRESHOLD_WEIGHT;

    /** {@inheritDoc} */
    @Override public HadoopMapReducePlan preparePlan(HadoopJob job, Collection<ClusterNode> nodes,
        @Nullable HadoopMapReducePlan oldPlan) throws IgniteCheckedException {
        List<HadoopInputSplit> splits = HadoopUtils.sortInputSplits(job.input());
        int reducerCnt = job.info().reducers();

        if (reducerCnt < 0)
            throw new IgniteCheckedException("Number of reducers must be non-negative, actual: " + reducerCnt);

        HadoopMapReducePlanTopology top = topology(nodes);

        Mappers mappers = assignMappers(splits, top);

        Map<UUID, int[]> reducers = assignReducers(splits, top, mappers, reducerCnt);

        return new HadoopDefaultMapReducePlan(mappers.nodeToSplits, reducers);
    }

    /**
     * Assign mappers to nodes.
     *
     * @param splits Input splits.
     * @param top Topology.
     * @return Mappers.
     * @throws IgniteCheckedException If failed.
     */
    private Mappers assignMappers(Collection<HadoopInputSplit> splits,
        HadoopMapReducePlanTopology top) throws IgniteCheckedException {
        Mappers res = new Mappers();

        for (HadoopInputSplit split : splits) {
            // Try getting IGFS affinity.
            Collection<UUID> nodeIds = affinityNodesForSplit(split, top);

            // Get best node.
            UUID node = bestMapperNode(nodeIds, top);

            res.add(split, node);
        }

        return res;
    }

    /**
     * Get affinity nodes for the given input split.
     *
     * @param split Split.
     * @param top Topology.
     * @return Affintiy nodes.
     * @throws IgniteCheckedException If failed.
     */
    private Collection<UUID> affinityNodesForSplit(HadoopInputSplit split, HadoopMapReducePlanTopology top)
        throws IgniteCheckedException {
        Collection<UUID> nodeIds = igfsAffinityNodesForSplit(split);

        if (nodeIds == null) {
            nodeIds = new HashSet<>();

            for (String host : split.hosts()) {
                HadoopMapReducePlanGroup grp = top.groupForHost(host);

                if (grp != null) {
                    for (int i = 0; i < grp.nodeCount(); i++)
                        nodeIds.add(grp.node(i).id());
                }
            }
        }

        return nodeIds;
    }

    /**
     * Get IGFS affinity nodes for split if possible.
     *
     * @param split Input split.
     * @return IGFS affinity or {@code null} if IGFS is not available.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable private Collection<UUID> igfsAffinityNodesForSplit(HadoopInputSplit split) throws IgniteCheckedException {
        if (split instanceof HadoopFileBlock) {
            HadoopFileBlock split0 = (HadoopFileBlock)split;

            if (IGFS_SCHEME.equalsIgnoreCase(split0.file().getScheme())) {
                HadoopIgfsEndpoint endpoint = new HadoopIgfsEndpoint(split0.file().getAuthority());

                IgfsEx igfs = null;

                if (F.eq(ignite.name(), endpoint.grid()))
                    igfs = (IgfsEx)((IgniteEx)ignite).igfsx(endpoint.igfs());

                if (igfs != null && !igfs.isProxy(split0.file())) {
                    IgfsPath path = new IgfsPath(split0.file());

                    if (igfs.exists(path)) {
                        Collection<IgfsBlockLocation> blocks;

                        try {
                            blocks = igfs.affinity(path, split0.start(), split0.length());
                        }
                        catch (IgniteException e) {
                            throw new IgniteCheckedException("Failed to get IGFS file block affinity [path=" + path +
                                ", start=" + split0.start() + ", len=" + split0.length() + ']', e);
                        }

                        assert blocks != null;

                        if (blocks.size() == 1)
                            return blocks.iterator().next().nodeIds();
                        else {
                            // The most "local" nodes go first.
                            Map<UUID, Long> idToLen = new HashMap<>();

                            for (IgfsBlockLocation block : blocks) {
                                for (UUID id : block.nodeIds()) {
                                    Long len = idToLen.get(id);

                                    idToLen.put(id, len == null ? block.length() : block.length() + len);
                                }
                            }

                            Map<NodeIdAndLength, UUID> res = new TreeMap<>();

                            for (Map.Entry<UUID, Long> idToLenEntry : idToLen.entrySet()) {
                                UUID id = idToLenEntry.getKey();

                                res.put(new NodeIdAndLength(id, idToLenEntry.getValue()), id);
                            }

                            return new HashSet<>(res.values());
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Find best mapper node.
     *
     * @param affIds Affinity node IDs.
     * @param top Topology.
     * @return Result.
     */
    private UUID bestMapperNode(@Nullable Collection<UUID> affIds, HadoopMapReducePlanTopology top) {
        // Priority node.
        UUID priorityAffId = F.first(affIds);

        // Find group with the least weight.
        HadoopMapReducePlanGroup resGrp = null;
        MapperPriority resPrio = MapperPriority.NORMAL;
        int resWeight = Integer.MAX_VALUE;

        for (HadoopMapReducePlanGroup grp : top.groups()) {
            MapperPriority priority = groupPriority(grp, affIds, priorityAffId);

            int weight = grp.weight() +
                (priority == MapperPriority.NORMAL ? rmtMapperWeight : locMapperWeight);

            if (resGrp == null || weight < resWeight || weight == resWeight && priority.value() > resPrio.value()) {
                resGrp = grp;
                resPrio = priority;
                resWeight = weight;
            }
        }

        assert resGrp != null;

        // Update group weight for further runs.
        resGrp.weight(resWeight);

        // Return the best node from the group.
        return bestMapperNodeForGroup(resGrp, resPrio, affIds, priorityAffId);
    }

    /**
     * Get best node in the group.
     *
     * @param grp Group.
     * @param priority Priority.
     * @param affIds Affinity IDs.
     * @param priorityAffId Priority affinitiy IDs.
     * @return Best node ID in the group.
     */
    private static UUID bestMapperNodeForGroup(HadoopMapReducePlanGroup grp, MapperPriority priority,
        @Nullable Collection<UUID> affIds, @Nullable UUID priorityAffId) {
        // Return the best node from the group.
        int idx = 0;

        // This is rare situation when several nodes are started on the same host.
        if (!grp.single()) {
            switch (priority) {
                case NORMAL: {
                    // Pick any node.
                    idx = ThreadLocalRandom.current().nextInt(grp.nodeCount());

                    break;
                }
                case HIGH: {
                    // Pick any affinity node.
                    assert affIds != null;

                    List<Integer> cands = new ArrayList<>();

                    for (int i = 0; i < grp.nodeCount(); i++) {
                        UUID id = grp.node(i).id();

                        if (affIds.contains(id))
                            cands.add(i);
                    }

                    idx = cands.get(ThreadLocalRandom.current().nextInt(cands.size()));

                    break;
                }
                default: {
                    // Find primary node.
                    assert priorityAffId != null;

                    for (int i = 0; i < grp.nodeCount(); i++) {
                        UUID id = grp.node(i).id();

                        if (F.eq(id, priorityAffId)) {
                            idx = i;

                            break;
                        }
                    }

                    assert priority == MapperPriority.HIGHEST;
                }
            }
        }

        return grp.node(idx).id();
    }

    /**
     * Generate reducers.
     *
     * @param splits Input splits.
     * @param top Topology.
     * @param mappers Mappers.
     * @param reducerCnt Reducer count.
     * @return Reducers.
     * @throws IgniteCheckedException If failed.
     */
    private Map<UUID, int[]> assignReducers(Collection<HadoopInputSplit> splits, HadoopMapReducePlanTopology top,
        Mappers mappers, int reducerCnt) throws IgniteCheckedException {
        Map<UUID, Integer> reducers = assignReducers0(top, splits, mappers, reducerCnt);

        int cnt = 0;

        Map<UUID, int[]> res = new HashMap<>(reducers.size());

        for (Map.Entry<UUID, Integer> reducerEntry : reducers.entrySet()) {
            int[] arr = new int[reducerEntry.getValue()];

            for (int i = 0; i < arr.length; i++)
                arr[i] = cnt++;

            res.put(reducerEntry.getKey(), arr);
        }

        assert reducerCnt == cnt;

        return res;
    }

    /**
     * Generate reducers.
     *
     * @param top Topology.
     * @param splits Input splits.
     * @param mappers Mappers.
     * @param reducerCnt Reducer count.
     * @return Reducers.
     * @throws IgniteCheckedException If failed.
     */
    private Map<UUID, Integer> assignReducers0(HadoopMapReducePlanTopology top, Collection<HadoopInputSplit> splits,
        Mappers mappers, int reducerCnt)
        throws IgniteCheckedException {
        Map<UUID, Integer> res = new HashMap<>();

        // Assign reducers to splits.
        Map<HadoopInputSplit, Integer> splitToReducerCnt = assignReducersToSplits(splits, reducerCnt);

        // Assign as much local reducers as possible.
        int remaining = 0;

        for (Map.Entry<HadoopInputSplit, Integer> entry : splitToReducerCnt.entrySet()) {
            HadoopInputSplit split = entry.getKey();
            int cnt = entry.getValue();

            if (cnt > 0) {
                int assigned = assignLocalReducers(split, cnt, top, mappers, res);

                remaining += cnt - assigned;
            }
        }

        // Assign the rest reducers.
        if (remaining > 0)
            assignRemoteReducers(remaining, top, mappers, res);

        return res;
    }

    /**
     * Assign local split reducers.
     *
     * @param split Split.
     * @param cnt Reducer count.
     * @param top Topology.
     * @param mappers Mappers.
     * @param resMap Reducers result map.
     * @return Number of assigned reducers.
     */
    private int assignLocalReducers(HadoopInputSplit split, int cnt, HadoopMapReducePlanTopology top, Mappers mappers,
        Map<UUID, Integer> resMap) {
        // Dereference node.
        UUID nodeId = mappers.splitToNode.get(split);

        assert nodeId != null;

        // Dereference group.
        HadoopMapReducePlanGroup grp = top.groupForId(nodeId);

        assert grp != null;

        // Assign more reducers to the node until threshold is reached.
        int res = 0;

        while (res < cnt && grp.weight() < reducerMigrationThresholdWeight) {
            res++;

            grp.weight(grp.weight() + locReducerWeight);
        }

        // Update result map.
        if (res > 0)
            resMap.put(nodeId, res);

        return res;
    }

    /**
     * Assign remote reducers. Assign to the least loaded first.
     *
     * @param cnt Count.
     * @param top Topology.
     * @param mappers Mappers.
     * @param resMap Reducers result map.
     */
    private void assignRemoteReducers(int cnt, HadoopMapReducePlanTopology top, Mappers mappers,
        Map<UUID, Integer> resMap) {

        TreeSet<HadoopMapReducePlanGroup> set = new TreeSet<>(new GroupWeightComparator());

        set.addAll(top.groups());

        while (cnt-- > 0) {
            HadoopMapReducePlanGroup grp = set.first();

            // Look for affinity nodes.
            List<UUID> affIds = null;

            for (int i = 0; i < grp.nodeCount(); i++) {
                UUID nodeId = grp.node(i).id();

                if (mappers.nodeToSplits.containsKey(nodeId)) {
                    if (affIds == null)
                        affIds = new ArrayList<>(2);

                    affIds.add(nodeId);
                }
            }

            // Select best node.
            UUID id;
            int newWeight;

            if (affIds != null) {
                id = affIds.get(ThreadLocalRandom.current().nextInt(affIds.size()));

                newWeight = grp.weight() + locReducerWeight;
            }
            else {
                id = grp.node(ThreadLocalRandom.current().nextInt(grp.nodeCount())).id();

                newWeight = grp.weight() + rmtReducerWeight;
            }

            // Re-add entry with new weight.
            boolean rmv = set.remove(grp);

            assert rmv;

            grp.weight(newWeight);

            boolean add = set.add(grp);

            assert add;

            // Update result map.
            Integer res = resMap.get(id);

            resMap.put(id, res == null ? 1 : res + 1);
        }
    }

    /**
     * Comparator based on group's weight.
     */
    private static class GroupWeightComparator implements Comparator<HadoopMapReducePlanGroup> {
        /** {@inheritDoc} */
        @Override public int compare(HadoopMapReducePlanGroup first, HadoopMapReducePlanGroup second) {
            int res = second.weight() - first.weight();

            if (res < 0)
                return -1;
            else if (res > 0)
                return 1;

            return first.macs().compareTo(second.macs());
        }
    }

    /**
     * Distribute reducers between splits.
     *
     * @param splits Splits.
     * @param reducerCnt Reducer count.
     * @return Map from input split to reducer count.
     */
    private Map<HadoopInputSplit, Integer> assignReducersToSplits(Collection<HadoopInputSplit> splits,
        int reducerCnt) {
        Map<HadoopInputSplit, Integer> res = new IdentityHashMap<>(splits.size());

        int base = reducerCnt / splits.size();

        int remainder = reducerCnt - base * splits.size();

        for (HadoopInputSplit split : splits) {
            int val = base;

            if (remainder > 0) {
                val++;

                remainder--;
            }

            res.put(split, val);
        }

        assert remainder == 0;

        return res;
    }

    /**
     * Calculate group priority.
     *
     * @param grp Group.
     * @param affIds Affintiy IDs.
     * @param priorityAffId Priority affinity ID.
     * @return Group priority.
     */
    private static MapperPriority groupPriority(HadoopMapReducePlanGroup grp, @Nullable Collection<UUID> affIds,
        @Nullable UUID priorityAffId) {
        MapperPriority priority = MapperPriority.NORMAL;

        if (!F.isEmpty(affIds)) {
            for (int i = 0; i < grp.nodeCount(); i++) {
                UUID id = grp.node(i).id();

                if (affIds.contains(id)) {
                    priority = MapperPriority.HIGH;

                    if (F.eq(priorityAffId, id)) {
                        priority = MapperPriority.HIGHEST;

                        break;
                    }
                }
            }
        }

        return priority;
    }

    /**
     * Get local mapper weight.
     *
     * @return Remote mapper weight.
     */
    // TODO: Docs.
    public int getLocalMapperWeight() {
        return locMapperWeight;
    }

    /**
     * Set local mapper weight. See {@link #getLocalMapperWeight()} for more information.
     *
     * @param locMapperWeight Local mapper weight.
     */
    public void setLocalMapperWeight(int locMapperWeight) {
        this.locMapperWeight = locMapperWeight;
    }

    /**
     * Get remote mapper weight.
     *
     * @return Remote mapper weight.
     */
    // TODO: Docs.
    public int getRemoteMapperWeight() {
        return rmtMapperWeight;
    }

    /**
     * Set remote mapper weight. See {@link #getRemoteMapperWeight()} for more information.
     *
     * @param rmtMapperWeight Remote mapper weight.
     */
    public void setRemoteMapperWeight(int rmtMapperWeight) {
        this.rmtMapperWeight = rmtMapperWeight;
    }

    /**
     * Get local reducer weight.
     *
     * @return Local reducer weight.
     */
    // TODO: Docs.
    public int getLocalReducerWeight() {
        return locReducerWeight;
    }

    /**
     * Set local reducer weight. See {@link #getLocalReducerWeight()} for more information.
     *
     * @param locReducerWeight Local reducer weight.
     */
    public void setLocalReducerWeight(int locReducerWeight) {
        this.locReducerWeight = locReducerWeight;
    }

    /**
     * Get remote reducer weight.
     *
     * @return Remote reducer weight.
     */
    // TODO: Docs.
    public int getRemoteReducerWeight() {
        return rmtMapperWeight;
    }

    /**
     * Set remote reducer weight. See {@link #getRemoteReducerWeight()} for more information.
     *
     * @param rmtMapperWeight Remote reducer weight.
     */
    public void setRemoteReducerWeight(int rmtMapperWeight) {
        this.rmtMapperWeight = rmtMapperWeight;
    }

    /**
     * Get reducer migration threshold weight.
     *
     * @return Reducer migration threshold weight.
     */
    // TODO: Docs.
    public int getReducerMigrationThresholdWeight() {
        return reducerMigrationThresholdWeight;
    }

    /**
     * Set reducer migration threshold weight. See {@link #getReducerMigrationThresholdWeight()} for more information.
     *
     * @param reducerMigrationThresholdWeight Reducer migration threshold weight.
     */
    public void setReducerMigrationThresholdWeight(int reducerMigrationThresholdWeight) {
        this.reducerMigrationThresholdWeight = reducerMigrationThresholdWeight;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(IgniteHadoopWeightedMapReducePlanner.class, this);
    }

    /**
     * Node ID and length.
     */
    private static class NodeIdAndLength implements Comparable<NodeIdAndLength> {
        /** Node ID. */
        private final UUID id;

        /** Length. */
        private final long len;

        /**
         * Constructor.
         *
         * @param id Node ID.
         * @param len Length.
         */
        public NodeIdAndLength(UUID id, long len) {
            this.id = id;
            this.len = len;
        }

        /** {@inheritDoc} */
        @SuppressWarnings("NullableProblems")
        @Override public int compareTo(NodeIdAndLength obj) {
            long res = len - obj.len;

            if (res > 0)
                return 1;
            else if (res < 0)
                return -1;
            else
                return id.compareTo(obj.id);
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            return id.hashCode();
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object obj) {
            return obj instanceof NodeIdAndLength && F.eq(id, ((NodeIdAndLength)obj).id);
        }
    }

    /**
     * Mappers.
     */
    private static class Mappers {
        /** Node-to-splits map. */
        private final Map<UUID, Collection<HadoopInputSplit>> nodeToSplits = new HashMap<>();

        /** Split-to-node map. */
        private final Map<HadoopInputSplit, UUID> splitToNode = new IdentityHashMap<>();

        /**
         * Add mapping.
         *
         * @param split Split.
         * @param node Node.
         */
        public void add(HadoopInputSplit split, UUID node) {
            Collection<HadoopInputSplit> nodeSplits = nodeToSplits.get(node);

            if (nodeSplits == null) {
                nodeSplits = new HashSet<>();

                nodeToSplits.put(node, nodeSplits);
            }

            nodeSplits.add(split);

            splitToNode.put(split, node);
        }
    }

    /**
     * Mapper priority enumeration.
     */
    private enum MapperPriority {
        /** Normal node. */
        NORMAL(0),

        /** Affinity node. */
        HIGH(1),

        /** Node with the highest priority (e.g. because it hosts more data than other nodes). */
        HIGHEST(2);

        /** Value. */
        private final int val;

        /**
         * Constructor.
         *
         * @param val Value.
         */
        MapperPriority(int val) {
            this.val = val;
        }

        /**
         * @return Value.
         */
        public int value() {
            return val;
        }
    }
}