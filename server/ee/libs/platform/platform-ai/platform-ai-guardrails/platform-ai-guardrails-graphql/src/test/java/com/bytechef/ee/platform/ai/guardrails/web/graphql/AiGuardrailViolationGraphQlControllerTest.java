/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolation;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolationAction;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailViolationService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGuardrailViolationGraphQlControllerTest {

    private final AiGuardrailViolationService aiGuardrailViolationService = mock(AiGuardrailViolationService.class);

    private final AiGuardrailViolationGraphQlController controller =
        new AiGuardrailViolationGraphQlController(aiGuardrailViolationService);

    @Test
    void testAnAbsentLimitFallsBackToTheDefaultRatherThanUnbounded() {
        // These rows exist at request volume, so "no limit given" must not mean "the whole retention window".
        when(aiGuardrailViolationService.getViolations(42L, 100)).thenReturn(List.of());

        controller.aiGuardrailViolations(42L, null);

        verify(aiGuardrailViolationService).getViolations(42L, 100);
    }

    @Test
    void testTheQueryReturnsWhatTheServiceGives() {
        AiGuardrailViolation violation = new AiGuardrailViolation(
            "EMAIL_ADDRESS", 0, 5, 11, new BigDecimal("0.90"), AiGuardrailViolationAction.REDACTED, "ai_hub", 42L, 1,
            null);

        when(aiGuardrailViolationService.getViolations(42L, 25)).thenReturn(List.of(violation));

        assertThat(controller.aiGuardrailViolations(42L, 25)).containsExactly(violation);
    }

    /**
     * The gate must key on the same argument the body reads. A gate keyed on something the body does not use authorizes
     * a different request than the one that runs — the exact defect
     * {@code AiGuardrailsWorkspaceSettingsGraphQlController}'s javadoc records having shipped once, where a caller
     * passing their own workspace id with {@code scope: EMBEDDED} was handed a tenant-wide row.
     */
    @Test
    void testTheReadIsGatedOnTheWorkspaceItReads() throws NoSuchMethodException {
        Method method = AiGuardrailViolationGraphQlController.class.getDeclaredMethod(
            "aiGuardrailViolations", Long.class, Integer.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
            .as("a workspace read must require a scope on THAT workspace")
            .contains("hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_VIEW')");
        assertThat(preAuthorize.value())
            .as("the tenant-default bucket has no workspace to scope against, so it must be admin-only")
            .contains("#workspaceId != null");
    }

    @Test
    void testThereIsNoWriteOrByIdSurface() {
        // Two properties in one check. A record whose subject can rewrite it is not evidence of anything, and a
        // by-id read would be a probe oracle for other workspaces' records -- the design closes that by not having
        // the lookup at all rather than by making it return not-found.
        List<String> methods = List.of(AiGuardrailViolationGraphQlController.class.getDeclaredMethods())
            .stream()
            .filter(method -> !method.isSynthetic())
            .map(Method::getName)
            // JaCoCo instruments an $jacocoInit into every class under `check`, so filtering by name is not enough;
            // synthetic covers it and the lambda case together.
            .filter(name -> !name.contains("$"))
            .toList();

        assertThat(methods).containsExactly("aiGuardrailViolations");
    }
}
