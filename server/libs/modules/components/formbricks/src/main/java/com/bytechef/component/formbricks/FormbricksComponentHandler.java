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

package com.bytechef.component.formbricks;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.formbricks.action.FormbricksListSurveysAction;
import com.bytechef.component.formbricks.connection.FormbricksConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class FormbricksComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("formbricks")
        .title("Formbricks")
        .version(1)
        .description("Formbricks is an open-source survey and experience management platform.")
        .customAction(true)
        .icon("path:assets/formbricks.svg")
        .categories(ComponentCategory.SURVEYS_AND_FEEDBACK)
        .connection(FormbricksConnection.CONNECTION_DEFINITION)
        .actions(FormbricksListSurveysAction.ACTION_DEFINITION)
        .clusterElements(tool(FormbricksListSurveysAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
