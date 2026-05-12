/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.personalagent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ee.automation.ai.gateway.domain.WorkspaceAiGatewayProvider;
import com.bytechef.ee.automation.ai.gateway.facade.WorkspaceAiGatewayModelFacade;
import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModel;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the save-time validation rules for {@link PersonalAgentSaveValidator#validate}. Validation only runs at save —
 * the runtime resolver ({@link com.bytechef.ee.automation.aihub.agent.AiHubChatClientResolver}) tolerates mismatches by
 * warn-and-fallback, so this validator's job is purely to catch admin misconfiguration before it lands in the row.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class PersonalAgentSaveValidatorTest {

    private static final long WORKSPACE_ID = 42L;
    private static final long OPENAI_PROVIDER_ID = 100L;
    private static final String OPENAI_MODEL = "gpt-4o";

    private WorkspaceAiGatewayProviderService workspaceAiGatewayProviderService;
    private WorkspaceAiGatewayModelFacade workspaceAiGatewayModelFacade;
    private AiGatewayProviderService aiGatewayProviderService;

    private AiGatewayProvider openAiProvider;

    private PersonalAgentSaveValidator validator;

    @BeforeEach
    void setUp() {
        workspaceAiGatewayProviderService = mock(WorkspaceAiGatewayProviderService.class);
        workspaceAiGatewayModelFacade = mock(WorkspaceAiGatewayModelFacade.class);
        aiGatewayProviderService = mock(AiGatewayProviderService.class);

        openAiProvider = mock(AiGatewayProvider.class);

        when(openAiProvider.getId()).thenReturn(OPENAI_PROVIDER_ID);
        when(openAiProvider.getType()).thenReturn(AiGatewayProviderType.OPENAI);
        when(openAiProvider.isEnabled()).thenReturn(true);

        WorkspaceAiGatewayProvider workspaceOpenAi = mock(WorkspaceAiGatewayProvider.class);

        when(workspaceOpenAi.getProviderId()).thenReturn(OPENAI_PROVIDER_ID);

        when(workspaceAiGatewayProviderService.getWorkspaceProviders(WORKSPACE_ID))
            .thenReturn(List.of(workspaceOpenAi));

        when(aiGatewayProviderService.getProvider(OPENAI_PROVIDER_ID)).thenReturn(openAiProvider);

        validator = new PersonalAgentSaveValidator(
            workspaceAiGatewayProviderService, workspaceAiGatewayModelFacade, aiGatewayProviderService);
    }

    @Test
    void testEnabledProviderAndEnabledModelPasses() {
        AiGatewayModel enabledModel = mock(AiGatewayModel.class);

        when(enabledModel.isEnabled()).thenReturn(true);
        when(enabledModel.getProviderId()).thenReturn(OPENAI_PROVIDER_ID);
        when(enabledModel.getName()).thenReturn(OPENAI_MODEL);

        when(workspaceAiGatewayModelFacade.getWorkspaceModels(WORKSPACE_ID)).thenReturn(List.of(enabledModel));

        assertDoesNotThrow(() -> validator.validate(WORKSPACE_ID, "OPENAI", OPENAI_MODEL));
    }

    @Test
    void testUnknownProviderThrowsWithProviderInMessage() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(WORKSPACE_ID, "GROQ", "llama-3"));

        // Message embeds the offending provider so the GraphQL surface returns an actionable error rather than a
        // generic "validation failed" — admins see exactly which value the workspace doesn't recognize.
        assertTrue(exception.getMessage()
            .contains("'GROQ'"));
        assertTrue(exception.getMessage()
            .contains("AI Gateway"));
    }

    @Test
    void testUnknownModelThrowsWithModelInMessage() {
        // Provider IS enabled in the workspace, but the requested model isn't — different error message, different
        // recovery path (enable the model, not the provider).
        AiGatewayModel enabledModel = mock(AiGatewayModel.class);

        when(enabledModel.isEnabled()).thenReturn(true);
        when(enabledModel.getProviderId()).thenReturn(OPENAI_PROVIDER_ID);
        when(enabledModel.getName()).thenReturn("gpt-3.5-turbo");

        when(workspaceAiGatewayModelFacade.getWorkspaceModels(WORKSPACE_ID)).thenReturn(List.of(enabledModel));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(WORKSPACE_ID, "OPENAI", OPENAI_MODEL));

        assertTrue(exception.getMessage()
            .contains("'" + OPENAI_MODEL + "'"));
        assertTrue(exception.getMessage()
            .contains("'OPENAI'"));
    }

    @Test
    void testDisabledModelTreatedAsUnknown() {
        // A model that exists in the workspace but is disabled in AI Gateway settings should fail validation the
        // same way as if the model didn't exist — the admin must enable it before assigning. Mirrors the runtime
        // resolver's enabled-only filter so save-time and runtime semantics line up.
        AiGatewayModel disabledModel = mock(AiGatewayModel.class);

        when(disabledModel.isEnabled()).thenReturn(false);

        when(workspaceAiGatewayModelFacade.getWorkspaceModels(WORKSPACE_ID)).thenReturn(List.of(disabledModel));

        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(WORKSPACE_ID, "OPENAI", OPENAI_MODEL));
    }

    @Test
    void testModelUnderDifferentProviderTreatedAsUnknown() {
        // The (provider, model) pair must match TOGETHER — a model with the right name but belonging to a different
        // provider doesn't count. Otherwise admins could assign Anthropic's "claude-opus" name to OpenAI and the
        // runtime would silently route the wrong API.
        AiGatewayModel modelUnderOtherProvider = mock(AiGatewayModel.class);

        when(modelUnderOtherProvider.isEnabled()).thenReturn(true);
        when(modelUnderOtherProvider.getProviderId()).thenReturn(999L);
        when(modelUnderOtherProvider.getName()).thenReturn(OPENAI_MODEL);

        when(workspaceAiGatewayModelFacade.getWorkspaceModels(WORKSPACE_ID))
            .thenReturn(List.of(modelUnderOtherProvider));

        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(WORKSPACE_ID, "OPENAI", OPENAI_MODEL));
    }
}
