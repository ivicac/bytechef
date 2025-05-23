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

package com.bytechef.component.twilio;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.twilio.action.TwilioSendSMSAction;
import com.bytechef.component.twilio.action.TwilioSendWhatsAppMessageAction;
import com.bytechef.component.twilio.connection.TwilioConnection;
import com.bytechef.component.twilio.trigger.TwilioNewWhatsappMessageTrigger;
import com.google.auto.service.AutoService;

/**
 * @author Monika Kušter
 */
@AutoService(ComponentHandler.class)
public class TwilioComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("twilio")
        .title("Twilio")
        .description(
            "Twilio is a cloud communications platform that enables developers to integrate messaging, voice, and " +
                "video capabilities into their applications.")
        .customAction(true)
        .icon("path:assets/twilio.svg")
        .categories(ComponentCategory.COMMUNICATION)
        .connection(TwilioConnection.CONNECTION_DEFINITION)
        .actions(
            TwilioSendSMSAction.ACTION_DEFINITION,
            TwilioSendWhatsAppMessageAction.ACTION_DEFINITION)
        .clusterElements(
            tool(TwilioSendSMSAction.ACTION_DEFINITION),
            tool(TwilioSendWhatsAppMessageAction.ACTION_DEFINITION))
        .triggers(TwilioNewWhatsappMessageTrigger.TRIGGER_DEFINITION);

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
