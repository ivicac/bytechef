/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.tool;

import com.bytechef.ee.platform.aihub.metric.AiHubToolAttachMetrics;
import com.bytechef.ee.ai.mcp.tool.util.LogSanitizer;
import com.bytechef.ee.ai.mcp.tool.util.ToolErrors;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bridges spring-ai-agent-utils' {@link AskUserQuestionTool} into the AI Hub streaming agent. Used by the LLM to pose a
 * multi-choice clarification ("Which Slack action did you mean?" / "Pick a connection"), with the actual UI buttons
 * rendered by the chat client via a {@code kind: "ask-user-question"} data part.
 *
 * <p>
 * <b>Why we wrap the library tool instead of using it directly:</b> the library's {@code QuestionHandler} contract is
 * synchronous — {@code handle(List<Question>)} must return {@code Map<String, String>} (the answers). The AI Hub's
 * streaming agent runs inside a Spring AI loop that does not have a built-in pause/resume mechanism (workflow chats use
 * {@code actionContext.suspend()} via the AG-UI bridge; the main agent does not). To integrate cleanly without blocking
 * a request thread for minutes on a {@code CompletableFuture.get(...)} call, we use the library tool to parse +
 * validate the question schema, capture the {@code Question[]} list via a thread-local in the {@code QuestionHandler},
 * then override the returned string to be a structured {@code {kind: "ask-user-question", questions: [...]}} envelope
 * the client recognises. The agent's turn ends with the envelope; the user clicks an option in the chat UI which
 * submits a follow-up user message; the agent reads that message naturally on its next turn.
 * </p>
 *
 * <p>
 * The library's {@code Question} / {@code Option} types are still the source of truth for the schema — the LLM sees the
 * standard {@code askUserQuestion} tool definition with the library's input schema. We just substitute the response on
 * the way back to keep the loop streaming-friendly.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class AskUserQuestionToolCallback implements ToolCallback {

    static final String TOOL_NAME = "askUserQuestion";
    static final String KIND = "ask-user-question";

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionToolCallback.class);

    /**
     * Thread-local question capture set by the library's {@code QuestionHandler} during
     * {@link #call(String, ToolContext)}. The library tool's {@code call(...)} runs the handler inline on the request
     * thread, so the thread-local is always read on the same thread it was written. Cleared in a {@code finally} block
     * so a leaked value can't bleed into the next tool call on the same worker thread.
     */
    private static final ThreadLocal<List<AskUserQuestionTool.Question>> CAPTURED_QUESTIONS = new ThreadLocal<>();

    private final ToolCallback delegate;
    private final AiHubToolAttachMetrics metrics;
    private final JsonMapper jsonMapper;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AskUserQuestionToolCallback(AiHubToolAttachMetrics metrics, JsonMapper jsonMapper) {
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;

        AskUserQuestionTool askUserQuestionTool = AskUserQuestionTool.builder()
            .questionHandler(AskUserQuestionToolCallback::captureQuestionsReturningPlaceholders)
            .answersValidation(false)
            .build();

        // ToolCallbacks.from produces one callback per tool method; AskUserQuestionTool exposes a single
        // tool-call entry point so the array has one element. Fail fast if the library ever changes shape.
        ToolCallback[] callbacks = ToolCallbacks.from(askUserQuestionTool);

        if (callbacks.length != 1) {
            throw new IllegalStateException(
                "Expected exactly one ToolCallback from AskUserQuestionTool, got " + callbacks.length
                    + " — library shape changed; wrapper needs updating");
        }

        this.delegate = callbacks[0];
    }

    @Override
    public ToolDefinition getToolDefinition() {
        // The library's tool name is "AskUserQuestionTool" (PascalCase, the class name) — every other AI Hub
        // tool callback uses camelCase ("createConnection", "attachTaskTool", "searchComponents"). Rename to
        // "askUserQuestion" so the LLM-facing prompt + system-prompt references stay consistent. Description
        // and input schema are forwarded from the library unchanged.
        ToolDefinition libraryDefinition = delegate.getToolDefinition();

        return ToolDefinition.builder()
            .name(TOOL_NAME)
            .description(libraryDefinition.description())
            .inputSchema(libraryDefinition.inputSchema())
            .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        CAPTURED_QUESTIONS.remove();

        // Pre-validate input shape BEFORE delegating to the library. Reason: the library's Question compact
        // constructor only logs WARN (does NOT throw) for header > 12 chars and options count outside 2-4,
        // and lets the partially-malformed Question through. Spring AI 2.0.0-M6's Anthropic adapter then
        // produces an empty tool_result content block when the malformed shape goes through serialisation,
        // and Anthropic rejects the next agent iteration with HTTP 400 "messages.<N>: user messages must
        // have non-empty content" — observed in production with prompts that exercise this path. Returning
        // a structured tool error here gives the LLM clear, actionable feedback for the retry instead.
        String validationError = validateInputShape(toolInput);

        if (validationError != null) {
            metrics.recordAskUserQuestion("error");

            log.warn(
                "askUserQuestion rejected by pre-validation: {} — first 300 chars of input: {}",
                validationError,
                LogSanitizer.sanitizeForLog(
                    toolInput == null ? "<null>" : toolInput.substring(0, Math.min(toolInput.length(), 300))));

            return toolError(validationError);
        }

        try {
            // The library tool parses the input, validates the schema, calls our QuestionHandler (which captures
            // the Question[] into CAPTURED_QUESTIONS and returns placeholders), then serialises the placeholders
            // as its return value. We discard that return value — what we care about is the captured questions.
            delegate.call(toolInput, toolContext);

            List<AskUserQuestionTool.Question> questions = CAPTURED_QUESTIONS.get();

            if (questions == null || questions.isEmpty()) {
                // Library accepted the input but emitted no questions — surfaces as a tool-error so the LLM can
                // recover (probably by re-issuing with non-empty questions).
                metrics.recordAskUserQuestion("empty");

                return toolError("No questions captured from askUserQuestion input");
            }

            Map<String, Object> envelope = new LinkedHashMap<>();

            envelope.put("kind", KIND);
            envelope.put("questions", serialiseQuestions(questions));
            envelope.put("awaitingAnswer", true);

            metrics.recordAskUserQuestion("success");

            return jsonMapper.writeValueAsString(envelope);
        } catch (RuntimeException exception) {
            metrics.recordAskUserQuestion("error");

            log.warn(
                "askUserQuestion failed: {} — first 200 chars of input: {}",
                exception.getMessage(),
                LogSanitizer.sanitizeForLog(
                    toolInput == null ? "<null>" : toolInput.substring(0, Math.min(toolInput.length(), 200))));

            return ToolErrors.runtimeFailure(jsonMapper, AskUserQuestionToolCallback.class, TOOL_NAME, exception);
        } finally {
            CAPTURED_QUESTIONS.remove();
        }
    }

    private static Map<String, String> captureQuestionsReturningPlaceholders(
        List<AskUserQuestionTool.Question> questions) {

        CAPTURED_QUESTIONS.set(questions);

        // Placeholder answers keyed by question text — the library expects a non-null map, but with
        // answersValidation(false) the contents are not checked. The outer wrapper discards this return value
        // entirely (see call()).
        Map<String, String> placeholders = new HashMap<>();

        for (AskUserQuestionTool.Question question : questions) {
            placeholders.put(question.question(), "");
        }

        return placeholders;
    }

    private static List<Map<String, Object>> serialiseQuestions(List<AskUserQuestionTool.Question> questions) {
        List<Map<String, Object>> serialised = new ArrayList<>(questions.size());

        for (AskUserQuestionTool.Question question : questions) {
            Map<String, Object> row = new LinkedHashMap<>();

            row.put("question", question.question());
            row.put("header", question.header());
            row.put("multiSelect", question.multiSelect());

            List<Map<String, Object>> options = new ArrayList<>();

            for (AskUserQuestionTool.Question.Option option : question.options()) {
                Map<String, Object> optionRow = new LinkedHashMap<>();

                optionRow.put("label", option.label());
                optionRow.put("description", option.description());

                options.add(optionRow);
            }

            row.put("options", options);

            serialised.add(row);
        }

        return serialised;
    }

    /**
     * Validates the LLM's input against the library's documented constraints — strictly, with hard rejection instead of
     * the library's WARN-and-continue behaviour. Returns a human-readable error string when a violation is found, or
     * {@code null} when the input is well-formed.
     *
     * <p>
     * Constraints enforced:
     * </p>
     * <ul>
     * <li>{@code questions} must be a non-empty array with 1-4 entries.</li>
     * <li>Each question's {@code header} must be present, non-blank, and {@code <= 12} characters.</li>
     * <li>Each question's {@code question} text must be present and non-blank.</li>
     * <li>Each question's {@code options} array must contain 2-4 entries.</li>
     * <li>Each option's {@code label} and {@code description} must be present and non-blank.</li>
     * </ul>
     */
    private @Nullable String validateInputShape(@Nullable String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return "askUserQuestion input is empty";
        }

        JsonNode root;

        try {
            root = jsonMapper.readTree(toolInput);
        } catch (JacksonException exception) {
            // Surface a parse failure so the LLM knows to fix its JSON. The library would also throw on
            // unparseable input, but its exception's message leaks internals — ours is LLM-friendly.
            return "askUserQuestion input is not valid JSON: " + exception.getMessage();
        }

        JsonNode questionsNode = root.get("questions");

        if (questionsNode == null || !questionsNode.isArray()) {
            return "askUserQuestion requires a 'questions' array";
        }

        int questionsCount = questionsNode.size();

        if (questionsCount < 1 || questionsCount > 4) {
            return "askUserQuestion 'questions' must contain 1-4 items, got: " + questionsCount;
        }

        for (int i = 0; i < questionsCount; i++) {
            String questionError = validateQuestionNode(questionsNode.get(i), i);

            if (questionError != null) {
                return questionError;
            }
        }

        return null;
    }

    private @Nullable String validateQuestionNode(JsonNode questionNode, int index) {
        if (questionNode == null || !questionNode.isObject()) {
            return "askUserQuestion 'questions[" + index + "]' must be an object";
        }

        String question = textOrNull(questionNode.get("question"));

        if (question == null || question.isBlank()) {
            return "askUserQuestion 'questions[" + index + "].question' is required and must be non-blank";
        }

        String header = textOrNull(questionNode.get("header"));

        if (header == null || header.isBlank()) {
            return "askUserQuestion 'questions[" + index + "].header' is required and must be non-blank";
        }

        if (header.length() > 12) {
            return "askUserQuestion 'questions[" + index + "].header' is " + header.length()
                + " characters; the limit is 12 (use a shorter chip label like 'Channel' or 'Auth')";
        }

        JsonNode optionsNode = questionNode.get("options");

        if (optionsNode == null || !optionsNode.isArray()) {
            return "askUserQuestion 'questions[" + index + "].options' must be an array of 2-4 items";
        }

        int optionsCount = optionsNode.size();

        if (optionsCount < 2 || optionsCount > 4) {
            return "askUserQuestion 'questions[" + index + "].options' must contain 2-4 items, got: "
                + optionsCount
                + " (the user always sees an 'Other' fallback automatically — do not include it as a hard-coded option)";
        }

        for (int j = 0; j < optionsCount; j++) {
            JsonNode optionNode = optionsNode.get(j);

            if (optionNode == null || !optionNode.isObject()) {
                return "askUserQuestion 'questions[" + index + "].options[" + j + "]' must be an object";
            }

            String label = textOrNull(optionNode.get("label"));

            if (label == null || label.isBlank()) {
                return "askUserQuestion 'questions[" + index + "].options[" + j
                    + "].label' is required and must be non-blank";
            }

            String description = textOrNull(optionNode.get("description"));

            if (description == null || description.isBlank()) {
                return "askUserQuestion 'questions[" + index + "].options[" + j
                    + "].description' is required and must be non-blank";
            }
        }

        return null;
    }

    private static @Nullable String textOrNull(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        return node.asText();
    }

    private String toolError(String message) {
        return ToolErrors.toolError(jsonMapper, message);
    }
}
