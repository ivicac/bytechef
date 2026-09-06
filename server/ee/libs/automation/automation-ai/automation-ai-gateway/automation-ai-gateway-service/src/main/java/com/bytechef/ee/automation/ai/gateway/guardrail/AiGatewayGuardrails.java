/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.guardrail;

import com.bytechef.ee.automation.ai.gateway.service.AiGatewayProjectSettingsService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProjectSettings;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionResponse;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.exception.AiGatewayGuardrailException;
import com.bytechef.ee.platform.ai.gateway.guardrail.AiGatewayInjectionClassifier;
import com.bytechef.ee.platform.ai.gateway.guardrail.AiGatewayModerationClassifier;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrailMetrics;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails;
import com.bytechef.ee.platform.ai.guardrails.StreamingResponseRedactor;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * AI Gateway adapter over the standalone {@link AiGuardrails} engine (extracted to {@code platform-ai-guardrails}).
 * Preserves the exact pre-extraction public surface — chat/embedding DTO methods and {@code projectId} overloads — so
 * every caller of this class (the gateway facade, and this module's own tests) keeps working unchanged. The
 * {@code redactPii}/{@code redactSecrets}/{@code redactAll} static delegates this class used to expose were removed
 * once the detector SPI turned the engine's own static redactors into instance methods: this adapter has always had an
 * {@link AiGuardrails} instance to call them on directly, and nothing outside this module's own tests ever called the
 * delegates, so keeping them would only have been dead public API.
 *
 * <p>
 * The engine resolves the global-property + workspace-settings union for plain text; this adapter owns everything the
 * engine deliberately does not: gateway DTO shapes (chat messages, completion responses), model-based moderation (never
 * moved — it is a gateway/chat-only concept), and the project-level guardrail overlay. The overlay is additive (a
 * project can only enable a guardrail or add blocked terms, never turn one off) — for each call, this adapter first
 * delegates the text to the engine (global + workspace policy), then layers any project-only additions on top using the
 * engine's own {@code redactPii}/{@code redactSecrets} instance methods and its own moderation/injection classifiers.
 * </p>
 *
 * <p>
 * The gateway does not consult {@link AiGuardrailsWorkspaceSettings#blockingMode()} — a blocked request always throws
 * {@link AiGatewayGuardrailException}, which the gateway's exception handling turns into an HTTP 422.
 * Redact-and-continue blocking mode is a concept for other (non-gateway) surfaces that consume the standalone engine
 * directly.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class AiGatewayGuardrails {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayGuardrails.class);

    private final AiGuardrails aiGuardrails;
    private final @Nullable AiGatewayProjectSettingsService aiGatewayProjectSettingsService;
    private final AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService;
    private final @Nullable AiGatewayModerationClassifier moderationClassifier;
    private final @Nullable AiGatewayInjectionClassifier injectionClassifier;
    private final @Nullable AiGuardrailMetrics metrics;
    private final boolean globalModerationEnabled;
    private final boolean globalStreamingResponseScanEnabled;

    public AiGatewayGuardrails(
        AiGuardrails aiGuardrails,
        @Nullable AiGatewayProjectSettingsService aiGatewayProjectSettingsService,
        AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService,
        @Nullable AiGatewayModerationClassifier moderationClassifier,
        @Nullable AiGatewayInjectionClassifier injectionClassifier,
        @Nullable AiGuardrailMetrics metrics,
        // Property names kept for compatibility with the pre-extraction AiGatewayGuardrails — moderation and the
        // streaming operator gate are gateway-only concerns that never moved into the engine, so this adapter reads
        // them directly rather than asking the engine for them.
        @Value("${bytechef.ai.gateway.guardrails.moderation-enabled:false}") boolean moderationEnabled,
        @Value("${bytechef.ai.gateway.guardrails.response-scan-streaming-enabled:false}") boolean streamingResponseScanEnabled) {

        this.aiGuardrails = aiGuardrails;
        this.aiGatewayProjectSettingsService = aiGatewayProjectSettingsService;
        this.aiGuardrailsWorkspaceSettingsService = aiGuardrailsWorkspaceSettingsService;
        this.moderationClassifier = moderationClassifier;
        this.injectionClassifier = injectionClassifier;
        this.metrics = metrics;
        this.globalModerationEnabled = moderationEnabled;
        this.globalStreamingResponseScanEnabled = streamingResponseScanEnabled;
    }

    /**
     * Returns a fresh token session for one HTTP exchange, so the facade can thread it through both
     * {@link #apply(AiGatewayChatCompletionRequest, Long, Long, PiiTokenSession)} and
     * {@link #redactResponse(AiGatewayChatCompletionResponse, Long, Long, PiiTokenSession)}. The caller owns closing it
     * on every termination path, including a downstream exception — see {@link PiiTokenSession}'s javadoc.
     *
     * @return the session
     */
    public PiiTokenSession newTokenSession() {
        return aiGuardrails.newTokenSession();
    }

    /**
     * Returns the chat-completion request with request-direction guardrails applied for the given workspace: PII and
     * secrets redacted, blocked terms rejected, and — when enabled and a classifier is available — content flagged by
     * moderation or injection detection rejected. Returns the request unchanged when no guardrail is active.
     *
     * @param request     the inbound chat-completion request
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed (global
     *                    guardrails still apply)
     * @return the guardrailed request
     * @throws AiGatewayGuardrailException if a message contains a blocked term or is flagged by moderation or injection
     *                                     detection
     */
    public AiGatewayChatCompletionRequest apply(
        AiGatewayChatCompletionRequest request, @Nullable Long workspaceId) {

        return apply(request, workspaceId, null);
    }

    /**
     * As {@link #apply(AiGatewayChatCompletionRequest, Long)}, additionally layering the project's guardrail overrides
     * (union semantics) on top of the workspace/global policy.
     *
     * @param request     the inbound chat-completion request
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @param projectId   the project the request is attributed to, or {@code null} when none
     * @return the guardrailed request
     * @throws AiGatewayGuardrailException if a message contains a blocked term or is flagged by moderation or injection
     *                                     detection
     */
    public AiGatewayChatCompletionRequest apply(
        AiGatewayChatCompletionRequest request, @Nullable Long workspaceId, @Nullable Long projectId) {

        return apply(request, workspaceId, projectId, null);
    }

    /**
     * As {@link #apply(AiGatewayChatCompletionRequest, Long, Long)}, additionally tokenizing PII into {@code session}'s
     * reversible tokens instead of redacting it irreversibly — secrets are still redacted irreversibly either way, and
     * blocked-term/moderation/injection rejection is unchanged. Pass {@code null} for a detached call with no session
     * to thread (the facade cannot always create one, e.g. an internal caller outside one HTTP exchange); behaviour is
     * then byte-for-byte {@link #apply(AiGatewayChatCompletionRequest, Long, Long)}.
     *
     * @param request     the inbound chat-completion request
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @param projectId   the project the request is attributed to, or {@code null} when none
     * @param session     the session minting PII tokens for this exchange, or {@code null} to redact PII irreversibly
     *                    as today
     * @return the guardrailed request
     * @throws AiGatewayGuardrailException if a message contains a blocked term or is flagged by moderation or injection
     *                                     detection
     */
    public AiGatewayChatCompletionRequest apply(
        AiGatewayChatCompletionRequest request, @Nullable Long workspaceId, @Nullable Long projectId,
        @Nullable PiiTokenSession session) {

        AiGatewayProjectSettings projectSettings = findProjectSettings(projectId);
        boolean moderate = moderationClassifier != null && resolveModerationEnabled(workspaceId, projectSettings);

        List<AiGatewayChatMessage> guardrailedMessages = new ArrayList<>();
        boolean changed = false;

        for (AiGatewayChatMessage message : request.messages()) {
            String content = message.content();
            String processed = content;

            if (content != null) {
                processed = redactOrTokenize(content, workspaceId, session);
                processed = applyProjectOverlay(processed, projectSettings, workspaceId);
            }

            if (moderate && processed != null && moderationClassifier.isFlagged(processed)) {
                record("moderation_flagged");

                log.warn("AI Gateway request rejected by moderation classifier");

                throw new AiGatewayGuardrailException("Request rejected by content moderation");
            }

            if (!Objects.equals(processed, content)) {
                changed = true;
            }

            guardrailedMessages.add(
                new AiGatewayChatMessage(
                    message.role(), processed, message.contentBlocks(), message.toolCalls(), message.toolCallId()));
        }

        if (!changed) {
            return request;
        }

        return new AiGatewayChatCompletionRequest(
            request.model(), guardrailedMessages, request.temperature(), request.maxTokens(), request.topP(),
            request.stream(), request.routingPolicy(), request.cache(), request.toolChoice(), request.tools(),
            request.tags());
    }

    /**
     * Redacts (when {@code session} is {@code null}) or tokenizes (otherwise) PII/secrets in one message's content for
     * the request path via {@link AiGuardrails#applyToInputs(List, Long, PiiTokenSession)}, which already throws
     * {@link AiGatewayGuardrailException} for a blocked term or flagged injection and never checks moderation (that
     * stays this adapter's own concern, applied separately in {@link #apply}) regardless of whether {@code session} is
     * {@code null} — so this method adds no behaviour of its own beyond the single delegating call.
     */
    private String redactOrTokenize(String content, @Nullable Long workspaceId, @Nullable PiiTokenSession session) {
        return aiGuardrails.applyToInputs(List.of(content), workspaceId, session)
            .getFirst();
    }

    /**
     * Returns the embedding inputs with request-direction guardrails applied: PII and secrets redacted, blocked terms
     * and injection attempts rejected. Moderation is intentionally not run on embedding inputs (they are documents /
     * records, not conversational prompts). Returns the inputs unchanged when no relevant guardrail is active.
     *
     * @param inputs      the embedding input strings
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @return the guardrailed inputs
     * @throws AiGatewayGuardrailException if an input contains a blocked term or is flagged by injection detection
     */
    public List<String> applyToInputs(List<String> inputs, @Nullable Long workspaceId) {
        return applyToInputs(inputs, workspaceId, null);
    }

    /**
     * As {@link #applyToInputs(List, Long)}, additionally layering the project's guardrail overrides on top of the
     * workspace/global policy.
     *
     * @param inputs      the embedding input strings
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @param projectId   the project the request is attributed to, or {@code null} when none
     * @return the guardrailed inputs
     * @throws AiGatewayGuardrailException if an input contains a blocked term or is flagged by injection detection
     */
    public List<String> applyToInputs(List<String> inputs, @Nullable Long workspaceId, @Nullable Long projectId) {
        if (inputs == null || inputs.isEmpty()) {
            return inputs;
        }

        List<String> engineProcessed = aiGuardrails.applyToInputs(inputs, workspaceId);
        AiGatewayProjectSettings projectSettings = findProjectSettings(projectId);

        if (projectSettings == null) {
            return engineProcessed;
        }

        List<String> guardrailedInputs = new ArrayList<>(engineProcessed.size());
        boolean changed = false;

        for (String input : engineProcessed) {
            String processed = applyProjectOverlay(input, projectSettings, workspaceId);

            if (!processed.equals(input)) {
                changed = true;
            }

            guardrailedInputs.add(processed);
        }

        return changed ? guardrailedInputs : engineProcessed;
    }

    /**
     * Returns the completion response with each choice's message content redacted for PII and secrets when response
     * scanning is enabled for the workspace (or globally), otherwise the response unchanged. Response scanning is
     * redaction only — it never blocks — so a caller always receives an answer, just with internal data masked. Applies
     * to the non-streaming completion path; the SSE streaming path emits tokens incrementally where a value can
     * straddle chunk boundaries, so it is not scanned. Tool-call arguments are left untouched so function calling is
     * not corrupted.
     *
     * @param response    the completion response
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @return the response with redacted content, or the original when response scanning is inactive or nothing matched
     */
    public AiGatewayChatCompletionResponse redactResponse(
        AiGatewayChatCompletionResponse response, @Nullable Long workspaceId) {

        return redactResponse(response, workspaceId, null);
    }

    /**
     * As {@link #redactResponse(AiGatewayChatCompletionResponse, Long)}, additionally honoring the project's response
     * scanning override. Scan-only — identical to {@link #scanResponse(AiGatewayChatCompletionResponse, Long, Long)},
     * kept as its own public entry point for callers with no session to restore through (this overload predates
     * tokenization).
     *
     * @param response    the completion response
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @param projectId   the project the request is attributed to, or {@code null} when none
     * @return the response with redacted content, or the original when response scanning is inactive or nothing matched
     */
    public AiGatewayChatCompletionResponse redactResponse(
        AiGatewayChatCompletionResponse response, @Nullable Long workspaceId, @Nullable Long projectId) {

        return scanResponse(response, workspaceId, projectId);
    }

    /**
     * As {@link #redactResponse(AiGatewayChatCompletionResponse, Long, Long)}, additionally restoring {@code
     * session}'s tokens back to their real values after scanning when {@code session} is not {@code null}. A thin
     * composition of {@link #scanResponse(AiGatewayChatCompletionResponse, Long, Long)} followed by
     * {@link #restoreResponse(AiGatewayChatCompletionResponse, PiiTokenSession)} — kept as one call for callers (this
     * adapter's own tests, and any future caller) that do not need anything to happen between the two steps.
     *
     * <p>
     * <b>The AI Gateway facade does NOT use this combined method for its live request path.</b> It calls
     * {@link #scanResponse} and {@link #restoreResponse} separately with tracing sandwiched in between, so that a
     * gateway trace/span records the response as the provider actually produced it — scanned for genuinely new
     * PII/secrets, but with this exchange's own tokens still in place — rather than the real values restoration would
     * otherwise have already substituted back in by the time tracing ran. See
     * {@code AiGatewayFacadeImpl#chatCompletion} and the design spec's "scan → trace → restore" ordering note.
     * </p>
     *
     * @param response    the completion response
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @param projectId   the project the request is attributed to, or {@code null} when none
     * @param session     the session that tokenized this exchange's request, or {@code null} to redact PII irreversibly
     *                    as today
     * @return the response with redacted content and/or restored tokens, or the original when nothing changed
     */
    public AiGatewayChatCompletionResponse redactResponse(
        AiGatewayChatCompletionResponse response, @Nullable Long workspaceId, @Nullable Long projectId,
        @Nullable PiiTokenSession session) {

        return restoreResponse(scanResponse(response, workspaceId, projectId), session);
    }

    /**
     * Returns the completion response with each choice's message content scanned for PII and secrets when response
     * scanning is enabled for the workspace (or globally) or the project overrides it on, otherwise the response
     * unchanged. This is the SCAN half only — no session tokens are restored, so a caller that also tokenized the
     * request gets back a response that still carries this exchange's own {@code [PII_*]} tokens verbatim (a token
     * never matches a PII/secret pattern, so scanning never disturbs it). Records {@code response_redacted} when
     * scanning masked anything in any choice.
     *
     * <p>
     * Split out from the combined {@link #redactResponse(AiGatewayChatCompletionResponse, Long, Long, PiiTokenSession)}
     * so a caller can trace/log the response exactly as scanned, before restoration puts this exchange's real PII
     * values back in — see that method's javadoc and {@code AiGatewayFacadeImpl#chatCompletion}, the one caller that
     * needs the two steps kept apart.
     * </p>
     *
     * @param response    the completion response
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @param projectId   the project the request is attributed to, or {@code null} when none
     * @return the scanned response, or the original when response scanning is inactive or nothing matched
     */
    public AiGatewayChatCompletionResponse scanResponse(
        AiGatewayChatCompletionResponse response, @Nullable Long workspaceId, @Nullable Long projectId) {

        if (response == null || response.choices() == null || response.choices()
            .isEmpty()) {

            return response;
        }

        AiGatewayProjectSettings projectSettings = findProjectSettings(projectId);
        boolean projectScanResponses = projectSettings != null && Boolean.TRUE.equals(projectSettings.scanResponses());

        List<AiGatewayChatCompletionResponse.Choice> choices = response.choices();
        List<AiGatewayChatCompletionResponse.Choice> scannedChoices = new ArrayList<>(choices.size());
        boolean responseRedacted = false;
        boolean changed = false;

        for (AiGatewayChatCompletionResponse.Choice choice : choices) {
            AiGatewayChatMessage message = choice.message();
            String content = message == null ? null : message.content();

            if (content == null) {
                scannedChoices.add(choice);

                continue;
            }

            String scanned = aiGuardrails.scanResponseText(content, workspaceId);

            if (projectScanResponses) {
                // 2026-08-31 final-branch-review fix: this project-only extra scan used to run at
                // SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE regardless of the workspace's own threshold, unlike
                // scanResponseText just above, which already resolves it. Same fix as applyProjectOverlay's.
                scanned = aiGuardrails.redactAll(scanned, aiGuardrails.resolveMinConfidence(workspaceId));
            }

            // scanResponseText/redactAll are declared @Nullable, so scanned is compared null-safely rather than
            // dereferenced -- the compiler cannot see that content != null (checked above) makes both calls return
            // non-null here.
            if (!Objects.equals(scanned, content)) {
                responseRedacted = true;
            }

            if (Objects.equals(scanned, content)) {
                scannedChoices.add(choice);

                continue;
            }

            changed = true;

            AiGatewayChatMessage scannedMessage = new AiGatewayChatMessage(
                message.role(), scanned, message.contentBlocks(), message.toolCalls(), message.toolCallId());

            scannedChoices.add(
                new AiGatewayChatCompletionResponse.Choice(choice.index(), scannedMessage, choice.finishReason()));
        }

        if (responseRedacted) {
            record("response_redacted");
        }

        if (!changed) {
            return response;
        }

        return new AiGatewayChatCompletionResponse(
            response.id(), response.object(), response.created(), response.model(), scannedChoices, response.usage(),
            response.gatewayMetadata());
    }

    /**
     * Returns an already-scanned completion response with {@code session}'s tokens restored back to their real values
     * in each choice, or the response unchanged when {@code session} is {@code null}. This is the RESTORE half only —
     * it does not scan for new PII/secrets, so it must only ever be called on a response
     * {@link #scanResponse(AiGatewayChatCompletionResponse, Long, Long)} has already produced; reversing that order
     * would hand a not-yet-scanned response straight back to the caller with no scanning ever applied. Records
     * {@code pii_restored}/{@code token_unresolved} through {@link AiGuardrails#restoreResponseText} itself.
     *
     * @param response the already-scanned completion response
     * @param session  the session that tokenized this exchange's request, or {@code null} to leave the response
     *                 unchanged (nothing to restore)
     * @return the response with {@code session}'s tokens restored, or the original when {@code session} is {@code null}
     *         or nothing needed restoring
     */
    public AiGatewayChatCompletionResponse restoreResponse(
        AiGatewayChatCompletionResponse response, @Nullable PiiTokenSession session) {

        if (session == null || response == null || response.choices() == null || response.choices()
            .isEmpty()) {

            return response;
        }

        List<AiGatewayChatCompletionResponse.Choice> choices = response.choices();
        List<AiGatewayChatCompletionResponse.Choice> restoredChoices = new ArrayList<>(choices.size());
        boolean changed = false;

        for (AiGatewayChatCompletionResponse.Choice choice : choices) {
            AiGatewayChatMessage message = choice.message();
            String content = message == null ? null : message.content();

            if (content == null) {
                restoredChoices.add(choice);

                continue;
            }

            // restoreResponseText is declared @Nullable but only ever returns null for a null input, and content is
            // provably non-null at this point, just not statically so.
            String restored = aiGuardrails.restoreResponseText(content, session, metrics);

            if (Objects.equals(restored, content)) {
                restoredChoices.add(choice);

                continue;
            }

            changed = true;

            AiGatewayChatMessage restoredMessage = new AiGatewayChatMessage(
                message.role(), restored, message.contentBlocks(), message.toolCalls(), message.toolCallId());

            restoredChoices.add(
                new AiGatewayChatCompletionResponse.Choice(choice.index(), restoredMessage, choice.finishReason()));
        }

        if (!changed) {
            return response;
        }

        return new AiGatewayChatCompletionResponse(
            response.id(), response.object(), response.created(), response.model(), restoredChoices, response.usage(),
            response.gatewayMetadata());
    }

    /**
     * Returns a stateful redactor for masking PII/secrets in a streamed completion when streaming response scanning is
     * active for the workspace, otherwise {@code null}. Streaming scanning requires BOTH response scanning to be
     * effective for the workspace (global {@code response-scan-enabled} or workspace {@code scanResponses}) AND the
     * global {@code response-scan-streaming-enabled} operator flag — because holding back a lookahead window to catch
     * values that straddle SSE chunk boundaries trades away some of streaming's incremental latency, which is an
     * operator-level decision. Caller uses the returned redactor across the token stream and flushes it at completion.
     *
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @return a fresh {@link StreamingResponseRedactor}, or {@code null} when streaming scanning is inactive
     */
    public @Nullable StreamingResponseRedactor newStreamingResponseRedactor(@Nullable Long workspaceId) {
        return newStreamingResponseRedactor(workspaceId, null);
    }

    /**
     * As {@link #newStreamingResponseRedactor(Long)}, additionally honoring the project's response scanning override.
     *
     * @param workspaceId the workspace the request is attributed to, or {@code null} when unattributed
     * @param projectId   the project the request is attributed to, or {@code null} when none
     * @return a fresh {@link StreamingResponseRedactor}, or {@code null} when streaming scanning is inactive
     */
    public @Nullable StreamingResponseRedactor newStreamingResponseRedactor(
        @Nullable Long workspaceId, @Nullable Long projectId) {

        StreamingResponseRedactor redactor = aiGuardrails.newStreamingResponseRedactor(workspaceId);

        if (redactor != null) {
            return redactor;
        }

        if (!globalStreamingResponseScanEnabled) {
            return null;
        }

        AiGatewayProjectSettings projectSettings = findProjectSettings(projectId);

        if (projectSettings != null && Boolean.TRUE.equals(projectSettings.scanResponses())) {
            // 2026-08-31 final-branch-review fix: this project-triggered branch used to call the zero-arg
            // newStreamingResponseRedactor(), which runs at SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE
            // unconditionally -- the workspace's own threshold was never consulted here, even though
            // newStreamingResponseRedactor(Long) just above already resolves it when workspace policy alone
            // triggers streaming.
            return aiGuardrails.newStreamingResponseRedactor(aiGuardrails.resolveMinConfidence(workspaceId));
        }

        return null;
    }

    /**
     * Records a single {@code response_redacted} guardrail metric. Used by the streaming path to emit one metric per
     * stream (via {@link StreamingResponseRedactor#isRedacted()}) rather than one per chunk; the non-streaming path
     * records inline in {@link #redactResponse}.
     */
    public void recordResponseRedacted() {
        record("response_redacted");
    }

    /**
     * Applies the project's ADDITIVE guardrail overrides to {@code text} on top of whatever the engine already did for
     * the workspace/global policy: extra PII/secret redaction, extra blocked terms, and injection detection if the
     * project enables it and the workspace/global policy did not. A project can only enable a guardrail or add blocked
     * terms — it never turns one off — so it is safe to always layer this on top, regardless of what the engine already
     * applied.
     *
     * <p>
     * 2026-08-31 final-branch-review fix: the extra PII/secret redaction below used to call
     * {@link AiGuardrails#redactPii(String)}/{@link AiGuardrails#redactSecrets(String)} with no threshold, which run at
     * {@code SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE} regardless of what {@code workspaceId} configured. This is a
     * response/request-direction redact-only path with a workspace id already in scope, so it now resolves and passes
     * the workspace's own threshold like every other call site.
     * </p>
     *
     * @param workspaceId the workspace the call is attributed to, or {@code null} when unattributed, used only to
     *                    resolve the effective minimum confidence for the extra redaction below
     */
    private String applyProjectOverlay(
        String text, @Nullable AiGatewayProjectSettings projectSettings, @Nullable Long workspaceId) {

        if (projectSettings == null) {
            return text;
        }

        String result = text;
        double minConfidence = aiGuardrails.resolveMinConfidence(workspaceId);

        if (Boolean.TRUE.equals(projectSettings.redactPii())) {
            String redacted = aiGuardrails.redactPii(result, minConfidence);

            // aiGuardrails.redactPii is declared @Nullable, so redacted is compared null-safely rather than
            // dereferenced -- result is never actually null here (callers only ever pass non-null, non-empty text
            // into applyProjectOverlay), but the compiler has no way to know that from the declared signature.
            if (!Objects.equals(redacted, result)) {
                record("pii_redacted");
            }

            result = redacted;
        }

        if (Boolean.TRUE.equals(projectSettings.redactSecrets())) {
            String redacted = aiGuardrails.redactSecrets(result, minConfidence);

            // Same null-safety reasoning as the redactPii branch above.
            if (!Objects.equals(redacted, result)) {
                record("secret_redacted");
            }

            result = redacted;
        }

        checkAdditionalBlockedTerms(result, projectSettings.blockedTerms());

        if (Boolean.TRUE.equals(projectSettings.injectionDetectionEnabled()) && injectionClassifier != null
            && injectionClassifier.isInjection(result)) {

            record("injection_flagged");

            log.warn("AI Gateway request rejected by injection detection");

            throw new AiGatewayGuardrailException("Request rejected by prompt-injection detection");
        }

        return result;
    }

    private void checkAdditionalBlockedTerms(String content, @Nullable String blockedTermsCsv) {
        List<String> terms = parseBlockedTerms(blockedTermsCsv);

        if (terms.isEmpty()) {
            return;
        }

        String lowerContent = content.toLowerCase(Locale.ROOT);

        for (String term : terms) {
            if (lowerContent.contains(term)) {
                record("blocked_term");

                log.warn("AI Gateway request rejected by content guardrail (blocked term matched)");

                throw new AiGatewayGuardrailException(
                    "Request rejected by content guardrail: matched a blocked term");
            }
        }
    }

    private boolean resolveModerationEnabled(
        @Nullable Long workspaceId, @Nullable AiGatewayProjectSettings projectSettings) {

        if (globalModerationEnabled) {
            return true;
        }

        AiGuardrailsWorkspaceSettings settings = fetchWorkspaceSettings(workspaceId);

        if (settings != null && Boolean.TRUE.equals(settings.moderationEnabled())) {
            return true;
        }

        return projectSettings != null && Boolean.TRUE.equals(projectSettings.moderationEnabled());
    }

    private @Nullable AiGuardrailsWorkspaceSettings fetchWorkspaceSettings(@Nullable Long workspaceId) {
        try {
            Optional<AiGuardrailsWorkspaceSettings> settingsOptional =
                aiGuardrailsWorkspaceSettingsService.fetchSettings(workspaceId);

            return settingsOptional.orElse(null);
        } catch (Exception exception) {
            // A settings lookup failure must not take the request path down; global/project moderation policy still
            // applies.
            log.warn(
                "Failed to load AI guardrails workspace settings for workspace {} while resolving moderation: {}",
                workspaceId, exception.getMessage());

            return null;
        }
    }

    private @Nullable AiGatewayProjectSettings findProjectSettings(@Nullable Long projectId) {
        if (projectId == null || aiGatewayProjectSettingsService == null) {
            return null;
        }

        try {
            Optional<AiGatewayProjectSettings> settingsOptional =
                aiGatewayProjectSettingsService.findByProjectId(projectId);

            return settingsOptional.orElse(null);
        } catch (Exception exception) {
            // A settings lookup failure must not take the request path down; workspace/global guardrails still apply.
            log.warn(
                "Failed to load AI Gateway project settings for project {}: {}", projectId, exception.getMessage());

            return null;
        }
    }

    private void record(String event) {
        if (metrics != null) {
            metrics.record(event);
        }
    }

    private static List<String> parseBlockedTerms(@Nullable String blockedTerms) {
        if (StringUtils.isBlank(blockedTerms)) {
            return List.of();
        }

        List<String> terms = new ArrayList<>();

        for (String term : blockedTerms.split(",")) {
            String trimmed = term.strip();

            if (!trimmed.isEmpty()) {
                terms.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }

        return terms;
    }
}
