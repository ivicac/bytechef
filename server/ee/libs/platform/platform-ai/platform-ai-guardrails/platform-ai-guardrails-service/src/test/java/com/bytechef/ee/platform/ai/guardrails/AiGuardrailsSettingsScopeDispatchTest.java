/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsTarget;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link AiGuardrails}'s dispatch on an explicit {@link AiGuardrailsSettingsTarget}: an {@code EMBEDDED} target
 * must read the embedded settings row and never the workspace/tenant-default one, and a workspace or platform target
 * must read its own row and never the embedded one. Both directions are asserted with {@code verify(..., never())} -- a
 * fix that made every target read the embedded row would satisfy a one-directional test, and that failure would be
 * exactly as silent as the bug being fixed.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGuardrailsSettingsScopeDispatchTest {

    private final AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService =
        mock(AiGuardrailsWorkspaceSettingsService.class);

    @Test
    void testAnEmbeddedTargetReadsTheEmbeddedRow() {
        AiGuardrails aiGuardrails = aiGuardrailsWithBothRows();

        assertThat(aiGuardrails.isActive(AiGuardrailsSettingsTarget.embedded())).isTrue();

        verify(aiGuardrailsWorkspaceSettingsService).fetchEmbeddedSettings();
        verify(aiGuardrailsWorkspaceSettingsService, never()).fetchSettings(any());
    }

    @Test
    void testAPlatformTargetReadsTheTenantDefaultRowAndNotTheEmbeddedOne() {
        AiGuardrails aiGuardrails = aiGuardrailsWithBothRows();

        aiGuardrails.isActive(AiGuardrailsSettingsTarget.platform());

        verify(aiGuardrailsWorkspaceSettingsService).fetchSettings(null);
        verify(aiGuardrailsWorkspaceSettingsService, never()).fetchEmbeddedSettings();
    }

    @Test
    void testAWorkspaceTargetReadsThatWorkspacesRow() {
        AiGuardrails aiGuardrails = aiGuardrailsWithBothRows();

        aiGuardrails.isActive(AiGuardrailsSettingsTarget.workspace(7L));

        verify(aiGuardrailsWorkspaceSettingsService).fetchSettings(7L);
        verify(aiGuardrailsWorkspaceSettingsService, never()).fetchEmbeddedSettings();
    }

    /**
     * Finding 1 of the task-3 fix round: {@code checkInputs} and {@code tokenizeInputs} are the INPUT-direction methods
     * -- where {@code redactPii}/{@code redactSecrets} actually act -- and they were the two methods the original task
     * missed (a multi-line signature the plan's own grep could not match), so an embedded run's request still resolved
     * the tenant-default {@code PLATFORM} row instead of the {@code EMBEDDED} row. Both directions are asserted,
     * exactly as {@link #testAnEmbeddedTargetReadsTheEmbeddedRow()} above does for {@code isActive}: the embedded row
     * must be read, and the workspace/platform row must not be.
     */
    @Test
    void testAnEmbeddedTargetReachesTheEmbeddedRowOnTheCheckInputsPath() {
        AiGuardrails aiGuardrails = aiGuardrailsWithBothRows();
        AiGuardrailMetrics metrics = new AiGuardrailMetrics(new SimpleMeterRegistry(), "test");

        aiGuardrails.checkInputs(List.of("mail bob@acme.io"), AiGuardrailsSettingsTarget.embedded(), metrics);

        verify(aiGuardrailsWorkspaceSettingsService).fetchEmbeddedSettings();
        verify(aiGuardrailsWorkspaceSettingsService, never()).fetchSettings(any());
    }

    /**
     * As {@link #testAnEmbeddedTargetReachesTheEmbeddedRowOnTheCheckInputsPath()}, for the tokenizing counterpart --
     * {@code tokenizeInputs} is the other input-direction method Finding 1 covers.
     */
    @Test
    void testAnEmbeddedTargetReachesTheEmbeddedRowOnTheTokenizeInputsPath() {
        AiGuardrails aiGuardrails = aiGuardrailsWithBothRows();
        AiGuardrailMetrics metrics = new AiGuardrailMetrics(new SimpleMeterRegistry(), "test");
        PiiTokenSession session = PiiTokenSession.create();

        aiGuardrails.tokenizeInputs(
            List.of("mail bob@acme.io"), AiGuardrailsSettingsTarget.embedded(), session, metrics);

        verify(aiGuardrailsWorkspaceSettingsService).fetchEmbeddedSettings();
        verify(aiGuardrailsWorkspaceSettingsService, never()).fetchSettings(any());
    }

    @Test
    void testAnEmbeddedLookupFailureDoesNotThrowAndFailsClosed() {
        AiGuardrails aiGuardrails = aiGuardrailsWithThrowingEmbeddedRow();

        assertThatCode(() -> aiGuardrails.isRestoreIntoWorkflowOutput(AiGuardrailsSettingsTarget.embedded()))
            .doesNotThrowAnyException();
        assertThat(aiGuardrails.isRestoreIntoWorkflowOutput(AiGuardrailsSettingsTarget.embedded())).isFalse();
    }

    private AiGuardrails aiGuardrailsWithBothRows() {
        AiGuardrailsWorkspaceSettings embeddedSettings = new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.EMBEDDED, null, false, true, null, null, null, null, null, null, null, null);
        AiGuardrailsWorkspaceSettings tenantDefaultSettings = new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.PLATFORM, null, true, false, null, null, null, null, null, null, null, null);

        when(aiGuardrailsWorkspaceSettingsService.fetchEmbeddedSettings())
            .thenReturn(Optional.of(embeddedSettings));
        when(aiGuardrailsWorkspaceSettingsService.fetchSettings(any()))
            .thenReturn(Optional.of(tenantDefaultSettings));

        return new AiGuardrails(
            aiGuardrailsWorkspaceSettingsService, null, null, null, false, false, "", false, false, false, false);
    }

    private AiGuardrails aiGuardrailsWithThrowingEmbeddedRow() {
        when(aiGuardrailsWorkspaceSettingsService.fetchEmbeddedSettings())
            .thenThrow(new IllegalStateException("connection reset"));

        return new AiGuardrails(
            aiGuardrailsWorkspaceSettingsService, null, null, null, false, false, "", false, false, false, false);
    }
}
