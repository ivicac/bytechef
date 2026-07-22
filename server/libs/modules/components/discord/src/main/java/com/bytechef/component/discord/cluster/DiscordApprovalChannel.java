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

package com.bytechef.component.discord.cluster;

import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.APPROVAL_CHANNELS;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.EXPIRES_AT;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.FORM_DESCRIPTION;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.FORM_TITLE;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.INPUTS;
import static com.bytechef.component.discord.constant.DiscordConstants.CONTENT;
import static com.bytechef.component.discord.constant.DiscordConstants.GUILD_ID;

import com.bytechef.component.definition.ActionDefinition.OptionsFunction;
import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.component.definition.ComponentDsl.ModifiableClusterElementDefinition;
import com.bytechef.component.definition.Context.Http;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import com.bytechef.component.definition.approval.ApprovalChannelFunction;
import com.bytechef.component.discord.util.DiscordUtils;
import java.util.List;
import java.util.Map;

/**
 * Approval channel that posts the request to a Discord channel. Field-less approvals get one-click Approve/Discard link
 * buttons (the hosted form auto-resolves via the {@code approved} query parameter); approvals with form fields get a
 * single "Open Approval Form" link button.
 *
 * @author Ivica Cardic
 */
public class DiscordApprovalChannel {

    private static final String CHANNEL_ID = "channelId";

    public static final ModifiableClusterElementDefinition<ApprovalChannelFunction> CLUSTER_ELEMENT_DEFINITION =
        ComponentDsl.<ApprovalChannelFunction>clusterElement("discord")
            .title("Discord")
            .description("Sends an approval request message to a Discord channel.")
            .type(APPROVAL_CHANNELS)
            .properties(
                string(GUILD_ID)
                    .label("Guild ID")
                    .description("ID of the guild (server) containing the channel.")
                    .options((OptionsFunction<String>) DiscordUtils::getGuildIdOptions)
                    .required(true),
                string(CHANNEL_ID)
                    .label("Channel ID")
                    .description("ID of the channel where to send the approval request.")
                    .options((OptionsFunction<String>) DiscordUtils::getChannelIdOptions)
                    .optionsLookupDependsOn(GUILD_ID)
                    .required(true))
            .object(() -> DiscordApprovalChannel::perform);

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static Object perform(
        Parameters inputParameters, Parameters connectionParameters, String formUrl, ClusterElementContext context) {

        String channelId = inputParameters.getRequiredString(CHANNEL_ID);

        List<Map<String, ?>> inputs = inputParameters.getList(INPUTS, new TypeReference<>() {}, List.of());

        String content = buildSummaryText(inputParameters);

        Http.Body body;

        if (formUrl == null || formUrl.isBlank()) {
            body = Http.Body.of(
                CONTENT, content + "\nThe approval form link is unavailable because no public URL is configured.");
        } else {
            List<Map<String, Object>> buttons;

            if (inputs.isEmpty()) {
                buttons = List.of(
                    linkButton("Approve", formUrl + "?approved=true"),
                    linkButton("Discard", formUrl + "?approved=false"));
            } else {
                buttons = List.of(linkButton("Open Approval Form", formUrl));
            }

            body = Http.Body.of(
                CONTENT, content,
                "components", List.of(Map.of("type", 1, "components", buttons)));
        }

        return context
            .http(http -> http.post("/channels/" + channelId + "/messages"))
            .body(body)
            .configuration(responseType(Http.ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }

    private static Map<String, Object> linkButton(String label, String url) {
        return Map.of("type", 2, "style", 5, "label", label, "url", url);
    }

    private static String buildSummaryText(Parameters inputParameters) {
        StringBuilder builder = new StringBuilder();

        String formTitle = inputParameters.getString(FORM_TITLE);

        if (formTitle != null && !formTitle.isBlank()) {
            builder.append("**")
                .append(formTitle.trim())
                .append("**");
        }

        String formDescription = inputParameters.getString(FORM_DESCRIPTION);

        if (formDescription != null && !formDescription.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }

            builder.append(formDescription.trim());
        }

        if (builder.isEmpty()) {
            builder.append("You have a new approval request.");
        }

        String expiresAt = inputParameters.getString(EXPIRES_AT);

        if (expiresAt != null && !expiresAt.isBlank()) {
            builder.append("\nExpires: ")
                .append(expiresAt);
        }

        return builder.toString();
    }
}
