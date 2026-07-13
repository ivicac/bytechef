/*
 * Copyright 2025 ByteChef
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

package com.bytechef.component.triggerdev;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.triggerdev.action.TriggerDevTriggerTaskAction;
import com.bytechef.component.triggerdev.connection.TriggerDevConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class TriggerDevComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("triggerDev")
        .title("Trigger.dev")
        .version(1)
        .description("Trigger.dev is an open-source platform for background jobs and AI workflows.")
        .customAction(true)
        .icon("path:assets/triggerdev.svg")
        .categories(ComponentCategory.DEVELOPER_TOOLS)
        .connection(TriggerDevConnection.CONNECTION_DEFINITION)
        .actions(TriggerDevTriggerTaskAction.ACTION_DEFINITION)
        .clusterElements(tool(TriggerDevTriggerTaskAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
