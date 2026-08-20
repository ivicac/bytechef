/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.automation.configuration.facade.WorkspaceUserFacade;
import com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole;
import com.bytechef.platform.configuration.domain.Environment;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class PreAuthorizeAnnotationTest {

    @Test
    void testServiceImplementationsCarryNoAuthorizationChecks() throws Exception {
        for (String className : new String[] {
            "com.bytechef.automation.configuration.service.ProjectDeploymentServiceImpl",
            "com.bytechef.automation.configuration.service.ProjectServiceImpl",
            "com.bytechef.automation.configuration.service.ProjectWorkflowServiceImpl",
            "com.bytechef.ee.automation.configuration.service.WorkspaceUserServiceImpl"
        }) {
            Class<?> clazz = Class.forName(className);

            for (Method method : clazz.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(PreAuthorize.class))
                    .as(clazz.getSimpleName() + "." + method.getName() + " must not carry @PreAuthorize")
                    .isFalse();
            }
        }
    }

    // NOTE: controller-level delegation is documented in each GraphQL controller's class-level Javadoc. The
    // controllers live in a sibling module (automation-configuration-graphql) and are not on this test's classpath,
    // so their existence is enforced at build time by module wiring rather than at runtime reflection. The
    // delegation contract is exercised end-to-end by PreAuthorizeProxyEnforcementIntTest in this module.

    /**
     * The scope catalogue is static metadata about what the server was built with — identical for every tenant, and
     * nobody's data. Gating it harder than authentication would stop a role editor listing what it may compose from.
     */
    @Test
    void testPermissionScopesRequiresOnlyAuthentication() throws NoSuchMethodException {
        assertPreAuthorize(
            CustomRoleService.class.getMethod("getPermissionScopeNames"),
            "isAuthenticated()");
    }

    /**
     * Mutations are tenant-admin-only: a custom role is tenant-global and assignable everywhere, so defining one is a
     * tenant-wide act. Pinned in full so a workspace-tier branch cannot quietly reappear.
     */
    @Test
    void testCustomRoleMutationsRequireTenantAdmin() throws NoSuchMethodException {
        assertPreAuthorize(
            CustomRoleService.class.getMethod(
                "createCustomRole", String.class, String.class, java.util.Set.class),
            "isTenantAdmin()");

        assertPreAuthorize(
            CustomRoleService.class.getMethod(
                "updateCustomRole", long.class, String.class, String.class, java.util.Set.class),
            "isTenantAdmin()");

        assertPreAuthorize(
            CustomRoleService.class.getMethod("deleteCustomRole", long.class),
            "isTenantAdmin()");
    }

    /**
     * The read is the deliberate remnant of the old two-tier model: it has two audiences — tenant admins managing
     * roles, and workspace member managers populating the assignment picker. The workspaceId argument is authorization
     * context only; the body returns every role either way.
     */
    @Test
    void testGetCustomRolesIsTieredForAssignmentReads() throws NoSuchMethodException {
        assertPreAuthorize(
            CustomRoleService.class.getMethod("getCustomRoles", Long.class),
            "(#workspaceId == null and isTenantAdmin()) or " +
                "(#workspaceId != null and hasPermission(#workspaceId, 'Workspace', 'WORKSPACE_MEMBER_MANAGE'))");
    }

    @Test
    void testWorkspaceUserFacadeMutationsAreProtected() throws NoSuchMethodException {
        assertPreAuthorize(
            WorkspaceUserFacade.class.getMethod(
                "addWorkspaceUser", long.class, long.class, WorkspaceRole.class, Long.class),
            "hasWorkspaceScopeInEveryEnvironment(#workspaceId, 'WORKSPACE_MEMBER_MANAGE')");

        assertPreAuthorize(
            WorkspaceUserFacade.class.getMethod("assignCustomRole", long.class, long.class, long.class),
            "hasWorkspaceScopeInEveryEnvironment(#workspaceId, 'WORKSPACE_MEMBER_MANAGE')");

        assertPreAuthorize(
            WorkspaceUserFacade.class.getMethod(
                "inviteWorkspaceUser", long.class, String.class, WorkspaceRole.class, Long.class),
            "hasWorkspaceScopeInEveryEnvironment(#workspaceId, 'WORKSPACE_MEMBER_MANAGE')");

        assertPreAuthorize(
            WorkspaceUserFacade.class.getMethod("removeWorkspaceUser", long.class, long.class),
            "hasWorkspaceScopeInEveryEnvironment(#workspaceId, 'WORKSPACE_MEMBER_MANAGE')");

        assertPreAuthorize(
            WorkspaceUserFacade.class.getMethod(
                "updateWorkspaceUserRole", long.class, long.class, WorkspaceRole.class),
            "hasWorkspaceScopeInEveryEnvironment(#workspaceId, 'WORKSPACE_MEMBER_MANAGE')");

        assertPreAuthorize(
            WorkspaceUserFacade.class.getMethod(
                "setEnvironmentRole", long.class, long.class, Environment.class, WorkspaceRole.class, Long.class),
            "hasWorkspaceScopeInEnvironment(#workspaceId, 'WORKSPACE_MEMBER_MANAGE', #environment)");

        assertPreAuthorize(
            WorkspaceUserFacade.class.getMethod("removeEnvironmentRole", long.class, long.class, Environment.class),
            "hasWorkspaceScopeInEnvironment(#workspaceId, 'WORKSPACE_MEMBER_MANAGE', #environment)");

        assertPreAuthorize(
            WorkspaceUserFacade.class.getMethod("getWorkspaceWorkspaceUsers", long.class),
            "hasPermission(#workspaceId, 'Workspace', 'WORKSPACE_VIEW')");
    }

    private void assertPreAuthorize(Method interfaceMethod, String expectedExpression) {
        Method implMethod = findImplMethod(interfaceMethod);

        PreAuthorize preAuthorize = implMethod.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as(
                "Method " + implMethod.getDeclaringClass()
                    .getSimpleName() + "." + implMethod.getName() + " must be @PreAuthorize-protected")
            .isNotNull();

        assertThat(preAuthorize.value())
            .as(
                "Method " + implMethod.getDeclaringClass()
                    .getSimpleName() + "." + implMethod.getName() + " must use the documented SpEL expression")
            .isEqualTo(expectedExpression);
    }

    private Method findImplMethod(Method interfaceMethod) {
        String implName = interfaceMethod.getDeclaringClass()
            .getName() + "Impl";

        try {
            Class<?> implClass = Class.forName(implName);

            return implClass.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            throw new AssertionError(
                "Could not resolve implementation method for " + interfaceMethod, exception);
        }
    }
}
