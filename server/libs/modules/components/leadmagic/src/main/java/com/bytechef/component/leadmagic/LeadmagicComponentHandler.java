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

package com.bytechef.component.leadmagic;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.leadmagic.action.LeadmagicFindEmailAction;
import com.bytechef.component.leadmagic.action.LeadmagicValidateEmailAction;
import com.bytechef.component.leadmagic.connection.LeadmagicConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class LeadmagicComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("leadmagic")
        .title("LeadMagic")
        .version(1)
        .description("LeadMagic is a B2B data enrichment platform for finding and validating contact data.")
        .customAction(true)
        .icon("path:assets/leadmagic.svg")
        .categories(ComponentCategory.MARKETING_AUTOMATION)
        .connection(LeadmagicConnection.CONNECTION_DEFINITION)
        .actions(
            LeadmagicFindEmailAction.ACTION_DEFINITION,
            LeadmagicValidateEmailAction.ACTION_DEFINITION)
        .clusterElements(
            tool(LeadmagicFindEmailAction.ACTION_DEFINITION),
            tool(LeadmagicValidateEmailAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
