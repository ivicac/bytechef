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

package com.bytechef.component.missive;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.missive.action.MissiveCreatePostAction;
import com.bytechef.component.missive.action.MissiveListUsersAction;
import com.bytechef.component.missive.connection.MissiveConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class MissiveComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("missive")
        .title("Missive")
        .version(1)
        .description("Missive is a team inbox and chat app for collaborative email, SMS, and messaging.")
        .customAction(true)
        .icon("path:assets/missive.svg")
        .categories(ComponentCategory.COMMUNICATION)
        .connection(MissiveConnection.CONNECTION_DEFINITION)
        .actions(
            MissiveCreatePostAction.ACTION_DEFINITION,
            MissiveListUsersAction.ACTION_DEFINITION)
        .clusterElements(
            tool(MissiveCreatePostAction.ACTION_DEFINITION),
            tool(MissiveListUsersAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
