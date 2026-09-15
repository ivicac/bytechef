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

package com.bytechef.platform.webhook.web.websocket;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.platform.component.context.ContextFactory;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessor;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import com.bytechef.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Mints and validates short-lived single-use tokens that authorize a browser to open a production-webhook voice
 * WebSocket against {@code wss://host/webhooks/(webhookId)/wss}.
 *
 * <p>
 * Sibling of {@link WorkflowTestVoiceSessionTokenService}; the test variant is keyed by workflowId and gated by the
 * standard {@code /internal/**} session cookie. This production variant is keyed by webhookId and lives under
 * {@code /webhooks/**} which is permit-all today — security comes from the single-use semantics of the token plus any
 * webhook-signature configuration on the trigger itself.
 *
 * @author Ivica Cardic
 */
@Service
public class BrowserVoiceSessionTokenService {

    private static final Logger log = LoggerFactory.getLogger(BrowserVoiceSessionTokenService.class);

    private static final String VOICE_SESSION_TRIGGER_TYPE = "browser/v1/voiceSession";

    private static final long TOKEN_TTL_SECONDS = 60L;

    private final Cache<String, String> tokenToWebhookId = Caffeine.newBuilder()
        .expireAfterWrite(TOKEN_TTL_SECONDS, TimeUnit.SECONDS)
        .maximumSize(100000)
        .build();

    private final ObjectProvider<ContextFactory> contextFactoryProvider;
    private final JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry;
    private final VoiceMetricsRecorder metricsRecorder;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public BrowserVoiceSessionTokenService(
        JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry, VoiceMetricsRecorder metricsRecorder,
        WorkflowService workflowService, ObjectProvider<ContextFactory> contextFactoryProvider) {

        this.contextFactoryProvider = contextFactoryProvider;
        this.jobPrincipalAccessorRegistry = jobPrincipalAccessorRegistry;
        this.metricsRecorder = metricsRecorder;
        this.workflowService = workflowService;
    }

    /**
     * Issues a token for {@code webhookId}, after verifying it resolves to a deployed workflow whose trigger type is
     * WEBSOCKET. Throws {@link InvalidWebhookException} for any failure so the controller can return a 4xx instead of a
     * permissive 200 that mints a token for a non-voice or non-existent webhook.
     */
    public Token issue(String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            metricsRecorder.recordTokenRejected();

            throw new InvalidWebhookException("Missing webhookId");
        }

        if (contextFactoryProvider.getIfAvailable() == null) {
            // A token minted here would only fail at start: the Voice Agent element needs a component context this node
            // does not have (the distributed EE webhook-app). Refuse at mint with a clear reason instead.
            metricsRecorder.recordTokenRejected();

            throw new VoiceUnavailableException(
                "Browser voice sessions are not available on this node; route voice traffic to a node that runs " +
                    "the full component runtime");
        }

        WorkflowExecutionId workflowExecutionId;

        try {
            workflowExecutionId = WorkflowExecutionId.parse(webhookId);
        } catch (RuntimeException runtimeException) {
            metricsRecorder.recordTokenRejected();

            throw new InvalidWebhookException(
                "Webhook is not a browser-voice trigger or does not exist: " + webhookId);
        }

        // The deployment and its workflow live in the webhook's tenant, as for an HTTP webhook delivery. Eligibility
        // is decided inside the block and returned, never thrown: TenantContext rewraps anything that escapes it.
        Eligibility eligibility = TenantContext.callWithTenantId(
            workflowExecutionId.getTenantId(), () -> eligibility(workflowExecutionId, webhookId));

        if (eligibility == Eligibility.NOT_VOICE) {
            metricsRecorder.recordTokenRejected();

            throw new InvalidWebhookException(
                "Webhook is not a browser-voice trigger or does not exist: " + webhookId);
        }

        if (eligibility == Eligibility.DISABLED) {
            metricsRecorder.recordTokenRejected();

            throw new InvalidWebhookException("The workflow behind this webhook is disabled: " + webhookId);
        }

        String token = UUID.randomUUID()
            .toString();

        tokenToWebhookId.put(token, webhookId);

        metricsRecorder.recordTokenIssued();

        return new Token(token, TOKEN_TTL_SECONDS);
    }

    /**
     * Cheap-enough check: resolves the webhookId to its workflow, verifies the trigger type, then that the deployment
     * has the workflow enabled — the check an HTTP webhook delivery makes too. Treats any resolution failure (missing
     * workflow, missing trigger) as not voice-capable, and logs the reason at debug so misconfigured webhook URLs can
     * be diagnosed without leaking detail in 4xx responses. Must run in the webhook's tenant.
     */
    private Eligibility eligibility(WorkflowExecutionId workflowExecutionId, String webhookId) {
        try {
            JobPrincipalAccessor jobPrincipalAccessor = jobPrincipalAccessorRegistry.getJobPrincipalAccessor(
                workflowExecutionId.getType());

            String workflowId = jobPrincipalAccessor.getWorkflowId(
                workflowExecutionId.getJobPrincipalId(), workflowExecutionId.getWorkflowUuid());

            Workflow workflow = workflowService.getWorkflow(workflowId);

            List<WorkflowTrigger> triggers = WorkflowTrigger.of(workflow);

            // Only a browser voice session trigger accepts a voice WebSocket. Whether it holds a Voice Agent is checked
            // when the session starts, so a trigger missing one fails with a hint instead of a refused token.
            WorkflowTrigger matchingTrigger = triggers.stream()
                .filter(trigger -> trigger.getName()
                    .equals(workflowExecutionId.getTriggerName()))
                .findFirst()
                .orElse(null);

            if (matchingTrigger == null || !VOICE_SESSION_TRIGGER_TYPE.equals(matchingTrigger.getType())) {
                return Eligibility.NOT_VOICE;
            }

            boolean enabled = jobPrincipalAccessor.isWorkflowEnabled(
                workflowExecutionId.getJobPrincipalId(), workflowExecutionId.getWorkflowUuid());

            return enabled ? Eligibility.VOICE : Eligibility.DISABLED;
        } catch (Exception exception) {
            if (log.isDebugEnabled()) {
                log.debug("Voice-capability check failed for webhookId={}: {}", webhookId, exception.getMessage());
            }

            return Eligibility.NOT_VOICE;
        }
    }

    private enum Eligibility {

        DISABLED, NOT_VOICE, VOICE
    }

    /**
     * Consume the token and verify it was issued for {@code webhookId}. Returns true on first call with a matching
     * pair; subsequent calls (and mismatched webhookIds) return false.
     */
    public boolean consume(String token, String webhookId) {
        if (token == null || webhookId == null) {
            metricsRecorder.recordTokenRejected();

            return false;
        }

        String issuedFor = tokenToWebhookId.getIfPresent(token);

        if (issuedFor == null || !issuedFor.equals(webhookId)) {
            metricsRecorder.recordTokenRejected();

            return false;
        }

        tokenToWebhookId.invalidate(token);
        metricsRecorder.recordTokenConsumed();

        return true;
    }

    public record Token(String token, long expiresInSeconds) {
    }

    /**
     * Voice sessions cannot run on this node at all, whatever the webhook: the distributed EE {@code webhook-app}
     * carries the voice endpoints but not the component context the Voice Agent element needs.
     */
    public static class VoiceUnavailableException extends RuntimeException {

        public VoiceUnavailableException(String message) {
            super(message);
        }
    }

    public static class InvalidWebhookException extends RuntimeException {

        public InvalidWebhookException(String message) {
            super(message);
        }
    }
}
