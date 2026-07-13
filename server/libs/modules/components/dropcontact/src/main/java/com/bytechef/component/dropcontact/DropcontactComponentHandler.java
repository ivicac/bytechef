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

package com.bytechef.component.dropcontact;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.dropcontact.action.DropcontactEnrichContactAction;
import com.bytechef.component.dropcontact.connection.DropcontactConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class DropcontactComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("dropcontact")
        .title("Dropcontact")
        .version(1)
        .description("Dropcontact is a B2B contact enrichment and email finding service.")
        .customAction(true)
        .icon("path:assets/dropcontact.svg")
        .categories(ComponentCategory.CRM)
        .connection(DropcontactConnection.CONNECTION_DEFINITION)
        .actions(DropcontactEnrichContactAction.ACTION_DEFINITION)
        .clusterElements(tool(DropcontactEnrichContactAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
