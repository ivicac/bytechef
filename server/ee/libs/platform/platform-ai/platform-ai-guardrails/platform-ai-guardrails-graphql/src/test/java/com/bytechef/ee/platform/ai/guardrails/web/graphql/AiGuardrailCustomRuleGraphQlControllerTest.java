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

import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailCustomRuleService;
import com.bytechef.ee.platform.ai.guardrails.web.graphql.AiGuardrailCustomRuleGraphQlController.AiGuardrailCustomRuleInput;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGuardrailCustomRuleGraphQlControllerTest {

    private static final long WORKSPACE_ID = 42L;
    private static final long OTHER_WORKSPACE_ID = 99L;

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
     * Every mutation must still permit a tenant admin ({@code ROLE_ADMIN}) and must also accept a workspace admin
     * through the {@code AI_GATEWAY_EDIT} scope -- keyed on the exact same argument the method body reads, per mutation
     * shape ({@code #input.workspaceId} for {@code createAiGuardrailCustomRule}, which takes a record, and
     * {@code #workspaceId} for the other three).
     *
     * <p>
     * The exact-string assertion is the load-bearing one. {@code AI_GATEWAY_VIEW} is a different, pre-existing scope
     * that maps to {@code WorkspaceRole.VIEWER} -- a copy-paste of the read gate's scope name onto a mutation would
     * compile and would look plausible, but would let a mere viewer write. This test is what turns that from a silent
     * privilege check into a red build.
     */
    @Test
    void testEveryMutationKeepsAdminAndNamesTheEditScopeOnItsOwnWorkspaceArgument() {
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

            String workspaceReference =
                mutation.getName()
                    .equals("createAiGuardrailCustomRule") ? "#input.workspaceId" : "#workspaceId";

            assertThat(preAuthorize.value())
                .as("%s must permit ROLE_ADMIN and the workspace-scoped AI_GATEWAY_EDIT permission", mutation.getName())
                .isEqualTo(
                    "hasAuthority('ROLE_ADMIN') or hasPermission(" + workspaceReference
                        + ", 'Workspace', 'AI_GATEWAY_EDIT')");
        }
    }

    @Test
    void testCreateAllowsWorkspaceAdminButRefusesViewerAndOtherWorkspaceAdmin() {
        AiGuardrailCustomRuleInput input = new AiGuardrailCustomRuleInput(
            WORKSPACE_ID, "REGEX", "\\d+", 1, 0.5, null, null, null);

        assertWorkspaceEditScopeGate(
            "createAiGuardrailCustomRule", new Class<?>[] {
                AiGuardrailCustomRuleInput.class
            },
            new Object[] {
                input
            });
    }

    @Test
    void testUpdatePatternAllowsWorkspaceAdminButRefusesViewerAndOtherWorkspaceAdmin() {
        assertWorkspaceEditScopeGate(
            "updateAiGuardrailCustomRulePattern", new Class<?>[] {
                long.class, long.class, String.class
            },
            new Object[] {
                WORKSPACE_ID, 7L, "\\bACME-\\d{4}\\b"
            });
    }

    @Test
    void testSetEnabledAllowsWorkspaceAdminButRefusesViewerAndOtherWorkspaceAdmin() {
        assertWorkspaceEditScopeGate(
            "setAiGuardrailCustomRuleEnabled", new Class<?>[] {
                long.class, long.class, boolean.class
            },
            new Object[] {
                WORKSPACE_ID, 7L, true
            });
    }

    @Test
    void testDeleteAllowsWorkspaceAdminButRefusesViewerAndOtherWorkspaceAdmin() {
        assertWorkspaceEditScopeGate(
            "deleteAiGuardrailCustomRule", new Class<?>[] {
                long.class, long.class
            }, new Object[] {
                WORKSPACE_ID, 7L
            });
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

    /**
     * Evaluates the method's actual {@code @PreAuthorize} SpEL expression -- not just its string -- against four
     * principals, distinguished only by their authority and by what a stub {@link PermissionEvaluator} grants them:
     *
     * <ul>
     * <li>a tenant admin ({@code ROLE_ADMIN}) with no workspace permission granted at all must be allowed -- the
     * branch's central safety claim is that widening access to workspace admins never narrows what a tenant admin could
     * already do, and a string assertion on the gate's text cannot catch a disjunct that is present but unreachable at
     * evaluation time;
     * <li>a workspace admin of the target workspace ({@link #WORKSPACE_ID}) must be allowed;
     * <li>a workspace member (viewer) of the target workspace -- nothing granted -- must be refused;
     * <li>a workspace admin of a different workspace ({@link #OTHER_WORKSPACE_ID}) must be refused.
     * </ul>
     *
     * <p>
     * The last case is the one that actually proves the gate is per-workspace: a {@code hasPermission} call keyed on a
     * constant, or on the wrong argument, would still evaluate true here even though the caller was never granted
     * anything on {@link #WORKSPACE_ID}.
     */
    private void assertWorkspaceEditScopeGate(String methodName, Class<?>[] parameterTypes, Object[] arguments) {
        Authentication tenantAdmin = new UsernamePasswordAuthenticationToken(
            "tenant-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        PermissionEvaluator grantsNothingAtAll = mock(PermissionEvaluator.class);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailCustomRuleGraphQlController.class, controller, methodName, parameterTypes, arguments,
                tenantAdmin, grantsNothingAtAll))
                    .as("a tenant admin must be allowed even with no workspace permission granted")
                    .isTrue();

        Authentication nonTenantAdmin = new UsernamePasswordAuthenticationToken(
            "workspace-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        PermissionEvaluator grantsTargetWorkspace = mock(PermissionEvaluator.class);

        when(grantsTargetWorkspace.hasPermission(nonTenantAdmin, WORKSPACE_ID, "Workspace", "AI_GATEWAY_EDIT"))
            .thenReturn(true);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailCustomRuleGraphQlController.class, controller, methodName, parameterTypes, arguments,
                nonTenantAdmin, grantsTargetWorkspace))
                    .as("a workspace admin of the target workspace must be allowed")
                    .isTrue();

        PermissionEvaluator grantsNoWorkspace = mock(PermissionEvaluator.class);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailCustomRuleGraphQlController.class, controller, methodName, parameterTypes, arguments,
                nonTenantAdmin, grantsNoWorkspace))
                    .as("a workspace member (viewer) of the target workspace must be refused")
                    .isFalse();

        PermissionEvaluator grantsOnlyAnotherWorkspace = mock(PermissionEvaluator.class);

        when(
            grantsOnlyAnotherWorkspace.hasPermission(
                nonTenantAdmin, OTHER_WORKSPACE_ID, "Workspace", "AI_GATEWAY_EDIT"))
                    .thenReturn(true);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailCustomRuleGraphQlController.class, controller, methodName, parameterTypes, arguments,
                nonTenantAdmin, grantsOnlyAnotherWorkspace))
                    .as("a workspace admin of a different workspace must be refused")
                    .isFalse();
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
