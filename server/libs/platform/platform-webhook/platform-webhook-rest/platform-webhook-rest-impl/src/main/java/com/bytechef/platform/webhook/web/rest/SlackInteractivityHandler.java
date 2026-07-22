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

package com.bytechef.platform.webhook.web.rest;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.execution.JobResumeId;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade.JobResumeOutcome;
import com.bytechef.tenant.TenantContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Handles Slack interactivity ({@code block_actions}) callbacks that resolve an approval in place. The Slack approval
 * channel sends in-place Approve/Discard buttons only when the connection carries the app's signing secret; this
 * handler verifies the request's {@code X-Slack-Signature} against every Slack connection carrying a signing secret in
 * the tenant anchored by the tokenized resume id inside the button value, resolves the approval through
 * {@link JobResumeFacade}, and rewrites the originating message via the payload's {@code response_url}.
 *
 * <p>
 * An unverifiable request is rejected without acting — the resume id alone is a capability, but acting on an unsigned
 * interactivity callback would let anyone who saw a resume id forge resolutions attributed to Slack users.
 * </p>
 *
 * @author Ivica Cardic
 */
public class SlackInteractivityHandler {

    static final String ACTION_APPROVE = "approval_approve";
    static final String ACTION_DISCARD = "approval_discard";

    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300;

    private static final Logger log = LoggerFactory.getLogger(SlackInteractivityHandler.class);

    private final ConnectionService connectionService;
    private final JobResumeFacade jobResumeFacade;
    private final RestClient restClient;

    @SuppressFBWarnings("EI")
    public SlackInteractivityHandler(
        ConnectionService connectionService, JobResumeFacade jobResumeFacade, RestClient restClient) {

        this.connectionService = connectionService;
        this.jobResumeFacade = jobResumeFacade;
        this.restClient = restClient;
    }

    public enum Result {
        HANDLED, IGNORED, UNAUTHORIZED
    }

    public Result handle(String rawBody, @Nullable String timestamp, @Nullable String signature) {
        Map<String, ?> payload = parsePayload(rawBody);

        if (payload == null || !Objects.equals(payload.get("type"), "block_actions")) {
            return Result.IGNORED;
        }

        Map<String, ?> action = firstAction(payload);

        if (action == null) {
            return Result.IGNORED;
        }

        String actionId = (String) action.get("action_id");

        boolean approved;

        if (ACTION_APPROVE.equals(actionId)) {
            approved = true;
        } else if (ACTION_DISCARD.equals(actionId)) {
            approved = false;
        } else {
            return Result.IGNORED;
        }

        String resumeId = (String) action.get("value");

        JobResumeId jobResumeId;

        try {
            jobResumeId = JobResumeId.parse(Objects.requireNonNull(resumeId, "value"));
        } catch (Exception exception) {
            return Result.IGNORED;
        }

        if (!isTimestampFresh(timestamp) || signature == null) {
            return Result.UNAUTHORIZED;
        }

        String tenantId = jobResumeId.getTenantId();

        boolean verified = TenantContext.callWithTenantId(
            tenantId, () -> verifySignature(rawBody, timestamp, signature));

        if (!verified) {
            log.warn("Rejected Slack interactivity callback with an unverifiable signature");

            return Result.UNAUTHORIZED;
        }

        JobResumeOutcome outcome = jobResumeFacade.resumeJob(resumeId, Map.of("approved", approved));

        rewriteMessage(payload, outcome, approved);

        return Result.HANDLED;
    }

    private static @Nullable Map<String, ?> parsePayload(String rawBody) {
        for (String parameter : rawBody.split("&")) {
            if (!parameter.startsWith("payload=")) {
                continue;
            }

            String json = URLDecoder.decode(parameter.substring("payload=".length()), StandardCharsets.UTF_8);

            try {
                return JsonUtils.readMap(json);
            } catch (Exception exception) {
                return null;
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, ?> firstAction(Map<String, ?> payload) {
        Object actions = payload.get("actions");

        if (actions instanceof List<?> actionList && !actionList.isEmpty()
            && actionList.getFirst() instanceof Map<?, ?> action) {

            return (Map<String, ?>) action;
        }

        return null;
    }

    private static boolean isTimestampFresh(@Nullable String timestamp) {
        if (timestamp == null) {
            return false;
        }

        long timestampSeconds;

        try {
            timestampSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException numberFormatException) {
            return false;
        }

        Instant now = Instant.now();

        return Math.abs(now.getEpochSecond() - timestampSeconds) <= TIMESTAMP_TOLERANCE_SECONDS;
    }

    /**
     * Verifies the Slack signature ({@code v0=hex(HMAC-SHA256(secret, "v0:{timestamp}:{rawBody}"))}) against every
     * signing secret configured on the current tenant's Slack connections. Constant-time comparison; any match
     * verifies.
     */
    private boolean verifySignature(String rawBody, String timestamp, String signature) {
        String baseString = "v0:" + timestamp + ":" + rawBody;

        byte[] signatureBytes = signature.getBytes(StandardCharsets.UTF_8);

        for (String signingSecret : findSigningSecrets()) {
            String expected = "v0=" + hmacSha256Hex(signingSecret, baseString);

            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signatureBytes)) {
                return true;
            }
        }

        return false;
    }

    private Set<String> findSigningSecrets() {
        Set<String> signingSecrets = new HashSet<>();

        for (PlatformType platformType : PlatformType.values()) {
            try {
                for (Connection connection : connectionService.getConnections("slack", 1, platformType)) {
                    Object signingSecret = connection.getParameters()
                        .get("signingSecret");

                    if (signingSecret instanceof String signingSecretString && !signingSecretString.isBlank()) {
                        signingSecrets.add(signingSecretString);
                    }
                }
            } catch (Exception exception) {
                if (log.isDebugEnabled()) {
                    log.debug("Could not enumerate {} Slack connections: {}", platformType, exception.getMessage());
                }
            }
        }

        return signingSecrets;
    }

    private static String hmacSha256Hex(String secret, String baseString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");

            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            HexFormat hexFormat = HexFormat.of();

            return hexFormat.formatHex(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute the Slack request signature", exception);
        }
    }

    /**
     * Rewrites the originating Slack message through the payload's {@code response_url} so the channel shows the
     * outcome and the buttons stop being actionable. Best-effort — a rewrite failure never fails the resolution.
     */
    @SuppressWarnings("unchecked")
    private void rewriteMessage(Map<String, ?> payload, JobResumeOutcome outcome, boolean approved) {
        String responseUrl = (String) payload.get("response_url");

        if (responseUrl == null || responseUrl.isBlank()) {
            return;
        }

        String userName = null;

        if (payload.get("user") instanceof Map<?, ?> user) {
            Map<String, ?> userMap = (Map<String, ?>) user;

            userName = (String) (userMap.get("username") != null ? userMap.get("username") : userMap.get("name"));
        }

        String by = userName == null ? "" : " by @" + userName;

        String text = switch (outcome) {
            case OK -> (approved ? ":white_check_mark: Approved" : ":no_entry_sign: Discarded") + by + ".";
            case GONE -> ":hourglass: This approval expired before it was resolved.";
            case INVALID_ID -> ":warning: This approval is no longer available.";
        };

        try {
            restClient.post()
                .uri(responseUrl)
                .body(Map.of("replace_original", true, "text", text))
                .retrieve()
                .toBodilessEntity();
        } catch (Exception exception) {
            log.warn("Could not rewrite the Slack approval message: {}", exception.getMessage());
        }
    }
}
