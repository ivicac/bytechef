/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails;

import com.bytechef.platform.ai.sensitivedata.SensitiveDataMetrics;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Counts guardrail activity so operators can see what the DLP layer is catching. Emits a single
 * {@code bytechef_ai_guardrail} counter tagged by {@code event} — one of {@code pii_redacted}, {@code pii_tokenized}
 * (PII was replaced with a reversible session token instead of an irreversible placeholder), {@code secret_redacted},
 * {@code response_redacted}, {@code pii_restored} (a token minted for this request was substituted back to its real
 * value in the response), {@code token_unresolved} (a token-shaped span — in the response, or in a tool call's
 * arguments — could not be resolved back to a value — an unknown ordinal, or a token minted by another session),
 * {@code blocked_term}, {@code moderation_flagged}, {@code injection_flagged}, {@code guardrail_allowed} (a blocking
 * violation was detected under {@code BlockingMode.ALLOW} and the content was forwarded unmodified -- observe mode's
 * entire product, and the reason {@code ALLOW} is distinguishable from the guardrail simply being off),
 * {@code detector_failed} (a {@code SensitiveDataDetector} threw and was skipped for that call),
 * {@code below_confidence_threshold} (at least one candidate span was dropped from a call because its confidence fell
 * below {@code SensitiveDataRedactor}'s {@code minConfidence}), {@code tool_args_restored} (at least one PII token in a
 * tool call's arguments was restored before {@code PiiTokenBoundaryToolCallingManager}'s delegate ran the tool),
 * {@code tool_result_tokenized} (at least one value in a tool's result was tokenized/redacted before it reached the
 * model), or {@code assistant_history_retokenized} (at least one assistant tool-call argument in the conversation
 * history {@code PiiTokenBoundaryToolCallingManager} returns was retokenized before that history went out) — and by
 * {@code surface}, identifying which caller is applying guardrails (e.g. {@code gateway} for the AI Gateway adapter).
 * Only these two low-cardinality tags are used (no workspace/project dimension) so the meter stays cheap on unbounded
 * multi-tenant deployments. Wired through {@link ObjectProvider} so lightweight app variants without an actuator
 * {@link MeterRegistry} start cleanly and recording is a no-op.
 *
 * <p>
 * The {@code surface} is fixed per bean instance (constructor argument) rather than passed per {@link #record} call.
 * This Spring-wired constructor backs the engine's own internal instance (default surface {@code gateway}, gated on
 * {@code bytechef.ai.gateway.enabled} so it stays a no-op when the gateway is disabled) — used ONLY by
 * {@code AiGuardrails#applyToInputs}, the AI Gateway adapter's throwing entry point, whose request-direction
 * redaction/blocking events therefore stay tagged {@code gateway} (or emit nothing when the gateway is off) regardless
 * of which caller triggered them. Every other caller of the engine constructs its own instance per request via the
 * other constructor, tagged with its own surface (e.g. {@code ai_agent}, {@code ai_hub}): {@code
 * AiGuardrailsAdvisorProviderImpl} and {@code AiHubSpringAIAgent} build the instance that {@code AiGuardrailsAdvisor}
 * then passes into {@code AiGuardrails#checkInputs} for every request-direction event (redaction, blocked terms,
 * injection) as well as the advisor-decided {@code blocking_downgraded} / {@code response_redacted} events — so the
 * advisor path carries an accurate, non-gateway-gated surface tag end to end.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class AiGuardrailMetrics implements SensitiveDataMetrics {

    public static final String COUNTER_NAME = "bytechef_ai_guardrail";

    private static final String DETECTOR_FAILED_EVENT = "detector_failed";
    private static final String BELOW_CONFIDENCE_THRESHOLD_EVENT = "below_confidence_threshold";
    private static final String TOOL_ARGS_RESTORED_EVENT = "tool_args_restored";
    private static final String TOOL_RESULT_TOKENIZED_EVENT = "tool_result_tokenized";
    private static final String TOKEN_UNRESOLVED_EVENT = "token_unresolved";
    private static final String ASSISTANT_HISTORY_RETOKENIZED_EVENT = "assistant_history_retokenized";

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    private final @Nullable MeterRegistry meterRegistry;
    private final String surface;

    // Two constructors are declared, so Spring cannot pick an autowire candidate implicitly and would fall back to a
    // (non-existent) default constructor. @Autowired marks this one as the container's entry point.
    @Autowired
    public AiGuardrailMetrics(
        ObjectProvider<MeterRegistry> meterRegistryProvider,
        @Value("${bytechef.ai.guardrails.surface:gateway}") String surface) {

        this(meterRegistryProvider.getIfAvailable(), surface);
    }

    // Public (not package-private like the pre-extraction AiGatewayGuardrailMetrics) so callers outside this module —
    // notably the AI Gateway adapter's own tests, which build this engine's beans directly with a raw MeterRegistry —
    // can construct an instance without going through Spring.
    public AiGuardrailMetrics(@Nullable MeterRegistry meterRegistry, String surface) {
        this.meterRegistry = meterRegistry;
        this.surface = surface;
    }

    /**
     * Increments the guardrail counter for the given {@code event}, tagged with this instance's configured
     * {@code surface}. No-op when no {@link MeterRegistry} is present.
     *
     * @param event one of the documented event names
     */
    public void record(String event) {
        if (meterRegistry == null) {
            return;
        }

        Counter.builder(COUNTER_NAME)
            .description("AI guardrail actions by event type and surface")
            .tag("event", event)
            .tag("surface", surface)
            .register(meterRegistry)
            .increment();
    }

    /**
     * {@link SensitiveDataMetrics} seam for {@code SensitiveDataRedactor}, which cannot depend on this EE type
     * directly. Delegates to {@link #record(String)} with the same {@code detector_failed} event this class already
     * recorded before the redactor moved to the CE {@code platform-ai-sensitive-data} module, so the event name and
     * this instance's {@code surface} tag are unchanged. {@code detectorName} is not itself a tag — see this class's
     * javadoc on why only {@code event}/{@code surface} are used, to keep the meter's cardinality low.
     */
    @Override
    @SuppressWarnings("PMD.UnusedFormalParameter")
    public void recordDetectorFailure(String detectorName) {
        record(DETECTOR_FAILED_EVENT);
    }

    /**
     * {@link SensitiveDataMetrics} seam for {@code SensitiveDataRedactor}'s confidence filter. Delegates to
     * {@link #record(String)} with the {@code below_confidence_threshold} event, matching how
     * {@link #recordDetectorFailure} delegates for its own event.
     */
    @Override
    public void recordBelowConfidenceThreshold() {
        record(BELOW_CONFIDENCE_THRESHOLD_EVENT);
    }

    /**
     * {@link SensitiveDataMetrics} seam for {@code PiiTokenBoundaryToolCallingManager}'s outbound (model-to-tool)
     * direction. Delegates to {@link #record(String)} with the {@code tool_args_restored} event.
     */
    @Override
    public void recordToolArgsRestored() {
        record(TOOL_ARGS_RESTORED_EVENT);
    }

    /**
     * {@link SensitiveDataMetrics} seam for {@code PiiTokenBoundaryToolCallingManager}'s inbound (tool-to-model)
     * direction. Delegates to {@link #record(String)} with the {@code tool_result_tokenized} event.
     */
    @Override
    public void recordToolResultTokenized() {
        record(TOOL_RESULT_TOKENIZED_EVENT);
    }

    /**
     * {@link SensitiveDataMetrics} seam for an unresolved token found in a tool call's arguments. Delegates to
     * {@link #record(String)} with the same {@code token_unresolved} event the response-direction restoration path
     * already records (see {@code AiGuardrails#restoreResponseText} / {@code StreamingResponseRedactor}), rather than a
     * separate name for the tool-boundary case.
     */
    @Override
    public void recordTokenUnresolved() {
        record(TOKEN_UNRESOLVED_EVENT);
    }

    /**
     * {@link SensitiveDataMetrics} seam for an assistant tool-call argument retokenized in the conversation history
     * {@code PiiTokenBoundaryToolCallingManager} returns. Delegates to {@link #record(String)} with the
     * {@code assistant_history_retokenized} event.
     */
    @Override
    public void recordAssistantHistoryRetokenized() {
        record(ASSISTANT_HISTORY_RETOKENIZED_EVENT);
    }
}
