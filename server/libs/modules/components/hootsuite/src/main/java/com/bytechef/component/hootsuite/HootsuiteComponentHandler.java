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

package com.bytechef.component.hootsuite;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.hootsuite.action.HootsuiteCreateMessageAction;
import com.bytechef.component.hootsuite.action.HootsuiteListSocialProfilesAction;
import com.bytechef.component.hootsuite.connection.HootsuiteConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class HootsuiteComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("hootsuite")
        .title("Hootsuite")
        .version(1)
        .description("Hootsuite is a social media management platform for scheduling and publishing posts.")
        .customAction(true)
        .icon("path:assets/hootsuite.svg")
        .categories(ComponentCategory.SOCIAL_MEDIA)
        .connection(HootsuiteConnection.CONNECTION_DEFINITION)
        .actions(
            HootsuiteCreateMessageAction.ACTION_DEFINITION,
            HootsuiteListSocialProfilesAction.ACTION_DEFINITION)
        .clusterElements(
            tool(HootsuiteCreateMessageAction.ACTION_DEFINITION),
            tool(HootsuiteListSocialProfilesAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
