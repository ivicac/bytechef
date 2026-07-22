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

package com.bytechef.component.approval.cluster;

import static com.bytechef.component.definition.approval.ApprovalChannelFunction.APPROVAL_CHANNELS;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.EXPIRES_AT;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.FORM_DESCRIPTION;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.FORM_TITLE;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.INPUTS;
import static com.bytechef.platform.ai.constant.AiAgentSseEventType.APPROVAL_REQUEST;
import static com.bytechef.platform.ai.constant.AiAgentSseEventType.EVENT_TYPE;

import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import com.bytechef.component.definition.approval.ApprovalChannelFunction;
import com.bytechef.message.broker.MessageBroker;
import com.bytechef.platform.component.definition.ClusterElementContextAware;
import com.bytechef.platform.webhook.event.SseStreamEvent;
import com.bytechef.platform.webhook.message.route.SseStreamMessageRoute;
import com.bytechef.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers an approval request into the chat conversation that started the workflow run, as an {@code approval_request}
 * SSE data event on the run's job stream. Connected chat clients (workflow chat, embedded chat) render the event as an
 * inline approval card; the card resolves through the standard tokenized approval-resolution endpoint, so typing in the
 * chat never resolves the approval.
 *
 * <p>
 * The channel is only meaningful for runs that have a live chat origin — a run started by webhook or schedule has no
 * chat stream listening, so pair this channel with a fallback channel (Slack, email, approval task) for such runs.
 * </p>
 *
 * @author Ivica Cardic
 */
public class ChatApprovalChannel {

    private ChatApprovalChannel() {
    }

    public static ClusterElementDefinition<ApprovalChannelFunction> of(MessageBroker messageBroker) {
        return ComponentDsl.<ApprovalChannelFunction>clusterElement("chat")
            .title("Chat")
            .description(
                "Delivers the approval request as an inline card in the chat conversation that started the workflow " +
                    "run. Pair with a fallback channel for runs not started from chat.")
            .type(APPROVAL_CHANNELS)
            .object(
                () -> (inputParameters, connectionParameters, formUrl, context) -> perform(
                    inputParameters, formUrl, context, messageBroker));
    }

    static Object perform(
        Parameters inputParameters, String formUrl, ClusterElementContext context, MessageBroker messageBroker) {

        Long jobId = ((ClusterElementContextAware) context).getJobId();

        if (jobId == null) {
            throw new IllegalStateException(
                "The chat approval channel requires a running job with a chat origin. Configure a fallback approval " +
                    "channel for runs that are not started from a chat conversation.");
        }

        Map<String, Object> eventData = buildApprovalRequestEventData(inputParameters, formUrl);

        SseStreamEvent sseStreamEvent = new SseStreamEvent(jobId, SseStreamEvent.EVENT_TYPE_DATA, eventData);

        sseStreamEvent.putMetadata(TenantContext.CURRENT_TENANT_ID, TenantContext.getCurrentTenantId());

        messageBroker.send(SseStreamMessageRoute.SSE_STREAM_EVENTS, sseStreamEvent);

        return null;
    }

    /**
     * Builds the {@code approval_request} data-event payload rendered as an inline approval card by chat surfaces. The
     * payload carries the resume id and hosted-form URL plus the optional form title, description, and input fields.
     * Shared with the approval action's editor-run emission, which delivers the same card onto the workflow test stream
     * without going through a channel.
     */
    public static Map<String, Object> buildApprovalRequestEventData(Parameters inputParameters, String formUrl) {
        Map<String, Object> eventData = new LinkedHashMap<>();

        eventData.put(EVENT_TYPE, APPROVAL_REQUEST);
        eventData.put("resumeId", formUrl.substring(formUrl.lastIndexOf('/') + 1));
        eventData.put("formUrl", formUrl);

        String formTitle = inputParameters.getString(FORM_TITLE);

        if (formTitle != null) {
            eventData.put(FORM_TITLE, formTitle);
        }

        String formDescription = inputParameters.getString(FORM_DESCRIPTION);

        if (formDescription != null) {
            eventData.put(FORM_DESCRIPTION, formDescription);
        }

        String expiresAt = inputParameters.getString(EXPIRES_AT);

        if (expiresAt != null) {
            eventData.put(EXPIRES_AT, expiresAt);
        }

        List<Map<String, ?>> inputs = inputParameters.getList(INPUTS, new TypeReference<>() {}, List.of());

        eventData.put(INPUTS, inputs);

        return eventData;
    }
}
