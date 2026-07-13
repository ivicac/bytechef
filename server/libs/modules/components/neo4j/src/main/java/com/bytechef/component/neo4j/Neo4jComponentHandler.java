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

package com.bytechef.component.neo4j;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.neo4j.action.Neo4jExecuteQueryAction;
import com.bytechef.component.neo4j.connection.Neo4jConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class Neo4jComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("neo4j")
        .title("Neo4j")
        .version(1)
        .description("Neo4j is a graph database that stores data as nodes and relationships.")
        .customAction(true)
        .icon("path:assets/neo4j.svg")
        .categories(ComponentCategory.DEVELOPER_TOOLS)
        .connection(Neo4jConnection.CONNECTION_DEFINITION)
        .actions(Neo4jExecuteQueryAction.ACTION_DEFINITION)
        .clusterElements(tool(Neo4jExecuteQueryAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
