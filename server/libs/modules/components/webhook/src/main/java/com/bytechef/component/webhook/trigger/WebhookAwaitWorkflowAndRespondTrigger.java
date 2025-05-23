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

package com.bytechef.component.webhook.trigger;

import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.placeholder;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.ComponentDsl.trigger;
import static com.bytechef.component.webhook.constant.WebhookConstants.BODY;
import static com.bytechef.component.webhook.constant.WebhookConstants.CSRF_TOKEN;
import static com.bytechef.component.webhook.constant.WebhookConstants.HEADERS;
import static com.bytechef.component.webhook.constant.WebhookConstants.METHOD;
import static com.bytechef.component.webhook.constant.WebhookConstants.PARAMETERS;

import com.bytechef.component.definition.ComponentDsl.ModifiableTriggerDefinition;
import com.bytechef.component.definition.TriggerDefinition.TriggerType;
import com.bytechef.component.webhook.util.WebhookUtils;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class WebhookAwaitWorkflowAndRespondTrigger {

    public static final ModifiableTriggerDefinition TRIGGER_DEFINITION = trigger("awaitWorkflowAndRespond")
        .title("Await Workflow and Respond")
        .description(
            "You have the flexibility to set up your preferred response. After a webhook request is received, the " +
                "webhook trigger enters a waiting state for the workflow's response.")
        .type(TriggerType.STATIC_WEBHOOK)
        .workflowSyncExecution(true)
        .properties(
            string(CSRF_TOKEN)
                .label("CSRF Token")
                .description(
                    "To trigger the workflow successfully, the security token must match the X-Csrf-Token HTTP " +
                        "header value passed by the client.")
                .required(true),
            integer("timeout")
                .label("Timeout (ms)")
                .description(
                    "The incoming request will time out after the specified number of milliseconds. The max wait " +
                        "time before a timeout is 5 minutes."))
        .output(placeholder(Map.of(METHOD, "POST", HEADERS, Map.of(), PARAMETERS, Map.of(), BODY, Map.of())))
        .webhookRequest(WebhookUtils::getWebhookResult)
        .webhookValidate(WebhookUtils::getWebhookValidate);
}
