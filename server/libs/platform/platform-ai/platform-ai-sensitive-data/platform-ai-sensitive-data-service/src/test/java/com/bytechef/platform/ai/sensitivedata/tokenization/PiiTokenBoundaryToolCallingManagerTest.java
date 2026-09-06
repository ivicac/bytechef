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

package com.bytechef.platform.ai.sensitivedata.tokenization;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.ai.constant.ToolSuspendConstants;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataDetector;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataMetrics;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.MimeType;

/**
 * @author Ivica Cardic
 */
class PiiTokenBoundaryToolCallingManagerTest {

    @Test
    void testTokensInToolArgumentsAreRestoredBeforeTheToolRuns() {
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        RecordingToolCallingManager delegate = new RecordingToolCallingManager();
        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), () -> null);

        manager.executeToolCalls(
            promptWithSession(session), chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

        assertThat(delegate.lastArguments()).isEqualTo("{\"to\":\"bob@acme.io\"}");
    }

    @Test
    void testAnUnresolvedTokenIsLeftInPlaceAndDoesNotAbortTheRun() {
        PiiTokenSession session = PiiTokenSession.create();

        RecordingToolCallingManager delegate = new RecordingToolCallingManager();
        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), () -> null);

        manager.executeToolCalls(
            promptWithSession(session),
            chatResponseWithToolCall("sendEmail", "{\"to\":\"[PII_EMAIL_ADDRESS_9_zzzz]\"}"));

        assertThat(delegate.lastArguments()).isEqualTo("{\"to\":\"[PII_EMAIL_ADDRESS_9_zzzz]\"}");
    }

    @Test
    void testWithNoSessionInToolContextTheDelegateSeesArgumentsUnchanged() {
        RecordingToolCallingManager delegate = new RecordingToolCallingManager();
        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), () -> null);

        manager.executeToolCalls(promptWithoutSession(), chatResponseWithToolCall("sendEmail", "{\"to\":\"x\"}"));

        assertThat(delegate.lastArguments()).isEqualTo("{\"to\":\"x\"}");
    }

    @Test
    void testWithNoSessionInToolContextTheDelegateSeesTheSameChatResponseInstance() {
        RecordingToolCallingManager delegate = new RecordingToolCallingManager();
        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), () -> null);
        ChatResponse chatResponse = chatResponseWithToolCall("sendEmail", "{\"to\":\"x\"}");

        manager.executeToolCalls(promptWithoutSession(), chatResponse);

        assertThat(delegate.lastChatResponse()).isSameAs(chatResponse);
    }

    @Test
    void testResolveToolDefinitionsDelegatesUnchanged() {
        RecordingToolCallingManager delegate = new RecordingToolCallingManager();
        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), () -> null);
        ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
            .build();

        List<ToolDefinition> toolDefinitions = manager.resolveToolDefinitions(chatOptions);

        assertThat(delegate.lastResolvedChatOptions()).isSameAs(chatOptions);
        assertThat(toolDefinitions).isSameAs(delegate.toolDefinitionsToReturn());
    }

    @Test
    void testPiiInAToolResultIsTokenizedBeforeItReachesTheModel() {
        PiiTokenSession session = PiiTokenSession.create();

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult("{\"email\":\"bob@acme.io\"}"), redactor(), () -> null);

        ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

        assertThat(lastResponseData(result)).doesNotContain("bob@acme.io")
            .contains("[PII_EMAIL_ADDRESS_");
    }

    /**
     * The workspace's off-switch: with PII redaction disabled (a policy whose {@code kinds} excludes
     * {@link SensitiveKind#PII}) carried on the tool context alongside the session, PII in a tool result must reach the
     * model unchanged -- {@link #tokenizeOrRedact} must consult the policy's {@code kinds}, not the fixed
     * {@code PII_AND_SECRET} set this class used to hardcode.
     */
    @Test
    void testPiiInAToolResultIsLeftUntouchedWhenTheWorkspaceHasPiiRedactionOff() {
        PiiTokenSession session = PiiTokenSession.create();
        PiiTokenBoundaryPolicy piiOffPolicy =
            new PiiTokenBoundaryPolicy(Set.of(SensitiveKind.SECRET), SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE);

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult("{\"email\":\"bob@acme.io\"}"), redactor(), () -> null);

        ToolExecutionResult result =
            manager.executeToolCalls(promptWithSessionAndPolicy(session, piiOffPolicy), anyToolCall());

        assertThat(lastResponseData(result)).contains("bob@acme.io")
            .doesNotContain("[PII_")
            .doesNotContain("[REDACTED_");
    }

    /**
     * The counterpart of the above: with PII off but secrets still on, a secret in the same tool result must still be
     * redacted -- the two toggles are independent, so disabling one must not disable the other.
     */
    @Test
    void testASecretInAToolResultIsStillRedactedWhenPiiRedactionIsOff() {
        PiiTokenSession session = PiiTokenSession.create();
        PiiTokenBoundaryPolicy piiOffPolicy =
            new PiiTokenBoundaryPolicy(Set.of(SensitiveKind.SECRET), SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE);

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult("key=AKIAIOSFODNN7EXAMPLE"), redactor(), () -> null);

        ToolExecutionResult result =
            manager.executeToolCalls(promptWithSessionAndPolicy(session, piiOffPolicy), anyToolCall());

        assertThat(lastResponseData(result)).doesNotContain("AKIAIOSFODNN7EXAMPLE")
            .contains("[REDACTED_");
    }

    /**
     * An explicit {@code minConfidence} carried on the policy must be honoured instead of always falling back to
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} -- {@code redactor()}'s email detector scores every match at
     * a fixed {@code 0.9}, comfortably above the {@code 0.4} default, so a policy explicitly raising the bar to
     * {@code 0.95} must suppress it even though the kind (PII) is enabled and the default threshold would have let it
     * through.
     */
    @Test
    void testAnExplicitMinConfidenceIsHonouredInsteadOfTheDefault() {
        PiiTokenSession session = PiiTokenSession.create();
        PiiTokenBoundaryPolicy highBarPolicy =
            new PiiTokenBoundaryPolicy(Set.of(SensitiveKind.PII, SensitiveKind.SECRET), 0.95);

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult("{\"email\":\"bob@acme.io\"}"), redactor(), () -> null);

        ToolExecutionResult result =
            manager.executeToolCalls(promptWithSessionAndPolicy(session, highBarPolicy), anyToolCall());

        assertThat(lastResponseData(result)).contains("bob@acme.io");
    }

    @Test
    void testTheSameValueKeepsOneTokenAcrossTheWholeCall() {
        PiiTokenSession session = PiiTokenSession.create();
        String promptToken = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult("{\"email\":\"bob@acme.io\"}"), redactor(), () -> null);

        ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

        assertThat(lastResponseData(result)).contains(promptToken);
    }

    @Test
    void testASecretInAToolResultIsRedactedAndNotTokenized() {
        PiiTokenSession session = PiiTokenSession.create();

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult("key=AKIAIOSFODNN7EXAMPLE"), redactor(), () -> null);

        ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

        assertThat(lastResponseData(result)).doesNotContain("AKIAIOSFODNN7EXAMPLE")
            .contains("[REDACTED_")
            .doesNotContain("[PII_");
    }

    @Test
    void testAResultThatCannotBeTokenizedIsRedactedRatherThanReturnedRaw() {
        PiiTokenSession session = PiiTokenSession.create();

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult("{\"email\":\"bob@acme.io\"}"), throwingOnTokenizeRedactor(), () -> null);

        ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

        assertThat(lastResponseData(result)).doesNotContain("bob@acme.io")
            .contains("[REDACTED_");
    }

    /**
     * The agent tool-suspend protocol's sentinel is a control marker, not user content -- it must reach the returned
     * conversation history byte-for-byte, since {@code SuspendableToolCallingManager} (which wraps this class) locates
     * the pending suspend by comparing a {@code ToolResponse}'s {@code responseData} against this exact value.
     * Unremarkable on the normal path (no detector matches it), but pinned anyway as the baseline the fail-closed test
     * below contrasts with.
     */
    @Test
    void testTheSuspendSentinelSurvivesTheNormalTokenizationPath() {
        PiiTokenSession session = PiiTokenSession.create();

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult(ToolSuspendConstants.SUSPENDED_SENTINEL), redactor(), () -> null);

        ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

        assertThat(lastResponseData(result)).isEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);
    }

    /**
     * The mutation this test exists to catch: before the sentinel was exempted from {@link #tokenizeOrRedact} entirely,
     * a tool result carrying it would still be run through the fail-closed pipeline like any other value. A redactor
     * whose tokenization AND redaction both throw is exactly
     * {@link #testAResultThatCannotBeTokenizedIsRedactedRatherThanReturnedRaw}'s scenario one step further -- the
     * empty-string floor -- and would silently blank the sentinel, making it unrecognisable to
     * {@code SuspendableToolCallingManager} and orphaning the pending human-in-the-loop suspend (the approval was
     * requested, but the run could no longer resume). Exempting the sentinel before it ever reaches
     * {@link #tokenizeOrRedact} closes that regardless of what the redactor does.
     */
    @Test
    void testTheSuspendSentinelSurvivesTheFailClosedPathEvenWhenRedactionAlsoThrows() {
        PiiTokenSession session = PiiTokenSession.create();

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult(ToolSuspendConstants.SUSPENDED_SENTINEL), throwingOnEverythingRedactor(), () -> null);

        ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

        assertThat(lastResponseData(result)).isEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);
    }

    @Test
    void testOtherMessagesInTheConversationHistoryAreLeftUnchangedAndInPlace() {
        PiiTokenSession session = PiiTokenSession.create();
        UserMessage userMessage = new UserMessage("what is the customer's email?");

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResultAfter(userMessage, "{\"email\":\"bob@acme.io\"}"), redactor(), () -> null);

        ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

        assertThat(result.conversationHistory()).hasSize(3);
        assertThat(result.conversationHistory()
            .getFirst()).isSameAs(userMessage);
        assertThat(lastResponseData(result)).doesNotContain("bob@acme.io");
    }

    /**
     * Pins the leak this class exists to close: {@code DefaultToolCallingManager} appends the exact
     * {@link AssistantMessage} it was given -- the one {@code PiiTokenBoundaryToolCallingManager} rebuilt with the real
     * value -- to the conversation history it returns. Before the fix, only {@link ToolResponseMessage} entries were
     * rewritten on the way out, so this assistant message reached the returned history (and from there, the next prompt
     * and any persisted task state) carrying {@code bob@acme.io} in clear, one call after this class went to the
     * trouble of tokenizing it.
     */
    @Test
    void testTheToolReceivesRealValuesWhileTheReturnedAssistantMessageCarriesTheToken() {
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        RecordingToolCallingManager delegate = new RecordingToolCallingManager();
        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), () -> null);

        ToolExecutionResult result = manager.executeToolCalls(
            promptWithSession(session), chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

        assertThat(delegate.lastArguments()).isEqualTo("{\"to\":\"bob@acme.io\"}");
        assertThat(assistantToolCallArguments(result)).doesNotContain("bob@acme.io");
        assertThat(assistantToolCallArguments(result)).isEqualTo("{\"to\":\"" + token + "\"}");
    }

    /**
     * The inbound-direction counterpart of the above -- a value the model never saw as a token (it appears here for the
     * first time, minted while restoring the tool call) must still not leak once the tool's result echoes it back, and
     * both the assistant message and the tool response must agree on the SAME token.
     */
    @Test
    void testTheSameValueKeepsOneTokenBetweenTheAssistantMessageAndTheToolResult() {
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResult("{\"email\":\"bob@acme.io\"}"), redactor(), () -> null);

        ToolExecutionResult result = manager.executeToolCalls(
            promptWithSession(session), chatResponseWithToolCall("lookupCustomer", "{\"email\":\"" + token + "\"}"));

        assertThat(assistantToolCallArguments(result)).isEqualTo("{\"email\":\"" + token + "\"}");
        assertThat(lastResponseData(result)).isEqualTo("{\"email\":\"" + token + "\"}");
    }

    /**
     * A second tool round built on the first round's returned history (as {@code ToolCallingAdvisor} would build its
     * next prompt) must not mint a second token for the same value, and the assistant message this invocation itself
     * appended must be retokenized just as reliably as the one carried over from the first round.
     */
    @Test
    void testMultipleToolRoundsKeepTheSameTokenInEveryReturnedAssistantMessage() {
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        RecordingToolCallingManager delegate = new RecordingToolCallingManager();
        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), () -> null);

        ToolExecutionResult firstRoundResult = manager.executeToolCalls(
            promptWithSession(session), chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

        Prompt secondPrompt = new Prompt(
            firstRoundResult.conversationHistory(),
            ToolCallingChatOptions.builder()
                .toolContext(PiiTokenSessionToolContext.into(Map.of(), session))
                .build());

        ToolExecutionResult secondRoundResult = manager.executeToolCalls(
            secondPrompt, chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

        List<AssistantMessage> assistantMessages = assistantMessagesIn(secondRoundResult);

        assertThat(assistantMessages).hasSize(2);

        for (AssistantMessage assistantMessage : assistantMessages) {
            String arguments = assistantMessage.getToolCalls()
                .getFirst()
                .arguments();

            assertThat(arguments).doesNotContain("bob@acme.io");
            assertThat(arguments).isEqualTo("{\"to\":\"" + token + "\"}");
        }
    }

    /**
     * A {@code ChatResponse} with {@code n > 1} generations -- multiple candidate completions -- is a real shape, and a
     * tool call on a LATER generation (not generation 0) must have its arguments restored just as reliably as one on
     * the first. Also pins that rebuilding a generation preserves its own {@link ChatGenerationMetadata} and the source
     * {@link AssistantMessage}'s {@link Media}, for every generation touched by the rebuild -- both are silently
     * dropped by a rebuild that only reads/writes {@code chatResponse.getResult()}.
     */
    @Test
    void testToolCallArgumentsAreRestoredOnANonFirstGenerationAndMetadataAndMediaSurviveTheRebuild() {
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        ChatGenerationMetadata firstGenerationMetadata = ChatGenerationMetadata.builder()
            .finishReason("STOP")
            .build();
        ChatGenerationMetadata secondGenerationMetadata = ChatGenerationMetadata.builder()
            .finishReason("TOOL_CALLS")
            .build();
        Media media = new Media(MimeType.valueOf("image/png"), URI.create("https://acme.io/screenshot.png"));

        AssistantMessage firstGenerationOutput = AssistantMessage.builder()
            .content("I looked into it but did not need any tools.")
            .build();
        AssistantMessage secondGenerationOutput = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(toolCall("call-1", "sendEmail", "{\"to\":\"" + token + "\"}")))
            .media(List.of(media))
            .build();

        ChatResponse chatResponse = new ChatResponse(
            List.of(
                new Generation(firstGenerationOutput, firstGenerationMetadata),
                new Generation(secondGenerationOutput, secondGenerationMetadata)));

        RecordingToolCallingManager delegate = new RecordingToolCallingManager();
        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), () -> null);

        manager.executeToolCalls(promptWithSession(session), chatResponse);

        ChatResponse forwarded = delegate.lastChatResponse();

        assertThat(forwarded).isNotNull();

        List<Generation> forwardedGenerations = Objects.requireNonNull(forwarded)
            .getResults();

        assertThat(forwardedGenerations).hasSize(2);
        assertThat(forwardedGenerations.get(0)
            .getMetadata()).isEqualTo(firstGenerationMetadata);

        AssistantMessage restoredOutput = forwardedGenerations.get(1)
            .getOutput();

        assertThat(restoredOutput.getToolCalls()).singleElement()
            .extracting(AssistantMessage.ToolCall::arguments)
            .isEqualTo("{\"to\":\"bob@acme.io\"}");
        assertThat(forwardedGenerations.get(1)
            .getMetadata()).isEqualTo(secondGenerationMetadata);
        assertThat(restoredOutput.getMedia()).containsExactly(media);
    }

    /**
     * Two tool calls in one invocation, each carrying its own resolvable token -- the natural shape for pinning
     * {@link SensitiveDataMetrics#recordToolArgsRestored()}'s "at most once per invocation, not once per tool call"
     * incidence semantics: both calls restore, but the event fires exactly once.
     */
    @Test
    void testRecordsToolArgsRestoredAtMostOncePerInvocationWithSeveralToolCalls() {
        PiiTokenSession session = PiiTokenSession.create();
        String firstToken = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");
        String secondToken = session.tokenFor("EMAIL_ADDRESS", "alice@acme.io");
        RecordingMetrics metrics = new RecordingMetrics();

        ToolCallingManager manager =
            PiiTokenBoundaryToolCallingManager.wrap(new RecordingToolCallingManager(), redactor(), () -> metrics);

        manager.executeToolCalls(
            promptWithSession(session),
            chatResponseWithToolCalls(
                toolCall("call-1", "sendEmail", "{\"to\":\"" + firstToken + "\"}"),
                toolCall("call-2", "sendEmail", "{\"to\":\"" + secondToken + "\"}")));

        assertThat(metrics.toolArgsRestoredCount).isEqualTo(1);
    }

    /**
     * The metrics supplier is consulted per invocation, not read once when the wrapper is built. Callers such as
     * {@code CopilotGuardrailsAdvisorFactory} build this wrapper once for a singleton bean at Spring context refresh,
     * while the guardrails settings that decide whether any metrics instance exists stay editable at runtime: a
     * workspace with guardrails off at boot resolves null, and a construction-time binding would keep recording nothing
     * for the rest of the JVM's life after an admin switched them on.
     */
    @Test
    void testTheMetricsSupplierIsConsultedPerInvocationNotOnceAtConstruction() {
        AtomicReference<SensitiveDataMetrics> currentMetrics = new AtomicReference<>();

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            new RecordingToolCallingManager(), redactor(), currentMetrics::get);

        PiiTokenSession firstSession = PiiTokenSession.create();
        String firstToken = firstSession.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        // Built and first used while nothing resolves -- the wrapper still works, it just records nothing.
        manager.executeToolCalls(
            promptWithSession(firstSession),
            chatResponseWithToolCall("sendEmail", "{\"to\":\"" + firstToken + "\"}"));

        RecordingMetrics metrics = new RecordingMetrics();

        currentMetrics.set(metrics);

        PiiTokenSession secondSession = PiiTokenSession.create();
        String secondToken = secondSession.tokenFor("EMAIL_ADDRESS", "alice@acme.io");

        manager.executeToolCalls(
            promptWithSession(secondSession),
            chatResponseWithToolCall("sendEmail", "{\"to\":\"" + secondToken + "\"}"));

        assertThat(metrics.toolArgsRestoredCount)
            .as("the invocation after the supplier started resolving should record through it")
            .isEqualTo(1);
    }

    /**
     * The tool a call resolves to may be user-authored -- a Script action exposed as an agent tool runs the tenant's
     * own Java/JavaScript/Python/Ruby, and Spring AI hands every callback the request's {@code ToolContext}. The
     * session is a live two-way token-to-value map, so leaving it on that context would give such code a de-tokenizing
     * oracle for everything the guardrail protected in this request. The boundary strips it before delegating and keeps
     * its own reference for the tokenize pass afterwards.
     *
     * <p>
     * {@code AgentToolInvocationContext}'s entries share this same map and must survive the strip -- losing them breaks
     * security-context rehydration on the tool's worker thread, and would surface far from here as an authorization
     * error inside an unrelated tool.
     * </p>
     */
    @Test
    void testTheSessionIsStrippedFromTheToolContextTheToolItselfSees() {
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        RecordingToolCallingManager delegate = new RecordingToolCallingManager();
        ToolCallingManager manager =
            PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), () -> null);

        ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
            .toolContext(
                PiiTokenSessionToolContext.into(
                    Map.of("bytechef.agent-tool-invocation-context", "must-survive"), session))
            .build();

        manager.executeToolCalls(
            new Prompt(List.of(new UserMessage("hi")), chatOptions),
            chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

        Map<String, Object> observedToolContext = delegate.lastToolContext();

        assertThat(observedToolContext)
            .as("the tool must not be able to reach the session")
            .doesNotContainKey(PiiTokenSessionToolContext.KEY);
        assertThat(observedToolContext)
            .as("every other tool-context entry must survive the strip")
            .containsEntry("bytechef.agent-tool-invocation-context", "must-survive");

        // The strip must not cost the boundary its own job: arguments are still restored for the tool.
        assertThat(delegate.lastArguments()).isEqualTo("{\"to\":\"bob@acme.io\"}");
    }

    @Test
    void testDoesNotRecordToolArgsRestoredWhenNothingWasRestored() {
        RecordingMetrics metrics = new RecordingMetrics();
        ToolCallingManager manager =
            PiiTokenBoundaryToolCallingManager.wrap(new RecordingToolCallingManager(), redactor(), () -> metrics);

        manager.executeToolCalls(
            promptWithSession(PiiTokenSession.create()), chatResponseWithToolCall("sendEmail", "{\"to\":\"x\"}"));

        assertThat(metrics.toolArgsRestoredCount).isZero();
    }

    /**
     * A token-shaped span this session never minted -- reuses the same fixture
     * {@link #testAnUnresolvedTokenIsLeftInPlaceAndDoesNotAbortTheRun} uses -- must record {@code token_unresolved}
     * (not {@code tool_args_restored}, since nothing was actually substituted).
     */
    @Test
    void testRecordsTokenUnresolvedWhenAnArgumentTokenCannotBeResolved() {
        RecordingMetrics metrics = new RecordingMetrics();
        ToolCallingManager manager =
            PiiTokenBoundaryToolCallingManager.wrap(new RecordingToolCallingManager(), redactor(), () -> metrics);

        manager.executeToolCalls(
            promptWithSession(PiiTokenSession.create()),
            chatResponseWithToolCall("sendEmail", "{\"to\":\"[PII_EMAIL_ADDRESS_9_zzzz]\"}"));

        assertThat(metrics.tokenUnresolvedCount).isEqualTo(1);
        assertThat(metrics.toolArgsRestoredCount).isZero();
    }

    /**
     * Several sensitive values returned across several tool responses in one invocation -- the inbound-direction
     * counterpart of {@link #testRecordsToolArgsRestoredAtMostOncePerInvocationWithSeveralToolCalls}, pinning that
     * {@link SensitiveDataMetrics#recordToolResultTokenized()} fires at most once per invocation, not once per response
     * and not once per accepted span within a response.
     */
    @Test
    void testRecordsToolResultTokenizedAtMostOncePerInvocationWithSeveralResponses() {
        PiiTokenSession session = PiiTokenSession.create();
        RecordingMetrics metrics = new RecordingMetrics();

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResults("{\"email\":\"bob@acme.io\"}", "key=AKIAIOSFODNN7EXAMPLE"), redactor(), () -> metrics);

        manager.executeToolCalls(promptWithSession(session), anyToolCall());

        assertThat(metrics.toolResultTokenizedCount).isEqualTo(1);
    }

    @Test
    void testDoesNotRecordToolResultTokenizedWhenNothingWasTokenized() {
        PiiTokenSession session = PiiTokenSession.create();
        RecordingMetrics metrics = new RecordingMetrics();

        ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
            returningResults("nothing sensitive here"), redactor(), () -> metrics);

        manager.executeToolCalls(promptWithSession(session), anyToolCall());

        assertThat(metrics.toolResultTokenizedCount).isZero();
    }

    @Test
    void testRecordsAssistantHistoryRetokenizedWhenAnAssistantToolCallArgumentIsRetokenized() {
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");
        RecordingMetrics metrics = new RecordingMetrics();

        ToolCallingManager manager =
            PiiTokenBoundaryToolCallingManager.wrap(new RecordingToolCallingManager(), redactor(), () -> metrics);

        manager.executeToolCalls(
            promptWithSession(session), chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

        assertThat(metrics.assistantHistoryRetokenizedCount).isEqualTo(1);
    }

    @Test
    void testDoesNotRecordAssistantHistoryRetokenizedWhenNothingWasRetokenized() {
        RecordingMetrics metrics = new RecordingMetrics();
        ToolCallingManager manager =
            PiiTokenBoundaryToolCallingManager.wrap(new RecordingToolCallingManager(), redactor(), () -> metrics);

        manager.executeToolCalls(
            promptWithSession(PiiTokenSession.create()), chatResponseWithToolCall("sendEmail", "{\"to\":\"x\"}"));

        assertThat(metrics.assistantHistoryRetokenizedCount).isZero();
    }

    private static ToolCallingManager returningResult(String responseData) {
        return new ReturningResultToolCallingManager(List.of(), responseData);
    }

    private static ToolCallingManager returningResultAfter(Message precedingMessage, String responseData) {
        return new ReturningResultToolCallingManager(List.of(precedingMessage), responseData);
    }

    private static ToolCallingManager returningResults(String... responseDataItems) {
        return new ReturningMultipleResultsToolCallingManager(List.of(responseDataItems));
    }

    private static ChatResponse anyToolCall() {
        return chatResponseWithToolCall("lookupCustomer", "{}");
    }

    private static String lastResponseData(ToolExecutionResult result) {
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) result.conversationHistory()
            .getLast();

        return toolResponseMessage.getResponses()
            .getFirst()
            .responseData();
    }

    private static String assistantToolCallArguments(ToolExecutionResult result) {
        return assistantMessageIn(result).getToolCalls()
            .getFirst()
            .arguments();
    }

    private static AssistantMessage assistantMessageIn(ToolExecutionResult result) {
        return assistantMessagesIn(result).getFirst();
    }

    private static List<AssistantMessage> assistantMessagesIn(ToolExecutionResult result) {
        List<AssistantMessage> assistantMessages = new ArrayList<>();

        for (Message message : result.conversationHistory()) {
            if (message instanceof AssistantMessage assistantMessage) {
                assistantMessages.add(assistantMessage);
            }
        }

        return assistantMessages;
    }

    /**
     * Extracts the {@link AssistantMessage} a {@link ChatResponse} carries, the way every stub delegate below builds
     * its returned conversation history -- matching how {@code DefaultToolCallingManager} itself reads
     * {@code chatResponse.getResult().getOutput()} before appending it, unchanged by reference, to the history it
     * returns.
     */
    private static AssistantMessage assistantMessageOf(ChatResponse chatResponse) {
        Generation generation = chatResponse.getResult();

        if (generation == null) {
            throw new IllegalStateException("Expected a generation in the ChatResponse passed to executeToolCalls");
        }

        return generation.getOutput();
    }

    /**
     * Builds the {@link ToolResponseMessage} a real delegate would append alongside {@code assistantMessage} -- one
     * inert response per tool call, carrying no sensitive data of its own, so a stub using this fixture does not
     * accidentally exercise the tool-result (inbound) direction when a test only means to exercise the
     * assistant-message (outbound) direction.
     */
    private static ToolResponseMessage inertToolResponseFor(AssistantMessage assistantMessage) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(assistantMessage.getToolCalls()
            .size());

        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), "ok"));
        }

        return ToolResponseMessage.builder()
            .responses(responses)
            .build();
    }

    private static SensitiveDataRedactor redactor() {
        return new SensitiveDataRedactor(
            List.of(
                literalDetector("email", SensitiveKind.PII, "EMAIL_ADDRESS", "bob@acme.io"),
                literalDetector("aws-key", SensitiveKind.SECRET, "AWS_ACCESS_KEY", "AKIAIOSFODNN7EXAMPLE")));
    }

    private static SensitiveDataRedactor throwingOnTokenizeRedactor() {
        return new ThrowingOnTokenizeRedactor(
            List.of(literalDetector("email", SensitiveKind.PII, "EMAIL_ADDRESS", "bob@acme.io")));
    }

    private static SensitiveDataRedactor throwingOnEverythingRedactor() {
        return new ThrowingOnEverythingRedactor(List.of());
    }

    private static SensitiveDataDetector literalDetector(
        String name, SensitiveKind kind, String category, String literal) {

        return new SensitiveDataDetector() {

            @Override
            public String name() {
                return name;
            }

            @Override
            public List<SensitiveSpan> detect(String text) {
                int start = text.indexOf(literal);

                if (start < 0) {
                    return List.of();
                }

                return List.of(new SensitiveSpan(kind, category, start, start + literal.length(), 0.9));
            }
        };
    }

    /**
     * A {@link SensitiveDataRedactor} whose {@code tokenizeWithSpans} always throws, so the fail-closed catch block in
     * {@link PiiTokenBoundaryToolCallingManager} can be exercised without relying on {@link PiiTokenSession#close()} --
     * closing a session only clears its maps, it does not make the session throw, so it cannot force this path.
     */
    private static final class ThrowingOnTokenizeRedactor extends SensitiveDataRedactor {

        private ThrowingOnTokenizeRedactor(List<SensitiveDataDetector> detectors) {
            super(detectors);
        }

        @Override
        public RedactionResult tokenizeWithSpans(
            String text, Set<SensitiveKind> kinds, PiiTokenSession session, double minConfidence,
            @Nullable SensitiveDataMetrics metrics) {

            throw new IllegalStateException("tokenization is broken");
        }
    }

    /**
     * A {@link SensitiveDataRedactor} whose tokenization AND redaction both throw -- the empty-string floor of
     * {@link PiiTokenBoundaryToolCallingManager}'s fail-closed pipeline, one step past what
     * {@link ThrowingOnTokenizeRedactor} exercises. Used only to prove the suspend sentinel never reaches either method
     * in the first place, since it is exempted before {@link #tokenizeOrRedact} is ever called.
     */
    private static final class ThrowingOnEverythingRedactor extends SensitiveDataRedactor {

        private ThrowingOnEverythingRedactor(List<SensitiveDataDetector> detectors) {
            super(detectors);
        }

        @Override
        public RedactionResult tokenizeWithSpans(
            String text, Set<SensitiveKind> kinds, PiiTokenSession session, double minConfidence,
            @Nullable SensitiveDataMetrics metrics) {

            throw new IllegalStateException("tokenization is broken");
        }

        @Override
        public String redact(
            String text, Set<SensitiveKind> kinds, double minConfidence, @Nullable SensitiveDataMetrics metrics) {

            throw new IllegalStateException("redaction is broken too");
        }
    }

    /**
     * Returns every item in {@code responseDataItems} as its own {@link ToolResponseMessage.ToolResponse} within a
     * single {@link ToolResponseMessage} -- the shape produced when a model issues several tool calls in one turn and
     * they are all resolved together, used by the several-responses incidence tests above.
     */
    private static final class ReturningMultipleResultsToolCallingManager implements ToolCallingManager {

        private final List<String> responseDataItems;

        private ReturningMultipleResultsToolCallingManager(List<String> responseDataItems) {
            this.responseDataItems = responseDataItems;
        }

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(responseDataItems.size());

            for (int index = 0; index < responseDataItems.size(); index++) {
                responses.add(
                    new ToolResponseMessage.ToolResponse(
                        "call-" + index, "lookupCustomer", responseDataItems.get(index)));
            }

            ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(responses)
                .build();

            // Realistic shape: DefaultToolCallingManager.buildConversationHistoryAfterToolExecution appends the exact
            // AssistantMessage it was given -- the one PiiTokenBoundaryToolCallingManager rebuilt with real values --
            // ahead of the ToolResponseMessage, not just the response on its own.
            return ToolExecutionResult.builder()
                .conversationHistory(List.of(assistantMessageOf(chatResponse), toolResponseMessage))
                .build();
        }
    }

    private static final class ReturningResultToolCallingManager implements ToolCallingManager {

        private final List<Message> precedingMessages;
        private final String responseData;

        private ReturningResultToolCallingManager(List<Message> precedingMessages, String responseData) {
            this.precedingMessages = precedingMessages;
            this.responseData = responseData;
        }

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                "call-1", "lookupCustomer", responseData);
            ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(response))
                .build();

            List<Message> conversationHistory = new ArrayList<>(precedingMessages);

            // Realistic shape: DefaultToolCallingManager.buildConversationHistoryAfterToolExecution appends the exact
            // AssistantMessage it was given -- the one PiiTokenBoundaryToolCallingManager rebuilt with real values --
            // ahead of the ToolResponseMessage, not just the response on its own.
            conversationHistory.add(assistantMessageOf(chatResponse));
            conversationHistory.add(toolResponseMessage);

            return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .build();
        }
    }

    private static Prompt promptWithSession(PiiTokenSession session) {
        return new Prompt(
            List.of(),
            ToolCallingChatOptions.builder()
                .toolContext(PiiTokenSessionToolContext.into(Map.of(), session))
                .build());
    }

    private static Prompt promptWithSessionAndPolicy(PiiTokenSession session, PiiTokenBoundaryPolicy policy) {
        Map<String, Object> toolContext = PiiTokenBoundaryPolicyToolContext.into(
            PiiTokenSessionToolContext.into(Map.of(), session), policy);

        return new Prompt(
            List.of(),
            ToolCallingChatOptions.builder()
                .toolContext(toolContext)
                .build());
    }

    private static Prompt promptWithoutSession() {
        return new Prompt(List.of(), ToolCallingChatOptions.builder()
            .build());
    }

    private static ChatResponse chatResponseWithToolCall(String toolName, String arguments) {
        return chatResponseWithToolCalls(toolCall("call-1", toolName, arguments));
    }

    private static ChatResponse chatResponseWithToolCalls(AssistantMessage.ToolCall... toolCalls) {
        AssistantMessage assistantMessage = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(toolCalls))
            .build();

        return new ChatResponse(List.of(new Generation(assistantMessage)));
    }

    private static AssistantMessage.ToolCall toolCall(String id, String toolName, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", toolName, arguments);
    }

    private static final class RecordingToolCallingManager implements ToolCallingManager {

        private final List<ToolDefinition> toolDefinitionsToReturn = List.of();

        private @Nullable ToolCallingChatOptions lastResolvedChatOptions;
        private @Nullable ChatResponse lastChatResponse;
        private @Nullable Map<String, Object> lastToolContext;

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            lastResolvedChatOptions = chatOptions;

            return toolDefinitionsToReturn;
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            lastChatResponse = chatResponse;

            // Captured as the real DefaultToolCallingManager would see it: this map is what Spring AI turns into the
            // ToolContext every ToolCallback -- including a user-authored Script action -- receives.
            if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
                lastToolContext = toolCallingChatOptions.getToolContext();
            }

            AssistantMessage assistantMessage = assistantMessageOf(chatResponse);
            List<Message> conversationHistory = new ArrayList<>(prompt.getInstructions());

            // Realistic shape: DefaultToolCallingManager.buildConversationHistoryAfterToolExecution appends the exact
            // AssistantMessage it was given -- the one PiiTokenBoundaryToolCallingManager rebuilt with real values --
            // ahead of the ToolResponseMessage, not just the previous prompt's instructions on their own.
            conversationHistory.add(assistantMessage);
            conversationHistory.add(inertToolResponseFor(assistantMessage));

            return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .build();
        }

        private Map<String, Object> lastToolContext() {
            return lastToolContext == null ? Map.of() : lastToolContext;
        }

        private String lastArguments() {
            ChatResponse chatResponse = lastChatResponse;

            if (chatResponse == null) {
                throw new IllegalStateException("executeToolCalls was never called");
            }

            Generation generation = chatResponse.getResult();

            if (generation == null) {
                throw new IllegalStateException("Expected a generation in the recorded ChatResponse");
            }

            return generation.getOutput()
                .getToolCalls()
                .getFirst()
                .arguments();
        }

        private @Nullable ChatResponse lastChatResponse() {
            return lastChatResponse;
        }

        private @Nullable ToolCallingChatOptions lastResolvedChatOptions() {
            return lastResolvedChatOptions;
        }

        private List<ToolDefinition> toolDefinitionsToReturn() {
            return toolDefinitionsToReturn;
        }
    }

    /**
     * A {@link SensitiveDataMetrics} test double that counts each event by name, for the incidence tests above.
     * {@link #recordDetectorFailure(String)} is left a no-op -- those tests are unrelated to detector failures.
     */
    private static final class RecordingMetrics implements SensitiveDataMetrics {

        private int toolArgsRestoredCount;
        private int toolResultTokenizedCount;
        private int tokenUnresolvedCount;
        private int assistantHistoryRetokenizedCount;

        @Override
        public void recordDetectorFailure(String detectorName) {
            // Not exercised by these tests.
        }

        @Override
        public void recordToolArgsRestored() {
            toolArgsRestoredCount++;
        }

        @Override
        public void recordToolResultTokenized() {
            toolResultTokenizedCount++;
        }

        @Override
        public void recordTokenUnresolved() {
            tokenUnresolvedCount++;
        }

        @Override
        public void recordAssistantHistoryRetokenized() {
            assistantHistoryRetokenizedCount++;
        }
    }
}
