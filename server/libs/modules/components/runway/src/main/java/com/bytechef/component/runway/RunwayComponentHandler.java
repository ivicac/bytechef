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

package com.bytechef.component.runway;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.runway.action.RunwayGetOrganizationAction;
import com.bytechef.component.runway.action.RunwayGetTaskAction;
import com.bytechef.component.runway.connection.RunwayConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class RunwayComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("runway")
        .title("Runway")
        .version(1)
        .description("Runway provides AI video and image generation models via API.")
        .customAction(true)
        .icon("path:assets/runway.svg")
        .categories(ComponentCategory.ARTIFICIAL_INTELLIGENCE)
        .connection(RunwayConnection.CONNECTION_DEFINITION)
        .actions(
            RunwayGetOrganizationAction.ACTION_DEFINITION,
            RunwayGetTaskAction.ACTION_DEFINITION)
        .clusterElements(
            tool(RunwayGetOrganizationAction.ACTION_DEFINITION),
            tool(RunwayGetTaskAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
