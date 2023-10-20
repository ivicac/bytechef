
/*
 * Copyright 2021 <your company/name>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.hermes.connection;

import com.bytechef.atlas.domain.Workflow;

import java.util.List;
import java.util.Optional;

/**
 * @author Ivica Cardic
 */
public record WorkflowConnection(String componentName, int connectionVersion, Long id) {

    public static List<WorkflowConnection> of(Workflow workflow) {
        return workflow.getTasks()
            .stream()
            .map(workflowTask -> workflowTask.fetchExtension(WorkflowConnection.class))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .toList();
    }
}
