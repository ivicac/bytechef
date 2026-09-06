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

import com.bytechef.platform.ai.constant.ToolSuspendConstants;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataMetrics;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.CollectionUtils;

/**
 * Decorates a {@link ToolCallingManager} to restore PII tokens in tool-call arguments before a tool runs, and to
 * tokenize sensitive data in a tool's result before it reaches the model.
 *
 * <p>
 * <b>Outbound</b> (model to tool): a tool such as {@code send-email} must receive a real address instead of a
 * placeholder like {@code [PII_EMAIL_ADDRESS_1_k3n9]}, so tool-call {@code arguments} are restored before the delegate
 * executes them. <b>Inbound</b> (tool to model): a tool result such as a fetched customer record must never reach the
 * model provider in clear, so tool results are tokenized/redacted after the delegate returns and before this class
 * hands the {@link ToolExecutionResult} back up.
 * </p>
 *
 * <p>
 * {@link #resolveToolDefinitions(ToolCallingChatOptions)} always delegates unchanged -- there is nothing to restore in
 * a tool definition. {@link #executeToolCalls(Prompt, ChatResponse)} reads the {@link PiiTokenSession} carried on the
 * prompt's tool context via {@link PiiTokenSessionToolContext#from(ToolContext)}. When there is no session -- every
 * workspace with guardrails/tokenization off, which is most tool calls in the product -- this decorator must be
 * genuinely inert: it delegates the exact same {@link ChatResponse} instance, with no rebuild and no allocation, and
 * returns the delegate's {@link ToolExecutionResult} untouched.
 * </p>
 *
 * <p>
 * When a session IS present AND {@link SensitiveDataPolicy#restoreOutboundArguments()} is {@code true} for the resolved
 * policy, each tool call's {@code arguments} is passed through
 * {@link PiiTokenSession#restoreWithUnresolvedCount(String)} before the delegate runs. Restoration fails open: a token
 * this session cannot resolve -- an unknown ordinal, or one minted by another session -- is left in the text exactly as
 * it stood. The tool then malfunctions visibly (a literal {@code [PII_EMAIL_ADDRESS_...]} string reaching an email API,
 * say) rather than the run being aborted. When {@code restoreOutboundArguments()} is {@code false}, this class instead
 * leaves the arguments in token form and the delegate's tool runs against tokens rather than real values -- see
 * {@link #restoreToolCallArguments} for why, and for the metric that path still records.
 * </p>
 *
 * <p>
 * <b>Tool results, by contrast, fail closed -- deliberately the opposite of this engine's usual fail-open posture.</b>
 * Each {@link ToolResponseMessage} in the delegate's returned conversation history has its
 * {@link ToolResponseMessage.ToolResponse#responseData()} passed through
 * {@link SensitiveDataRedactor#tokenizeWithSpans}. If that throws, the text is substituted with
 * {@link SensitiveDataRedactor#redact} instead; if redaction also throws, it is substituted with the empty string. The
 * original text is never returned on a failure path. Applying this engine's global "fail open, never block a call" rule
 * here would hand the model provider exactly the data this feature exists to keep away from it, so this is a
 * deliberate, local exception to that rule -- not an inconsistency to "fix".
 * </p>
 *
 * <p>
 * <b>One tool-result value is exempt from this pipeline entirely: {@link ToolSuspendConstants#SUSPENDED_SENTINEL}.</b>
 * It is a control marker the agent tool-suspend protocol uses to identify a pending human-in-the-loop suspend --
 * {@code SuspendableToolCallingManager}, which wraps this class, locates the matching {@code ToolResponse} by that
 * exact value. Running it through the fail-closed pipeline above risks a detector failure substituting redacted or
 * empty text for it, making the sentinel unrecognisable and orphaning the suspend (the approval was requested, but the
 * run can no longer resume). So it is skipped before {@link #tokenizeOrRedact} ever sees it, in both
 * {@link #tokenizeConversationHistory}'s tool-result pass and the fail-closed path within it -- not special-cased
 * inside that pipeline, since the whole point is that it never enters it.
 * </p>
 *
 * <p>
 * <b>Which kinds are tokenized/redacted, and at what confidence, is the workspace's own policy -- not a fixed
 * constant.</b> {@link #executeToolCalls(Prompt, ChatResponse)} reads a {@link SensitiveDataPolicy} carried alongside
 * the session via {@link SensitiveDataPolicyToolContext#from(ToolContext)}, falling back to
 * {@link SensitiveDataPolicy#DEFAULT} when a session is present but no policy was wired (see that constant's javadoc).
 * This is what lets a workspace with PII redaction switched off leave PII in a tool result untouched while still
 * redacting secrets, and what lets an explicit {@code minConfidence} override take effect here rather than always
 * falling back to {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}.
 * </p>
 *
 * <p>
 * <b>The delegate's returned conversation history also carries the {@link AssistantMessage} that requested the tool
 * call -- and that message is the very one {@link #restoreToolCallArguments} rebuilt with real values, because Spring
 * AI's {@code DefaultToolCallingManager} appends the exact {@link AssistantMessage} it was given to the history it
 * returns.</b> Left alone, that message would carry real PII one turn after this class went to the trouble of
 * tokenizing it, in both the next prompt {@code ToolCallingAdvisor} sends and in anything (e.g. suspended task state)
 * that persists the returned history. So every {@link AssistantMessage} with tool calls in the returned history -- not
 * only the one this invocation's delegate call just appended, but any earlier one already present in
 * {@code result.conversationHistory()} -- has each tool call's {@code arguments} passed back through the same
 * fail-closed {@code tokenizeWithSpans}-then-{@code redact}-then-empty pipeline used for tool results. This is
 * deliberately symmetric with the tool-results handling above, including re-scanning history entries this invocation
 * did not itself add: {@link PiiTokenSession#tokenFor} is keyed only on the value, so a value already tokenized earlier
 * in the session comes back as the SAME token rather than a new one, and running the pipeline over text that is already
 * in token form is a no-op (a token like {@code [PII_EMAIL_ADDRESS_1_k3n9]} does not match any detector). Re-executing
 * the tool call itself never happens here -- the delegate already ran it, against the real-valued {@link ChatResponse}
 * {@link #restoreToolCallArguments} produced, before this method ever sees the result.
 * </p>
 *
 * <p>
 * When {@code metrics} is not {@code null}, each direction records at most one incidence event per
 * {@link #executeToolCalls} invocation: {@link SensitiveDataMetrics#recordToolArgsRestored()} when the outbound
 * direction substituted at least one token, {@link SensitiveDataMetrics#recordTokenUnresolved()} (the same event the
 * response-direction restoration path already records) when it left at least one unresolved token in place,
 * {@link SensitiveDataMetrics#recordToolResultTokenized()} when the inbound direction tokenized or redacted at least
 * one tool-result value, and {@link SensitiveDataMetrics#recordAssistantHistoryRetokenized()} when at least one
 * assistant tool-call argument in the returned history was retokenized. Each is an incidence counter, not a
 * per-tool-call or per-span count -- see {@link #restoreToolCallArguments} and {@link #tokenizeConversationHistory} for
 * why the guarding booleans live outside their loops.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class PiiTokenBoundaryToolCallingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger(PiiTokenBoundaryToolCallingManager.class);

    private final ToolCallingManager delegate;
    private final SensitiveDataRedactor redactor;
    private final Supplier<@Nullable SensitiveDataMetrics> metricsSupplier;

    private PiiTokenBoundaryToolCallingManager(
        ToolCallingManager delegate, SensitiveDataRedactor redactor,
        Supplier<@Nullable SensitiveDataMetrics> metricsSupplier) {

        this.delegate = delegate;
        this.redactor = redactor;
        this.metricsSupplier = metricsSupplier;
    }

    /**
     * Wraps {@code delegate} so tool-call arguments have their PII tokens restored before the delegate executes them,
     * and so tool results are tokenized/redacted before they are returned.
     *
     * @param delegate        the {@link ToolCallingManager} to decorate
     * @param redactor        tokenizes/redacts sensitive data found in a tool's result, and re-tokenizes it in the
     *                        assistant tool-call messages the returned history carries
     * @param metricsSupplier resolves, for each invocation, the metrics instance detector failures, confidence drops,
     *                        and this class's own {@code tool_args_restored}/{@code token_unresolved}/
     *                        {@code tool_result_tokenized}/{@code assistant_history_retokenized} incidence events are
     *                        recorded through; may return {@code null}, which records nothing for that invocation. A
     *                        supplier rather than an instance because a caller may build this wrapper once for a
     *                        long-lived singleton while the guardrails settings behind the metrics instance stay
     *                        editable at runtime -- a workspace with guardrails off at boot resolves no metrics, and
     *                        binding that at construction would keep recording nothing for the rest of the JVM's life
     *                        after an admin switches them on
     * @return a {@link ToolCallingManager} that restores tokens in tool-call arguments before delegating, and
     *         tokenizes/redacts sensitive data in tool results and in the returned history's assistant tool-call
     *         messages before returning
     */
    public static ToolCallingManager wrap(
        ToolCallingManager delegate, SensitiveDataRedactor redactor,
        Supplier<@Nullable SensitiveDataMetrics> metricsSupplier) {

        return new PiiTokenBoundaryToolCallingManager(delegate, redactor, metricsSupplier);
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ToolContext toolContext = toolContext(prompt);
        PiiTokenSession session = PiiTokenSessionToolContext.from(toolContext);

        if (session == null) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        SensitiveDataPolicy policy = resolvePolicy(toolContext);

        ToolExecutionResult result = delegate.executeToolCalls(
            withoutSession(prompt), restoreToolCallArguments(chatResponse, session, policy));

        return tokenizeConversationHistory(result, session, policy);
    }

    /**
     * Returns {@code prompt} with the {@link PiiTokenSession} stripped from its tool context, so the tools the delegate
     * is about to run never see it -- see {@link PiiTokenSessionToolContext#without} for why. This method is the only
     * reason the session ever leaves this class's own frame, and it does so by not leaving it: the session used to
     * restore arguments above and to tokenize the history below is this method's caller's local reference, never
     * re-read from the tool context.
     *
     * <p>
     * The {@link SensitiveDataPolicy} on the same map is deliberately left in place. It is configuration -- which
     * {@code SensitiveKind}s to act on, and a confidence threshold -- not data, so a tool reading it learns nothing
     * about any value.
     * </p>
     */
    private static Prompt withoutSession(Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions)) {
            return prompt;
        }

        Map<String, Object> toolContext = toolCallingChatOptions.getToolContext();

        if (CollectionUtils.isEmpty(toolContext)) {
            return prompt;
        }

        Map<String, Object> strippedToolContext = PiiTokenSessionToolContext.without(toolContext);

        if (strippedToolContext == toolContext) {
            return prompt;
        }

        // `.toolContext(null)` first is load-bearing, not defensive: ToolCallingChatOptions.Builder#toolContext does
        // putAll, and mutate() has already copied the ORIGINAL context (session included) into the builder -- so
        // setting the stripped map alone merges it back over itself and removes nothing. Passing null clears the
        // builder's map (DefaultToolCallingChatOptions.Builder#toolContext, spring-ai-model 2.0.1); only then does
        // setting the stripped map actually replace it.
        ChatOptions strippedChatOptions = toolCallingChatOptions.mutate()
            .toolContext(null)
            .toolContext(strippedToolContext)
            .build();

        return new Prompt(prompt.getInstructions(), strippedChatOptions);
    }

    private static @Nullable ToolContext toolContext(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions
            && !CollectionUtils.isEmpty(toolCallingChatOptions.getToolContext())) {

            return new ToolContext(toolCallingChatOptions.getToolContext());
        }

        return null;
    }

    /**
     * Returns the {@link SensitiveDataPolicy} carried on {@code toolContext}, or {@link SensitiveDataPolicy#DEFAULT}
     * when none was wired -- see that constant's javadoc for why falling back to the pre-policy-aware behaviour, rather
     * than treating an absent policy as "everything off", is the correct default for a caller that has not been updated
     * yet.
     */
    private static SensitiveDataPolicy resolvePolicy(@Nullable ToolContext toolContext) {
        SensitiveDataPolicy policy = SensitiveDataPolicyToolContext.from(toolContext);

        return policy != null ? policy : SensitiveDataPolicy.DEFAULT;
    }

    /**
     * Restores every tool call's arguments across EVERY generation in {@code chatResponse}, not only generation 0 --
     * {@code n > 1} (multiple candidate completions) is a real {@code ChatResponse} shape, and a tool call on a later
     * generation must have its arguments restored just as reliably as one on the first. Each rebuilt generation
     * preserves its own {@link Generation#getMetadata()} and the source {@link AssistantMessage}'s
     * {@link AssistantMessage#getMedia()} -- dropping either on a rebuild would silently discard data the delegate (and
     * whatever consumes its result) still expects to see, for every call that reaches this method, not only the rare
     * {@code n > 1} case.
     *
     * <p>
     * Then records {@link SensitiveDataMetrics#recordToolArgsRestored()} and/or
     * {@link SensitiveDataMetrics#recordTokenUnresolved()} at most once for the whole invocation -- both booleans are
     * set inside the per-tool-call loop but only checked, and so only ever recorded, once after every generation is
     * processed. A version that called either {@code record*} method from inside the loop would inflate the count by
     * however many of this invocation's tool calls (across however many generations) happened to contain a restorable
     * or unresolved token, rather than reporting the single incidence this event exists to represent.
     * </p>
     *
     * <p>
     * Returns {@code chatResponse} unchanged, with no rebuild and no allocation, when NO generation carries a tool call
     * -- the same "genuinely inert when there is nothing to restore" shortcut the single-generation version of this
     * method used, just checked across every generation instead of only the first.
     * </p>
     *
     * <p>
     * <b>Also returns {@code chatResponse} unchanged, arguments left in token form, when
     * {@code !policy.restoreOutboundArguments()}.</b> This is the OUTBOUND half of {@link SensitiveDataPolicy} --
     * distinct from {@code policy.kinds()}/{@code policy.minConfidence()}, which govern only the INBOUND direction a
     * tool RESULT takes back to the model, and which this method does not consult at all. On that path this method
     * still scans every tool call's {@code arguments} via {@link PiiTokenSession#restoreWithUnresolvedCount(String)} --
     * without substituting anything -- solely to decide whether {@link SensitiveDataMetrics#recordRestoreSuppressed()}
     * applies: that event fires only when a tool call's arguments genuinely carried a resolvable token, never merely
     * because the setting is off, matching the same condition the response-direction restoration path already uses (see
     * {@code AiGuardrailsAdvisor#applyResponseGuardrails}).
     * </p>
     *
     * <p>
     * {@link SensitiveDataMetrics#recordTokenUnresolved()} is never evaluated on this gated path, unlike the
     * restore-attempted branch below it. That is intentional, not an oversight: nothing was attempted to resolve here,
     * so "unresolved" does not apply -- only {@code recordRestoreSuppressed()} describes what actually happened.
     * </p>
     */
    private ChatResponse restoreToolCallArguments(
        ChatResponse chatResponse, PiiTokenSession session, SensitiveDataPolicy policy) {

        List<Generation> generations = chatResponse.getResults();

        if (generations.isEmpty()) {
            throw new IllegalStateException("Expected a generation in the ChatResponse passed to executeToolCalls");
        }

        boolean anyGenerationHasToolCalls = generations.stream()
            .anyMatch(generation -> !CollectionUtils.isEmpty(generation.getOutput()
                .getToolCalls()));

        if (!anyGenerationHasToolCalls) {
            return chatResponse;
        }

        if (!policy.restoreOutboundArguments()) {
            // No recordTokenUnresolved() here, unlike the restore-attempted branch below: nothing was attempted to
            // resolve, so "unresolved" does not apply.
            recordRestoreSuppressedIfAnyArgumentWasResolvable(generations, session);

            return chatResponse;
        }

        List<Generation> rebuiltGenerations = new ArrayList<>(generations.size());
        boolean anyArgumentsRestored = false;
        boolean anyArgumentUnresolved = false;

        for (Generation generation : generations) {
            AssistantMessage output = generation.getOutput();
            List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

            if (CollectionUtils.isEmpty(toolCalls)) {
                rebuiltGenerations.add(generation);

                continue;
            }

            List<AssistantMessage.ToolCall> restoredToolCalls = new ArrayList<>(toolCalls.size());

            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                PiiTokenSession.RestoreResult restoreResult = session.restoreWithUnresolvedCount(toolCall.arguments());
                String restoredArguments = restoreResult.text();

                if (!Objects.equals(restoredArguments, toolCall.arguments())) {
                    anyArgumentsRestored = true;
                }

                if (restoreResult.unresolvedCount() > 0) {
                    anyArgumentUnresolved = true;
                }

                restoredToolCalls.add(
                    new AssistantMessage.ToolCall(toolCall.id(), toolCall.type(), toolCall.name(), restoredArguments));
            }

            AssistantMessage rebuiltAssistantMessage = AssistantMessage.builder()
                .content(output.getText())
                .properties(output.getMetadata())
                .toolCalls(restoredToolCalls)
                .media(output.getMedia())
                .build();

            rebuiltGenerations.add(new Generation(rebuiltAssistantMessage, generation.getMetadata()));
        }

        SensitiveDataMetrics metrics = metricsSupplier.get();

        if (metrics != null) {
            if (anyArgumentsRestored) {
                metrics.recordToolArgsRestored();
            }

            if (anyArgumentUnresolved) {
                metrics.recordTokenUnresolved();
            }
        }

        return new ChatResponse(rebuiltGenerations, chatResponse.getMetadata());
    }

    /**
     * Records {@link SensitiveDataMetrics#recordRestoreSuppressed()} at most once when
     * {@link #restoreToolCallArguments} withheld restoration under {@code !policy.restoreOutboundArguments()} -- and
     * only when withholding actually changed something. Scans every tool call's {@code arguments} across every
     * generation via {@link PiiTokenSession#restoreWithUnresolvedCount(String)} WITHOUT substituting anything back into
     * {@code generations}, purely to learn whether at least one argument carried a token this session could have
     * resolved. A call whose arguments carried no resolvable token in the first place has nothing to suppress, and must
     * not tick the same counter a genuine withholding does -- the one distinction {@code restore_suppressed} exists to
     * preserve (see that event's javadoc).
     *
     * <p>
     * This once-per-invocation guarantee is per boundary, not per agent turn: {@code AiGuardrailsAdvisor} records its
     * own {@code restore_suppressed} independently, for the response text, under the identical gate. A single
     * canvas-agent turn passes through both boundaries, so a turn that withholds restoration on both the response AND a
     * tool call's arguments records {@code restore_suppressed} twice, both tagged {@code surface=ai_agent} -- harmless
     * for the question the metric exists to answer, but not a count of turns.
     * </p>
     */
    private void recordRestoreSuppressedIfAnyArgumentWasResolvable(
        List<Generation> generations, PiiTokenSession session) {

        boolean anyArgumentWasResolvable = generations.stream()
            .flatMap(generation -> generation.getOutput()
                .getToolCalls()
                .stream())
            .anyMatch(toolCall -> !Objects.equals(
                session.restoreWithUnresolvedCount(toolCall.arguments())
                    .text(),
                toolCall.arguments()));

        if (!anyArgumentWasResolvable) {
            return;
        }

        SensitiveDataMetrics metrics = metricsSupplier.get();

        if (metrics != null) {
            metrics.recordRestoreSuppressed();
        }
    }

    /**
     * Tokenizes every tool result AND re-tokenizes every assistant tool-call argument in {@code result}'s conversation
     * history, then records {@link SensitiveDataMetrics#recordToolResultTokenized()} and/or
     * {@link SensitiveDataMetrics#recordAssistantHistoryRetokenized()} at most once each for the whole invocation --
     * each flag is set inside its loop but only checked, and so only ever recorded, once after both loops finish. A
     * version that called either from inside its loop would inflate the count by however many responses/tool calls --
     * or, worse, however many individual sensitive spans within one -- happened to be tokenized, rather than reporting
     * the single incidence each event exists to represent.
     *
     * <p>
     * The assistant-message pass covers every {@link AssistantMessage} with tool calls in the history, not only the one
     * this invocation's delegate call appended -- see the class javadoc for why re-scanning entries already in token
     * form is both necessary (an earlier invocation's message is exactly as capable of carrying real values, if this
     * pass did not exist yet when it was appended) and safe (idempotent per value, a no-op on already-tokenized text).
     * </p>
     */
    private ToolExecutionResult tokenizeConversationHistory(
        ToolExecutionResult result, PiiTokenSession session, SensitiveDataPolicy policy) {
        List<Message> conversationHistory = result.conversationHistory();
        List<Message> tokenizedConversationHistory = new ArrayList<>(conversationHistory.size());
        boolean rewroteAMessage = false;
        boolean anyToolResultValueTokenized = false;
        boolean anyAssistantArgumentRetokenized = false;

        for (Message message : conversationHistory) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                List<ToolResponseMessage.ToolResponse> tokenizedResponses =
                    new ArrayList<>(toolResponseMessage.getResponses()
                        .size());

                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    if (ToolSuspendConstants.SUSPENDED_SENTINEL.equals(response.responseData())) {
                        // A control marker, not user content -- SuspendableToolCallingManager (which wraps this
                        // class) locates the matching ToolResponse by this EXACT value to identify a pending
                        // suspend. Running it through tokenizeOrRedact risks the fail-closed path substituting
                        // redacted or empty text for it on a detector failure, which would make the sentinel
                        // unrecognisable and orphan the suspend -- so it is exempted before that pipeline ever
                        // sees it, not special-cased inside it.
                        tokenizedResponses.add(response);

                        continue;
                    }

                    String tokenized = tokenizeOrRedact(response.responseData(), session, policy);

                    if (!Objects.equals(tokenized, response.responseData())) {
                        anyToolResultValueTokenized = true;
                    }

                    tokenizedResponses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(),
                        tokenized));
                }

                tokenizedConversationHistory.add(ToolResponseMessage.builder()
                    .responses(tokenizedResponses)
                    .metadata(toolResponseMessage.getMetadata())
                    .build());
                rewroteAMessage = true;
            } else if (message instanceof AssistantMessage assistantMessage
                && !CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {

                List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                List<AssistantMessage.ToolCall> retokenizedToolCalls = new ArrayList<>(toolCalls.size());
                boolean thisMessageChanged = false;

                for (AssistantMessage.ToolCall toolCall : toolCalls) {
                    String retokenizedArguments = tokenizeOrRedact(toolCall.arguments(), session, policy);

                    if (!Objects.equals(retokenizedArguments, toolCall.arguments())) {
                        anyAssistantArgumentRetokenized = true;
                        thisMessageChanged = true;
                    }

                    retokenizedToolCalls.add(new AssistantMessage.ToolCall(
                        toolCall.id(), toolCall.type(), toolCall.name(), retokenizedArguments));
                }

                if (thisMessageChanged) {
                    tokenizedConversationHistory.add(AssistantMessage.builder()
                        .content(assistantMessage.getText())
                        .properties(assistantMessage.getMetadata())
                        .toolCalls(retokenizedToolCalls)
                        .build());
                    rewroteAMessage = true;
                } else {
                    tokenizedConversationHistory.add(message);
                }
            } else {
                tokenizedConversationHistory.add(message);
            }
        }

        SensitiveDataMetrics metrics = metricsSupplier.get();

        if (metrics != null) {
            if (anyToolResultValueTokenized) {
                metrics.recordToolResultTokenized();
            }

            if (anyAssistantArgumentRetokenized) {
                metrics.recordAssistantHistoryRetokenized();
            }
        }

        if (!rewroteAMessage) {
            return result;
        }

        return ToolExecutionResult.builder()
            .conversationHistory(tokenizedConversationHistory)
            .returnDirect(result.returnDirect())
            .build();
    }

    /**
     * Tokenizes {@code text}, falling back to redaction and then to the empty string on failure. Shared by both
     * conversation-history passes in {@link #tokenizeConversationHistory} -- tool results and assistant tool-call
     * arguments alike must never go out carrying real values, so both use the same fail-closed pipeline. See the class
     * javadoc for why this fails closed instead of returning {@code text} unchanged on an error path.
     */
    private String tokenizeOrRedact(String text, PiiTokenSession session, SensitiveDataPolicy policy) {
        try {
            return redactor
                .tokenizeWithSpans(text, policy.kinds(), session, policy.minConfidence(), metricsSupplier.get())
                .text();
        } catch (RuntimeException tokenizeException) {
            log.warn("Failed to tokenize outgoing conversation history text; falling back to redaction",
                tokenizeException);

            try {
                return redactor.redact(text, policy.kinds(), policy.minConfidence(), metricsSupplier.get());
            } catch (RuntimeException redactException) {
                log.warn(
                    "Failed to redact outgoing conversation history text after tokenization also failed; "
                        + "substituting empty text",
                    redactException);

                return "";
            }
        }
    }
}
