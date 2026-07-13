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

package com.bytechef.component.email.octopus;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.email.octopus.action.EmailOctopusCreateContactAction;
import com.bytechef.component.email.octopus.action.EmailOctopusListListsAction;
import com.bytechef.component.email.octopus.connection.EmailOctopusConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class EmailOctopusComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("emailOctopus")
        .title("EmailOctopus")
        .version(1)
        .description("EmailOctopus is an email marketing platform for creating campaigns and managing subscribers.")
        .customAction(true)
        .icon("path:assets/email-octopus.svg")
        .categories(ComponentCategory.MARKETING_AUTOMATION)
        .connection(EmailOctopusConnection.CONNECTION_DEFINITION)
        .actions(
            EmailOctopusCreateContactAction.ACTION_DEFINITION,
            EmailOctopusListListsAction.ACTION_DEFINITION)
        .clusterElements(
            tool(EmailOctopusCreateContactAction.ACTION_DEFINITION),
            tool(EmailOctopusListListsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
