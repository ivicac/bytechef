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

package com.bytechef.component.devin;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.devin.action.DevinCreateSessionAction;
import com.bytechef.component.devin.connection.DevinConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class DevinComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("devin")
        .title("Devin")
        .version(1)
        .description("Devin is an AI software engineer by Cognition that autonomously completes coding tasks.")
        .customAction(true)
        .icon("path:assets/devin.svg")
        .categories(ComponentCategory.ARTIFICIAL_INTELLIGENCE)
        .connection(DevinConnection.CONNECTION_DEFINITION)
        .actions(DevinCreateSessionAction.ACTION_DEFINITION)
        .clusterElements(tool(DevinCreateSessionAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
