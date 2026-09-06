/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.security.AutomationAuthorizationContext;
import com.bytechef.automation.configuration.security.WorkspaceOwnershipResolver;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver.VisibilityRecord;
import com.bytechef.ee.automation.configuration.domain.WorkspaceUser;
import com.bytechef.ee.automation.configuration.repository.WorkspaceUserRepository;
import com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;

/**
 * Characterization test for the two {@link PermissionServiceImpl} overloads
 * {@code docs/superpowers/specs/2026-09-03-environment-scoped-authorization-design.md} identified as the union gap:
 * {@code hasWorkspaceScope(workspaceId, scope)} (environment-unaware, unions every environment the caller can reach)
 * versus {@code hasWorkspaceScope(workspaceId, scope, environment)} (checks the named environment alone). Wires the
 * REAL {@link PermissionServiceImpl} to the REAL {@link WorkspaceScopeCacheService} (only
 * {@link WorkspaceUserRepository} is a fixture), so the union behaviour documented on
 * {@code WorkspaceScopeCacheService#getWorkspaceScopes(long, long)} runs for real rather than being stubbed away — the
 * failure mode {@link PermissionServiceEnvironmentTest} cannot exercise, since it mocks
 * {@code WorkspaceScopeCacheService} itself.
 *
 * <p>
 * The fixture is a member in EXPLICIT mode: one {@code WorkspaceUser} row naming DEVELOPMENT, holding VIEWER
 * (DEPLOYMENT_VIEW), and deliberately no environment-null row. Both {@link PermissionServiceImpl#isTenantAdmin()} and
 * {@code AutomationAuthorizationContext.isSkipChecks()} are confirmed false for the duration of this test — the
 * SecurityContext authenticates a plain, non-admin login, and skip mode is never entered.
 *
 * <p>
 * This is a CHARACTERIZATION test, not a regression pin, and it stays valid after the design's implementation: it
 * asserts each overload's own semantics directly, not which {@code @PreAuthorize} expression routes to it. The union
 * overload it exercises here is exactly what {@code hasWorkspaceScopeInEnvironmentId}'s {@code null}-environment branch
 * deliberately keeps calling (R1 — a caller-supplied {@code null} preserves today's unfiltered-listing behaviour rather
 * than becoming a denial); the per-environment overload is what that same built-in calls instead once an environment is
 * named. The site this gap was first traced through,
 * {@code ProjectDeploymentFacadeImpl.getWorkspaceProjectDeployments(long, Long, Long, Long, boolean)}, no longer
 * carries the vulnerable {@code hasPermission(#id, 'Workspace', 'DEPLOYMENT_VIEW')} expression this class's history
 * once quoted — see {@link ProjectDeploymentCrossEnvironmentReadReproductionIntTest} for the facade-level regression
 * pin against the closed gate.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class PermissionServiceCrossEnvironmentUnionGapTest {

    private static final String LOGIN = "alice";
    private static final long USER_ID = 42L;
    private static final long WORKSPACE_ID = 7L;

    private PermissionServiceImpl permissionService;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        UserService userService = mock(UserService.class);
        WorkspaceUserRepository workspaceUserRepository = mock(WorkspaceUserRepository.class);
        PermissionScopeRegistry permissionScopeRegistry = mock(PermissionScopeRegistry.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);

        // The fixture the trap in the task description warns about: EXPLICIT mode (no environment-null row) with
        // exactly one per-environment row, in DEVELOPMENT only.
        when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironmentIsNull(USER_ID, WORKSPACE_ID))
            .thenReturn(Optional.empty());
        when(workspaceUserRepository.findAllByUserIdAndWorkspaceId(USER_ID, WORKSPACE_ID))
            .thenReturn(
                List.of(WorkspaceUser.forRole(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER, Environment.DEVELOPMENT)));
        when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironment(
            USER_ID, WORKSPACE_ID, Environment.PRODUCTION.ordinal()))
                .thenReturn(Optional.empty());

        when(permissionScopeRegistry.getScopeNames(WorkspaceRole.VIEWER)).thenReturn(Set.of("DEPLOYMENT_VIEW"));

        WorkspaceScopeCacheService workspaceScopeCacheService = new WorkspaceScopeCacheService(
            mock(CacheManager.class), null, permissionScopeRegistry, workspaceUserRepository);

        permissionService = new PermissionServiceImpl(
            new CurrentUserResolver(userService), permissionScopeRegistry, projectRepository,
            workspaceScopeCacheService, workspaceUserRepository, List.of(new WorkspaceOwnershipResolver()),
            List.of(), permissiveResolver(), List.of(), mock(ObjectProvider.class));

        securityUtilsMock = mockStatic(SecurityUtils.class);

        // A plain, non-admin login. isTenantAdmin() reads this authority list directly, so this is how the fixture
        // proves the admin short-circuit is not what is granting the check below.
        securityUtilsMock.when(() -> SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN))
            .thenReturn(false);
        securityUtilsMock.when(SecurityUtils::fetchCurrentUserLogin)
            .thenReturn(Optional.of(LOGIN));

        User user = new User();

        user.setId(USER_ID);
        user.setLogin(LOGIN);

        when(userService.getUser(LOGIN)).thenReturn(user);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    void testFixtureIsNotTenantAdminAndNotSkippingChecks() {
        // Confirms the trap named in the task is not silently satisfied: neither short circuit that would make the
        // assertions below pass for the wrong reason is active.
        assertThat(permissionService.isTenantAdmin()).isFalse();
        assertThat(AutomationAuthorizationContext.isSkipChecks()).isFalse();
    }

    @Test
    void testEnvironmentUnawareCheckGrantsFromAForeignEnvironmentRole() {
        // The environment-unaware overload -- what hasResourceScope(id, "Workspace", scope) falls back to for a
        // caller-supplied environment, since no ResourceEnvironmentResolver is registered for "Workspace" -- unions
        // every environment the member can reach. A DEVELOPMENT-only VIEWER therefore satisfies it.
        assertThat(permissionService.hasWorkspaceScope(WORKSPACE_ID, "DEPLOYMENT_VIEW")).isTrue();
    }

    @Test
    void testEnvironmentAwareCheckDeniesTheSameMemberInProduction() {
        // The same member, asked about the SAME scope in the environment they actually asked to read (PRODUCTION),
        // is denied -- they hold no row there and there is no implicit row to fall back to.
        assertThat(permissionService.hasWorkspaceScope(WORKSPACE_ID, "DEPLOYMENT_VIEW", Environment.PRODUCTION))
            .isFalse();
    }

    @Test
    void testOneMemberIsAnsweredBothWaysByTheTwoOverloadsAtOnce() {
        // Both halves in one assertion: this is the authorization-divergence pair the spec's "Not yet demonstrated"
        // section asks be settled with a test. A gate that consults the two-argument overload for a caller-supplied
        // PRODUCTION argument is granting exactly what the three-argument overload, asked about the same argument,
        // refuses.
        boolean environmentUnawareGrant = permissionService.hasWorkspaceScope(WORKSPACE_ID, "DEPLOYMENT_VIEW");
        boolean environmentAwareGrantForProduction =
            permissionService.hasWorkspaceScope(WORKSPACE_ID, "DEPLOYMENT_VIEW", Environment.PRODUCTION);

        assertThat(environmentUnawareGrant).isTrue();
        assertThat(environmentAwareGrantForProduction).isFalse();
    }

    private static ResourceVisibilityResolver permissiveResolver() {
        return (resourceType, workspaceId, candidates) -> candidates.stream()
            .map(VisibilityRecord::id)
            .collect(Collectors.toSet());
    }
}
