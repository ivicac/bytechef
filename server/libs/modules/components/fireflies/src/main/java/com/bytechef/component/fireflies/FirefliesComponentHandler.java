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

package com.bytechef.component.fireflies;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.fireflies.action.FirefliesGetTranscriptsAction;
import com.bytechef.component.fireflies.connection.FirefliesConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class FirefliesComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("fireflies")
        .title("Fireflies.ai")
        .version(1)
        .description("Fireflies.ai records, transcribes and summarizes meetings with AI.")
        .customAction(true)
        .icon("path:assets/fireflies.svg")
        .categories(ComponentCategory.ARTIFICIAL_INTELLIGENCE)
        .connection(FirefliesConnection.CONNECTION_DEFINITION)
        .actions(FirefliesGetTranscriptsAction.ACTION_DEFINITION)
        .clusterElements(tool(FirefliesGetTranscriptsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
