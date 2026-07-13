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

package com.bytechef.component.sendpulse;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.sendpulse.action.SendPulseListMailingListsAction;
import com.bytechef.component.sendpulse.connection.SendPulseConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class SendPulseComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("sendPulse")
        .title("SendPulse")
        .version(1)
        .description("SendPulse is a multi-channel marketing platform for email, SMS, and chatbots.")
        .customAction(true)
        .icon("path:assets/sendpulse.svg")
        .categories(ComponentCategory.MARKETING_AUTOMATION)
        .connection(SendPulseConnection.CONNECTION_DEFINITION)
        .actions(SendPulseListMailingListsAction.ACTION_DEFINITION)
        .clusterElements(tool(SendPulseListMailingListsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
