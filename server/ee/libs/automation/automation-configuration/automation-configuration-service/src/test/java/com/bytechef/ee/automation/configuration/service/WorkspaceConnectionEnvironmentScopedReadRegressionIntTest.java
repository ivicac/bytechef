/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.WorkspaceConnection;
import com.bytechef.automation.configuration.facade.WorkspaceConnectionFacade;
import com.bytechef.automation.configuration.facade.WorkspaceConnectionFacadeImpl;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.security.AutomationAuthorizationContext;
import com.bytechef.automation.configuration.security.AutomationMethodSecurityConfiguration;
import com.bytechef.automation.configuration.security.EnvironmentScopeFilter;
import com.bytechef.automation.configuration.security.WorkspaceOwnershipResolver;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver.VisibilityRecord;
import com.bytechef.automation.configuration.service.WorkspaceConnectionService;
import com.bytechef.ee.automation.configuration.domain.WorkspaceUser;
import com.bytechef.ee.automation.configuration.repository.WorkspaceUserRepository;
import com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.security.domain.ResourceVisibility;
import com.bytechef.platform.tag.service.TagService;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.platform.workflow.execution.facade.ConnectionLifecycleFacade;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The pair {@code docs/superpowers/specs/2026-09-03-environment-scoped-authorization-design.md} requires for
 * {@code WorkspaceConnectionFacadeImpl#getConnections}, now gated by
 * {@code hasWorkspaceScopeInEnvironmentId(#workspaceId, 'CONNECTION_VIEW', #environmentId)} instead of the
 * environment-unaware {@code hasPermission(#workspaceId, 'Workspace', 'CONNECTION_VIEW')}: a member holding
 * {@code CONNECTION_VIEW} in DEVELOPMENT only, calling with a {@code null} environment, must still be let through (R1:
 * a null keeps today's behaviour) -- and the same member naming PRODUCTION explicitly must be denied (the forgery this
 * gate exists to close). Neither assertion alone is evidence: the first also passes if the gate were removed outright,
 * and the second also passes if a null environment denied by accident.
 *
 * <p>
 * Wires the REAL {@code PermissionServiceImpl} and the REAL {@code WorkspaceScopeCacheService} behind the
 * {@code PermissionService} bean, with only {@link WorkspaceUserRepository} stubbed -- the same shape
 * {@link ProjectDeploymentCrossEnvironmentReadReproductionIntTest} uses. {@code @EnableMethodSecurity} is genuinely
 * active, so the {@code @PreAuthorize} annotation on {@code WorkspaceConnectionFacadeImpl.getConnections} is evaluated
 * by the real Spring Security proxy, not simulated.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@SpringBootTest(
    classes = WorkspaceConnectionEnvironmentScopedReadRegressionIntTest.Config.class,
    properties = "bytechef.edition=ee")
class WorkspaceConnectionEnvironmentScopedReadRegressionIntTest {

    private static final String LOGIN = "alice";
    private static final long USER_ID = 42L;
    private static final long WORKSPACE_ID = 7L;
    private static final long DEVELOPMENT_CONNECTION_ID = 100L;
    private static final long PRODUCTION_CONNECTION_ID = 200L;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private ConnectionFacade connectionFacade;

    @Autowired
    private WorkspaceConnectionFacade workspaceConnectionFacade;

    @Autowired
    private WorkspaceConnectionService workspaceConnectionService;

    @BeforeEach
    void authenticateAsDevelopmentOnlyViewer() {
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    LOGIN, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        when(workspaceConnectionService.getWorkspaceConnections(WORKSPACE_ID)).thenReturn(List.of());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testFixtureIsNotTenantAdminAndNotSkippingChecks() {
        assertThat(permissionService.isTenantAdmin()).isFalse();
        assertThat(AutomationAuthorizationContext.isSkipChecks()).isFalse();
    }

    @Test
    void testDevelopmentOnlyViewerIsAllowedWhenNoEnvironmentIsNamed() {
        List<ConnectionDTO> result = workspaceConnectionFacade.getConnections(
            WORKSPACE_ID, null, null, null, null);

        assertThat(result).isEmpty();
    }

    /**
     * D4 of {@code docs/superpowers/specs/2026-09-06-environment-scoped-authorization-remaining-families-design.md}:
     * being let through with no environment named is not the same as being shown every environment's rows. The two
     * tests above pin the gate; this one pins the body. Without the {@code EnvironmentScopeFilter} call in
     * {@code getConnections} the Production row comes back to a member who holds nothing in Production -- the gate
     * cannot catch it, because there was no environment for the gate to check.
     *
     * <p>
     * Asserting on the Development row as well as against the Production one matters: "returns only Development" and
     * "returns nothing" both satisfy an assertion that merely denies Production, and the second would be a filter that
     * empties the page for everyone.
     */
    @Test
    void testDevelopmentOnlyViewerSeesOnlyDevelopmentRowsWhenNoEnvironmentIsNamed() {
        when(workspaceConnectionService.getWorkspaceConnections(WORKSPACE_ID))
            .thenReturn(
                List.of(
                    new WorkspaceConnection(DEVELOPMENT_CONNECTION_ID, WORKSPACE_ID),
                    new WorkspaceConnection(PRODUCTION_CONNECTION_ID, WORKSPACE_ID)));

        when(
            connectionFacade.getConnections(
                null, null, List.of(DEVELOPMENT_CONNECTION_ID, PRODUCTION_CONNECTION_ID), null, null,
                PlatformType.AUTOMATION))
                    .thenReturn(
                        List.of(
                            connectionDTO(DEVELOPMENT_CONNECTION_ID, Environment.DEVELOPMENT),
                            connectionDTO(PRODUCTION_CONNECTION_ID, Environment.PRODUCTION)));

        List<ConnectionDTO> result = workspaceConnectionFacade.getConnections(WORKSPACE_ID, null, null, null, null);

        assertThat(result)
            .extracting(ConnectionDTO::id)
            .containsExactly(DEVELOPMENT_CONNECTION_ID);
    }

    private static ConnectionDTO connectionDTO(long id, Environment environment) {
        return ConnectionDTO.builder()
            .id(id)
            .componentName("dummy")
            .name("connection-" + id)
            .environmentId(environment.ordinal())
            .visibility(ResourceVisibility.WORKSPACE)
            .build();
    }

    @Test
    void testDevelopmentOnlyViewerIsDeniedWhenNamingProduction() {
        assertThatThrownBy(
            () -> workspaceConnectionFacade.getConnections(
                WORKSPACE_ID, null, null, (long) Environment.PRODUCTION.ordinal(), null))
                    .isInstanceOf(AccessDeniedException.class);
    }

    // @SpringBootConfiguration (not @TestConfiguration) because @SpringBootTest(classes = Config.class) requires a
    // primary Spring Boot configuration class -- see the identical reasoning documented on
    // ProjectDeploymentCrossEnvironmentReadReproductionIntTest.
    @SpringBootConfiguration
    @EnableMethodSecurity
    @ImportAutoConfiguration(AutomationMethodSecurityConfiguration.class)
    static class Config {

        @Bean
        WorkspaceConnectionFacade workspaceConnectionFacade(
            ApplicationEventPublisher applicationEventPublisher, ConnectionFacade connectionFacade,
            ConnectionLifecycleFacade connectionLifecycleFacade, ConnectionService connectionService,
            EnvironmentScopeFilter environmentScopeFilter, EnvironmentService environmentService,
            ResourceVisibilityResolver resourceVisibilityResolver,
            ProjectDeploymentWorkflowService projectDeploymentWorkflowService, ProjectService projectService,
            TagService tagService, UserService userService,
            WorkflowTestConfigurationService workflowTestConfigurationService,
            WorkspaceConnectionService workspaceConnectionService, WorkspaceFacade workspaceFacade) {

            return new WorkspaceConnectionFacadeImpl(
                applicationEventPublisher, connectionFacade, connectionLifecycleFacade, connectionService,
                environmentScopeFilter, environmentService, resourceVisibilityResolver, mock(ObjectProvider.class),
                projectDeploymentWorkflowService,
                projectService, tagService, userService, workflowTestConfigurationService,
                workspaceConnectionService, workspaceFacade);
        }

        @Bean
        WorkspaceUserRepository workspaceUserRepository() {
            WorkspaceUserRepository workspaceUserRepository = mock(WorkspaceUserRepository.class);

            // The fixture: EXPLICIT mode, one row naming DEVELOPMENT, holding VIEWER (which carries
            // CONNECTION_VIEW) -- and deliberately NO environment-null row, so the member holds nothing in
            // PRODUCTION and nothing implicit to fall back to.
            //
            // Both lookups the member's DEVELOPMENT row can be reached through are stubbed, and both are needed.
            // findAllByUserIdAndWorkspaceId feeds the environment-UNAWARE union
            // (WorkspaceScopeCacheService.getWorkspaceScopes(userId, workspaceId));
            // findByUserIdAndWorkspaceIdAndEnvironment
            // feeds the per-environment lookup. Stubbing only the former, as this fixture originally did, leaves the
            // member holding the scope through the union while holding NOTHING in any single environment -- which
            // still satisfies "allowed with no environment named" and still satisfies "denied when naming
            // PRODUCTION", because a member who holds nothing anywhere is denied everywhere. The PRODUCTION denial
            // then passes without being environment-specific at all. The DEVELOPMENT stub is what makes the denial
            // mean what the test says it means.
            when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironmentIsNull(USER_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());
            when(workspaceUserRepository.findAllByUserIdAndWorkspaceId(USER_ID, WORKSPACE_ID))
                .thenReturn(
                    List.of(
                        WorkspaceUser.forRole(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER, Environment.DEVELOPMENT)));
            when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironment(
                USER_ID, WORKSPACE_ID, Environment.DEVELOPMENT.ordinal()))
                    .thenReturn(
                        Optional.of(
                            WorkspaceUser.forRole(
                                USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER, Environment.DEVELOPMENT)));
            when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironment(
                USER_ID, WORKSPACE_ID, Environment.PRODUCTION.ordinal()))
                    .thenReturn(Optional.empty());

            return workspaceUserRepository;
        }

        @Bean
        PermissionScopeRegistry permissionScopeRegistry() {
            PermissionScopeRegistry permissionScopeRegistry = mock(PermissionScopeRegistry.class);

            when(permissionScopeRegistry.getScopeNames(WorkspaceRole.VIEWER)).thenReturn(Set.of("CONNECTION_VIEW"));

            return permissionScopeRegistry;
        }

        @Bean
        UserService userService() {
            UserService userService = mock(UserService.class);

            User user = new User();

            user.setId(USER_ID);
            user.setLogin(LOGIN);

            when(userService.getUser(LOGIN)).thenReturn(user);

            return userService;
        }

        @Bean
        CurrentUserResolver currentUserResolver(UserService userService) {
            return new CurrentUserResolver(userService);
        }

        @Bean
        WorkspaceScopeCacheService workspaceScopeCacheService(
            PermissionScopeRegistry permissionScopeRegistry, WorkspaceUserRepository workspaceUserRepository) {

            return new WorkspaceScopeCacheService(
                mock(CacheManager.class), null, permissionScopeRegistry, workspaceUserRepository);
        }

        @Bean
        ProjectRepository projectRepository() {
            return mock(ProjectRepository.class);
        }

        // The bean is the REAL PermissionServiceImpl -- not a mock -- so the environment-scoped check this test
        // pins runs for real. The return type is the PermissionService interface, matching every other
        // permissionService bean declaration in this package's IntTests.
        @Bean("permissionService")
        PermissionService permissionService(
            CurrentUserResolver currentUserResolver, PermissionScopeRegistry permissionScopeRegistry,
            ProjectRepository projectRepository, WorkspaceScopeCacheService workspaceScopeCacheService,
            WorkspaceUserRepository workspaceUserRepository) {

            return new PermissionServiceImpl(
                currentUserResolver, permissionScopeRegistry, projectRepository, workspaceScopeCacheService,
                workspaceUserRepository, List.of(new WorkspaceOwnershipResolver()), List.of(), permissiveResolver(),
                List.of(), mock(ObjectProvider.class));
        }

        @Bean
        ApplicationEventPublisher applicationEventPublisher() {
            return mock(ApplicationEventPublisher.class);
        }

        @Bean
        ConnectionFacade connectionFacade() {
            return mock(ConnectionFacade.class);
        }

        @Bean
        ConnectionLifecycleFacade connectionLifecycleFacade() {
            return mock(ConnectionLifecycleFacade.class);
        }

        @Bean
        ConnectionService connectionService() {
            return mock(ConnectionService.class);
        }

        // Permissive rather than a bare mock: filterVisible runs BEFORE the environment filter in getConnections,
        // and a mock returns no visible ids, so every row would be dropped for a reason this test is not about --
        // and the environment assertion would pass without the filter under test ever running.
        @Bean
        ResourceVisibilityResolver resourceVisibilityResolver() {
            return permissiveResolver();
        }

        @Bean
        ProjectDeploymentWorkflowService projectDeploymentWorkflowService() {
            return mock(ProjectDeploymentWorkflowService.class);
        }

        @Bean
        ProjectService projectService() {
            return mock(ProjectService.class);
        }

        @Bean
        TagService tagService() {
            return mock(TagService.class);
        }

        @Bean
        WorkflowTestConfigurationService workflowTestConfigurationService() {
            return mock(WorkflowTestConfigurationService.class);
        }

        @Bean
        WorkspaceConnectionService workspaceConnectionService() {
            return mock(WorkspaceConnectionService.class);
        }

        @Bean
        WorkspaceFacade workspaceFacade() {
            return mock(WorkspaceFacade.class);
        }

        private static ResourceVisibilityResolver permissiveResolver() {
            return (resourceType, workspaceId, candidates) -> candidates.stream()
                .map(VisibilityRecord::id)
                .collect(Collectors.toSet());
        }

        /**
         * The REAL {@link EnvironmentScopeFilter} over the REAL {@code PermissionService} bean above, so the
         * per-environment narrowing this test pins runs for real rather than being simulated.
         */
        @Bean
        EnvironmentScopeFilter environmentScopeFilter(ObjectProvider<PermissionService> permissionServiceProvider) {
            return new EnvironmentScopeFilter(permissionServiceProvider);
        }

        @Bean
        EnvironmentService environmentService() {
            return () -> List.of(Environment.values());
        }

    }
}
