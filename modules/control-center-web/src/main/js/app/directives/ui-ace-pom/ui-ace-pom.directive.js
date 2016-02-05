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

import template from './ui-ace-pom.jade!';
import controller from './ui-ace-pom.controller';

export default ['igniteUiAcePom', [() => {
    const link = ($scope, $el, $attrs, [igniteUiAce]) => {
        if (igniteUiAce.onLoad)
            $scope.onLoad = igniteUiAce.onLoad;

        if (igniteUiAce.onChange)
            $scope.onChange = igniteUiAce.onChange;
    };

    return {
        restrict: 'E',
        scope: {
            cluster: '='
        },
        link,
        template,
        controller,
        controllerAs: 'ctrl',
        require: ['?^igniteUiAce']
    };
}]];
