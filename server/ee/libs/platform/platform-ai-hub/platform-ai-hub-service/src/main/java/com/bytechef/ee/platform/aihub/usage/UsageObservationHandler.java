/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.usage;

import com.bytechef.ee.platform.ai.llm.usage.LlmUsageContext;
import com.bytechef.ee.platform.ai.llm.usage.LlmUsageRecorder;
import com.bytechef.ee.platform.aihub.task.AiHubTask;
import com.bytechef.ee.platform.aihub.task.AiHubTaskService;
import com.bytechef.ee.platform.aihub.tool.AiHubToolInvocationContext;
import com.bytechef.ee.platform.aihub.usage.CurrentAgentContext.AgentBinding;
import com.bytechef.ee.platform.aihub.util.LogSanitizer;
import com.bytechef.ee.platform.aihub.util.Source;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * Spring AI {@link ChatModelObservationContext} listener that records one usage row per LLM call. Resolves the
 * workspace + user + task + agent attribution from the {@link Prompt}'s {@link ToolCallingChatOptions#getToolContext()}
 * and {@link CurrentAgentContext}.
 *
 * <p>
 * On {@link Observation.Context#onStop(io.micrometer.observation.Observation.Context) onStop} the handler pulls the
 * model name and token counts from {@link ChatResponse#getMetadata()}, computes a cost via {@link CostEstimator}, and
 * writes a {@code ai_hub_usage} row via {@link UsageRecorder}. Failures are logged and swallowed so a metering hiccup
 * never breaks the user-facing request.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class UsageObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    private static final Logger log = LoggerFactory.getLogger(UsageObservationHandler.class);

    private static final String START_NANOS_KEY = "bytechef.cc.usage.startNanos";

    /**
     * Latches once per JVM the first time {@link #onStop} skips an LLM call because the prompt's tool context did not
     * carry workspace/user identifiers. Pairs with the {@code bytechef.usage.context_unavailable_total} counter (tagged
     * {@code source=llm}) so a non-zero rate alerts in metrics while the WARN-once keeps log volume bounded.
     */
    private static final AtomicBoolean CONTEXT_UNAVAILABLE_LOGGED = new AtomicBoolean(false);

    /**
     * Latches once per JVM the first time {@link #onStop} skips a billing row because the {@link ChatResponse} or its
     * {@link ChatResponseMetadata} was null. A malformed final chunk from a provider produces a free LLM call otherwise
     * — pairs with the {@code bytechef.usage.observation_skipped_total} counter (tagged {@code reason=...}) so
     * dashboards can alert on rate.
     */
    private static final AtomicBoolean OBSERVATION_SKIPPED_LOGGED = new AtomicBoolean(false);

    /**
     * Metadata marker key set on every synthetic {@link ChatResponse} produced by {@link #buildSafeResponse}. Read by
     * {@code UsageObservationHandler#onStop} to skip the billing row for the fallback chunk: charging the workspace for
     * an "unexpected error occurred" placeholder turn is a real revenue bug, since {@code onStop} reads the upstream
     * {@link org.springframework.ai.chat.metadata.ChatResponseMetadata} (which still carries non-zero token counts when
     * the upstream parser failed mid-chunk).
     */
    public static final String SYNTHETIC_RESPONSE_METADATA_KEY = "bytechef.synthetic_response";

    private final LlmUsageRecorder llmUsageRecorder;
    private final AiHubTaskService taskService;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public UsageObservationHandler(LlmUsageRecorder llmUsageRecorder, AiHubTaskService taskService) {
        this.llmUsageRecorder = llmUsageRecorder;
        this.taskService = taskService;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStart(ChatModelObservationContext context) {
        context.put(START_NANOS_KEY, System.nanoTime());
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        try {
            ChatResponse response = context.getResponse();

            if (response == null) {
                recordObservationSkipped("null_response");

                return;
            }

            ChatResponseMetadata metadata = response.getMetadata();

            if (metadata == null) {
                recordObservationSkipped("null_metadata");

                return;
            }

            if (metadata.getOrDefault(SYNTHETIC_RESPONSE_METADATA_KEY, Boolean.FALSE)
                .equals(Boolean.TRUE)) {
                // The upstream library returned null/threw for this chunk and SafeAnthropicChatModel substituted
                // a placeholder assistant message ("[Sorry, an unexpected error occurred...]") so the user sees
                // an in-line error instead of a blank turn. The original metadata still carries the upstream's
                // token count, so without this skip the workspace would be billed for the synthetic chunk.
                recordObservationSkipped("synthetic_response");

                return;
            }

            Usage usage = metadata.getUsage();

            if (usage == null) {
                // Skip the row entirely rather than billing inputTokens=0/outputTokens=0. A null Usage on a final
                // chunk means the provider didn't report tokens — treating that as "free LLM call billed at $0"
                // would silently corrupt cost-by-agent dashboards. Mirrors the null_response and null_metadata
                // skip arms above.
                recordObservationSkipped("null_usage");

                return;
            }

            int inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            int outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            String model = metadata.getModel();

            AiHubToolInvocationContext invocationContext = invocationContextFromPrompt(context.getRequest());

            Long workspaceId = invocationContext == null ? null : invocationContext.workspaceId();
            Long userId = invocationContext == null ? null : invocationContext.userId();

            if (workspaceId == null || userId == null) {
                Metrics.counter("bytechef.usage.context_unavailable_total", "source", "llm")
                    .increment();

                if (CONTEXT_UNAVAILABLE_LOGGED.compareAndSet(false, true)) {
                    log.warn(
                        "Skipping LLM usage record: prompt tool context missing workspaceId or userId " +
                            "(workspaceId={}, userId={}). This message logs once per JVM; the " +
                            "bytechef.usage.context_unavailable_total counter (source=llm) tracks ongoing rate.",
                        workspaceId, userId);
                }

                return;
            }

            Long taskId =
                resolveTaskId(invocationContext == null ? null : invocationContext.threadId());

            AgentBinding binding = CurrentAgentContext.current();
            Agent agentName = binding != null
                ? binding.agentName()
                : deriveAgentName(invocationContext == null ? null : invocationContext.sourceOrdinal());
            Agent parentAgent = binding != null ? binding.parentAgent() : null;

            Long startNanos = context.get(START_NANOS_KEY);
            long durationMs = startNanos != null ? (System.nanoTime() - startNanos) / 1_000_000L : 0L;

            // Persist UNKNOWN as a null agentName rather than as the literal "UNKNOWN" string — a null in the
            // shared schema means "no CC routing dim for this row" and analytics queries that GROUP BY agentName
            // already cope with NULL. Recording UNKNOWN as a real value would taint cost-by-agent dashboards
            // permanently (mirrors the old UsageRecorderImpl skip-on-UNKNOWN behaviour, but expressed at the
            // context level instead of inside the recorder).
            String agentNameStr = (agentName == null || agentName == Agent.UNKNOWN) ? null : agentName.name();
            String parentAgentStr = parentAgent == null ? null : parentAgent.name();

            LlmUsageContext usageContext = LlmUsageContext.forAiHub(
                workspaceId, userId, taskId, agentNameStr, parentAgentStr);

            llmUsageRecorder.recordLlm(
                usageContext, model != null ? model : "", inputTokens, outputTokens, durationMs);
        } catch (RuntimeException exception) {
            // Mirror UsageRecorderImpl.recordLlm/recordTool: emit on the same unified counter so dashboards
            // alerting on bytechef.usage.recording.failures{reason=*} pick up observation-handler drops too,
            // and log at ERROR (not WARN) — a free LLM call that didn't produce a billing row is the strongest
            // possible signal that ops should investigate.
            Metrics.counter("bytechef.usage.recording.failures", "reason", "observation_handler_error")
                .increment();

            log.error("Failed to record LLM usage observation: {}", exception.getMessage(), exception);
        }
    }

    private static AiHubToolInvocationContext invocationContextFromPrompt(Prompt prompt) {
        if (prompt == null) {
            return null;
        }

        ChatOptions options = prompt.getOptions();

        if (!(options instanceof ToolCallingChatOptions toolCallingChatOptions)) {
            return null;
        }

        Map<String, Object> toolContext = toolCallingChatOptions.getToolContext();

        if (toolContext == null || toolContext.isEmpty()) {
            return null;
        }

        return AiHubToolInvocationContext.fromToolContext(
            new org.springframework.ai.chat.model.ToolContext(toolContext));
    }

    private void recordObservationSkipped(String reason) {
        Metrics.counter("bytechef.usage.observation_skipped_total", "reason", reason)
            .increment();

        if (OBSERVATION_SKIPPED_LOGGED.compareAndSet(false, true)) {
            log.warn(
                "Skipping LLM billing row: ChatModelObservationContext.onStop saw {} — a malformed final chunk " +
                    "produces a free LLM call. This message logs once per JVM; the " +
                    "bytechef.usage.observation_skipped_total counter (reason={}) tracks ongoing rate.",
                reason, reason);
        }
    }

    private Long resolveTaskId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }

        try {
            return taskService.findByThreadId(threadId)
                .map(AiHubTask::getId)
                .orElse(null);
        } catch (RuntimeException exception) {
            // Service-layer failures (DB outage, transient connection loss) need their own counter so dashboards
            // surface a sustained DB outage instead of letting orphan rows accumulate invisibly.
            Metrics.counter(
                "bytechef.usage.task_unresolved_total",
                "source", "llm",
                "reason", "service_failure")
                .increment();

            log.warn(
                "Could not resolve task by threadId={} — recording usage row with taskId=null",
                LogSanitizer.sanitizeForLog(threadId), exception);

            return null;
        }
    }

    /**
     * Falls back to a coarse agent name derived from the {@link Source} ordinal when no
     * {@link CurrentAgentContext.AgentBinding} is bound. Mode-level distinction (ask vs build) is the responsibility of
     * the caller binding {@link CurrentAgentContext}.
     *
     * <p>
     * The exhaustive {@code switch} on the resolved {@link Source} value (rather than a generic
     * {@code SOURCE_TO_AGENT.get(source)}) forces a compile error when a new {@link Source} variant is added — the
     * author must explicitly decide the agent-name mapping rather than getting a silent fallthrough to UNKNOWN. The
     * preceding bounds check on {@code sourceOrdinal} keeps a stale ordinal from indexing past the array.
     * </p>
     */
    private Agent deriveAgentName(Short sourceOrdinal) {
        if (sourceOrdinal == null) {
            recordUnknownAgent("null_source_ordinal");

            return Agent.UNKNOWN;
        }

        Source[] values = Source.values();
        int index = sourceOrdinal.intValue();

        if (index < 0 || index >= values.length) {
            recordUnknownAgent("out_of_range_source_ordinal");

            return Agent.UNKNOWN;
        }

        Source source = values[index];

        return switch (source) {
            case WORKFLOW_EDITOR -> Agent.WORKFLOW_EDITOR;
            case CODE_EDITOR -> Agent.CODE_EDITOR;
            case CLUSTER_ELEMENT -> Agent.CLUSTER_ELEMENT;
            case FILES -> Agent.FILES;
            case AI_HUB -> Agent.AI_HUB;
        };
    }

    /**
     * Tracks how often {@link #deriveAgentName} resolves to {@link Agent#UNKNOWN}. A sustained non-zero rate is a
     * wiring regression worth alerting on — usage rows tagged {@code agent=UNKNOWN} silently corrupt cost-by-agent
     * dashboards because they aggregate across surfaces.
     */
    private static void recordUnknownAgent(String reason) {
        Metrics.counter("bytechef.usage.agent_unknown_total", "reason", reason)
            .increment();
    }

    /**
     * Returns the observation context key under which {@link #onStart} stores the wall-clock start time. Visible for
     * tests so a synthesised context can be primed without going through the live {@code Observation} machinery.
     */
    public static String startNanosKey() {
        return START_NANOS_KEY;
    }
}
