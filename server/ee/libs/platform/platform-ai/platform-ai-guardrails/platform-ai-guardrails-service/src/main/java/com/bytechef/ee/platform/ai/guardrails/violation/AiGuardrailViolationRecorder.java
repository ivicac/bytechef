/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.violation;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolation;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolationAction;
import com.bytechef.ee.platform.ai.guardrails.repository.AiGuardrailViolationRepository;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Persists per-detection guardrail records, off by default, asynchronously, and under a daily per-workspace cap.
 *
 * <p>
 * <b>Off unless a workspace asked for it.</b> The counter {@code bytechef_ai_guardrail} stays the free path; this is
 * the drill-down a workspace opts into. An unconfigured workspace writes nothing and pays nothing, which is what makes
 * a row-per-detection store defensible on a request path at all.
 * </p>
 *
 * <p>
 * <b>Never on the calling thread, and dropped rather than blocking.</b> A record is evidence about a call, not part of
 * it: a slow or failing write must not delay or fail the guarded model call. So submissions go to a bounded executor
 * and a full queue sheds them. That is only acceptable because drops are counted -- an operator debugging a rule has to
 * be able to tell "it never fired" from "we lost the row", and without {@code violation_record_dropped} those look
 * identical.
 * </p>
 *
 * <p>
 * <b>Capped per workspace per day.</b> Uncapped, one runaway workspace fills a shared deployment's table. Sampling was
 * the obvious alternative and is worse: a 1-in-N sample makes "did my new rule fire on this request?" unanswerable,
 * which is the question the whole feature exists to answer. A cap preserves completeness up to a limit instead of
 * degrading it everywhere.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class AiGuardrailViolationRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiGuardrailViolationRecorder.class);

    private final AiGuardrailViolationRepository aiGuardrailViolationRepository;
    private final ExecutorService executorService;
    private final int dailyCap;

    /**
     * Tracks, per workspace, whether the cap has already been reported today, so {@code violation_records_capped} fires
     * once per workspace per day rather than once per suppressed record. A capped workspace can produce thousands of
     * suppressed records an hour; a counter that moved for each would be measuring the traffic, not the event.
     */
    private final Map<CapKey, AtomicBoolean> capReported = new ConcurrentHashMap<>();

    @Autowired
    @SuppressFBWarnings("EI")
    public AiGuardrailViolationRecorder(
        AiGuardrailViolationRepository aiGuardrailViolationRepository,
        @Value("${bytechef.ai.guardrails.violation.daily-cap:100000}") int dailyCap,
        @Value("${bytechef.ai.guardrails.violation.queue-capacity:1000}") int queueCapacity) {

        // The executor is owned here rather than injected because its two properties ARE the design: a bounded queue,
        // and an abort policy so a full queue sheds submissions instead of running them on the caller's thread.
        // Spring's default pools do neither -- CallerRunsPolicy in particular would quietly turn the guarantee
        // "recording never delays the guarded call" into its opposite under exactly the load that makes it matter.
        this(
            aiGuardrailViolationRepository,
            new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "guardrail-violation-recorder");

                    thread.setDaemon(true);

                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()),
            dailyCap);
    }

    /**
     * Test seam. A caller passing a same-thread executor gets synchronous writes, so a test can assert on the outcome
     * without waiting on a background thread -- which would otherwise mean either a sleep or a latch in every test
     * here.
     */
    AiGuardrailViolationRecorder(
        AiGuardrailViolationRepository aiGuardrailViolationRepository, ExecutorService executorService, int dailyCap) {

        this.aiGuardrailViolationRepository = aiGuardrailViolationRepository;
        this.executorService = executorService;
        this.dailyCap = dailyCap;
    }

    /**
     * Submits one record per span, returning immediately.
     *
     * @param spans       the spans this call acted on; nothing is submitted when empty
     * @param enabled     whether the workspace has opted into recording -- resolved by the caller, since it owns the
     *                    settings lookup and this class must not read settings on the request thread
     * @param action      what was done about the detections. {@code ALLOWED} records are counterfactuals and must stay
     *                    distinguishable from enforcements.
     * @param recordEvent invoked with a metric event name for each outcome, so this class stays free of the EE metrics
     *                    type and remains unit-testable without one
     */
    public void submit(
        List<SensitiveSpan> spans, boolean enabled, AiGuardrailViolationAction action, String surface,
        @Nullable Long workspaceId, @Nullable Integer environment, @Nullable String principal,
        Consumer<String> recordEvent) {

        if (!enabled || spans.isEmpty()) {
            return;
        }

        try {
            executorService.execute(() -> write(spans, action, surface, workspaceId, environment, principal,
                recordEvent));
        } catch (RejectedExecutionException rejectedExecutionException) {
            // Shedding is the designed behaviour, not a failure to handle: the queue being full means the guarded
            // calls are outrunning the writer, and blocking one to record evidence about it would be the wrong trade.
            recordEvent.accept("violation_record_dropped");

            log.debug("Guardrail violation record dropped: writer queue full");
        }
    }

    private void write(
        List<SensitiveSpan> spans, AiGuardrailViolationAction action, String surface, @Nullable Long workspaceId,
        @Nullable Integer environment, @Nullable String principal, Consumer<String> recordEvent) {

        try {
            Instant since = Instant.now()
                .minus(Duration.ofDays(1));

            long written = aiGuardrailViolationRepository.countForWorkspaceSince(workspaceId, since);

            if (written + spans.size() > dailyCap) {
                reportCapOnce(workspaceId, recordEvent);

                return;
            }

            for (SensitiveSpan span : spans) {
                aiGuardrailViolationRepository.save(
                    new AiGuardrailViolation(
                        span.category(), span.kind()
                            .ordinal(),
                        span.start(), span.length(),
                        BigDecimal.valueOf(span.confidence())
                            .setScale(2, RoundingMode.HALF_UP),
                        action, surface, workspaceId, environment, principal));
            }

            recordEvent.accept("violation_record_written");
        } catch (RuntimeException runtimeException) {
            // Caught rather than propagated because this runs on the writer thread, where an escaping exception would
            // be logged by the executor and nothing else -- the counter is what an operator actually sees.
            recordEvent.accept("violation_record_dropped");

            log.warn("Guardrail violation record write failed; continuing without it", runtimeException);
        }
    }

    private void reportCapOnce(@Nullable Long workspaceId, Consumer<String> recordEvent) {
        CapKey capKey = new CapKey(workspaceId, Instant.now()
            .truncatedTo(ChronoUnit.DAYS));

        AtomicBoolean reported = capReported.computeIfAbsent(capKey, key -> new AtomicBoolean());

        if (reported.compareAndSet(false, true)) {
            recordEvent.accept("violation_records_capped");

            log.info(
                "Guardrail violation recording capped for workspace {} at {} records/day; further records today are " +
                    "not persisted",
                workspaceId, dailyCap);

            // Yesterday's keys are dead weight once a new day starts, and this map would otherwise grow without
            // bound on a long-lived process.
            capReported.keySet()
                .removeIf(key -> !key.day()
                    .equals(capKey.day()));
        }
    }

    private record CapKey(@Nullable Long workspaceId, Instant day) {
    }
}
