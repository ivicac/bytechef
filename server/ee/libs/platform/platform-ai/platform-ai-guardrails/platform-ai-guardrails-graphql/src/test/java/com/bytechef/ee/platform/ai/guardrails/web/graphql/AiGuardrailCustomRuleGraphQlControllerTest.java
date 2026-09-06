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

import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailCustomRuleService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGuardrailCustomRuleGraphQlControllerTest {

    private final AiGuardrailCustomRuleService aiGuardrailCustomRuleService =
        mock(AiGuardrailCustomRuleService.class);

    private final AiGuardrailCustomRuleGraphQlController controller =
        new AiGuardrailCustomRuleGraphQlController(aiGuardrailCustomRuleService);

    @Test
    void testTheReadIsGatedOnTheWorkspaceItReads() {
        // The gate must key on the same argument the body reads. A gate keyed on something the body does not use
        // authorizes a different request than the one that runs -- the defect the settings controller's javadoc
        // records having shipped once.
        assertThat(preAuthorizeOf("aiGuardrailCustomRules", long.class))
            .contains("hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_VIEW')");
    }

    /**
     * Every mutation is admin-only, and no mutation may reference a permission scope.
     *
     * <p>
     * The second half is the load-bearing one. {@code AI_GATEWAY_VIEW} is the only AI-gateway scope that exists, so a
     * plausible-looking {@code AI_GATEWAY_EDIT} in one of these expressions would not create a scope — an unregistered
     * token makes {@code hasPermission} deny, and every write would fail for everyone including admins. This test is
     * what turns that from a silent outage into a red build; it caught exactly that mistake once.
     * </p>
     */
    @Test
    void testEveryMutationIsAdminOnlyAndNamesNoScope() {
        List<Method> mutations = List.of(AiGuardrailCustomRuleGraphQlController.class.getDeclaredMethods())
            .stream()
            .filter(method -> method.isAnnotationPresent(MutationMapping.class))
            .toList();

        assertThat(mutations)
            .as("create, updatePattern, setEnabled and delete")
            .hasSize(4);

        for (Method mutation : mutations) {
            PreAuthorize preAuthorize = mutation.getAnnotation(PreAuthorize.class);

            assertThat(preAuthorize)
                .as("%s must be gated", mutation.getName())
                .isNotNull();
            assertThat(preAuthorize.value())
                .as("%s must be admin-only", mutation.getName())
                .isEqualTo("hasAuthority('ROLE_ADMIN')");
            assertThat(preAuthorize.value())
                .as("%s must not name a scope that does not exist", mutation.getName())
                .doesNotContain("hasPermission");
        }
    }

    @Test
    void testTheControllerDelegatesRatherThanValidating() {
        // Validation lives in the service so every write path gets it, not only this one. A controller that
        // validated would be a gate a future importer walks around.
        controller.updateAiGuardrailCustomRulePattern(42L, 7L, "\\bACME-\\d{4}\\b");

        verify(aiGuardrailCustomRuleService).updatePattern(7L, 42L, "\\bACME-\\d{4}\\b");
    }

    @Test
    void testDeleteIsScopedToTheWorkspaceItWasGivenNotJustTheId() {
        controller.deleteAiGuardrailCustomRule(42L, 7L);

        verify(aiGuardrailCustomRuleService).delete(7L, 42L);
    }

    private static String preAuthorizeOf(String name, Class<?>... parameterTypes) {
        try {
            PreAuthorize preAuthorize = AiGuardrailCustomRuleGraphQlController.class
                .getDeclaredMethod(name, parameterTypes)
                .getAnnotation(PreAuthorize.class);

            assertThat(preAuthorize).isNotNull();

            return preAuthorize.value();
        } catch (NoSuchMethodException noSuchMethodException) {
            throw new IllegalStateException(noSuchMethodException);
        }
    }
}
