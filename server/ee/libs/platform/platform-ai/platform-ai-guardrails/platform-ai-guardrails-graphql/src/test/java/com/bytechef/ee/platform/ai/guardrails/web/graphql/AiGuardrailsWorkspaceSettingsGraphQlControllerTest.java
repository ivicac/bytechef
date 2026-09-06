/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings.BlockingMode;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService;
import com.bytechef.graphql.error.GraphQlBadRequestException;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * @version ee
 */
class AiGuardrailsWorkspaceSettingsGraphQlControllerTest {

    private AiGuardrailsWorkspaceSettingsGraphQlController aiGuardrailsWorkspaceSettingsGraphQlController;
    private AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService;

    @BeforeEach
    void beforeEach() {
        aiGuardrailsWorkspaceSettingsService = mock(AiGuardrailsWorkspaceSettingsService.class);
        aiGuardrailsWorkspaceSettingsGraphQlController =
            new AiGuardrailsWorkspaceSettingsGraphQlController(aiGuardrailsWorkspaceSettingsService);
    }

    @Test
    void testAiGuardrailsWorkspaceSettingsQueryRequiresWorkspaceMembershipOrAdmin() throws NoSuchMethodException {
        Method method = AiGuardrailsWorkspaceSettingsGraphQlController.class.getDeclaredMethod(
            "aiGuardrailsWorkspaceSettings", Long.class, AiGuardrailsSettingsScope.class);

        assertThat(method.getAnnotation(QueryMapping.class)).isNotNull();

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
            .as("the non-admin branch must exclude the EMBEDDED scope: this gate keys on workspaceId while the "
                + "method body dispatches on scope, so without that condition a member of any workspace could pass "
                + "their own id alongside scope: EMBEDDED and be handed the tenant-wide row, which only ROLE_ADMIN "
                + "may write")
            .isEqualTo(
                "hasAuthority('ROLE_ADMIN') or (#scope != "
                    + "T(com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope).EMBEDDED "
                    + "&& #workspaceId != null && hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_VIEW'))");
    }

    @Test
    void testEveryArgumentTheQueryBodyBranchesOnAlsoAppearsInItsGate() throws NoSuchMethodException {
        Method method = AiGuardrailsWorkspaceSettingsGraphQlController.class.getDeclaredMethod(
            "aiGuardrailsWorkspaceSettings", Long.class, AiGuardrailsSettingsScope.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
            .as("a gate that ignores an argument the body switches on authorizes a different request than the one "
                + "that runs - the defect this query shipped with")
            .contains("#workspaceId")
            .contains("#scope");
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsMutationRequiresAdmin() throws NoSuchMethodException {
        Method method = AiGuardrailsWorkspaceSettingsGraphQlController.class.getDeclaredMethod(
            "updateAiGuardrailsWorkspaceSettings",
            AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput.class);

        assertThat(method.getAnnotation(MutationMapping.class)).isNotNull();

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
    }

    @Test
    void testAiGuardrailsWorkspaceSettingsWithNullWorkspaceIdFetchesTenantDefault() {
        AiGuardrailsWorkspaceSettings tenantDefault = new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.PLATFORM, null, true, true, "secret", false, true, false,
            BlockingMode.REDACT_AND_CONTINUE, null, null);

        when(aiGuardrailsWorkspaceSettingsService.fetchSettings(isNull())).thenReturn(Optional.of(tenantDefault));

        AiGuardrailsWorkspaceSettings result =
            aiGuardrailsWorkspaceSettingsGraphQlController.aiGuardrailsWorkspaceSettings(null, null);

        assertThat(result).isEqualTo(tenantDefault);

        verify(aiGuardrailsWorkspaceSettingsService).fetchSettings(isNull());
    }

    @Test
    void testAiGuardrailsWorkspaceSettingsReturnsNullWhenNoSettingsRowExists() {
        when(aiGuardrailsWorkspaceSettingsService.fetchSettings(eq(1L))).thenReturn(Optional.empty());

        AiGuardrailsWorkspaceSettings result =
            aiGuardrailsWorkspaceSettingsGraphQlController.aiGuardrailsWorkspaceSettings(1L, null);

        assertThat(result).isNull();
    }

    @Test
    void testAiGuardrailsWorkspaceSettingsWithEmbeddedScopeFetchesEmbeddedSettings() {
        AiGuardrailsWorkspaceSettings embeddedSettings = new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.EMBEDDED, null, true, false, null, false, false, false,
            BlockingMode.BLOCK, null, true);

        when(aiGuardrailsWorkspaceSettingsService.fetchEmbeddedSettings()).thenReturn(
            Optional.of(embeddedSettings));

        AiGuardrailsWorkspaceSettings result = aiGuardrailsWorkspaceSettingsGraphQlController
            .aiGuardrailsWorkspaceSettings(null, AiGuardrailsSettingsScope.EMBEDDED);

        assertThat(result).isEqualTo(embeddedSettings);

        verify(aiGuardrailsWorkspaceSettingsService).fetchEmbeddedSettings();
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsRoundTripsThroughService() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                null, 1L, true, false, "foo,bar", true, false, true, BlockingMode.BLOCK, 0.75, null);

