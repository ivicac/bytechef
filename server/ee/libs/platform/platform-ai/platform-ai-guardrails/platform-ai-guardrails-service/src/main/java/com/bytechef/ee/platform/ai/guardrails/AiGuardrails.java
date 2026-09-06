/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails;

import com.bytechef.ee.platform.ai.gateway.exception.AiGatewayGuardrailException;
import com.bytechef.ee.platform.ai.gateway.guardrail.AiGatewayInjectionClassifier;
import com.bytechef.ee.platform.ai.gateway.guardrail.AiGatewayModerationClassifier;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings.BlockingMode;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataDetector;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataDetectors;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor.RedactionResult;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenBoundaryPolicy;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applies inline content guardrails to text before it leaves ByteChef and, optionally, to model output before it is
 * returned to a caller. This is the standalone, DTO-free guardrail engine — it operates on plain strings and lists of
 * strings, not on any particular caller's request/response shapes. All guardrails are off by default; effective policy
 * per call is the union of global {@code bytechef.ai.gateway.guardrails.*} properties (property names kept for
 * compatibility — this engine was extracted from the AI Gateway, which is still the sole owner/reader of these keys
 * today) and the request workspace's {@link AiGuardrailsWorkspaceSettings}.
 *
 * <ul>
 * <li><b>PII redaction / tokenization</b> — {@code pii-redaction-enabled} / workspace {@code redactPii}. Email, US SSN,
 * credit-card, phone, and IPv4 matches are, when the caller threads a {@link PiiTokenSession} into the call (e.g.
 * {@link #applyToInputs(List, Long, PiiTokenSession)}, {@link #tokenizeInputs}), replaced with reversible
 * {@code [PII_<CATEGORY>_<ordinal>_<sessionId>]} tokens that are substituted back to their real values via
 * {@link #restoreResponseText} once the response comes back; without a session, matches are replaced with irreversible
 * {@code [REDACTED_*]} placeholders, as secrets always are regardless of a session. See "PII tokenization" in
 * {@code .agents/ai-guardrails.md} for the full round-trip.</li>
 * <li><b>Secret redaction</b> — {@code secret-redaction-enabled} / workspace {@code redactSecrets}. High-signal
 * developer-secret shapes (cloud/provider API keys, tokens, JWTs, PEM private keys) are replaced with
 * {@code [REDACTED_SECRET]}.</li>
 * <li><b>Blocked terms</b> — union of the global {@code blocked-terms} list and the workspace's {@code blockedTerms}
 * (both comma-separated). Content containing any term (case-insensitive) is rejected.</li>
 * <li><b>Prompt-injection detection</b> — {@code injection-detection-enabled} / workspace
 * {@code injectionDetectionEnabled}, when an {@link AiGatewayInjectionClassifier} bean is present. Content the
 * classifier judges to be a jailbreak / instruction-override / exfiltration attempt is rejected. Fails open.</li>
 * <li><b>Model-based moderation</b> — {@code moderation-enabled} / workspace {@code moderationEnabled}, when an
 * {@link AiGatewayModerationClassifier} bean is present. Content the classifier judges unsafe is a blocking violation,
 * reported ONLY through the non-throwing {@link #checkInputs}/{@link #checkInput} path (see below) — never through
 * {@link #applyToInputs}. Fails open.</li>
 * <li><b>Response scanning</b> — {@code response-scan-enabled} / workspace {@code scanResponses}. Text is redacted for
 * PII and secrets before it is returned (redaction only, never blocking) so internal data does not leak back through
 * completions. See {@link #scanResponseText}.</li>
 * </ul>
 *
 * <p>
 * The gateway-DTO/project-overlay layer (chat-completion requests/responses, project-level guardrail overrides) does
 * not live here — it stays with the AI Gateway adapter that wraps this engine, since it is a caller-specific concern
 * rather than shared text-level guardrail logic. Model-based moderation is different: as of the standalone-advisor
 * follow-up, {@link #checkInputs} runs the optional {@link AiGatewayModerationClassifier} bean so every advisor-fronted
 * agent surface (canvas AI Agent, AI Hub) gets moderation coverage, not just gateway-routed traffic. The throwing
 * {@link #applyToInputs} entry point — the AI Gateway adapter's own request path — deliberately does NOT run moderation
 * here: the gateway adapter ({@code AiGatewayGuardrails}) already moderates at its own DTO level with its own
 * classifier wiring (plus its project overlay), and running it a second time inside this shared engine would
 * double-moderate every gateway call.
 * </p>
 *
 * <p>
 * <b>Moderation and {@code BlockingMode}</b> — {@code BlockingMode} is documented (see
 * {@link AiGuardrailsWorkspaceSettings#blockingMode()}) to govern all three blocking guardrails: blocked terms,
 * moderation, and injection. Blocked-term violations downgrade by masking only the matched term; a moderation verdict,
 * like an injection verdict, has no locatable span — the classifier judges the whole message. Unlike injection (which
 * today forwards the pii/secret-redacted original text unchanged on downgrade — a pre-existing behavior this change
 * does not touch), a downgraded moderation violation replaces the ENTIRE message with a fixed placeholder,
 * {@code [REDACTED_MODERATED]}, rather than letting any of the flagged content continue on to the model: "masks the
 * offending content" for a whole-message judgment means masking the whole message.
 * </p>
 *
 * <p>
 * Redaction runs before the blocked-term check and injection detection, so those checks evaluate the redacted text. The
 * redactors are deterministic and side-effect-free, so they are safe to run on every piece of content. Regexes
 * deliberately avoid nested optional quantifiers (no catastrophic backtracking / ReDoS).
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class AiGuardrails {

    private static final Logger log = LoggerFactory.getLogger(AiGuardrails.class);

    private static final String BLOCKED_TERM_PLACEHOLDER = "[REDACTED_BLOCKED_TERM]";
    // A moderation verdict has no locatable span (the classifier judges the whole message), so a REDACT_AND_CONTINUE
    // downgrade replaces the entire message rather than masking a substring -- see the class javadoc's "Moderation and
    // BlockingMode" section for why this differs from how injection currently downgrades.
    private static final String MODERATION_PLACEHOLDER = "[REDACTED_MODERATED]";

    private final AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService;
    private final List<String> globalBlockedTerms;
    private final boolean globalInjectionDetectionEnabled;
    private final boolean globalModerationEnabled;
    private final boolean globalPiiRedactionEnabled;
    private final boolean globalResponseScanEnabled;
    private final boolean globalSecretRedactionEnabled;
    private final boolean globalStreamingResponseScanEnabled;
    private final @Nullable AiGatewayInjectionClassifier injectionClassifier;
    private final @Nullable AiGatewayModerationClassifier moderationClassifier;
    private final @Nullable AiGuardrailMetrics metrics;
    private final SensitiveDataRedactor sensitiveDataRedactor;
    // Resolved once at construction rather than per streamed response -- streamSafeView() logs an exclusion line for
    // each non-stream-safe detector, and newStreamingResponseRedactor() is called once per streamed response, so
    // re-deriving this view per call would log on every response in production. The detector list is fixed at
    // construction, so nothing is lost by resolving it once here.
    private final SensitiveDataRedactor streamSafeSensitiveDataRedactor;

    /**
     * Legacy constructor retained so that callers assembling this engine by hand — unit tests in this and other modules
     * — keep compiling. Uses the built-in regex detectors, which is what those callers had before the detector SPI
     * existed. Spring uses the {@code @Autowired} constructor below instead, so contributed detector beans participate.
     */
    public AiGuardrails(
        AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService,
        @Nullable AiGatewayInjectionClassifier injectionClassifier,
        @Nullable AiGatewayModerationClassifier moderationClassifier,
        @Nullable AiGuardrailMetrics metrics,
        @Value("${bytechef.ai.gateway.guardrails.pii-redaction-enabled:false}") boolean piiRedactionEnabled,
        @Value("${bytechef.ai.gateway.guardrails.secret-redaction-enabled:false}") boolean secretRedactionEnabled,
        @Value("${bytechef.ai.gateway.guardrails.blocked-terms:}") String blockedTerms,
        @Value("${bytechef.ai.gateway.guardrails.injection-detection-enabled:false}") boolean injectionDetectionEnabled,
        @Value("${bytechef.ai.gateway.guardrails.moderation-enabled:false}") boolean moderationEnabled,
        @Value("${bytechef.ai.gateway.guardrails.response-scan-enabled:false}") boolean responseScanEnabled,
        @Value("${bytechef.ai.gateway.guardrails.response-scan-streaming-enabled:false}") boolean streamingResponseScanEnabled) {

        this(
            aiGuardrailsWorkspaceSettingsService, injectionClassifier, moderationClassifier, metrics,
            SensitiveDataDetectors.builtIn(), piiRedactionEnabled, secretRedactionEnabled, blockedTerms,
            injectionDetectionEnabled, moderationEnabled, responseScanEnabled, streamingResponseScanEnabled);
    }

    // Two constructors are declared, so Spring cannot pick an autowire candidate implicitly. @Autowired marks this one
    // as the container's entry point, so contributed SensitiveDataDetector beans reach the engine.
    @Autowired
    public AiGuardrails(
        AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService,
        @Nullable AiGatewayInjectionClassifier injectionClassifier,
        @Nullable AiGatewayModerationClassifier moderationClassifier,
        @Nullable AiGuardrailMetrics metrics,
        List<SensitiveDataDetector> sensitiveDataDetectors,
        // Property names kept for compatibility: this engine was extracted from the AI Gateway, which is still the
        // sole owner/reader of these keys today.
        @Value("${bytechef.ai.gateway.guardrails.pii-redaction-enabled:false}") boolean piiRedactionEnabled,
        @Value("${bytechef.ai.gateway.guardrails.secret-redaction-enabled:false}") boolean secretRedactionEnabled,
        @Value("${bytechef.ai.gateway.guardrails.blocked-terms:}") String blockedTerms,
        @Value("${bytechef.ai.gateway.guardrails.injection-detection-enabled:false}") boolean injectionDetectionEnabled,
        @Value("${bytechef.ai.gateway.guardrails.moderation-enabled:false}") boolean moderationEnabled,
        @Value("${bytechef.ai.gateway.guardrails.response-scan-enabled:false}") boolean responseScanEnabled,
        @Value("${bytechef.ai.gateway.guardrails.response-scan-streaming-enabled:false}") boolean streamingResponseScanEnabled) {

        this.aiGuardrailsWorkspaceSettingsService = aiGuardrailsWorkspaceSettingsService;
        this.globalBlockedTerms = parseBlockedTerms(blockedTerms);
        this.globalInjectionDetectionEnabled = injectionDetectionEnabled;
        this.globalModerationEnabled = moderationEnabled;
        this.globalPiiRedactionEnabled = piiRedactionEnabled;
        this.globalResponseScanEnabled = responseScanEnabled;
        this.globalSecretRedactionEnabled = secretRedactionEnabled;
        this.globalStreamingResponseScanEnabled = streamingResponseScanEnabled;
        this.injectionClassifier = injectionClassifier;
        this.moderationClassifier = moderationClassifier;
        this.metrics = metrics;
        this.sensitiveDataRedactor = new SensitiveDataRedactor(sensitiveDataDetectors);
        this.streamSafeSensitiveDataRedactor = this.sensitiveDataRedactor.streamSafeView();
    }

    /**
     * Returns the input strings with request-direction guardrails applied: PII and secrets redacted, blocked terms and
     * injection attempts rejected. Returns the inputs unchanged when no relevant guardrail is active. Equivalent to
     * {@link #applyToInputs(List, Long, PiiTokenSession)} with a {@code null} session.
     *
     * @param inputs      the input strings
     * @param workspaceId the workspace the call is attributed to, or {@code null} when unattributed (global guardrails
     *                    still apply)
     * @return the guardrailed inputs
     * @throws AiGatewayGuardrailException if an input contains a blocked term or is flagged by injection detection
     */
    public List<String> applyToInputs(List<String> inputs, @Nullable Long workspaceId) {
        return applyToInputs(inputs, workspaceId, null);
    }

    /**
     * As {@link #applyToInputs(List, Long)}, additionally tokenizing PII into {@code session}'s reversible tokens
     * instead of redacting it irreversibly when {@code session} is not {@code null} — secrets are still redacted
     * irreversibly either way, and blocked-term/injection handling — including the unconditional throw on a blocking
     * violation — is unchanged. {@code session == null} reproduces {@link #applyToInputs(List, Long)} exactly (the two
     * used to be separate, near-identical loops; {@link #redactPiiAndSecrets} already branches on
     * {@code session == null} to decide redact-vs-tokenize, so one loop serves both).
     *
     * <p>
     * Deliberately does NOT check moderation, for the same reason as when {@code session} is {@code null}: this
     * method's only caller is the AI Gateway adapter's throwing request path, and the adapter already moderates its own
     * DTO pipeline with its own classifier wiring — moderating here too would double-moderate every gateway call.
     * Routing a tokenizing call through the non-throwing {@link #tokenizeInputs} instead (which DOES check moderation,
     * for the advisor's benefit) would silently reintroduce exactly that double-moderation, so this method keeps its
     * own throwing loop rather than reusing {@link #checkOrTokenizeInputs}.
     * </p>
     *
     * @param inputs      the input strings
     * @param workspaceId the workspace the call is attributed to, or {@code null} when unattributed (global guardrails
     *                    still apply)
     * @param session     the session minting tokens for this call, or {@code null} to redact PII irreversibly
     * @return the guardrailed inputs, PII tokenized rather than redacted when {@code session} is not {@code null}
     * @throws AiGatewayGuardrailException if an input contains a blocked term or is flagged by injection detection
     */
    public List<String> applyToInputs(
        List<String> inputs, @Nullable Long workspaceId, @Nullable PiiTokenSession session) {

        if (inputs == null || inputs.isEmpty()) {
            return inputs;
        }

        EffectivePolicy policy = resolvePolicy(workspaceId);

        if (!policy.anyInputGuardrailActive()) {
            return inputs;
        }

        List<String> guardrailedInputs = new ArrayList<>(inputs.size());

        for (String input : inputs) {
            guardrailedInputs.add(checkAndRedact(input, policy, session));
        }

        return guardrailedInputs;
    }

    /**
     * Non-throwing counterpart to {@link #applyToInputs} for callers that need to choose HOW to handle a blocking
     * violation (a blocked-term match or a flagged prompt injection) instead of having this engine always throw
     * {@link AiGatewayGuardrailException} — e.g. an advisor implementing a workspace's configurable
     * {@link BlockingMode}. {@link #applyToInputs} keeps its unconditional-throw contract unchanged (the AI Gateway
     * adapter's HTTP 422 behavior must not change); this method exists alongside it, not instead of it.
     *
     * <p>
     * PII and secret redaction are always applied inline, exactly as in {@link #applyToInputs}. A blocking violation is
     * reported via {@link GuardrailCheckResult#category()} rather than thrown; for a blocked-term match the matched
     * term is additionally masked out of {@link GuardrailCheckResult#text()} (replaced with
     * {@code [REDACTED_BLOCKED_TERM]}) so a caller that decides to continue anyway never forwards the raw offending
     * text. An injection-flagged input has no single locatable span — the classifier judges the whole message — so its
     * {@code text} carries only the PII/secret redaction already applied. A moderation-flagged input also has no
     * locatable span, but unlike injection its {@code text} is replaced wholesale with {@code [REDACTED_MODERATED]} —
     * see the class javadoc's "Moderation and {@code BlockingMode}" section for why the two differ. Moderation is only
     * checked here, never in {@link #applyToInputs} (the AI Gateway adapter moderates its own DTO pipeline directly, so
     * running it here too would double-moderate gateway traffic).
     * </p>
     *
     * <p>
     * Unlike {@link #applyToInputs}, the request-direction events this method triggers ({@code pii_redacted},
     * {@code secret_redacted}, {@code blocked_term}, {@code injection_flagged}, {@code moderation_flagged}) are
     * recorded through the caller-supplied {@code metrics} instance rather than this engine's own constructor-injected
     * bean — this method has exactly one caller, {@code AiGuardrailsAdvisor}, which passes its own per-request,
     * surface-tagged instance so events land under the calling surface (e.g. {@code ai_agent}, {@code ai_hub}) instead
     * of the engine bean's fixed {@code gateway}-or-nothing tag. {@link #applyToInputs} is untouched and keeps
     * recording through the engine's own bean, so the AI Gateway adapter's metrics are unaffected.
     * </p>
     *
     * @param inputs      the input strings
     * @param workspaceId the workspace the call is attributed to, or {@code null} when unattributed
     * @param metrics     the metrics instance to record request-direction events through, tagged with the caller's own
     *                    surface
     * @return one {@link GuardrailCheckResult} per input, in order; empty when {@code inputs} is {@code null} or empty
     */
    public List<GuardrailCheckResult> checkInputs(
        @Nullable List<String> inputs, @Nullable Long workspaceId, AiGuardrailMetrics metrics) {

        return checkOrTokenizeInputs(inputs, workspaceId, metrics, null);
    }

    /**
     * Returns a fresh token session for one request.
     *
     * @return the session; the caller owns closing it on every termination path
     */
    public PiiTokenSession newTokenSession() {
        return PiiTokenSession.create();
    }

    /**
     * The tokenizing counterpart of {@link #checkInputs}: identical blocked-term, injection and moderation handling,
     * differing only in that PII becomes session-minted tokens instead of {@code [REDACTED_*]} placeholders. Secrets
     * are still redacted irreversibly.
     *
     * @param inputs      the input strings
     * @param workspaceId the workspace the call is attributed to, or {@code null}
     * @param session     the session minting tokens for this request
     * @param metrics     the metrics instance to record through
     * @return one result per input, in order
     */
    public List<GuardrailCheckResult> tokenizeInputs(
        @Nullable List<String> inputs, @Nullable Long workspaceId, PiiTokenSession session,
        AiGuardrailMetrics metrics) {

        return checkOrTokenizeInputs(inputs, workspaceId, metrics, session);
    }

    /**
     * Substitutes values back for tokens {@code session} minted. Runs AFTER response scanning — see the design spec's
     * "scan, then restore" ordering rule
     * ({@code docs/superpowers/specs/2026-08-24-guardrails-pii-tokenization-design.md}, §7); restoring first would let
     * response scanning immediately re-redact what was just restored, making the whole round trip a no-op. Mirrors
     * {@link #scanResponseText(String, Long, AiGuardrailMetrics)}'s shape so both halves of the round trip are
     * available to every caller (this advisor today, the AI Gateway adapter once it adopts tokenization) without
     * duplicating the restore-and-record logic.
     *
     * <p>
     * Records {@code pii_restored} when at least one token was substituted, and {@code token_unresolved} when one or
     * more tokens in {@code text} could not be resolved back to a value (an anomaly — the model mangled a token, or
     * emitted one this session never minted). Both are incidence counters — recorded at most once per call, not once
     * per token — matching how {@code pii_redacted}/{@code pii_tokenized}/{@code secret_redacted} are recorded once per
     * call rather than once per span (see {@link #redactPiiAndSecrets}); mixing volume and incidence semantics across
     * events in the same metric family would make cross-event comparisons meaningless.
     * </p>
     *
     * @param text             the model's response text, already scanned
     * @param session          the session that tokenized the request
     * @param recordingMetrics the instance to record {@code pii_restored}/{@code token_unresolved} through, or
     *                         {@code null}
     * @return the text with known tokens restored, or {@code null} when {@code text} was {@code null}
     */
    public @Nullable String restoreResponseText(
        String text, PiiTokenSession session, @Nullable AiGuardrailMetrics recordingMetrics) {

        PiiTokenSession.RestoreResult restoreResult = session.restoreWithUnresolvedCount(text);
        String restored = restoreResult.text();

        if (restoreResult.unresolvedCount() > 0) {
            record(recordingMetrics, "token_unresolved");
        }

        if (!Objects.equals(restored, text)) {
            record(recordingMetrics, "pii_restored");
        }

        return restored;
    }

    /**
     * Returns {@code text} redacted for PII and secrets when response scanning is enabled for the workspace (or
     * globally), otherwise {@code text} unchanged. Response scanning is redaction only — it never blocks.
     *
     * @param text        the text to scan (e.g. a single completion choice's content)
     * @param workspaceId the workspace the call is attributed to, or {@code null} when unattributed
     * @return the redacted text, or the original when response scanning is inactive
     */
    public String scanResponseText(String text, @Nullable Long workspaceId) {
        return scanResponseText(text, workspaceId, metrics);
    }

    /**
     * As {@link #scanResponseText(String, Long)}, but counting detector failures through {@code recordingMetrics}
     * rather than this engine's own bean.
     *
     * <p>
     * Response-direction redaction reaches the engine here, and without this overload it fell through to the engine's
     * constructor-injected instance — a bean tagged with one fixed {@code surface} for the whole deployment and gated
     * on the AI Gateway toggle. A detector failing while scanning an AI Hub or canvas-agent completion was therefore
     * either uncounted or counted as {@code surface=gateway}, pointing an operator at the wrong surface. Callers that
     * hold a surface-tagged instance should pass it.
     * </p>
     *
     * @param text             the text to scan
     * @param workspaceId      the workspace the call is attributed to, or {@code null} when unattributed
     * @param recordingMetrics the instance to count detector failures through, or {@code null}
     * @return the redacted text, or the original when response scanning is inactive
     */
    public String scanResponseText(
        String text, @Nullable Long workspaceId, @Nullable AiGuardrailMetrics recordingMetrics) {

        if (text == null) {
            return null;
        }

        EffectivePolicy policy = resolvePolicy(workspaceId);

        if (!policy.scanResponses()) {
            return text;
        }

        return redactAll(text, policy.minConfidence(), recordingMetrics);
    }

    /**
     * Returns a stateful redactor for masking PII/secrets in a streamed completion when streaming response scanning is
     * active for the workspace, otherwise {@code null}. Streaming scanning requires BOTH response scanning to be
     * effective for the workspace (global {@code response-scan-enabled} or workspace {@code scanResponses}) AND the
     * global {@code response-scan-streaming-enabled} operator flag — because holding back a lookahead window to catch
     * values that straddle SSE chunk boundaries trades away some of streaming's incremental latency, which is an
     * operator-level decision. Caller uses the returned redactor across the token stream and flushes it at completion.
     *
     * @param workspaceId the workspace the call is attributed to, or {@code null} when unattributed
     * @return a fresh {@link StreamingResponseRedactor}, or {@code null} when streaming scanning is inactive
     */
    public @Nullable StreamingResponseRedactor newStreamingResponseRedactor(@Nullable Long workspaceId) {
        return newStreamingResponseRedactor(workspaceId, metrics);
    }

    /**
     * As {@link #newStreamingResponseRedactor(Long)}, but the returned redactor counts detector failures through
     * {@code recordingMetrics}. Without it a detector failing mid-stream is logged and never counted, on every
     * deployment.
     *
     * @param workspaceId      the workspace the call is attributed to, or {@code null} when unattributed
     * @param recordingMetrics the instance to count detector failures through, or {@code null}
     * @return a fresh streaming redactor, or {@code null} when streaming scanning is inactive
     */
    public @Nullable StreamingResponseRedactor newStreamingResponseRedactor(
        @Nullable Long workspaceId, @Nullable AiGuardrailMetrics recordingMetrics) {
        if (!globalStreamingResponseScanEnabled) {
            return null;
        }

        EffectivePolicy policy = resolvePolicy(workspaceId);

        if (!policy.scanResponses()) {
            return null;
        }

        return new StreamingResponseRedactor(streamSafeSensitiveDataRedactor, policy.minConfidence(), recordingMetrics);
    }

    /**
     * As {@link #newStreamingResponseRedactor(Long, AiGuardrailMetrics)}, but the returned redactor also restores
     * {@code session}'s tokens back to their real values as each emitted segment is scanned — see
     * {@link StreamingResponseRedactor}'s class javadoc for the scan-then-restore ordering. Without this overload, a
     * caller that tokenizes a streamed request (see {@link #tokenizeInputs}) would have no way to give the streamed
     * response half a session to restore through, and the caller would end up forwarding raw {@code [PII_*]} tokens —
     * worse than not tokenizing at all.
     *
     * <p>
     * A 3-argument overload rather than a {@code PiiTokenSession} sibling of the 2-argument form: that would leave two
     * distinct 3-argument overloads differing only in the type of a nullable reference-type third parameter
     * ({@code AiGuardrailMetrics} vs. {@code PiiTokenSession}), which is ambiguous for a caller passing a bare
     * {@code null} — the same hazard already documented on {@link #newStreamingResponseRedactor()}. This overload
     * instead adds {@code session} as a genuinely new (non-nullable) parameter onto the existing 2-argument shape, so
     * arity alone disambiguates every call site.
     * </p>
     *
     * <p>
     * <b>Restoration is not scanning, but it still needs something to restore.</b> The
     * {@code response-scan-streaming-enabled} operator flag and the workspace's {@code scanResponses} setting govern
     * only whether NEW sensitive spans in the model's output get masked — a lookahead-latency trade-off the operator
     * opts into. Restoring a token {@code session} itself minted is a different thing: it is completing a
     * transformation this engine's own {@link #tokenizeInputs} already started on the request, not an additional scan,
     * so it is NOT gated on that flag. But when {@code session} minted nothing ({@link PiiTokenSession#size()} is zero
     * — e.g. a workspace with PII tokenization disabled, or a request with no PII in it) there is nothing to restore
     * either, and forcing every such stream through the lookahead buffer regardless would silently reintroduce the
     * exact latency the operator opted out of, for every workspace that never tokenizes anything. This method therefore
     * returns {@code null} exactly when NEITHER applies — streaming scanning is inactive AND {@code session} minted
     * nothing — mirroring the 2-argument form's contract for a caller with nothing to gain from a redactor. When it
     * returns a redactor, that redactor restores {@code session}'s tokens unconditionally and additionally scans for
     * new PII/secrets only when the policy gate is active.
     * </p>
     *
     * <p>
     * <b>Known gap: a foreign/dead token cannot be counted on this null-returning path.</b> {@code token_unresolved} is
     * recorded only by a redactor's own {@code restore()} step (see {@link StreamingResponseRedactor}); when this
     * method returns {@code null} — {@code session} minted nothing here — no redactor is ever created, so a
     * token-shaped string that arrives anyway (e.g. an earlier turn's token replayed from retained chat history, see
     * {@code .agents/ai-guardrails.md}'s "PII tokenization" §Phase 1 constraint) is never inspected and the metric
     * never fires for it. Detecting it here would mean buffering every stream through the lookahead window
     * unconditionally to check token shape, which is precisely the per-request cost this null-return path exists to
     * avoid for the common case (a workspace/session with nothing of its own to restore) — deliberately left unresolved
     * rather than reintroducing that cost for every stream to catch an anomaly on this one path.
     * </p>
     *
     * @param workspaceId      the workspace the call is attributed to, or {@code null} when unattributed
     * @param recordingMetrics the instance to count detector failures through, or {@code null}
     * @param session          the session that tokenized this call's request
     * @return a fresh streaming redactor restoring {@code session}'s tokens (and additionally scanning for new
     *         PII/secrets when streaming response scanning is active for the workspace), or {@code null} when streaming
     *         scanning is inactive AND {@code session} minted nothing
     */
    public @Nullable StreamingResponseRedactor newStreamingResponseRedactor(
        @Nullable Long workspaceId, @Nullable AiGuardrailMetrics recordingMetrics, PiiTokenSession session) {

        EffectivePolicy policy = resolvePolicy(workspaceId);
        boolean streamingScanActive = globalStreamingResponseScanEnabled && policy.scanResponses();

        if (!streamingScanActive && session.size() == 0) {
            return null;
        }

        EnumSet<SensitiveKind> kinds =
            streamingScanActive ? EnumSet.allOf(SensitiveKind.class) : EnumSet.noneOf(SensitiveKind.class);

        return new StreamingResponseRedactor(
            streamSafeSensitiveDataRedactor, policy.minConfidence(), recordingMetrics, session, kinds);
    }

    /**
     * Returns a fresh streaming redactor over this engine's stream-safe detectors, with no policy check, counting
     * detector failures through this engine's own metrics instance. For callers that have already decided streaming
     * scanning applies — the AI Gateway's project-level overlay.
     *
     * <p>
     * There is deliberately no {@code newStreamingResponseRedactor(AiGuardrailMetrics)} overload beside this one: it
     * would collide with {@link #newStreamingResponseRedactor(Long)} for a bare {@code null} argument, forcing callers
     * to cast. A caller that wants to supply its own metrics instance passes it alongside the workspace id through the
     * two-argument form above.
     * </p>
     *
     * @return a fresh streaming redactor
     */
    public StreamingResponseRedactor newStreamingResponseRedactor() {
        return new StreamingResponseRedactor(streamSafeSensitiveDataRedactor, metrics);
    }

    /**
     * As {@link #newStreamingResponseRedactor()}, but honoring an explicit minimum confidence instead of
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}. For callers that have already decided streaming scanning
     * applies but still need to resolve a workspace's own threshold themselves — the AI Gateway's project-level
     * overlay, where workspace policy alone did not trigger streaming (so {@link #newStreamingResponseRedactor(Long)}
     * returned {@code null}) but the project override turned it on; without this overload that path fell back to
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} regardless of what the workspace had configured.
     *
     * @param minConfidence the minimum confidence, inclusive, a candidate span must meet to be redacted
     * @return a fresh streaming redactor
     */
    public StreamingResponseRedactor newStreamingResponseRedactor(double minConfidence) {
        return new StreamingResponseRedactor(streamSafeSensitiveDataRedactor, minConfidence, metrics);
    }

    /**
     * Returns the effective {@link BlockingMode} for the workspace: the workspace's configured mode, or {@code BLOCK}
     * when no settings row exists (or the row does not configure a mode).
     *
     * @param workspaceId the workspace to resolve, or {@code null} for the tenant default
     * @return the effective blocking mode
     */
    public BlockingMode resolveBlockingMode(@Nullable Long workspaceId) {
        AiGuardrailsWorkspaceSettings settings = findSettings(workspaceId);

        if (settings == null || settings.blockingMode() == null) {
            return BlockingMode.BLOCK;
        }

        return settings.blockingMode();
    }

    /**
     * Returns the effective minimum confidence for {@code workspaceId}: the workspace's configured
     * {@link AiGuardrailsWorkspaceSettings#minConfidence()} override, or
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} when no settings row exists or the row does not configure
     * one. For callers outside this engine (the AI Gateway adapter's response-direction and project-overlay paths) that
     * need to resolve the threshold themselves rather than going through one of this class's own threshold-applying
     * methods.
     *
     * @param workspaceId the workspace to resolve, or {@code null} for the tenant default
     * @return the effective minimum confidence
     */
    public double resolveMinConfidence(@Nullable Long workspaceId) {
        return resolvePolicy(workspaceId).minConfidence();
    }

    /**
     * Returns the effective {@link PiiTokenBoundaryPolicy} for {@code workspaceId} -- which {@link SensitiveKind}s the
     * tool-call boundary should tokenize/redact, and at what minimum confidence -- for
     * {@code AiGuardrailsAdvisor#withSessionInToolContext} to carry onto the {@code ToolContext} alongside the
     * {@link PiiTokenSession} it opens for the same call, so {@code PiiTokenBoundaryToolCallingManager} honours this
     * workspace's own {@code redactPii}/{@code redactSecrets}/{@code minConfidence} settings instead of a fixed
     * constant. Built from the same {@link #resolvePolicy(Long)} every other policy-driven method here already uses --
     * this is not a second resolution path.
     *
     * @param workspaceId the workspace to resolve, or {@code null} for the tenant default
     * @return the effective tool-boundary policy
     */
    public PiiTokenBoundaryPolicy resolveToolBoundaryPolicy(@Nullable Long workspaceId) {
        return toolBoundaryPolicyOf(resolvePolicy(workspaceId));
    }

    /**
     * Converts an already-resolved {@link EffectivePolicy} into the kinds-and-threshold pair the redaction boundaries
     * take. Split out from {@link #resolveToolBoundaryPolicy(Long)} so {@link #resolveMcpOutboundPolicy} can reach it
     * without triggering a second settings read -- see that method for why a second read is unacceptable there.
     */
    private static PiiTokenBoundaryPolicy toolBoundaryPolicyOf(EffectivePolicy policy) {
        Set<SensitiveKind> kinds = EnumSet.noneOf(SensitiveKind.class);

        if (policy.redactPii()) {
            kinds.add(SensitiveKind.PII);
        }

        if (policy.redactSecrets()) {
            kinds.add(SensitiveKind.SECRET);
        }

        return new PiiTokenBoundaryPolicy(kinds, policy.minConfidence());
    }

    /**
     * Returns the policy for redacting an MCP server's outbound tool results, or {@code null} when
     * {@code redactMcpResults} is unset or false for {@code workspaceId}.
     *
     * <p>
     * Gated on its own setting rather than on {@link #isActive} or on {@code redactPii}/{@code redactSecrets}: an MCP
     * tool is frequently how a customer hands data to their own agent on purpose, so enabling guardrails for chat
     * surfaces must not silently start rewriting an MCP pipeline's payloads. The switch selects the surface; the kinds
     * and threshold still come from the same workspace fields every other surface reads.
     * </p>
     *
     * <p>
     * Reads the settings row directly rather than going through {@link #resolvePolicy}: {@code redactMcpResults} has no
     * global counterpart to union with, so there is nothing for the effective-policy machinery to combine.
     * </p>
     *
     * <p>
     * It reads that row through the settings service itself rather than through this class's fail-open
     * {@code findSettings}, and this is the one caller that must: a swallowed lookup failure is indistinguishable from
     * a workspace with no settings row, and on this path "no settings" means "return the payload unredacted". The
     * failure propagates so the MCP decorator can fail closed on it -- a database blip during a {@code tools/call} must
     * end in a tool error, never in raw customer records with {@code isError} false. The chat surfaces reading
     * {@code findSettings} keep their fail-open behavior, which is correct for them: there a broken guardrail is never
     * worse than no guardrail.
     * </p>
     *
     * <p>
     * The row is read <b>exactly once</b> and then reused for the kinds and threshold, rather than resolved again
     * through {@link #resolveToolBoundaryPolicy(Long)}. A second read would reach the fail-open {@code findSettings},
     * and a failure there resolves no workspace override at all: with the shipped global defaults that yields an EMPTY
     * kind set, a non-null policy, and a redactor that returns the payload unchanged without even emitting a metric.
     * That is the same silent leak the guarded first read exists to prevent, one read later -- and a realistic one,
     * since both reads hit an uncached {@code PropertyService} row and the failures that motivate this (pool
     * exhaustion, failover, connection reset) are bursty enough to break one read and not the other.
     * </p>
     *
     * @param workspaceId the workspace to resolve, or {@code null} for the tenant default
     * @return the outbound policy, or {@code null} when outbound redaction is off
     * @throws RuntimeException when the settings lookup fails; the caller must fail closed rather than treat it as off
     */
    public @Nullable PiiTokenBoundaryPolicy resolveMcpOutboundPolicy(@Nullable Long workspaceId) {
        AiGuardrailsWorkspaceSettings settings = aiGuardrailsWorkspaceSettingsService.fetchSettings(workspaceId)
            .orElse(null);

        if (settings == null || !Boolean.TRUE.equals(settings.redactMcpResults())) {
            return null;
        }

        return toolBoundaryPolicyOf(effectivePolicyOf(settings));
    }

    /**
     * As {@link #resolveMcpOutboundPolicy(Long)}, but for an embedded MCP server: reads the
     * {@code AiGuardrailsSettingsScope#EMBEDDED} row rather than a workspace's (or the tenant-default) row, since
     * embedded MCP servers are not workspace-scoped. Every property documented on
     * {@link #resolveMcpOutboundPolicy(Long)} -- the dedicated {@code redactMcpResults} gate, the fail-closed settings
     * read, and the single read reused for both the gate and the resulting policy -- applies here identically.
     *
     * @return the outbound policy, or {@code null} when outbound redaction is off for the embedded scope
     * @throws RuntimeException when the settings lookup fails; the caller must fail closed rather than treat it as off
     */
    public @Nullable PiiTokenBoundaryPolicy resolveEmbeddedMcpOutboundPolicy() {
        AiGuardrailsWorkspaceSettings settings = aiGuardrailsWorkspaceSettingsService.fetchEmbeddedSettings()
            .orElse(null);

        if (settings == null || !Boolean.TRUE.equals(settings.redactMcpResults())) {
            return null;
        }

        return toolBoundaryPolicyOf(effectivePolicyOf(settings));
    }

    /**
     * Returns whether at least one guardrail (PII/secret redaction, blocked terms, injection detection, model-based
     * moderation, or response scanning) is active for {@code workspaceId} once global and workspace-level policy are
     * unioned. Used by callers that want to skip attaching a guardrail advisor entirely when nothing would apply (e.g.
     * {@code AiGuardrailsAdvisorProviderImpl}), so a workspace with every guardrail disabled pays no per-call advisor
     * overhead. Moderation only counts as active when a {@link AiGatewayModerationClassifier} bean is present — a
     * workspace enabling {@code moderationEnabled} without a configured moderation model stays inert (see
     * {@link #resolvePolicy}), matching how injection detection already behaves.
     *
     * @param workspaceId the workspace to resolve, or {@code null} for the tenant default
     * @return {@code true} when at least one guardrail is active
     */
    public boolean isActive(@Nullable Long workspaceId) {
        EffectivePolicy policy = resolvePolicy(workspaceId);

        return policy.anyInputGuardrailActive() || policy.scanResponses() || policy.moderate();
    }

    /**
     * Replaces personally-identifiable data in {@code content} with {@code [REDACTED_*]} placeholders, at
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}.
     */
    public @Nullable String redactPii(@Nullable String content) {
        return redactPii(content, SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE);
    }

    /**
     * As {@link #redactPii(String)}, but with an explicit minimum confidence instead of
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} — for callers that have already resolved a workspace's own
     * threshold (see {@link #resolveMinConfidence(Long)}).
     *
     * @param content       the content to redact
     * @param minConfidence the minimum confidence, inclusive, a candidate span must meet to be redacted
     * @return the redacted content, or {@code content} unchanged when nothing applies
     */
    public @Nullable String redactPii(@Nullable String content, double minConfidence) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        return sensitiveDataRedactor.redact(content, EnumSet.of(SensitiveKind.PII), minConfidence, metrics);
    }

    /**
     * Replaces recognised developer-secret shapes (cloud/provider API keys, tokens, JWTs, PEM private keys) in
     * {@code content} with a {@code [REDACTED_SECRET]} placeholder, at
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}.
     */
    public @Nullable String redactSecrets(@Nullable String content) {
        return redactSecrets(content, SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE);
    }

    /**
     * As {@link #redactSecrets(String)}, but with an explicit minimum confidence instead of
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} — for callers that have already resolved a workspace's own
     * threshold (see {@link #resolveMinConfidence(Long)}).
     *
     * @param content       the content to redact
     * @param minConfidence the minimum confidence, inclusive, a candidate span must meet to be redacted
     * @return the redacted content, or {@code content} unchanged when nothing applies
     */
    public @Nullable String redactSecrets(@Nullable String content, double minConfidence) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        return sensitiveDataRedactor.redact(content, EnumSet.of(SensitiveKind.SECRET), minConfidence, metrics);
    }

    /**
     * Applies both PII and secret redaction to {@code content} in ONE detection pass, resolving any overlap between the
     * two in favour of the secret, at {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}. Used for response-direction
     * scanning where both categories are masked regardless of the request-direction toggles.
     */
    public @Nullable String redactAll(@Nullable String content) {
        return redactAll(content, SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE, metrics);
    }

    /**
     * As {@link #redactAll(String)}, but with an explicit minimum confidence instead of
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} — for callers that have already resolved a workspace's own
     * threshold (see {@link #resolveMinConfidence(Long)}).
     *
     * @param content       the text to redact
     * @param minConfidence the minimum confidence, inclusive, a candidate span must meet to be redacted
     * @return the redacted text, or {@code content} unchanged when nothing applies
     */
    public @Nullable String redactAll(@Nullable String content, double minConfidence) {
        return redactAll(content, minConfidence, metrics);
    }

    /**
     * As {@link #redactAll(String)}, but counting detector failures through {@code recordingMetrics} rather than this
     * engine's own bean, so the failure is attributed to the surface that actually ran the redaction — at
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}.
     *
     * @param content          the text to redact
     * @param recordingMetrics the instance to count detector failures through, or {@code null}
     * @return the redacted text, or {@code content} unchanged when nothing applies
     */
    public @Nullable String redactAll(@Nullable String content, @Nullable AiGuardrailMetrics recordingMetrics) {
        return redactAll(content, SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE, recordingMetrics);
    }

    /**
     * As {@link #redactAll(String, AiGuardrailMetrics)}, but with an explicit minimum confidence instead of
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}. The single implementation every other {@code redactAll}
     * overload delegates to.
     *
     * @param content          the text to redact
     * @param minConfidence    the minimum confidence, inclusive, a candidate span must meet to be redacted
     * @param recordingMetrics the instance to count detector failures through, or {@code null}
     * @return the redacted text, or {@code content} unchanged when nothing applies
     */
    public @Nullable String redactAll(
        @Nullable String content, double minConfidence, @Nullable AiGuardrailMetrics recordingMetrics) {

        if (content == null || content.isEmpty()) {
            return content;
        }

        return sensitiveDataRedactor.redact(content, EnumSet.allOf(SensitiveKind.class), minConfidence,
            recordingMetrics);
    }

    /**
     * The shared outer loop behind both {@link #checkInputs} and {@link #tokenizeInputs}: same null/empty guard, same
     * policy resolution, same per-input dispatch to {@link #checkInput}. The two public methods differ only in whether
     * {@code session} is {@code null} (redact) or a real session (tokenize), which {@link #checkInput} and
     * {@link #redactPiiAndSecrets} already resolve per input — this method exists so that shape does not have to be
     * copied a second time for the outer loop.
     *
     * @param session the session minting tokens for this call, or {@code null} to redact PII irreversibly as today
     */
    private List<GuardrailCheckResult> checkOrTokenizeInputs(
        @Nullable List<String> inputs, @Nullable Long workspaceId, AiGuardrailMetrics metrics,
        @Nullable PiiTokenSession session) {

        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        EffectivePolicy policy = resolvePolicy(workspaceId);
        List<GuardrailCheckResult> results = new ArrayList<>(inputs.size());

        for (String input : inputs) {
            results.add(checkInput(input, policy, metrics, session));
        }

        return results;
    }

    /**
     * The single throwing check-and-transform loop body behind BOTH {@code applyToInputs} overloads: same blocked-term
     * and injection handling and the same unconditional throw either way. {@code session} is passed straight through to
     * {@link #redactPiiAndSecrets}, which is itself already the one place that decides redact-vs-tokenize on a
     * {@code session == null} check — so this method needed no branch of its own to serve both the redacting
     * ({@code session == null}) and tokenizing ({@code session != null}) callers; it used to exist twice, as
     * {@code checkAndRedact}/{@code checkAndTokenize}, differing only in that one literal argument.
     *
     * @param session the session minting tokens for this call, or {@code null} to redact PII irreversibly
     */
    private String checkAndRedact(@Nullable String content, EffectivePolicy policy, @Nullable PiiTokenSession session) {
        if (content == null) {
            return null;
        }

        String result = redactPiiAndSecrets(content, policy, metrics, session);

        if (findBlockedTerm(result, policy.blockedTerms()) != null) {
            record(metrics, "blocked_term");

            log.warn("Content rejected by content guardrail (blocked term matched)");

            throw new AiGatewayGuardrailException("Request rejected by content guardrail: matched a blocked term");
        }

        if (policy.detectInjection() && injectionClassifier != null && injectionClassifier.isInjection(result)) {
            record(metrics, "injection_flagged");

            log.warn("Content rejected by injection detection");

            throw new AiGatewayGuardrailException("Request rejected by prompt-injection detection");
        }

        return result;
    }

    /**
     * The non-throwing counterpart of {@link #checkAndRedact} used by {@link #checkInputs} and {@link #tokenizeInputs}:
     * same PII/secret redaction (or tokenization, when {@code session} is not {@code null}) and blocking-violation
     * detection, but a blocking violation is reported via {@link GuardrailCheckResult#category()} (with the offending
     * term masked out of the text, or — for moderation — the whole text replaced) instead of being thrown. Records
     * through {@code recordingMetrics} — the caller-supplied instance from {@link #checkInputs} /
     * {@link #tokenizeInputs} — rather than this engine's own bean. Moderation is checked LAST and only here, never in
     * {@link #checkAndRedact}: the throwing path is the AI Gateway adapter's, which already moderates its own DTO
     * pipeline with its own classifier wiring, so moderating here too would double-moderate gateway traffic.
     *
     * @param session the session minting tokens for this call, or {@code null} to redact PII irreversibly as today
     */
    private GuardrailCheckResult checkInput(
        @Nullable String content, EffectivePolicy policy, AiGuardrailMetrics recordingMetrics,
        @Nullable PiiTokenSession session) {

        if (content == null) {
            return new GuardrailCheckResult(null, null, null);
        }

        String redacted = redactPiiAndSecrets(content, policy, recordingMetrics, session);

        String blockedTerm = findBlockedTerm(redacted, policy.blockedTerms());

        if (blockedTerm != null) {
            record(recordingMetrics, "blocked_term");

            return new GuardrailCheckResult(maskBlockedTerm(redacted, blockedTerm), redacted, "blocked_term");
        }

        if (policy.detectInjection() && injectionClassifier != null && injectionClassifier.isInjection(redacted)) {
            record(recordingMetrics, "injection_flagged");

            return new GuardrailCheckResult(redacted, redacted, "injection_flagged");
        }

        if (policy.moderate() && moderationClassifier != null && moderationClassifier.isFlagged(redacted)) {
            record(recordingMetrics, "moderation_flagged");

            return new GuardrailCheckResult(MODERATION_PLACEHOLDER, redacted, "moderation_flagged");
        }

        return new GuardrailCheckResult(redacted, redacted, null);
    }

    /**
     * Redacts (or, when {@code session} is not {@code null}, tokenizes) PII/secrets in {@code content}, recording
     * {@code pii_redacted}/{@code pii_tokenized} and {@code secret_redacted} through {@code recordingMetrics} when a
     * redaction actually changed the text. Shared by the throwing ({@link #checkAndRedact}, passed this engine's own
     * bean; {@code session} is {@code null} when reached from the redacting {@link #applyToInputs(List, Long)} overload
     * but a real session when reached from the tokenizing {@link #applyToInputs(List, Long, PiiTokenSession)} overload)
     * and non-throwing ({@link #checkInput}, passed the caller-supplied instance and, from {@link #tokenizeInputs}, a
     * real session) paths, which differ only in which {@link AiGuardrailMetrics} instance they record through and
     * whether a session is present.
     *
     * @param session the session minting tokens for this call, or {@code null} to redact PII irreversibly as today
     */
    private String redactPiiAndSecrets(
        String content, EffectivePolicy policy, @Nullable AiGuardrailMetrics recordingMetrics,
        @Nullable PiiTokenSession session) {

        Set<SensitiveKind> kinds = EnumSet.noneOf(SensitiveKind.class);

        if (policy.redactPii()) {
            kinds.add(SensitiveKind.PII);
        }

        if (policy.redactSecrets()) {
            kinds.add(SensitiveKind.SECRET);
        }

        RedactionResult redactionResult = session == null
            ? sensitiveDataRedactor.redactWithSpans(content, kinds, policy.minConfidence(), recordingMetrics)
            : sensitiveDataRedactor.tokenizeWithSpans(
                content, kinds, session, policy.minConfidence(), recordingMetrics);

        List<SensitiveSpan> accepted = redactionResult.accepted();

        // Recorded from the accepted spans rather than by comparing strings, so the counters describe what was
        // actually redacted. Under the old chain an overlap could record pii_redacted for a match that the secret
        // pattern would have covered better; now exactly the winning kind is counted.
        if (containsKind(accepted, SensitiveKind.PII)) {
            record(recordingMetrics, session == null ? "pii_redacted" : "pii_tokenized");
        }

        if (containsKind(accepted, SensitiveKind.SECRET)) {
            record(recordingMetrics, "secret_redacted");
        }

        return redactionResult.text();
    }

    private static boolean containsKind(List<SensitiveSpan> spans, SensitiveKind kind) {
        for (SensitiveSpan span : spans) {
            if (span.kind() == kind) {
                return true;
            }
        }

        return false;
    }

    private EffectivePolicy resolvePolicy(@Nullable Long workspaceId) {
        return effectivePolicyOf(findSettings(workspaceId));
    }

    /**
     * Unions the global properties with an already-fetched settings row. Separate from {@link #resolvePolicy(Long)} so
     * a caller that has already read the row -- {@link #resolveMcpOutboundPolicy}, which must not read it twice -- can
     * reuse it instead of reading again. Deliberately not an overload of {@code resolvePolicy}: both parameter types
     * are nullable reference types, so {@code resolvePolicy(null)} would be ambiguous at every existing call site.
     */
    private EffectivePolicy effectivePolicyOf(@Nullable AiGuardrailsWorkspaceSettings settings) {
        // Union semantics across global -> workspace: a level can enable a guardrail (or add blocked terms) but never
        // turn one off. A null field on `settings` just means "not set at this level" -- it unions with the GLOBAL
        // properties above, not with the tenant-default (null-workspaceId) row; a real workspace's settings never
        // fall back to the tenant-default row's values.
        boolean redactPii = globalPiiRedactionEnabled ||
            (settings != null && Boolean.TRUE.equals(settings.redactPii()));
        boolean redactSecrets = globalSecretRedactionEnabled ||
            (settings != null && Boolean.TRUE.equals(settings.redactSecrets()));

        Set<String> blockedTerms = new LinkedHashSet<>(globalBlockedTerms);

        if (settings != null && settings.blockedTerms() != null) {
            blockedTerms.addAll(parseBlockedTerms(settings.blockedTerms()));
        }

        boolean detectInjection = injectionClassifier != null &&
            (globalInjectionDetectionEnabled ||
                (settings != null && Boolean.TRUE.equals(settings.injectionDetectionEnabled())));
        boolean moderate = moderationClassifier != null &&
            (globalModerationEnabled || (settings != null && Boolean.TRUE.equals(settings.moderationEnabled())));
        boolean scanResponses = globalResponseScanEnabled ||
            (settings != null && Boolean.TRUE.equals(settings.scanResponses()));

        // Override, not union: unlike the booleans above, a workspace either sets its own threshold or it doesn't --
        // there is no global counterpart to combine it with. `settings.minConfidence()` is a Double; unboxing it only
        // inside this ternary (never assigning it to a primitive double first) keeps a null settings row or a null
        // field from ever being coerced through 0.0, which -- with the >= comparison in
        // SensitiveDataRedactor#filterByConfidence -- would silently disable the confidence filter entirely instead
        // of falling back to the CE default.
        double minConfidence = settings != null && settings.minConfidence() != null
            ? settings.minConfidence()
            : SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE;

        return new EffectivePolicy(
            redactPii, redactSecrets, blockedTerms, detectInjection, moderate, scanResponses, minConfidence);
    }

    private @Nullable AiGuardrailsWorkspaceSettings findSettings(@Nullable Long workspaceId) {
        try {
            Optional<AiGuardrailsWorkspaceSettings> settingsOptional =
                aiGuardrailsWorkspaceSettingsService.fetchSettings(workspaceId);

            return settingsOptional.orElse(null);
        } catch (Exception exception) {
            // A settings lookup failure must not take the request path down; global guardrails still apply.
            log.warn(
                "Failed to load AI guardrails workspace settings for workspace {}: {}", workspaceId,
                exception.getMessage());

            return null;
        }
    }

    private static void record(@Nullable AiGuardrailMetrics recordingMetrics, String event) {
        if (recordingMetrics != null) {
            recordingMetrics.record(event);
        }
    }

    private static @Nullable String findBlockedTerm(String content, Set<String> blockedTerms) {
        if (blockedTerms.isEmpty()) {
            return null;
        }

        String lowerContent = content.toLowerCase(Locale.ROOT);

        for (String blockedTerm : blockedTerms) {
            if (lowerContent.contains(blockedTerm)) {
                return blockedTerm;
            }
        }

        return null;
    }

    /**
     * Masks every case-insensitive occurrence of {@code blockedTerm} in {@code content} with
     * {@link #BLOCKED_TERM_PLACEHOLDER}. Used by {@link #checkInput} so a REDACT_AND_CONTINUE caller never forwards the
     * raw matched term.
     */
    private static String maskBlockedTerm(String content, String blockedTerm) {
        Pattern pattern = Pattern.compile(Pattern.quote(blockedTerm), Pattern.CASE_INSENSITIVE);

        return pattern.matcher(content)
            .replaceAll(Matcher.quoteReplacement(BLOCKED_TERM_PLACEHOLDER));
    }

    private static List<String> parseBlockedTerms(String blockedTerms) {
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

    /**
     * Result of {@link #checkInputs} for one input. {@code category} is {@code null} when the input triggered no
     * blocking violation; otherwise it is one of {@code "blocked_term"}, {@code "injection_flagged"}, or
     * {@code "moderation_flagged"} — {@code "blocked_term"} and {@code "injection_flagged"} are the same categories
     * {@link #applyToInputs} throws for; {@code "moderation_flagged"} is checked ONLY here (see {@link #checkInput}).
     * See {@link #checkInputs} for the full contract.
     *
     * @param text         the content to forward, with any blocking-specific transformation already applied -- a
     *                     blocked term masked out, or the whole message replaced for moderation
     * @param unmaskedText the same content with PII and secret redaction applied but WITHOUT the blocking-specific
     *                     transformation. Equal to {@code text} whenever nothing blocking-specific was applied
     *                     (injection has no locatable span, so nothing is masked for it either). Exists so a caller
     *                     running {@code BlockingMode.ALLOW} can forward the content unmodified while still learning
     *                     that the violation happened: this engine stays mode-agnostic and computes both candidates
     *                     rather than resolving the mode itself, keeping {@link #checkInputs}' documented contract that
     *                     the CALLER decides how to handle a blocking violation.
     * @param category     the violation category, or {@code null} when nothing blocking fired
     */
    public record GuardrailCheckResult(
        @Nullable String text, @Nullable String unmaskedText, @Nullable String category) {

        public boolean blocked() {
            return category != null;
        }
    }

    /**
     * The guardrail policy resolved for one call from the union of global properties and workspace settings.
     * {@code moderate} deliberately does NOT factor into {@link #anyInputGuardrailActive()} — moderation is checked
     * only by the non-throwing {@link #checkInput} path, never by {@link #checkAndRedact} (the throwing
     * {@link #applyToInputs} path {@code anyInputGuardrailActive} gates), so including it there would make
     * {@link #applyToInputs} loop over inputs it would still leave untouched.
     */
    private record EffectivePolicy(
        boolean redactPii, boolean redactSecrets, Set<String> blockedTerms, boolean detectInjection, boolean moderate,
        boolean scanResponses, double minConfidence) {

        boolean anyInputGuardrailActive() {
            return redactPii || redactSecrets || !blockedTerms.isEmpty() || detectInjection;
        }
    }
}
