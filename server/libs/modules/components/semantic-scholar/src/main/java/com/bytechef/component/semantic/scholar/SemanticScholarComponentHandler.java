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

package com.bytechef.component.semantic.scholar;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.semantic.scholar.action.SemanticScholarSearchPapersAction;
import com.bytechef.component.semantic.scholar.connection.SemanticScholarConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class SemanticScholarComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("semanticScholar")
        .title("Semantic Scholar")
        .version(1)
        .description("Semantic Scholar is a free, AI-powered research tool for scientific literature.")
        .customAction(true)
        .icon("path:assets/semantic-scholar.svg")
        .categories(ComponentCategory.HELPERS)
        .connection(SemanticScholarConnection.CONNECTION_DEFINITION)
        .actions(SemanticScholarSearchPapersAction.ACTION_DEFINITION)
        .clusterElements(tool(SemanticScholarSearchPapersAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