        AiGuardrailsWorkspaceSettings saved = new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.WORKSPACE, 1L, true, false, "foo,bar", true, false, true, BlockingMode.BLOCK,
            0.75, null);

        when(aiGuardrailsWorkspaceSettingsService.saveSettings(eq(saved))).thenReturn(saved);

        AiGuardrailsWorkspaceSettings result =
            aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(input);

        assertThat(result).isEqualTo(saved);

        verify(aiGuardrailsWorkspaceSettingsService).saveSettings(eq(saved));
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsPassesRedactMcpResultsThrough() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                null, 1L, null, null, null, null, null, null, null, null, true);

        aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(input);

        ArgumentCaptor<AiGuardrailsWorkspaceSettings> captor =
            ArgumentCaptor.forClass(AiGuardrailsWorkspaceSettings.class);

        verify(aiGuardrailsWorkspaceSettingsService).saveSettings(captor.capture());

        AiGuardrailsWorkspaceSettings saved = captor.getValue();

        assertThat(saved.redactMcpResults()).isTrue();
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsPassesTheEmbeddedScopeThrough() {
        aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(embeddedInput());

        ArgumentCaptor<AiGuardrailsWorkspaceSettings> captor =
            ArgumentCaptor.forClass(AiGuardrailsWorkspaceSettings.class);

        verify(aiGuardrailsWorkspaceSettingsService).saveSettings(captor.capture());

        AiGuardrailsWorkspaceSettings saved = captor.getValue();

        assertThat(saved.scope()).isEqualTo(AiGuardrailsSettingsScope.EMBEDDED);
        assertThat(saved.workspaceId()).isNull();
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsRejectsEmbeddedScopeWithNonNullWorkspaceId() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                AiGuardrailsSettingsScope.EMBEDDED, 1L, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(
            () -> aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(input))
                .isInstanceOf(GraphQlBadRequestException.class)
                .hasMessageContaining("scope=EMBEDDED")
                .hasMessageContaining("workspaceId=1");

        verify(aiGuardrailsWorkspaceSettingsService, never()).saveSettings(any());
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsRejectsWorkspaceScopeWithNullWorkspaceId() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                AiGuardrailsSettingsScope.WORKSPACE, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(
            () -> aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(input))
                .isInstanceOf(GraphQlBadRequestException.class)
                .hasMessageContaining("scope=WORKSPACE")
                .hasMessageContaining("workspaceId=null");

        verify(aiGuardrailsWorkspaceSettingsService, never()).saveSettings(any());
    }

    private AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput embeddedInput() {
        return new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
            AiGuardrailsSettingsScope.EMBEDDED, null, true, false, null, false, false, false, BlockingMode.BLOCK,
            null, true);
    }
}
