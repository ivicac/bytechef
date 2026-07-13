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

package com.bytechef.component.google.mybusiness;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.google.mybusiness.action.GoogleMyBusinessListAccountsAction;
import com.bytechef.component.google.mybusiness.connection.GoogleMyBusinessConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class GoogleMyBusinessComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("googleMyBusiness")
        .title("Google My Business")
        .version(1)
        .description("Google Business Profile (formerly Google My Business) manages business listings on Google.")
        .customAction(true)
        .icon("path:assets/google-my-business.svg")
        .categories(ComponentCategory.MARKETING_AUTOMATION)
        .connection(GoogleMyBusinessConnection.CONNECTION_DEFINITION)
        .actions(GoogleMyBusinessListAccountsAction.ACTION_DEFINITION)
        .clusterElements(tool(GoogleMyBusinessListAccountsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
