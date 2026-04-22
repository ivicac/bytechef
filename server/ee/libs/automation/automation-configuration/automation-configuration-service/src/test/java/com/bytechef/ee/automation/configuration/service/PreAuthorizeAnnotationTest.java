/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Reflection test that pins the {@code @PreAuthorize} expressions on every controller-facing service method. This is a
 * lightweight stand-in for proxy-based enforcement tests: it cannot verify Spring Security actually invokes the rule,
 * but it does catch the highest-likelihood regression (someone removing the annotation or changing its expression so
 * the wrong role is required).
 *
 * <p>
 * Pair with {@code PermissionServiceTest} (verifies the SpEL methods do what they promise) and the
 * {@code PreAuthorizeProxyEnforcementIntTest} suite (which exercises the proxy-based enforcement end-to-end against a
 * real Spring context with method security enabled).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class PreAuthorizeAnnotationTest {

    @Test
    void testWorkspaceUserServiceMutationsAreProtected() throws NoSuchMethodException {
        assertPreAuthorize(
            WorkspaceUserService.class.getMethod(
                "addWorkspaceUser", long.class, long.class,
                com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole.class),
            "hasPermission(#workspaceId, 'WorkspaceRole', 'ADMIN')");

        assertPreAuthorize(
            WorkspaceUserService.class.getMethod(
                "updateWorkspaceUserRole", long.class, long.class,
                com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole.class),
            "hasPermission(#workspaceId, 'WorkspaceRole', 'ADMIN')");

        assertPreAuthorize(
            WorkspaceUserService.class.getMethod("removeWorkspaceUser", long.class, long.class),
            "hasPermission(#workspaceId, 'WorkspaceRole', 'ADMIN')");

        assertPreAuthorize(
            WorkspaceUserService.class.getMethod("getWorkspaceWorkspaceUsers", long.class),
            "hasPermission(#workspaceId, 'WorkspaceRole', 'VIEWER')");
    }

    @Test
    void testProjectUserServiceMutationsAreProtected() throws NoSuchMethodException {
        assertPreAuthorize(
            ProjectUserService.class.getMethod("addProjectUser", long.class, long.class, int.class),
            "hasPermission(#projectId, 'ProjectScope', 'PROJECT_MANAGE_USERS')");

        assertPreAuthorize(
            ProjectUserService.class.getMethod("deleteProjectUser", long.class, long.class),
            "hasPermission(#projectId, 'ProjectScope', 'PROJECT_MANAGE_USERS')");

        assertPreAuthorize(
            ProjectUserService.class.getMethod("updateProjectUserRole", long.class, long.class, int.class),
            "hasPermission(#projectId, 'ProjectScope', 'PROJECT_MANAGE_USERS')");

        assertPreAuthorize(
            ProjectUserService.class.getMethod("getProjectUsers", long.class),
            "hasPermission(#projectId, 'ProjectScope', 'PROJECT_VIEW_USERS')");
    }

    @Test
    void testEeWorkspaceServiceMutationsAreProtected() throws Exception {
        // EE WorkspaceServiceImpl gained @PreAuthorize on create/delete/update/getWorkspace in the RBAC PR.
        // Reflect directly because WorkspaceService is a CE interface whose CE impl does not have these annotations.
        Class<?> clazz = Class.forName(
            "com.bytechef.ee.automation.configuration.service.WorkspaceServiceImpl");

        assertMethodPreAuthorizeExists(clazz, "create", "hasPermission('Tenant', 'ADMIN')");
        assertMethodPreAuthorizeExists(clazz, "delete", "hasPermission('Tenant', 'ADMIN')");
        assertMethodPreAuthorizeExists(clazz, "update", "hasPermission(#workspace.id, 'WorkspaceRole', 'ADMIN')");
        assertMethodPreAuthorizeExists(
            clazz, "getWorkspace", "hasPermission(#id, 'WorkspaceRole', 'VIEWER')");
    }

    @Test
    void testCeProjectFacadeCreateMutationsAreProtected() throws Exception {
        Class<?> clazz = Class.forName(
            "com.bytechef.automation.configuration.facade.ProjectFacadeImpl");

        // createProject / importProject / importProjectTemplate all require workspace EDITOR membership. Assert the
        // annotation is present on at least one method per name rather than enumerating overloads — the important
        // property is that SOME overload gates the entry point.
        assertAnyMethodHasPreAuthorize(
            clazz, "createProject", "hasPermission(#projectDTO.workspaceId, 'WorkspaceRole', 'EDITOR')");
        assertAnyMethodHasPreAuthorize(
            clazz, "importProject", "hasPermission(#workspaceId, 'WorkspaceRole', 'EDITOR')");
        assertAnyMethodHasPreAuthorize(
            clazz, "importProjectTemplate", "hasPermission(#workspaceId, 'WorkspaceRole', 'EDITOR')");
    }

    @Test
    void testCeProjectFacadeMutatingMethodsAreProtected() throws Exception {
        // Every mutating facade method must enforce authorization at the facade layer BEFORE any non-transactional
        // side effect runs (file-storage deletes, project-deployment cleanup, zip streaming). Deeper service-layer
        // @PreAuthorize provides defense-in-depth but fires too late to stop external state from being mutated.
        Class<?> clazz = Class.forName(
            "com.bytechef.automation.configuration.facade.ProjectFacadeImpl");

        assertMethodPreAuthorizeExists(
            clazz, "deleteProject", "hasPermission(#id, 'ProjectScope', 'PROJECT_DELETE')");
        assertMethodPreAuthorizeExists(
            clazz, "deleteSharedProject", "hasPermission(#id, 'ProjectScope', 'PROJECT_SETTINGS')");
        assertMethodPreAuthorizeExists(
            clazz, "duplicateProject", "hasPermission(#id, 'ProjectScope', 'WORKFLOW_VIEW')");
        assertMethodPreAuthorizeExists(
            clazz, "exportProject", "hasPermission(#id, 'ProjectScope', 'WORKFLOW_VIEW')");
        assertMethodPreAuthorizeExists(
            clazz, "exportSharedProject", "hasPermission(#id, 'ProjectScope', 'PROJECT_SETTINGS')");
        assertMethodPreAuthorizeExists(
            clazz, "publishProject", "hasPermission(#id, 'ProjectScope', 'WORKFLOW_EDIT')");
        assertMethodPreAuthorizeExists(
            clazz, "updateProject", "hasPermission(#projectDTO.id, 'ProjectScope', 'WORKFLOW_EDIT')");
    }

    @Test
    void testCeProjectFacadeReadMethodsAreProtected() throws Exception {
        Class<?> clazz = Class.forName(
            "com.bytechef.automation.configuration.facade.ProjectFacadeImpl");

        // Read methods on the facade must be gated independently of the underlying service — the facade is a public
        // API surface (REST/GraphQL) and an unauthenticated cross-workspace enumeration is a security hole even if
        // the service eventually filters.
        assertMethodPreAuthorizeExists(
            clazz, "getProject", "hasPermission(#id, 'ProjectScope', 'WORKFLOW_VIEW')");
        assertAnyMethodHasPreAuthorize(
            clazz, "getWorkspaceProjects", "hasPermission(#workspaceId, 'WorkspaceRole', 'VIEWER')");
        assertMethodPreAuthorizeExists(
            clazz, "getWorkspaceProjectWorkflows", "hasPermission(#workspaceId, 'WorkspaceRole', 'VIEWER')");
        // Tenant-wide listing without a workspaceId is admin-only.
        assertAnyMethodHasPreAuthorize(clazz, "getProjects", "hasPermission('Tenant', 'ADMIN')");
    }

    @Test
    void testCeProjectServiceDeleteIsProtectedByProjectDelete() throws Exception {
        // ProjectServiceImpl.delete switched from WORKFLOW_DELETE to PROJECT_DELETE in the PR. Pin the new scope so
        // a future edit reverting the change is caught.
        Class<?> clazz = Class.forName(
            "com.bytechef.automation.configuration.service.ProjectServiceImpl");

        assertMethodPreAuthorizeExists(clazz, "delete", "hasPermission(#id, 'ProjectScope', 'PROJECT_DELETE')");
    }

    @Test
    void testEeWorkspaceFacadeGetUserWorkspacesIsGated() throws Exception {
        // getUserWorkspaces(long id) was previously ungated — any authenticated user could enumerate another user's
        // workspace memberships by passing their id. The annotation pins the self-or-admin check. Pair with
        // PermissionService.isCurrentUser(long) which backs the SpEL expression.
        Class<?> clazz = Class.forName(
            "com.bytechef.ee.automation.configuration.facade.WorkspaceFacadeImpl");

        assertMethodPreAuthorizeExists(
            clazz, "getUserWorkspaces",
            "hasPermission('Tenant', 'ADMIN') or hasPermission(#id, 'User', 'SELF')");
    }

    // NOTE: controller-level delegation is documented in each GraphQL controller's class-level Javadoc. The
    // controllers live in a sibling module (automation-configuration-graphql) and are not on this test's classpath,
    // so their existence is enforced at build time by module wiring rather than at runtime reflection. The
    // delegation contract is exercised end-to-end by PreAuthorizeProxyEnforcementIntTest in this module.

    @Test
    void testCustomRoleServiceIsTenantAdminOnly() throws NoSuchMethodException {
        assertPreAuthorize(
            CustomRoleService.class.getMethod(
                "createCustomRole", String.class, String.class, java.util.Set.class),
            "hasPermission('Tenant', 'ADMIN')");

        assertPreAuthorize(
            CustomRoleService.class.getMethod(
                "updateCustomRole", long.class, String.class, String.class, java.util.Set.class),
            "hasPermission('Tenant', 'ADMIN')");

        assertPreAuthorize(
            CustomRoleService.class.getMethod("deleteCustomRole", long.class),
            "hasPermission('Tenant', 'ADMIN')");

        // Reads are also tenant-admin only — a custom-role row reveals the org's permission strategy.
        assertPreAuthorize(
            CustomRoleService.class.getMethod("getCustomRole", long.class),
            "hasPermission('Tenant', 'ADMIN')");

        assertPreAuthorize(
            CustomRoleService.class.getMethod("getCustomRoles"),
            "hasPermission('Tenant', 'ADMIN')");
    }

    private void assertPreAuthorize(Method interfaceMethod, String expectedExpression) {
        // The annotation lives on the implementation, not the interface, so resolve through the impl class. We
        // accept any class in the same package whose name follows the conventional ServiceImpl pattern.
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

    /**
     * Locates a method by name on the given class (first arity match) and asserts that it has the expected
     * {@code @PreAuthorize} expression. Used for facades/services where the interface hierarchy does not follow the
     * simple {@code *Service → *ServiceImpl} convention.
     */
    private void assertMethodPreAuthorizeExists(Class<?> clazz, String methodName, String expectedExpression) {
        Method match = null;

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName()
                .equals(methodName) && method.isAnnotationPresent(PreAuthorize.class)) {
                match = method;

                break;
            }
        }

        assertThat(match)
            .as("Class " + clazz.getSimpleName() + " must have a @PreAuthorize-annotated method named " + methodName)
            .isNotNull();

        assertThat(match.getAnnotation(PreAuthorize.class)
            .value())
                .as(clazz.getSimpleName() + "." + methodName + " must use the documented SpEL expression")
                .isEqualTo(expectedExpression);
    }

    /**
     * Asserts that at least one overload of {@code methodName} on {@code clazz} has the expected {@code @PreAuthorize}.
     * Used when multiple overloads exist (e.g., import-from-file vs. import-from-url) and we only require that the
     * entry point is gated.
     */
    private void assertAnyMethodHasPreAuthorize(Class<?> clazz, String methodName, String expectedExpression) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName()
                .equals(methodName)) {
                continue;
            }

            PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

            if (preAuthorize != null && preAuthorize.value()
                .equals(expectedExpression)) {
                return;
            }
        }

        throw new AssertionError(
            clazz.getSimpleName() + " has no overload of " + methodName
                + " annotated with @PreAuthorize(" + expectedExpression + ")");
    }

    private Method findImplMethod(Method interfaceMethod) {
        String interfaceName = interfaceMethod.getDeclaringClass()
            .getName();
        // e.g. com.bytechef.ee.automation.configuration.service.WorkspaceUserService
        // -> com.bytechef.ee.automation.configuration.service.WorkspaceUserServiceImpl
        String implName = interfaceName + "Impl";

        try {
            Class<?> implClass = Class.forName(implName);

            return implClass.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            throw new AssertionError(
                "Could not resolve implementation method for " + interfaceMethod, exception);
        }
    }
}
