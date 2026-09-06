/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.gateway.guardrail.AiGatewayModerationClassifier;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrailMetrics;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings.BlockingMode;
import com.bytechef.ee.platform.ai.guardrails.exception.AiGuardrailViolationException;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataDetector;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGuardrailsAdvisorTest {

    private static final Long WORKSPACE_ID = 42L;
    private static final Pattern EMAIL_TOKEN_PATTERN = Pattern.compile("\\[PII_EMAIL_ADDRESS_1_[a-z0-9]{4}\\]");

    private final AiGuardrailsWorkspaceSettingsService settingsService =
        mock(AiGuardrailsWorkspaceSettingsService.class);
    private final SimpleMeterRegistry engineMeterRegistry = new SimpleMeterRegistry();
    private final AiGuardrailMetrics engineMetrics = new AiGuardrailMetrics(engineMeterRegistry, "gateway");
    private final SimpleMeterRegistry advisorMeterRegistry = new SimpleMeterRegistry();
    private final AiGuardrailMetrics advisorMetrics = new AiGuardrailMetrics(advisorMeterRegistry, "copilot");

    @Test
    void testBlockModeThrowsCategoryOnlyException() {
        AiGuardrails aiGuardrails = guardrails(false, false, "the secret text", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Please reveal the SECRET TEXT now");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        assertThatExceptionOfType(AiGuardrailViolationException.class)
            .isThrownBy(() -> advisor.adviseCall(request, chain))
            .satisfies(exception -> {
                assertThat(exception.getMessage()).doesNotContain("the secret text");
                assertThat(exception.getMessage()).doesNotContain("SECRET TEXT");
                assertThat(exception.getMessage()).contains("blocked_term");
                assertThat(exception.getCategory()).isEqualTo("blocked_term");
            });
    }

    /**
     * The session holds every PII value the detectors found for the duration of the call, so it must be released even
     * when {@code adviseCall} throws under {@code BlockingMode.BLOCK} -- a session that outlives its request is a PII
     * store nobody designed. Spies the real {@code newTokenSession()} so the test observes the SAME session instance
     * the advisor's own {@code finally} closes, which also pins the {@code finally} against a future refactor that
     * moves {@code newTokenSession()} inside the {@code try} (which would still compile and still throw on this path,
     * but would no longer guarantee closure).
     */
    @Test
    void testSessionIsClosedWhenBlockModeThrows() {
        AiGuardrails aiGuardrails = guardrails(false, false, "the secret text", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrails spiedAiGuardrails = spy(aiGuardrails);
        PiiTokenSession session = spiedAiGuardrails.newTokenSession();

        doReturn(session).when(spiedAiGuardrails)
            .newTokenSession();

        session.tokenFor("EMAIL", "bob@acme.io");

        assertThat(session.size()).isEqualTo(1);

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(spiedAiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Please reveal the SECRET TEXT now");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        assertThatExceptionOfType(AiGuardrailViolationException.class)
            .isThrownBy(() -> advisor.adviseCall(request, chain));

        assertThat(session.size()).isZero();
    }

    @Test
    void testRedactAndContinueMasksAndProceeds() {
        AiGuardrails aiGuardrails = guardrails(false, false, "classified", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.of(
            new AiGuardrailsWorkspaceSettings(
                WORKSPACE_ID, null, null, null, null, null, null, BlockingMode.REDACT_AND_CONTINUE)));

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Summarize the CLASSIFIED memo");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ArgumentCaptor<ChatClientRequest> forwardedRequestCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);

        when(chain.nextCall(forwardedRequestCaptor.capture())).thenReturn(emptyResponse());

        advisor.adviseCall(request, chain);

        ChatClientRequest forwardedRequest = forwardedRequestCaptor.getValue();
        String forwardedText = forwardedRequest.prompt()
            .getInstructions()
            .getFirst()
            .getText();

        assertThat(forwardedText).doesNotContain("CLASSIFIED");
        assertThat(forwardedText).contains("[REDACTED_BLOCKED_TERM]");
        assertThat(counter(advisorMeterRegistry, "blocking_downgraded", "copilot")).isEqualTo(1.0);
    }

    @Test
    void testRequestPiiRedactionRecordsUnderAdvisorSurface() {
        AiGuardrails aiGuardrails = guardrails(true, false, "", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Contact bob@acme.io");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(request, chain);

        // adviseCall now tokenizes request-direction PII (a reversible session token) rather than redacting it
        // irreversibly, so this records pii_tokenized, not pii_redacted -- see AiGuardrails#tokenizeInputs.
        assertThat(counter(advisorMeterRegistry, "pii_tokenized", "copilot")).isEqualTo(1.0);
        assertThat(counter(advisorMeterRegistry, "pii_redacted", "copilot")).isEqualTo(0.0);
        assertThat(counter(engineMeterRegistry, "pii_redacted", "gateway")).isEqualTo(0.0);
    }

    @Test
    void testRequestSecretRedactionRecordsUnderAdvisorSurface() {
        AiGuardrails aiGuardrails = guardrails(false, true, "", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("token AKIAIOSFODNN7EXAMPLE please");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(request, chain);

        assertThat(counter(advisorMeterRegistry, "secret_redacted", "copilot")).isEqualTo(1.0);
        assertThat(counter(engineMeterRegistry, "secret_redacted", "gateway")).isEqualTo(0.0);
    }

    @Test
    void testBlockedTermRecordsUnderAdvisorSurfaceInBlockMode() {
        AiGuardrails aiGuardrails = guardrails(false, false, "classified", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("the CLASSIFIED memo");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        assertThatExceptionOfType(AiGuardrailViolationException.class)
            .isThrownBy(() -> advisor.adviseCall(request, chain));

        assertThat(counter(advisorMeterRegistry, "blocked_term", "copilot")).isEqualTo(1.0);
        assertThat(counter(engineMeterRegistry, "blocked_term", "gateway")).isEqualTo(0.0);
    }

    @Test
    void testStreamRedactsAcrossChunkBoundary() {
        AiGuardrails aiGuardrails = guardrails(false, false, "", false, true, true);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Summarize the incident report");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        // "sk-" + 20+ alphanumerics matches AiGuardrails' OpenAI-key secret pattern, but only once the two chunks are
        // seen together — this pins that a value split across a chunk boundary is never emitted in the clear.
        ChatClientResponse chunk1 = responseChunk("The API key is sk-12");
        ChatClientResponse chunk2 = responseChunk("345678901234567890abcdef, keep it safe");

        when(chain.nextStream(any())).thenReturn(Flux.just(chunk1, chunk2));

        List<ChatClientResponse> emitted = Objects.requireNonNull(
            advisor.adviseStream(request, chain)
                .collectList()
                .block(),
            "emitted");

        String combinedText = emitted.stream()
            .map(response -> {
                ChatResponse chatResponse = Objects.requireNonNull(response.chatResponse(), "chatResponse");

                Generation generation = Objects.requireNonNull(chatResponse.getResult(), "generation");

                return generation.getOutput()
                    .getText();
            })
            .collect(Collectors.joining());

        assertThat(combinedText).contains("[REDACTED_SECRET]");
        assertThat(combinedText).doesNotContain("sk-12345678901234567890abcdef");
        assertThat(counter(advisorMeterRegistry, "response_redacted", "copilot")).isEqualTo(1.0);
    }

    /**
     * The streaming half of the request/response round trip {@code testRoundTripRestoresDistinctValues} pins for
     * {@code adviseCall}: the request is tokenized (not irreversibly redacted), and the streamed response -- which
     * echoes the token itself back, split down the middle across two chunks -- comes back as the real value. This
     * proves the advisor wires request tokenization to response restoration end to end; it is NOT safe-cut coverage --
     * the two chunks total well under {@code StreamingResponseRedactor}'s default 512-character window, so everything
     * here is buffered and comes out through {@code flush}, not {@code push}. The token-aware safe cut itself is pinned
     * directly by {@code StreamingResponseRedactorTest#testATokenIsNeverSplitAcrossEmittedChunks}.
     */
    @Test
    void testStreamTokenizesRequestAndRestoresResponseAcrossChunkBoundary() {
        AiGuardrails aiGuardrails = guardrails(true, false, "", false, true, true);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("forward bob@acme.io's note to the team");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ArgumentCaptor<ChatClientRequest> forwardedCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);

        when(chain.nextStream(forwardedCaptor.capture())).thenAnswer(invocation -> {
            ChatClientRequest forwardedRequest = invocation.getArgument(0);
            String forwardedText = forwardedRequest.prompt()
                .getInstructions()
                .getFirst()
                .getText();

            Matcher tokenMatcher = EMAIL_TOKEN_PATTERN.matcher(forwardedText);

            assertThat(tokenMatcher.find())
                .as("forwarded text should contain an email token, was: %s", forwardedText)
                .isTrue();

            String token = tokenMatcher.group();
            int splitIndex = token.length() / 2;
            ChatClientResponse chunk1 = responseChunk("Sure, forwarding to " + token.substring(0, splitIndex));
            ChatClientResponse chunk2 = responseChunk(token.substring(splitIndex) + " now.");

            return Flux.just(chunk1, chunk2);
        });

        List<ChatClientResponse> emitted = Objects.requireNonNull(
            advisor.adviseStream(request, chain)
                .collectList()
                .block(),
            "emitted");

        String forwardedText = forwardedCaptor.getValue()
            .prompt()
            .getInstructions()
            .getFirst()
            .getText();

        assertThat(forwardedText).doesNotContain("bob@acme.io");
        assertThat(forwardedText).containsPattern(EMAIL_TOKEN_PATTERN);

        String combinedText = emitted.stream()
            .map(response -> {
                ChatResponse chatResponse = Objects.requireNonNull(response.chatResponse(), "chatResponse");

                Generation generation = Objects.requireNonNull(chatResponse.getResult(), "generation");

                return generation.getOutput()
                    .getText();
            })
            .collect(Collectors.joining());

        assertThat(combinedText).isEqualTo("Sure, forwarding to bob@acme.io now.");
    }

    /**
     * The session holds every PII value the detectors found for the duration of the stream, so it must be released once
     * the upstream stream completes normally -- mirroring {@code testSessionIsClosedWhenBlockModeThrows} for the
     * exception path in {@code adviseCall}.
     */
    @Test
    void testStreamSessionClosedOnCompletion() {
        AiGuardrails aiGuardrails = guardrails(true, false, "", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrails spiedAiGuardrails = spy(aiGuardrails);
        PiiTokenSession session = spiedAiGuardrails.newTokenSession();

        doReturn(session).when(spiedAiGuardrails)
            .newTokenSession();

        // Pre-populate the session so this assertion has teeth: an untouched session (e.g. one the advisor never
        // actually received, or never closed) would already report size() == 0, making the final assertion pass
        // vacuously either way.
        session.tokenFor("EMAIL", "bob@acme.io");

        assertThat(session.size()).isEqualTo(1);

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(spiedAiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("contact bob@acme.io");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        when(chain.nextStream(any())).thenReturn(Flux.just(responseChunk("done")));

        advisor.adviseStream(request, chain)
            .collectList()
            .block();

        assertThat(session.size()).isZero();
    }

    /**
     * As {@link #testStreamSessionClosedOnCompletion}, but for the upstream stream failing rather than completing --
     * {@code doFinally} must release the session on the error signal too, not just success.
     */
    @Test
    void testStreamSessionClosedOnError() {
        AiGuardrails aiGuardrails = guardrails(true, false, "", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrails spiedAiGuardrails = spy(aiGuardrails);
        PiiTokenSession session = spiedAiGuardrails.newTokenSession();

        doReturn(session).when(spiedAiGuardrails)
            .newTokenSession();

        // See testStreamSessionClosedOnCompletion's comment: pre-populating gives the final assertion teeth.
        session.tokenFor("EMAIL", "bob@acme.io");

        assertThat(session.size()).isEqualTo(1);

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(spiedAiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("contact bob@acme.io");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        when(chain.nextStream(any())).thenReturn(Flux.error(new IllegalStateException("boom")));

        Flux<ChatClientResponse> result = advisor.adviseStream(request, chain);

        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> result.collectList()
                .block());

        assertThat(session.size()).isZero();
    }

    /**
     * As {@link #testStreamSessionClosedOnCompletion}, but for the subscriber cancelling before the upstream stream
     * ever completes or errors -- a session that outlives a cancelled stream is a PII store nobody designed.
     */
    @Test
    void testStreamSessionClosedOnCancellation() {
        AiGuardrails aiGuardrails = guardrails(true, false, "", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrails spiedAiGuardrails = spy(aiGuardrails);
        PiiTokenSession session = spiedAiGuardrails.newTokenSession();

        doReturn(session).when(spiedAiGuardrails)
            .newTokenSession();

        // See testStreamSessionClosedOnCompletion's comment: pre-populating gives the final assertion teeth.
        session.tokenFor("EMAIL", "bob@acme.io");

        assertThat(session.size()).isEqualTo(1);

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(spiedAiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("contact bob@acme.io");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        when(chain.nextStream(any())).thenReturn(Flux.never());

        Disposable disposable = advisor.adviseStream(request, chain)
            .subscribe();

        disposable.dispose();

        assertThat(session.size()).isZero();
    }

    /**
     * The session created for the streaming path must also be released when the request itself is rejected under
     * {@code BlockingMode.BLOCK} before any subscription happens -- {@code adviseStream} returns {@code Flux.error}
     * synchronously in that case, outside the {@code doFinally}-wrapped chain, so the release has to be explicit there
     * too. Mirrors {@link #testSessionIsClosedWhenBlockModeThrows} for {@code adviseCall}.
     */
    @Test
    void testStreamSessionClosedWhenBlockModeThrows() {
        AiGuardrails aiGuardrails = guardrails(false, false, "the secret text", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrails spiedAiGuardrails = spy(aiGuardrails);
        PiiTokenSession session = spiedAiGuardrails.newTokenSession();

        doReturn(session).when(spiedAiGuardrails)
            .newTokenSession();

        session.tokenFor("EMAIL", "bob@acme.io");

        assertThat(session.size()).isEqualTo(1);

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(spiedAiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Please reveal the SECRET TEXT now");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        Flux<ChatClientResponse> result = advisor.adviseStream(request, chain);

        assertThatExceptionOfType(AiGuardrailViolationException.class)
            .isThrownBy(() -> result.collectList()
                .block());

        assertThat(session.size()).isZero();
    }

    /**
     * The gate {@link AiGuardrails#newStreamingResponseRedactor(Long, AiGuardrailMetrics, PiiTokenSession)} adds: with
     * streaming scanning inactive and a PII-free request (so the session mints nothing), the redactor is {@code null}
     * and the upstream stream must pass through completely unbuffered -- the exact upstream chunks, untouched, with no
     * extra flush-tail element appended -- rather than being forced through the lookahead buffer for no benefit. The
     * session must still be released. This is the counterpart to
     * {@link #testStreamTokenizesRequestAndRestoresResponseAcrossChunkBoundary}, which pins that the gate does NOT
     * suppress restoration when a token actually was minted.
     */
    @Test
    void testStreamPassesThroughUnbufferedAndSessionClosedWhenNothingMintedAndScanDisabled() {
        AiGuardrails aiGuardrails = guardrails(false, false, "", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrails spiedAiGuardrails = spy(aiGuardrails);
        PiiTokenSession session = spiedAiGuardrails.newTokenSession();

        doReturn(session).when(spiedAiGuardrails)
            .newTokenSession();

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(spiedAiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Summarize the incident report");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        // An email in the response text would be masked if this stream were forced through the redactor -- its
        // survival in the clear is part of the evidence that this is a genuine pass-through, not an active-but-
        // scanning-nothing redactor.
        ChatClientResponse chunk1 = responseChunk("Contact ");
        ChatClientResponse chunk2 = responseChunk("bob@acme.io for details");

        when(chain.nextStream(any())).thenReturn(Flux.just(chunk1, chunk2));

        List<ChatClientResponse> emitted = Objects.requireNonNull(
            advisor.adviseStream(request, chain)
                .collectList()
                .block(),
            "emitted");

        // Exactly the two upstream chunks, same instances, in order -- a buffered stream would instead emit two
        // empty pushes plus a separate flush-tail chunk carrying the joined text.
        assertThat(emitted).hasSize(2);
        assertThat(emitted.get(0)).isSameAs(chunk1);
        assertThat(emitted.get(1)).isSameAs(chunk2);

        String combinedText = emitted.stream()
            .map(response -> {
                ChatResponse chatResponse = Objects.requireNonNull(response.chatResponse(), "chatResponse");

                Generation generation = Objects.requireNonNull(chatResponse.getResult(), "generation");

                return generation.getOutput()
                    .getText();
            })
            .collect(Collectors.joining());

        assertThat(combinedText).isEqualTo("Contact bob@acme.io for details");

        assertThat(session.size()).isZero();
    }

    @Test
    void testRoundTripRestoresDistinctValues() {
        // responseScanEnabled=true is load-bearing: it is what gives this test teeth against the scan-then-restore
        // ordering. The forwarded text contains only tokens ([PII_EMAIL_ADDRESS_*_xxxx]), which match no PII pattern,
        // so scanning it is a no-op -- but restoring FIRST would hand the scanner real email addresses to re-redact,
        // and the final assertion would see [REDACTED_EMAIL_ADDRESS] instead of the original addresses. With scanning
        // disabled, restoration would be indistinguishable from an identity scan and this test could not catch a
        // reversed ordering.
        AiGuardrails aiGuardrails = guardrails(true, true, "", false, true, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("forward bob@acme.io's note to alice@acme.io");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ArgumentCaptor<ChatClientRequest> forwardedCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);

        // Echo whatever the model was sent, so the assertion covers the full round trip.
        when(chain.nextCall(forwardedCaptor.capture()))
            .thenAnswer(invocation -> responseChunk(
                ((ChatClientRequest) invocation.getArgument(0)).prompt()
                    .getInstructions()
                    .getFirst()
                    .getText()));

        ChatClientResponse response = advisor.adviseCall(request, chain);

        String forwarded = forwardedCaptor.getValue()
            .prompt()
            .getInstructions()
            .getFirst()
            .getText();

        assertThat(forwarded).doesNotContain("bob@acme.io");
        assertThat(forwarded).doesNotContain("alice@acme.io");
        assertThat(forwarded).containsPattern("\\[PII_EMAIL_ADDRESS_1_[a-z0-9]{4}\\]");
        assertThat(forwarded).containsPattern("\\[PII_EMAIL_ADDRESS_2_[a-z0-9]{4}\\]");

        ChatResponse chatResponse = Objects.requireNonNull(response.chatResponse(), "chatResponse");
        Generation generation = Objects.requireNonNull(chatResponse.getResult(), "generation");
        String returned = generation.getOutput()
            .getText();

        assertThat(returned).isEqualTo("forward bob@acme.io's note to alice@acme.io");
    }

    @Test
    void testAdvisorOrderIsHighestPrecedence() {
        AiGuardrails aiGuardrails = guardrails(false, false, "", false, false, false);
        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);

        assertThat(advisor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void testResponseScanningRedactsCompletionAndRecordsMetric() {
        AiGuardrails aiGuardrails = guardrails(false, false, "", false, true, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Who do I contact?");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(any())).thenReturn(responseChunk("Contact bob@acme.io for details"));

        ChatClientResponse response = advisor.adviseCall(request, chain);

        ChatResponse chatResponse = Objects.requireNonNull(response.chatResponse(), "chatResponse");

        Generation generation = Objects.requireNonNull(chatResponse.getResult(), "generation");

        String responseText = generation.getOutput()
            .getText();

        assertThat(responseText).contains("[REDACTED_EMAIL_ADDRESS]");
        assertThat(responseText).doesNotContain("bob@acme.io");
        assertThat(counter(advisorMeterRegistry, "response_redacted", "copilot")).isEqualTo(1.0);
    }

    /**
     * A detector that fails while scanning a completion must be counted against the surface that actually ran the scan.
     * Response-direction redaction reaches the engine through {@code scanResponseText}, which previously carried no
     * metrics instance of its own and fell back to the engine's constructor-injected bean — so the failure landed under
     * that bean's fixed {@code surface} tag (or nowhere at all, since the bean is gated on the AI Gateway toggle)
     * rather than under the calling surface, sending an operator to the wrong place.
     */
    @Test
    void testDetectorFailureDuringResponseScanIsRecordedUnderTheCallingSurface() {
        AiGuardrails aiGuardrails = guardrailsWithDetectors(List.of(throwingDetector()), true);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Who do I contact?");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(any())).thenReturn(responseChunk("Contact bob@acme.io for details"));

        advisor.adviseCall(request, chain);

        assertThat(counter(advisorMeterRegistry, "detector_failed", "copilot")).isEqualTo(1.0);
        assertThat(counter(engineMeterRegistry, "detector_failed", "gateway")).isEqualTo(0.0);
    }

    @Test
    void testModerationBlockModeThrowsCategoryOnlyException() {
        AiGuardrails aiGuardrails = guardrails(content -> true, false, false, "", false, true, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Describe something unsafe");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        assertThatExceptionOfType(AiGuardrailViolationException.class)
            .isThrownBy(() -> advisor.adviseCall(request, chain))
            .satisfies(exception -> {
                assertThat(exception.getMessage()).contains("moderation_flagged");
                assertThat(exception.getCategory()).isEqualTo("moderation_flagged");
            });

        assertThat(counter(advisorMeterRegistry, "moderation_flagged", "copilot")).isEqualTo(1.0);
    }

    @Test
    void testModerationRedactAndContinueReplacesWholeMessageAndProceeds() {
        AiGuardrails aiGuardrails = guardrails(content -> true, false, false, "", false, true, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.of(
            new AiGuardrailsWorkspaceSettings(
                WORKSPACE_ID, null, null, null, null, null, null, BlockingMode.REDACT_AND_CONTINUE)));

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Describe something unsafe");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ArgumentCaptor<ChatClientRequest> forwardedRequestCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);

        when(chain.nextCall(forwardedRequestCaptor.capture())).thenReturn(emptyResponse());

        advisor.adviseCall(request, chain);

        ChatClientRequest forwardedRequest = forwardedRequestCaptor.getValue();
        String forwardedText = forwardedRequest.prompt()
            .getInstructions()
            .getFirst()
            .getText();

        // Moderation has no locatable span -- unlike a blocked-term match, the downgrade replaces the whole message
        // rather than masking a substring.
        assertThat(forwardedText).isEqualTo("[REDACTED_MODERATED]");
        assertThat(counter(advisorMeterRegistry, "blocking_downgraded", "copilot")).isEqualTo(1.0);
    }

    @Test
    void testModerationSkippedWithoutClassifier() {
        AiGuardrails aiGuardrails = guardrails(null, false, false, "", false, true, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Describe something unsafe");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(request, chain);

        assertThat(counter(advisorMeterRegistry, "moderation_flagged", "copilot")).isEqualTo(0.0);
    }

    @Test
    void testModerationClassifierFailsOpenAndIsNotFlagged() {
        // AiGuardrails never wraps the classifier call in its own try/catch -- fail-open is the classifier
        // implementation's own responsibility (mirroring injection detection, and matching
        // PromptBasedModerationClassifier's real catch-and-return-false behavior on a classification error). A
        // classifier that has already failed open surfaces here as a plain "not flagged" verdict.
        AiGatewayModerationClassifier failOpenClassifier = content -> false;

        AiGuardrails aiGuardrails = guardrails(failOpenClassifier, false, false, "", false, true, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("Describe something unsafe");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(request, chain);

        assertThat(counter(advisorMeterRegistry, "moderation_flagged", "copilot")).isEqualTo(0.0);
    }

    private static SensitiveDataDetector throwingDetector() {
        return new SensitiveDataDetector() {

            @Override
            public String name() {
                return "broken";
            }

            @Override
            public List<SensitiveSpan> detect(String text) {
                throw new IllegalStateException("detector is broken");
            }
        };
    }

    private AiGuardrails guardrailsWithDetectors(
        List<SensitiveDataDetector> sensitiveDataDetectors, boolean responseScanEnabled) {

        return new AiGuardrails(
            settingsService, null, null, engineMetrics, sensitiveDataDetectors, false, false, "", false, false,
            responseScanEnabled, false);
    }

    private static double counter(SimpleMeterRegistry meterRegistry, String event, String surface) {
        return meterRegistry.counter(AiGuardrailMetrics.COUNTER_NAME, "event", event, "surface", surface)
            .count();
    }

    private static ChatClientRequest requestWithUserMessage(String text) {
        List<Message> instructions = List.of(new UserMessage(text));

        return new ChatClientRequest(new Prompt(instructions), Map.of());
    }

    private static ChatClientResponse emptyResponse() {
        return ChatClientResponse.builder()
            .context(Map.of())
            .build();
    }

    private static ChatClientResponse responseChunk(String text) {
        ChatResponse chatResponse = ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(text))))
            .build();

        return ChatClientResponse.builder()
            .chatResponse(chatResponse)
            .context(Map.of())
            .build();
    }

    private AiGuardrails guardrails(
        boolean piiRedactionEnabled, boolean secretRedactionEnabled, String blockedTerms,
        boolean injectionDetectionEnabled, boolean responseScanEnabled, boolean streamingResponseScanEnabled) {

        return guardrails(
            null, piiRedactionEnabled, secretRedactionEnabled, blockedTerms, injectionDetectionEnabled, false,
            responseScanEnabled, streamingResponseScanEnabled);
    }

    private AiGuardrails guardrails(
        AiGatewayModerationClassifier moderationClassifier, boolean piiRedactionEnabled,
        boolean secretRedactionEnabled, String blockedTerms, boolean injectionDetectionEnabled,
        boolean moderationEnabled, boolean responseScanEnabled, boolean streamingResponseScanEnabled) {

        return new AiGuardrails(
            settingsService, null, moderationClassifier, engineMetrics, piiRedactionEnabled, secretRedactionEnabled,
            blockedTerms, injectionDetectionEnabled, moderationEnabled, responseScanEnabled,
            streamingResponseScanEnabled);
    }
}
