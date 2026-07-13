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

package com.bytechef.component.linkup;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.linkup.action.LinkupSearchAction;
import com.bytechef.component.linkup.connection.LinkupConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class LinkupComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("linkup")
        .title("Linkup")
        .version(1)
        .description(
            "Linkup is an AI-powered search engine that provides sourced answers and search results from the web.")
        .customAction(true)
        .icon("path:assets/linkup.svg")
        .categories(ComponentCategory.ARTIFICIAL_INTELLIGENCE)
        .connection(LinkupConnection.CONNECTION_DEFINITION)
        .actions(LinkupSearchAction.ACTION_DEFINITION)
        .clusterElements(tool(LinkupSearchAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
