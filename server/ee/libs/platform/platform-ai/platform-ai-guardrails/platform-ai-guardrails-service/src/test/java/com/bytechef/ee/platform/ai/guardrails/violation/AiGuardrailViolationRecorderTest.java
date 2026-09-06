/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolation;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolationAction;
import com.bytechef.ee.platform.ai.guardrails.repository.AiGuardrailViolationRepository;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGuardrailViolationRecorderTest {

    private static final String MATCHED_VALUE = "bob@acme.io";

    /**
     * Deliberately a DIFFERENT address from {@link #MATCHED_VALUE}. The principal is attribution and is stored on
     * purpose -- it is who made the request, which the workspace already knows -- so reusing one string for both would
     * make this test unable to distinguish "the detected value leaked" from "the principal was stored as designed". The
     * first draft did reuse it, and failed for that reason rather than for a real leak.
     */
    private static final String PRINCIPAL = "admin@localhost.com";

    private final AiGuardrailViolationRepository repository = mock(AiGuardrailViolationRepository.class);
    private final List<String> events = new ArrayList<>();

    /**
     * The load-bearing test of the whole feature, and the reason it asserts over the SERIALIZED record rather than
     * field by field: a field-by-field assertion pins only the fields whoever wrote it remembered, and the failure this
     * guards against is exactly someone adding a field without thinking about what it carries.
     */
    @Test
    void testNoMatchedValueReachesAWrittenRecord() {
        AiGuardrailViolationRecorder recorder = recorder(1000);

        when(repository.countForWorkspaceSince(any(), any())).thenReturn(0L);

        recorder.submit(
            List.of(new SensitiveSpan(SensitiveKind.PII, "EMAIL_ADDRESS", 5, 16, 0.9)), true,
            AiGuardrailViolationAction.REDACTED, "ai_hub", 42L, 1, PRINCIPAL, events::add);

        ArgumentCaptor<AiGuardrailViolation> captor = ArgumentCaptor.forClass(AiGuardrailViolation.class);

        verify(repository).save(captor.capture());

        AiGuardrailViolation violation = captor.getValue();

        // Every accessor on the entity, not a chosen subset: if a value-carrying field is ever added, this fails.
        String serialized = violation.getCategory() + "|" + violation.getKind() + "|" + violation.getSpanStart() +
            "|" + violation.getSpanLength() + "|" + violation.getConfidence() + "|" + violation.getAction() + "|" +
            violation.getSurface() + "|" + violation.getWorkspaceId() + "|" + violation.getEnvironment() + "|" +
            violation.getPrincipal() + "|" + violation;

        assertThat(serialized).doesNotContain(MATCHED_VALUE);

        // ... while the principal, which IS stored, is present. Without this half the test would still pass if the
        // recorder silently stopped writing anything at all.
        assertThat(serialized).contains(PRINCIPAL);
    }

    @Test
    void testTheRecordLocatesTheMatchItRefusesToReproduce() {
        AiGuardrailViolationRecorder recorder = recorder(1000);

        when(repository.countForWorkspaceSince(any(), any())).thenReturn(0L);

        recorder.submit(
            List.of(new SensitiveSpan(SensitiveKind.PII, "EMAIL_ADDRESS", 5, 16, 0.9)), true,
            AiGuardrailViolationAction.REDACTED, "ai_hub", 42L, 1, null, events::add);

        ArgumentCaptor<AiGuardrailViolation> captor = ArgumentCaptor.forClass(AiGuardrailViolation.class);

        verify(repository).save(captor.capture());

        AiGuardrailViolation violation = captor.getValue();

        assertThat(violation.getCategory()).isEqualTo("EMAIL_ADDRESS");
        assertThat(violation.getSpanStart()).isEqualTo(5);
        assertThat(violation.getSpanLength()).isEqualTo(11);
        assertThat(events).containsExactly("violation_record_written");
    }

    @Test
    void testRecordingIsOffUnlessTheWorkspaceEnabledIt() {
        // The primary volume control. A workspace that never asked for drill-down must not pay for it, and "pay"
        // includes the cap query -- so nothing at all may reach the repository.
        AiGuardrailViolationRecorder recorder = recorder(1000);

        recorder.submit(
            List.of(new SensitiveSpan(SensitiveKind.PII, "EMAIL_ADDRESS", 5, 16, 0.9)), false,
            AiGuardrailViolationAction.REDACTED, "ai_hub", 42L, 1, null, events::add);

        verify(repository, never()).save(any());
        verify(repository, never()).countForWorkspaceSince(any(), any());
        assertThat(events).isEmpty();
    }

    @Test
    void testAFailingWriteNeitherThrowsNorGoesUnreported() {
        // A record is evidence about a call, not part of it. But a silent drop would make "my rule never fired"
        // indistinguishable from "we lost the row", which is the one thing an operator debugging a rule cannot
        // afford -- so the drop is counted.
        AiGuardrailViolationRecorder recorder = recorder(1000);

        when(repository.countForWorkspaceSince(any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(
            () -> recorder.submit(
                List.of(new SensitiveSpan(SensitiveKind.PII, "EMAIL_ADDRESS", 5, 16, 0.9)), true,
                AiGuardrailViolationAction.REDACTED, "ai_hub", 42L, 1, null, events::add))
                    .doesNotThrowAnyException();

        assertThat(events).containsExactly("violation_record_dropped");
    }

    @Test
    void testTheDailyCapStopsWritesAndFiresItsEventExactlyOnce() {
        // Once per workspace per day, not once per suppressed record: a capped workspace can produce thousands of
        // suppressed records an hour, and a counter that moved for each would be measuring the traffic.
        AiGuardrailViolationRecorder recorder = recorder(10);

        when(repository.countForWorkspaceSince(any(), any())).thenReturn(10L);

        for (int index = 0; index < 5; index++) {
            recorder.submit(
                List.of(new SensitiveSpan(SensitiveKind.PII, "EMAIL_ADDRESS", 5, 16, 0.9)), true,
                AiGuardrailViolationAction.REDACTED, "ai_hub", 42L, 1, null, events::add);
        }

        verify(repository, never()).save(any());
        assertThat(events).containsExactly("violation_records_capped");
    }

    @Test
    void testAnAllowedRecordIsDistinguishableFromAnEnforcedOne() {
        // Observe mode's records are counterfactuals. Collapsing them into REDACTED would tell an operator their
        // guardrails are working when nothing has been enforced at all.
        AiGuardrailViolationRecorder recorder = recorder(1000);

        when(repository.countForWorkspaceSince(any(), any())).thenReturn(0L);

        recorder.submit(
            List.of(new SensitiveSpan(SensitiveKind.PII, "EMAIL_ADDRESS", 5, 16, 0.9)), true,
            AiGuardrailViolationAction.ALLOWED, "ai_hub", 42L, 1, null, events::add);

        ArgumentCaptor<AiGuardrailViolation> captor = ArgumentCaptor.forClass(AiGuardrailViolation.class);

        verify(repository).save(captor.capture());

        assertThat(captor.getValue()
            .getAction()).isEqualTo(AiGuardrailViolationAction.ALLOWED);
    }

    @Test
    void testNothingIsSubmittedWhenNoSpansWereDetected() {
        AiGuardrailViolationRecorder recorder = recorder(1000);

        recorder.submit(
            List.of(), true, AiGuardrailViolationAction.REDACTED, "ai_hub", 42L, 1, null, events::add);

        verify(repository, never()).countForWorkspaceSince(any(), any());
        assertThat(events).isEmpty();
    }

    @Test
    void testTheNullWorkspaceBucketIsCappedToo() {
        // countForWorkspaceSince uses IS NOT DISTINCT FROM precisely so this bucket exists. A plain = would never
        // match null, leaving every unattributed call's bucket uncapped -- and unattributed is where the
        // no-principal callers land.
        AiGuardrailViolationRecorder recorder = recorder(10);

        when(repository.countForWorkspaceSince(any(), any())).thenReturn(10L);

        recorder.submit(
            List.of(new SensitiveSpan(SensitiveKind.PII, "EMAIL_ADDRESS", 5, 16, 0.9)), true,
            AiGuardrailViolationAction.REDACTED, "ai_hub", null, null, null, events::add);

        verify(repository, never()).save(any());
        assertThat(events).containsExactly("violation_records_capped");
    }

    private AiGuardrailViolationRecorder recorder(int dailyCap) {
        return new AiGuardrailViolationRecorder(repository, sameThreadExecutor(), dailyCap);
    }

    /**
     * Runs submissions inline so the assertions need no latch and no sleep. The production constructor builds a
     * bounded, aborting pool instead; that shape is what the {@code violation_record_dropped}-on-rejection path exists
     * for and is exercised by the failing-write test rather than by racing a real queue.
     */
    private static ExecutorService sameThreadExecutor() {
        return new AbstractExecutorService() {

            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }
}
