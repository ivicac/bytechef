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
import com.bytechef.ee.platform.ai.guardrails.StreamingResponseRedactor;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings.BlockingMode;
import com.bytechef.ee.platform.ai.guardrails.exception.AiGuardrailViolationException;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Spring AI {@link CallAdvisor}/{@link StreamAdvisor} wiring the standalone {@link AiGuardrails} engine into an agent
 * surface's {@code ChatClient}. Both the AI Hub and Copilot chat surfaces register one instance of this advisor each
 * (see the module javadoc on {@link AiGuardrails} for what the engine itself owns vs. what stays with the caller).
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
 * {@link AiGuardrails#newStreamingResponseRedactor(Long, AiGuardrailMetrics, PiiTokenSession)} so a value split across
 * a chunk boundary is never emitted in the clear, flushing the redactor's held-back remainder as one trailing chunk
 * once the upstream stream completes and recording {@code response_redacted} at most once per stream (mirroring the AI
 * Gateway's own SSE redaction path in {@code AiGatewayFacadeImpl}). {@link StreamingResponseRedactor} scans each
 * emitted segment and then restores this call's session tokens through it, same ordering and same reason as
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
 * Runs at {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE} — the guardrail floor must see (and, in
 * {@code BLOCK} mode, be able to reject) the final outbound request before any other advisor's rewrite, and must see
 * the model's raw completion before any other advisor post-processes it.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class AiGuardrailsAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String NAME = "AiGuardrailsAdvisor";

    private final AiGuardrails aiGuardrails;
    private final AiGuardrailMetrics metrics;
    private final @Nullable Long workspaceId;

    public AiGuardrailsAdvisor(AiGuardrails aiGuardrails, @Nullable Long workspaceId, AiGuardrailMetrics metrics) {
        this.aiGuardrails = Objects.requireNonNull(aiGuardrails, "aiGuardrails");
        this.workspaceId = workspaceId;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        PiiTokenSession session = aiGuardrails.newTokenSession();

        try {
            ChatClientRequest guardedRequest = applyInputGuardrails(chatClientRequest, session);
            ChatClientResponse response = callAdvisorChain.nextCall(guardedRequest);

            return applyResponseGuardrails(response, session);
        } finally {
            session.close();
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
        ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {

        PiiTokenSession session = aiGuardrails.newTokenSession();
        ChatClientRequest guardedRequest;

        try {
            guardedRequest = applyInputGuardrails(chatClientRequest, session);
        } catch (AiGuardrailViolationException exception) {
            // The chain is never subscribed to on this path, so the doFinally below never runs -- release the
            // session here explicitly, mirroring adviseCall's try/finally for the same exception.
            session.close();

            return Flux.error(exception);
        }

        // Null exactly when streaming scanning is inactive AND session minted nothing -- see the overload's javadoc.
        // Restoration is not gated on the streaming-scan flag, but a session with nothing to restore and nothing to
        // scan should not pay the lookahead buffer's latency for no benefit.
        StreamingResponseRedactor redactor = aiGuardrails.newStreamingResponseRedactor(workspaceId, metrics, session);
        Flux<ChatClientResponse> stream = streamAdvisorChain.nextStream(guardedRequest);

        // Releases session on every termination of the returned Flux -- completion, error and cancellation alike.
        // PiiTokenSession deliberately has no AutoCloseable/try-with-resources shape of its own (see its javadoc):
        // doFinally is a Consumer<SignalType>, not a resource acquisition, so the sync path's try-with-resources and
        // this Consumer-based release are the two idiomatic shapes for their respective call styles, not an
        // inconsistency between them. Applied on both the pass-through and the redacting path below, so the session
        // is released either way.
        if (redactor == null) {
            return stream.doFinally(signalType -> session.close());
        }

        ChatClientRequest finalGuardedRequest = guardedRequest;

        return stream.map(response -> redactStreamChunk(response, redactor))
            .concatWith(Flux.defer(() -> flushStreamTail(redactor, finalGuardedRequest)))
            .doFinally(signalType -> session.close());
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
            ? aiGuardrails.checkInputs(texts, workspaceId, metrics)
            : aiGuardrails.tokenizeInputs(texts, workspaceId, session, metrics);
        boolean anyBlocked = results.stream()
            .anyMatch(GuardrailCheckResult::blocked);

        if (anyBlocked && aiGuardrails.resolveBlockingMode(workspaceId) == BlockingMode.BLOCK) {
            String category = results.stream()
                .filter(GuardrailCheckResult::blocked)
                .findFirst()
                .orElseThrow()
                .category();

            throw new AiGuardrailViolationException(category);
        }

        List<Message> patched = new ArrayList<>(instructions);
        boolean changed = false;

        for (int i = 0; i < guardedIndexes.size(); i++) {
            GuardrailCheckResult result = results.get(i);

            if (result.blocked()) {
                metrics.record("blocking_downgraded");
            }

            int index = guardedIndexes.get(i);
            Message original = instructions.get(index);
            String newText = result.text();

            if (!Objects.equals(newText, original.getText())) {
                patched.set(index, withText(original, newText));

                changed = true;
            }
        }

        if (!changed) {
            return chatClientRequest;
        }

        Prompt patchedPrompt = new Prompt(patched, prompt.getOptions());

        return chatClientRequest.mutate()
            .prompt(patchedPrompt)
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
     * @param session the session that tokenized this call's request, never {@code null} — only {@link #adviseCall}
     *                calls this method, and it always opens a real session first
     */
    private ChatClientResponse applyResponseGuardrails(ChatClientResponse response, PiiTokenSession session) {
        ChatResponse chatResponse = response.chatResponse();

        if (chatResponse == null) {
            return response;
        }

        List<Generation> generations = chatResponse.getResults();
        List<Generation> rewrittenGenerations = new ArrayList<>(generations.size());
        boolean responseRedacted = false;
        boolean piiRestored = false;

        for (Generation generation : generations) {
            AssistantMessage original = generation.getOutput();
            String text = original == null ? null : original.getText();

            if (text == null) {
                rewrittenGenerations.add(generation);

                continue;
            }

            String scanned = aiGuardrails.scanResponseText(text, workspaceId, metrics);
            String restored = aiGuardrails.restoreResponseText(scanned, session, metrics);

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
}
