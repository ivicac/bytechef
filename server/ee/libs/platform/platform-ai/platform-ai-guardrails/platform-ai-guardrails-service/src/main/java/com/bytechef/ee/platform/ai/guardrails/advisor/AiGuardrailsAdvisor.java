/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.advisor;

import com.bytechef.ee.platform.ai.guardrails.AiGuardrailMetrics;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails.GuardrailCheckResult;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails.TokenSessionHandle;
import com.bytechef.ee.platform.ai.guardrails.StreamingResponseRedactor;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolationAction;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsTarget;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings.BlockingMode;
import com.bytechef.ee.platform.ai.guardrails.exception.AiGuardrailViolationException;
import com.bytechef.ee.platform.ai.guardrails.violation.AiGuardrailViolationRecorder;
import com.bytechef.platform.ai.guardrails.ConversationScope;
import com.bytechef.platform.ai.guardrails.GuardrailAdvisorOrder;
import com.bytechef.platform.ai.guardrails.GuardrailSurface;
import com.bytechef.platform.ai.guardrails.PublishedInputSpans;
import com.bytechef.platform.ai.guardrails.RestorationDestination;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSessionToolContext;
import com.bytechef.platform.ai.sensitivedata.tokenization.SensitiveDataPolicy;
import com.bytechef.platform.ai.sensitivedata.tokenization.SensitiveDataPolicyToolContext;
import com.bytechef.platform.security.util.SecurityUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * Spring AI {@link CallAdvisor}/{@link StreamAdvisor} wiring the standalone {@link AiGuardrails} engine into an agent
 * surface's {@code ChatClient}. Registration is opt-in and per call site: nothing attaches this advisor automatically,
 * so it protects only the specific {@code ChatClient} builders that explicitly register an instance (typically obtained
 * via the CE seam {@link com.bytechef.platform.ai.guardrails.AiGuardrailsAdvisorProvider AiGuardrailsAdvisorProvider}
 * rather than constructed directly - see its javadoc for the workspace-resolution contract). This class deliberately
 * does not enumerate which surfaces currently register it: that inventory drifts as surfaces are added, and a stale
 * claim here is worse than no claim - check each surface's own {@code ChatClient} wiring instead of trusting this
 * comment (see the module javadoc on {@link AiGuardrails} for what the engine itself owns vs. what stays with the
 * caller).
 *
 * <p>
 * <b>Request direction — {@link CallAdvisor#adviseCall}</b> — a fresh {@link PiiTokenSession} is opened for the call,
 * and every USER and SYSTEM message's text is run through {@link AiGuardrails#tokenizeInputs}: PII becomes a
 * reversible, per-session token (e.g. {@code [PII_EMAIL_ADDRESS_1_k3n9]}) instead of an irreversible
 * {@code [REDACTED_*]} placeholder, and secrets are still redacted irreversibly. Two distinct values get two distinct
 * tokens, so the model can tell them apart. When a message additionally trips a <em>blocking</em> violation (a
 * blocked-term match, a flagged prompt injection, or a flagged moderation verdict — moderation only checked when a
 * moderation classifier bean is configured), the workspace's {@link BlockingMode} decides what happens next:
 * <ul>
 * <li>{@code BLOCK} (default) — the call is aborted with {@link AiGuardrailViolationException}, whose message carries
 * only the violation category, never the offending content.</li>
 * <li>{@code REDACT_AND_CONTINUE} — the offending content is masked (see {@link AiGuardrails#tokenizeInputs} — a
 * matched blocked term is masked in place, a moderation verdict replaces the whole message since it has no locatable
 * span), a {@code blocking_downgraded} metric is recorded, and the call proceeds with the masked text.</li>
 * <li>{@code ALLOW} — observe mode. The violation is detected and a {@code guardrail_allowed} metric is recorded, but
 * the content is forwarded UNMODIFIED: {@code GuardrailCheckResult#unmaskedText()} is patched in rather than
 * {@code text()}, so neither the blocked-term mask nor the moderation placeholder is applied. PII and secret redaction
 * still apply -- {@code BlockingMode} governs the three blocking guardrails only. Exists so a guardrail can be turned
 * on against real traffic before it enforces.</li>
 * </ul>
 * Any rewrite (blocking or not) replaces the affected message in the forwarded request; other message types (assistant,
 * tool) are left untouched. {@link StreamAdvisor#adviseStream} tokenizes the same way, through its own session — see
 * below for how that session's tokens come back out of the streamed response.
 * </p>
 *
 * <p>
 * <b>Response direction</b> — {@link CallAdvisor#adviseCall} scans the completion text via
 * {@link AiGuardrails#scanResponseText} once workspace/global policy enables response scanning, THEN restores the
 * request's session tokens back to their real values via {@link AiGuardrails#restoreResponseText}. This ordering is
 * load-bearing (see {@link #applyResponseGuardrails}): scanning first means it also sees any NEW PII the model produced
 * in the clear, so novel leakage is still caught; restoring first would hand the scanner the real values back and it
 * would immediately re-redact them, making the round trip a no-op. {@code response_redacted} is recorded here when
 * scanning masked anything; {@code pii_restored} (a token was substituted back) and {@code token_unresolved} (a
 * token-shaped span could not be resolved — the model mangled a token, or emitted one that was never minted) are
 * recorded by {@link AiGuardrails#restoreResponseText} itself, since it is the one place that both restores and knows
 * the outcome. The session is opened and closed for the whole call in {@link #adviseCall}, including on the
 * blocking-exception path.
 * </p>
 *
 * <p>
 * {@link StreamAdvisor#adviseStream} instead pipes each chunk through a single {@link StreamingResponseRedactor}
 * obtained from the session-carrying
 * {@link AiGuardrails#newStreamingResponseRedactor(AiGuardrailsSettingsTarget, AiGuardrailMetrics, PiiTokenSession)} so
 * a value split across a chunk boundary is never emitted in the clear, flushing the redactor's held-back remainder as
 * one trailing chunk once the upstream stream completes and recording {@code response_redacted} at most once per stream
 * (mirroring the AI Gateway's own SSE redaction path in {@code AiGatewayFacadeImpl}). {@link StreamingResponseRedactor}
 * scans each emitted segment and then restores this call's session tokens through it, same ordering and same reason as
 * {@link #applyResponseGuardrails} above — see its class javadoc. The session is opened once at the top of
 * {@link #adviseStream} and released via {@code Flux#doFinally} on the returned stream, so completion, error and
 * cancellation all release it exactly once; the one path outside that {@code Flux} — the request being rejected under
 * {@code BlockingMode.BLOCK} before any subscription happens — releases it explicitly instead, since {@code doFinally}
 * on a stream nobody subscribed to never runs. This session-carrying overload of {@code newStreamingResponseRedactor} —
 * see its javadoc — decouples restoration from the streaming-scan policy gate: a workspace with streaming response
 * scanning policy-disabled but a session that minted at least one token still gets that token restored, and
 * additionally gets new-PII/secret scanning only when the policy is active. It still returns {@code null} exactly when
 * NEITHER applies — scanning is inactive AND {@code session} minted nothing — so a stream with nothing to restore and
 * nothing to scan is not forced through the lookahead buffer for no benefit; that {@code null} path is handled above by
 * returning the upstream {@code Flux} straight through, still wrapped in the same {@code doFinally} so the session is
 * released either way.
 * </p>
 *
 * <p>
 * {@code metrics} is a per-advisor-instance {@link AiGuardrailMetrics}, tagged with this surface's own {@code surface}
 * value (e.g. {@code ai_hub} or {@code ai_agent}) — deliberately independent of the engine's own internal metrics bean,
 * whose {@code surface} is a single deployment-wide property ({@code bytechef.ai.guardrails.surface}) and which is a
 * no-op unless {@code bytechef.ai.gateway.enabled=true}. Every event this advisor's request/response path can trigger —
 * the request-direction events ({@code pii_tokenized}/{@code pii_redacted}, {@code secret_redacted},
 * {@code blocked_term}, {@code injection_flagged}, {@code moderation_flagged}, recorded inside
 * {@link AiGuardrails#tokenizeInputs}/{@link AiGuardrails#checkInputs}), the response-direction events
 * ({@code pii_restored}, {@code token_unresolved}, recorded inside {@link AiGuardrails#restoreResponseText} for
 * {@link #adviseCall} and inside {@link StreamingResponseRedactor} itself for {@link #adviseStream} — the streaming
 * path never calls {@code restoreResponseText}, so {@code StreamingResponseRedactor} records these two events directly
 * off the same {@code metrics} instance it is constructed with) as well as the events decided here
 * ({@code blocking_downgraded}, {@code response_redacted}) — goes through this instance, since each of those engine
 * methods takes {@code metrics} as a parameter and records through whatever instance the caller passes rather than
 * through the engine's own bean. This is deliberate: the advisor surface must be accurately tagged and must emit
 * regardless of the gateway toggle. Only {@link AiGuardrails#applyToInputs} — the gateway adapter's throwing entry
 * point, not used by this advisor — still records through the engine's own bean.
 * </p>
 *
 * <p>
 * <b>Conversation scope</b> — the session is per call by default, so a token minted for one turn is dead in the next.
 * When {@link ConversationScope#trustedKey} accepts this call's conversation id — only a platform-issued id with a
 * verified user and a resolved workspace qualifies, never a workflow author's expression — the session is instead
 * loaded from that conversation's stored tokens, minted into, and saved back before it is closed. Everything else is
 * unchanged: no key means no load, no store, and the same fresh session as before. The save necessarily precedes
 * {@link PiiTokenSession#close()}, which clears the mapping (see {@link #releaseSession}).
 * </p>
 *
 * <p>
 * Runs at {@link GuardrailAdvisorOrder#WORKSPACE_FLOOR} — the guardrail floor must see (and, in {@code BLOCK} mode, be
 * able to reject) the final outbound request before any other advisor's rewrite, and must see the model's raw
 * completion before any other advisor post-processes it.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class AiGuardrailsAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String NAME = "AiGuardrailsAdvisor";

    private static final Logger log = LoggerFactory.getLogger(AiGuardrailsAdvisor.class);

    private final AiGuardrails aiGuardrails;
    private final AiGuardrailMetrics metrics;
    private final @Nullable AiGuardrailViolationRecorder aiGuardrailViolationRecorder;
    private final AiGuardrailsSettingsTarget target;
    private final RestorationDestination destination;
    // Explicit rather than read off metrics.getSurface() at gate-check time: withSessionInToolContext's
    // workflowSurface gate is a security boundary, and metrics is telemetry. A rename or split of the metrics
    // surface tag reads as a telemetry-only change and would silently disable the gate if it stayed keyed on
    // metrics -- this field is set once, at construction, from the identical `surface` argument the caller already
    // passes to build metrics, so that coupling is visible at every call site instead of buried inside this class.
    private final String surface;

    public AiGuardrailsAdvisor(
        AiGuardrails aiGuardrails, AiGuardrailsSettingsTarget target, AiGuardrailMetrics metrics, String surface,
        RestorationDestination destination) {

        this(aiGuardrails, target, metrics, surface, null, destination);
    }

    /**
     * @param surface                      the calling surface -- the identical value the caller used to build
     *                                     {@code metrics} -- gating {@link #withSessionInToolContext}'s outbound
     *                                     tool-argument restoration; see this class's {@code surface} field javadoc
     * @param aiGuardrailViolationRecorder the per-detection drill-down recorder, or {@code null} where none is wired.
     *                                     Optional rather than required so the nine existing construction sites -- and
     *                                     any deployment that has not turned recording on -- are unaffected.
     * @param destination                  where this advisor's restored response text goes; see
     *                                     {@link RestorationDestination} for why the surface alone cannot answer this
     */
    @SuppressFBWarnings("EI2")
    public AiGuardrailsAdvisor(
        AiGuardrails aiGuardrails, AiGuardrailsSettingsTarget target, AiGuardrailMetrics metrics, String surface,
        @Nullable AiGuardrailViolationRecorder aiGuardrailViolationRecorder, RestorationDestination destination) {

        this.aiGuardrails = Objects.requireNonNull(aiGuardrails, "aiGuardrails");
        this.target = Objects.requireNonNull(target, "target");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.surface = Objects.requireNonNull(surface, "surface");
        this.aiGuardrailViolationRecorder = aiGuardrailViolationRecorder;
        this.destination = Objects.requireNonNull(destination, "destination");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return GuardrailAdvisorOrder.WORKSPACE_FLOOR;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ConversationScope.@Nullable Key conversationKey = trustedConversationKey(chatClientRequest);
        TokenSessionHandle tokenSessionHandle = newTokenSession(conversationKey);

        PiiTokenSession session = tokenSessionHandle.session();

        try {
            ChatClientRequest guardedRequest = applyInputGuardrails(chatClientRequest, session);

            // Resolved once, here, and threaded into both halves below as a parameter -- never cached on a field,
            // since a field would silently go stale if this advisor instance ever stopped being per-call, and a
            // parameter cannot. withSessionInToolContext needs it only for the canvas AI Agent surface;
            // applyResponseGuardrails needs it only for a WORKFLOW_OUTPUT destination -- resolving it whenever
            // EITHER half would consult it, and skipping the lookup entirely otherwise, is what keeps a
            // CONVERSATION-destination call from any other surface (AI Hub, Copilot) paying zero settings reads,
            // exactly as before this method existed. adviseStream resolves the same lookup with a narrower,
            // surface-only gate below -- see its own comment -- since its response half never consults this
            // setting at all (D8: a streamed response always restores).
            boolean workflowSurface = GuardrailSurface.AI_AGENT.equals(surface);
            boolean restoreIntoWorkflowOutput = true;

            if (workflowSurface || destination == RestorationDestination.WORKFLOW_OUTPUT) {
                restoreIntoWorkflowOutput = aiGuardrails.isRestoreIntoWorkflowOutput(target);
            }

            guardedRequest = withSessionInToolContext(guardedRequest, session, restoreIntoWorkflowOutput);

            ChatClientResponse response = callAdvisorChain.nextCall(guardedRequest);

            return applyResponseGuardrails(response, session, restoreIntoWorkflowOutput);
        } finally {
            releaseSession(conversationKey, tokenSessionHandle);
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
        ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {

        ConversationScope.@Nullable Key conversationKey = trustedConversationKey(chatClientRequest);
        TokenSessionHandle tokenSessionHandle = newTokenSession(conversationKey);

        PiiTokenSession session = tokenSessionHandle.session();
        ChatClientRequest guardedRequest;

        try {
            guardedRequest = applyInputGuardrails(chatClientRequest, session);

            // adviseStream's own RESPONSE half never consults this setting at all -- D8: a streamed response
            // restores unconditionally through StreamingResponseRedactor below, untouched by this task. This
            // lookup exists only for withSessionInToolContext's outbound tool-call-argument gate, so unlike
            // adviseCall's resolution above, the gate here stays surface-only -- unchanged from before this task.
            boolean workflowSurface = GuardrailSurface.AI_AGENT.equals(surface);
            boolean restoreIntoWorkflowOutput = true;

            if (workflowSurface) {
                restoreIntoWorkflowOutput = aiGuardrails.isRestoreIntoWorkflowOutput(target);
            }

            guardedRequest = withSessionInToolContext(guardedRequest, session, restoreIntoWorkflowOutput);
        } catch (AiGuardrailViolationException exception) {
            // The chain is never subscribed to on this path, so the doFinally below never runs -- release the
            // session here explicitly, mirroring adviseCall's try/finally for the same exception.
            releaseSession(conversationKey, tokenSessionHandle);

            return Flux.error(exception);
        }

        // Null exactly when streaming scanning is inactive AND session minted nothing -- see the overload's javadoc.
        // Restoration is not gated on the streaming-scan flag, but a session with nothing to restore and nothing to
        // scan should not pay the lookahead buffer's latency for no benefit.
        StreamingResponseRedactor redactor = aiGuardrails.newStreamingResponseRedactor(target, metrics, session);
        Flux<ChatClientResponse> stream = streamAdvisorChain.nextStream(guardedRequest);

        // Releases session on every termination of the returned Flux -- completion, error and cancellation alike.
        // PiiTokenSession deliberately has no AutoCloseable/try-with-resources shape of its own (see its javadoc):
        // doFinally is a Consumer<SignalType>, not a resource acquisition, so the sync path's try-with-resources and
        // this Consumer-based release are the two idiomatic shapes for their respective call styles, not an
        // inconsistency between them. Applied on both the pass-through and the redacting path below, so the session
        // is released either way.
        if (redactor == null) {
            return stream.doFinally(signalType -> releaseSession(conversationKey, tokenSessionHandle));
        }

        ChatClientRequest finalGuardedRequest = guardedRequest;

        return stream.map(response -> redactStreamChunk(response, redactor))
            .concatWith(Flux.defer(() -> flushStreamTail(redactor, finalGuardedRequest)))
            .doFinally(signalType -> releaseSession(conversationKey, tokenSessionHandle));
    }

    /**
     * The conversation this call's tokens may be keyed to, or {@code null} when the call is request-scoped — which is
     * every surface whose conversation id the platform did not itself issue, the canvas AI Agent's author-written
     * expression included. See {@link ConversationScope#trustedKey}.
     */
    private ConversationScope.@Nullable Key trustedConversationKey(ChatClientRequest chatClientRequest) {
        return ConversationScope.trustedKey(chatClientRequest.context(), target.workspaceId())
            .orElse(null);
    }

    /**
     * Opens the session this call mints into: the conversation's stored one, rehydrated, for a trusted key, and
     * otherwise the same fresh, request-scoped session this advisor has always opened. The {@code null} branch
     * deliberately calls the no-argument {@link AiGuardrails#newTokenSession()} rather than passing {@code null} on, so
     * a request without a trusted key reaches the engine through exactly the call it did before conversation scoping
     * existed.
     */
    private TokenSessionHandle newTokenSession(ConversationScope.@Nullable Key conversationKey) {
        if (conversationKey == null) {
            return new TokenSessionHandle(aiGuardrails.newTokenSession(), true);
        }

        return aiGuardrails.newTokenSession(conversationKey);
    }

    /**
     * Stores {@code tokenSessionHandle}'s tokens against a trusted conversation, then closes the session. Ordering is
     * load-bearing: {@link PiiTokenSession#close()} clears the mapping, so a save placed after it stores nothing while
     * every test that only checks "no exception" still passes. Nothing is stored for a {@code null}
     * {@code conversationKey} — the request-scoped case — which is what keeps an untrusted call's behaviour identical
     * to what it was.
     *
     * <p>
     * The store contract is fail-soft, but this advisor cannot verify every implementation of it, and this runs from
     * {@link #adviseCall}'s own {@code finally}: an implementation that threw would leave the decrypted mapping live in
     * memory and turn an already-successful model response into a failure. The close therefore sits in a
     * {@code finally} and the save's failure is logged rather than propagated — a store outage costs cross-turn
     * coherence, never the request.
     * </p>
     */
    private void releaseSession(
        ConversationScope.@Nullable Key conversationKey, TokenSessionHandle tokenSessionHandle) {

        try {
            if (conversationKey != null) {
                aiGuardrails.saveTokenSession(conversationKey, tokenSessionHandle);
            }
        } catch (RuntimeException exception) {
            log.warn("Could not save the conversation's token session; continuing request-scoped", exception);
        } finally {
            PiiTokenSession session = tokenSessionHandle.session();

            session.close();
        }
    }

    /**
     * Runs every USER/SYSTEM message's text through {@link AiGuardrails#tokenizeInputs} when {@code session} is not
     * {@code null} (PII becomes a reversible session token) or {@link AiGuardrails#checkInputs} when {@code session} is
     * {@code null} (PII is redacted irreversibly). Both {@link CallAdvisor#adviseCall} and
     * {@link StreamAdvisor#adviseStream} always pass a real session today — the {@code null} branch stays here as the
     * documented, tested fallback shape of this method rather than being deleted as unreachable. Either way, throws
     * {@link AiGuardrailViolationException} for a blocking violation under {@code BLOCK} mode, or masks and records
     * {@code blocking_downgraded} under {@code REDACT_AND_CONTINUE}. Returns {@code chatClientRequest} unchanged when
     * nothing was guarded or nothing changed.
     *
     * @param session the session to mint tokens into, or {@code null} to redact PII irreversibly instead
     */
    private ChatClientRequest applyInputGuardrails(
        ChatClientRequest chatClientRequest, @Nullable PiiTokenSession session) {

        Prompt prompt = chatClientRequest.prompt();
        List<Message> instructions = prompt.getInstructions();

        List<Integer> guardedIndexes = new ArrayList<>();
        List<String> texts = new ArrayList<>();

        for (int index = 0; index < instructions.size(); index++) {
            Message message = instructions.get(index);
            MessageType messageType = message.getMessageType();

            if (messageType != MessageType.USER && messageType != MessageType.SYSTEM) {
                continue;
            }

            String text = message.getText();

            if (text == null || text.isEmpty()) {
                continue;
            }

            guardedIndexes.add(index);
            texts.add(text);
        }

        if (texts.isEmpty()) {
            return chatClientRequest;
        }

        List<GuardrailCheckResult> results = session == null
            ? aiGuardrails.checkInputs(texts, target, metrics)
            : aiGuardrails.tokenizeInputs(texts, target, session, metrics);
        boolean anyBlocked = results.stream()
            .anyMatch(GuardrailCheckResult::blocked);

        // Resolved only when something is blocked, so an unblocked request costs exactly the settings lookups it
        // did before ALLOW existed.
        BlockingMode blockingMode = anyBlocked ? aiGuardrails.resolveBlockingMode(target) : null;

        if (blockingMode == BlockingMode.BLOCK) {
            // Submitted BEFORE the throw. Records exist to explain what the guardrails did, and a blocked call is the
            // one an operator is most likely to be asked about -- leaving it as the single case with no record would
            // be the worst possible gap.
            submitViolationRecords(results, blockingMode, target);

            String category = results.stream()
                .filter(GuardrailCheckResult::blocked)
                .findFirst()
                .orElseThrow()
                .category();

            throw new AiGuardrailViolationException(category);
        }

        submitViolationRecords(results, blockingMode, target);

        List<SensitiveSpan> publishedSpans = userMessageSpans(instructions, guardedIndexes, results);

        List<Message> patched = new ArrayList<>(instructions);
        boolean changed = false;

        for (int i = 0; i < guardedIndexes.size(); i++) {
            GuardrailCheckResult result = results.get(i);

            boolean allowed = result.blocked() && blockingMode == BlockingMode.ALLOW;

            if (result.blocked()) {
                metrics.record(allowed ? "guardrail_allowed" : "blocking_downgraded");
            }

            int index = guardedIndexes.get(i);
            Message original = instructions.get(index);
            String newText = allowed ? result.unmaskedText() : result.text();

            if (!Objects.equals(newText, original.getText())) {
                patched.set(index, withText(original, newText));

                changed = true;
            }
        }

        if (!changed && publishedSpans.isEmpty()) {
            return chatClientRequest;
        }

        ChatClientRequest.Builder builder = chatClientRequest.mutate();

        if (changed) {
            builder.prompt(new Prompt(patched, prompt.getOptions()));
        }

        if (!publishedSpans.isEmpty()) {
            builder.context(PublishedInputSpans.CONTEXT_KEY, publishedSpans);
        }

        return builder.build();
    }

    /**
     * Merges {@code session} into {@code chatClientRequest}'s tool context so it survives the hop onto the worker
     * thread the model's tool calls run on -- tool calls run outside the request thread and inherit none of its
     * {@code ThreadLocal} state, which is why {@code AgentToolInvocationContext}-style context travels through Spring
     * AI's {@code ToolContext} instead (see this class's javadoc and {@code RehydrateContextToolCallback}).
     *
     * <p>
     * Reads the tool context off {@code chatClientRequest}'s {@link Prompt#getOptions()} when those options are
     * {@link ToolCallingChatOptions} -- the shape every provider {@code ChatOptions} ByteChef registers implements, and
     * the only shape {@code ToolContext} is actually threaded through to a running tool call (see
     * {@code DefaultChatClientUtils#toChatClientRequest}: a {@code ChatClientRequest}'s own {@code context()} map is a
     * separate, advisor-only channel that tool callbacks never see). {@link PiiTokenSessionToolContext#into} and
     * {@link SensitiveDataPolicyToolContext#into} both merge rather than replace, so every existing entry -- including
     * {@code AgentToolInvocationContext}'s workspace/user/environment/tenant/authentication keys, which live in this
     * same map -- survives untouched.
     * </p>
     *
     * <p>
     * Carries {@link AiGuardrails#resolveToolBoundaryPolicy(AiGuardrailsSettingsTarget)} onto the same map, alongside
     * the session -- this is what lets {@code PiiTokenBoundaryToolCallingManager} honour this workspace's own
     * {@code redactPii}/{@code redactSecrets}/{@code minConfidence} settings at the tool-call boundary instead of a
     * fixed constant. The 1-arg overload never itself resolves {@code restoreOutboundArguments} (it always returns
     * {@code true} there); this method builds the effective policy from that base plus
     * {@code restoreIntoWorkflowOutput} so the kinds/threshold still come from the same {@code target} every other
     * guardrail check here already uses, without this method re-reading the setting
     * {@code adviseCall}/{@code adviseStream} already resolved.
     * </p>
     *
     * <p>
     * Overrides the base policy's {@code restoreOutboundArguments} with
     * {@code !workflowSurface || restoreIntoWorkflowOutput} -- {@code workflowSurface} is
     * {@code GuardrailSurface.AI_AGENT.equals(surface)}, keyed on this advisor's own {@code surface} field,
     * deliberately NOT on {@link #destination} and deliberately not read off {@code metrics.getSurface()} (see the
     * field's own javadoc for why). A tool call leaves the agent for a system the workflow author chose no matter how
     * the agent's own reply reaches its caller, so all three canvas AI Agent actions gate their OUTBOUND tool-call
     * arguments on {@code restoreIntoWorkflowOutput}, streaming included -- even a streaming call, whose own response
     * always restores because {@link #destination} is {@code CONVERSATION}, still withholds its tool arguments when the
     * workspace setting is off. Every other surface behind this advisor (Copilot, AI Hub) resolves
     * {@code workflowSurface} {@code false} here and so always restores outbound arguments, matching
     * {@link SensitiveDataPolicy#DEFAULT}, regardless of what {@code restoreIntoWorkflowOutput} carries.
     * </p>
     *
     * <p>
     * Returns {@code chatClientRequest} unchanged when its options are not {@link ToolCallingChatOptions} (including
     * {@code null} options): there is no {@code ToolContext} channel to carry the session through on that path, so a
     * tool call reached that way -- if the request has any -- would not get its arguments/results restored. Every
     * options type this codebase's {@code ChatModel}s produce implements {@link ToolCallingChatOptions}, so this is a
     * defensive fallback rather than an expected path.
     * </p>
     *
     * @param restoreIntoWorkflowOutput {@code AiGuardrails#isRestoreIntoWorkflowOutput(AiGuardrailsSettingsTarget)}'s
     *                                  result, resolved once by the caller ({@code adviseCall}/{@code adviseStream}) --
     *                                  never read here directly, so this method never re-triggers the settings lookup
     */

    private ChatClientRequest withSessionInToolContext(
        ChatClientRequest chatClientRequest, PiiTokenSession session, boolean restoreIntoWorkflowOutput) {

        Prompt prompt = chatClientRequest.prompt();
        ChatOptions chatOptions = prompt.getOptions();

        if (!(chatOptions instanceof ToolCallingChatOptions toolCallingChatOptions)) {
            return chatClientRequest;
        }

        Map<String, Object> existingToolContext = toolCallingChatOptions.getToolContext();
        Map<String, Object> toolContextWithSession = PiiTokenSessionToolContext.into(
            existingToolContext == null ? Map.of() : existingToolContext, session);

        boolean workflowSurface = GuardrailSurface.AI_AGENT.equals(surface);
        SensitiveDataPolicy basePolicy = aiGuardrails.resolveToolBoundaryPolicy(target);
        SensitiveDataPolicy policy = new SensitiveDataPolicy(
            basePolicy.kinds(), basePolicy.minConfidence(), !workflowSurface || restoreIntoWorkflowOutput);
        Map<String, Object> mergedToolContext = SensitiveDataPolicyToolContext.into(toolContextWithSession, policy);

        ChatOptions mergedChatOptions = toolCallingChatOptions.mutate()
            .toolContext(mergedToolContext)
            .build();

        Prompt mergedPrompt = new Prompt(prompt.getInstructions(), mergedChatOptions);

        return chatClientRequest.mutate()
            .prompt(mergedPrompt)
            .build();
    }

    /**
     * Scans the completion's assistant text via {@link AiGuardrails#scanResponseText}, THEN restores {@code session}'s
     * tokens back to their real values via {@link AiGuardrails#restoreResponseText} (which also records
     * {@code pii_restored}/{@code token_unresolved}), rewriting the text when either step changed it.
     *
     * <p>
     * Ordering is load-bearing: scanning first means it sees tokens (which match no PII pattern) plus any NEW PII the
     * model produced, so novel leakage is still caught. Restoring first would hand the scanner the real values back and
     * it would immediately re-redact them, making the round trip a no-op.
     * </p>
     *
     * <p>
     * Whether to rewrite a generation is decided from the two steps' effects independently — {@code scanned} vs.
     * {@code text}, and {@code restored} vs. {@code scanned} — rather than by comparing the final {@code restored} text
     * against the original {@code text}. Collapsing that into one comparison would depend on scan output ({@code
     * [REDACTED_*]}) and restore input ({@code [PII_*]}) never coinciding closely enough for a restore to exactly undo
     * a scan's redaction; that would silently drop a genuine redaction on a coincidence between two files, rather than
     * being a stated invariant.
     * </p>
     *
     * <p>
     * {@code response_redacted} is recorded when scanning masked anything in any generation (unchanged from before
     * tokenization existed). Returns {@code response} unchanged when neither step changed anything in any generation.
     * </p>
     *
     * <p>
     * Restoration itself is gated on {@link #destination}: a conversation always restores, since it goes back to
     * whoever just supplied the value. A workflow output restores only while {@code restoreIntoWorkflowOutput} says the
     * workspace still wants that -- resolved ONCE by {@link #adviseCall}, before this method and
     * {@link #withSessionInToolContext} both run, and passed in here as a parameter rather than read again from
     * {@link AiGuardrails#isRestoreIntoWorkflowOutput(AiGuardrailsSettingsTarget)}. That single resolution is what
     * stops a multi-generation response from reading the setting twice and disagreeing with itself -- and, more
     * importantly, stops the request half ({@code withSessionInToolContext}'s outbound tool-call-argument restoration)
     * and this response half from disagreeing with each other were the workspace toggle flipped mid-call. When
     * restoration is withheld, {@code restore_suppressed} is recorded at most once per call (not once per generation,
     * matching {@code response_redacted}/{@code pii_restored}'s own incidence-counter shape), and only when withholding
     * actually changed something -- a call whose response carried no resolvable token in the first place has nothing to
     * suppress, and must not tick the same counter a genuine withholding does (see
     * {@code SensitiveDataMetrics#recordRestoreSuppressed()}).
     * </p>
     *
     * <p>
     * That once-per-call guarantee is per boundary, not per agent turn: {@code PiiTokenBoundaryToolCallingManager}
     * records its own {@code restore_suppressed} independently, for outbound tool-call arguments, under the identical
     * gate. A single canvas-agent turn passes through both boundaries, so a turn that withholds restoration on both the
     * response text AND a tool call's arguments records {@code restore_suppressed} twice, both tagged
     * {@code surface=ai_agent}. That is harmless for the question the metric exists to answer -- whether a workspace is
     * withholding data at all -- but the counter should not be read as a count of turns.
     * </p>
     *
     * @param session                   the session that tokenized this call's request, never {@code null} — only
     *                                  {@link #adviseCall} calls this method, and it always opens a real session first
     * @param restoreIntoWorkflowOutput {@code AiGuardrails#isRestoreIntoWorkflowOutput(AiGuardrailsSettingsTarget)}'s
     *                                  result, resolved once by {@link #adviseCall} -- never read here directly
     */
    private ChatClientResponse applyResponseGuardrails(
        ChatClientResponse response, PiiTokenSession session, boolean restoreIntoWorkflowOutput) {

        ChatResponse chatResponse = response.chatResponse();

        if (chatResponse == null) {
            return response;
        }

        boolean restoring = destination == RestorationDestination.CONVERSATION || restoreIntoWorkflowOutput;

        List<Generation> generations = chatResponse.getResults();
        List<Generation> rewrittenGenerations = new ArrayList<>(generations.size());
        boolean responseRedacted = false;
        boolean piiRestored = false;
        boolean restoreSuppressed = false;

        for (Generation generation : generations) {
            AssistantMessage original = generation.getOutput();
            String text = original == null ? null : original.getText();

            if (text == null) {
                rewrittenGenerations.add(generation);

                continue;
            }

            String scanned = aiGuardrails.scanResponseText(text, target, metrics);
            String restored;

            if (restoring) {
                restored = aiGuardrails.restoreResponseText(scanned, session, metrics);
            } else {
                restored = scanned;

                PiiTokenSession.RestoreResult wouldHaveRestored = session.restoreWithUnresolvedCount(scanned);

                if (!Objects.equals(wouldHaveRestored.text(), scanned)) {
                    restoreSuppressed = true;
                }
            }

            // restoreResponseText is declared @Nullable but only ever returns null for a null input, and scanned is
            // provably non-null at this point (text is non-null, and scanResponseText only returns null for a null
            // input) -- just not statically so, hence the null-safe comparison rather than a direct dereference.
            boolean generationRedacted = !scanned.equals(text);
            boolean generationRestored = !Objects.equals(restored, scanned);

            if (!generationRedacted && !generationRestored) {
                rewrittenGenerations.add(generation);

                continue;
            }

            responseRedacted = responseRedacted || generationRedacted;
            piiRestored = piiRestored || generationRestored;

            AssistantMessage rewritten = AssistantMessage.builder()
                .content(restored)
                .properties(original.getMetadata())
                .toolCalls(original.getToolCalls())
                .media(original.getMedia())
                .build();

            rewrittenGenerations.add(new Generation(rewritten, generation.getMetadata()));
        }

        if (restoreSuppressed) {
            metrics.recordRestoreSuppressed();
        }

        if (!responseRedacted && !piiRestored) {
            return response;
        }

        if (responseRedacted) {
            metrics.record("response_redacted");
        }

        ChatResponse rewrittenChatResponse = ChatResponse.builder()
            .generations(rewrittenGenerations)
            .metadata(chatResponse.getMetadata())
            .build();

        return response.mutate()
            .chatResponse(rewrittenChatResponse)
            .build();
    }

    /**
     * Pushes one streamed chunk's assistant text through {@code redactor}, replacing the chunk's content with whatever
     * is now safe to emit (which may be empty while a value straddling the chunk boundary is still held back). Chunks
     * that carry no assistant text are passed through unchanged.
     */
    private ChatClientResponse redactStreamChunk(ChatClientResponse response, StreamingResponseRedactor redactor) {
        ChatResponse chatResponse = response.chatResponse();

        if (chatResponse == null) {
            return response;
        }

        Generation generation = chatResponse.getResult();

        if (generation == null) {
            return response;
        }

        AssistantMessage original = generation.getOutput();
        String chunkText = original == null ? null : original.getText();
        String deltaText = redactor.push(chunkText);

        AssistantMessage redactedMessage = AssistantMessage.builder()
            .content(deltaText)
            .properties(original == null ? Map.of() : original.getMetadata())
            .toolCalls(original == null ? List.of() : original.getToolCalls())
            .media(original == null ? List.of() : original.getMedia())
            .build();

        Generation redactedGeneration = new Generation(redactedMessage, generation.getMetadata());

        ChatResponse redactedChatResponse = ChatResponse.builder()
            .generations(List.of(redactedGeneration))
            .metadata(chatResponse.getMetadata())
            .build();

        return response.mutate()
            .chatResponse(redactedChatResponse)
            .build();
    }

    /**
     * Emitted once the upstream stream completes: flushes {@code redactor}'s held-back remainder as a single trailing
     * chunk (when non-empty) and records {@code response_redacted} exactly once for the whole stream when anything was
     * masked across any {@link StreamingResponseRedactor#push} or this flush.
     */
    private Flux<ChatClientResponse> flushStreamTail(StreamingResponseRedactor redactor, ChatClientRequest request) {
        String tail = redactor.flush();

        if (redactor.isRedacted()) {
            metrics.record("response_redacted");
        }

        if (tail.isEmpty()) {
            return Flux.empty();
        }

        ChatResponse chatResponse = ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(tail))))
            .build();

        ChatClientResponse tailResponse = ChatClientResponse.builder()
            .chatResponse(chatResponse)
            .context(request.context())
            .build();

        return Flux.just(tailResponse);
    }

    /**
     * Returns {@code message} with its text replaced by {@code text}, preserving metadata (and media, for a
     * {@link UserMessage}). Message types other than USER/SYSTEM are returned unchanged — callers only ever pass one of
     * those two, since {@link #applyInputGuardrails} only guards USER/SYSTEM messages.
     */
    private static Message withText(Message message, String text) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.mutate()
                .text(text)
                .build();
        }

        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.mutate()
                .text(text)
                .build();
        }

        return message;
    }

    /**
     * Submits one drill-down record per detected span. Assembled here rather than in {@code AiGuardrails} because this
     * is the only place that knows all three of the workspace, the surface and the resolved action -- the engine
     * resolves none of them, and a record that could not say what was done about a detection would read every observe-
     * mode counterfactual as an enforcement.
     *
     * <p>
     * Submits redactions too, not only blocking violations: "which pattern fired, and where" is mostly a question about
     * redactions, and a store that held only blocks would answer it for the rarest case alone.
     * </p>
     */
    private void submitViolationRecords(
        List<GuardrailCheckResult> results, @Nullable BlockingMode blockingMode, AiGuardrailsSettingsTarget target) {

        if (aiGuardrailViolationRecorder == null) {
            return;
        }

        boolean enabled = aiGuardrails.isViolationRecordingEnabled(target);

        if (!enabled) {
            return;
        }

        AiGuardrailViolationAction action = actionOf(blockingMode);

        for (GuardrailCheckResult result : results) {
            aiGuardrailViolationRecorder.submit(
                result.spans(), true, action, metrics.getSurface(), target.workspaceId(), null,
                SecurityUtils.fetchCurrentUserLogin()
                    .orElse(null),
                metrics::record);
        }
    }

    /**
     * Maps the mode that governed this call to what was actually done. A null mode means nothing blocking fired, so any
     * span present was redacted rather than blocked or observed.
     */
    private static AiGuardrailViolationAction actionOf(@Nullable BlockingMode blockingMode) {
        if (blockingMode == BlockingMode.BLOCK) {
            return AiGuardrailViolationAction.BLOCKED;
        }

        if (blockingMode == BlockingMode.ALLOW) {
            return AiGuardrailViolationAction.ALLOWED;
        }

        return AiGuardrailViolationAction.REDACTED;
    }

    /**
     * The spans the floor detected in the caller's USER messages, for {@link PublishedInputSpans}. SYSTEM messages are
     * guarded too, but their spans are the operator's prompt, not the caller's input, and a per-node input check judges
     * the latter.
     */
    private static List<SensitiveSpan> userMessageSpans(
        List<Message> instructions, List<Integer> guardedIndexes, List<GuardrailCheckResult> results) {

        List<SensitiveSpan> spans = new ArrayList<>();

        for (int i = 0; i < guardedIndexes.size(); i++) {
            Message message = instructions.get(guardedIndexes.get(i));

            if (message.getMessageType() != MessageType.USER) {
                continue;
            }

            spans.addAll(results.get(i)
                .spans());
        }

        return List.copyOf(spans);
    }
}
