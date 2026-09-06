/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.dto.ProjectDeploymentDTO;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacadeImpl;
import com.bytechef.automation.configuration.listener.ProjectDeploymentDeleteEventListener;
import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.security.AutomationAuthorizationContext;
import com.bytechef.automation.configuration.security.AutomationMethodSecurityConfiguration;
import com.bytechef.automation.configuration.security.EnvironmentScopeFilter;
import com.bytechef.automation.configuration.security.ProjectVisibilityFilter;
import com.bytechef.automation.configuration.security.WorkspaceOwnershipResolver;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver.VisibilityRecord;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.ee.automation.configuration.domain.WorkspaceUser;
import com.bytechef.ee.automation.configuration.repository.WorkspaceUserRepository;
import com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.tag.service.TagService;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import com.bytechef.platform.workflow.execution.facade.TriggerLifecycleFacade;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import com.bytechef.platform.workflow.execution.service.TriggerExecutionService;
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
 * The regression pin for the closed vulnerability {@code docs/superpowers/specs/2026-09-03-environment-scoped-
 * authorization-design.md} identified: {@code ProjectDeploymentFacadeImpl.getWorkspaceProjectDeployments(long, Long,
 * Long, Long, boolean)} used to be gated by {@code hasPermission(#id, 'Workspace', 'DEPLOYMENT_VIEW')}, which finds no
 * {@code ResourceEnvironmentResolver} registered for {@code "Workspace"} and falls back to the environment-unaware
 * {@code hasWorkspaceScope(workspaceId, scope)} — unioning every environment the caller can reach. A member holding
 * {@code DEPLOYMENT_VIEW} in DEVELOPMENT alone therefore passed the gate, and the method body applied the
 * caller-supplied PRODUCTION filter with no further check. The gate is now
 * {@code hasWorkspaceScopeInEnvironmentId(#id, 'DEPLOYMENT_VIEW', #environmentId)}, which checks the named environment
 * alone rather than the union. This test pins the pair that proves the fix: the same DEVELOPMENT-only member is still
 * let through when no environment is named (R1 — a {@code null} keeps today's behaviour) and is denied outright when
 * PRODUCTION is named explicitly (the forgery the new gate closes).
 *
 * <p>
 * Unlike {@link PreAuthorizeProxyEnforcementIntTest} and {@link RealImplProxyEnforcementIntTest}, which mock
 * {@link PermissionService} entirely to pin which method an expression routes to, this test wires the REAL
 * {@link PermissionServiceImpl} and the REAL {@link WorkspaceScopeCacheService} behind the {@code PermissionService}
 * bean — only {@link WorkspaceUserRepository} is a test fixture. {@code @EnableMethodSecurity} is genuinely active, so
 * the {@code @PreAuthorize} annotation on {@code ProjectDeploymentFacadeImpl.getWorkspaceProjectDeployments(long, Long,
 * Long, Long, boolean)} is evaluated by the real Spring Security proxy, not simulated.
 *
 * <p>
 * The caller authenticates as a plain, non-admin user; {@code isTenantAdmin()} and
 * {@code AutomationAuthorizationContext.isSkipChecks()} are both confirmed false so neither short circuit is what
 * grants or denies the call.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@SpringBootTest(
    classes = ProjectDeploymentCrossEnvironmentReadReproductionIntTest.Config.class,
    properties = "bytechef.edition=ee")
class ProjectDeploymentCrossEnvironmentReadReproductionIntTest {

    private static final String LOGIN = "alice";
    private static final long DEVELOPMENT_DEPLOYMENT_ID = 800L;
    private static final long PRODUCTION_DEPLOYMENT_ID = 900L;
    private static final long PROJECT_ID = 55L;
    private static final long USER_ID = 42L;
    private static final long WORKSPACE_ID = 7L;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private ProjectDeploymentFacade projectDeploymentFacade;

    @Autowired
    private ProjectDeploymentService projectDeploymentService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectVisibilityFilter projectVisibilityFilter;

    @Autowired
    private EnvironmentService environmentService;

    @BeforeEach
    void authenticateAsDevelopmentOnlyViewer() {
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    LOGIN, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        when(environmentService.getEnvironment(anyLong())).thenReturn(Environment.PRODUCTION);

        Project project = new Project();

        project.setId(PROJECT_ID);
        project.setName("Customer Sync");
        project.setWorkspaceId(WORKSPACE_ID);

        ProjectDeployment productionDeployment = new ProjectDeployment();

        productionDeployment.setId(PRODUCTION_DEPLOYMENT_ID);
        productionDeployment.setName("Customer Sync (Production)");
        productionDeployment.setProjectId(PROJECT_ID);
        productionDeployment.setEnvironment(Environment.PRODUCTION);

        when(
            projectDeploymentService.getProjectDeployments(
                eq(false), eq(Environment.PRODUCTION), isNull(), isNull(), eq(WORKSPACE_ID)))
                    .thenReturn(List.of(productionDeployment));
        when(projectService.getProjects(List.of(PROJECT_ID))).thenReturn(List.of(project));
        when(projectVisibilityFilter.visibleProjectIds(List.of(project))).thenReturn(Set.of(PROJECT_ID));
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

    /**
     * The forgery this gate now closes: naming an environment the caller holds no role in used to return that
     * environment's rows anyway (see the class javadoc). With {@code hasWorkspaceScopeInEnvironmentId} in place, the
     * DEVELOPMENT-only member's request for PRODUCTION is answered against PRODUCTION alone, which the member holds
     * nothing in, and the call is denied.
     */
    @Test
    void testDevelopmentOnlyViewerIsDeniedWhenNamingProduction() {
        // The fixture: DEVELOPMENT-only VIEWER (holds DEPLOYMENT_VIEW), no environment-null row -- confirmed in
        // Config below via WorkspaceUserRepository, and confirmed not to route through the tenant-admin or skip-mode
        // short circuits by the test above.
        assertThatThrownBy(
            () -> projectDeploymentFacade.getWorkspaceProjectDeployments(
                WORKSPACE_ID, (long) Environment.PRODUCTION.ordinal(), null, null, false))
                    .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The companion R1 assertion: a {@code null} environment keeps today's behaviour rather than becoming a second,
     * accidental denial. The same DEVELOPMENT-only member, asking for no particular environment, falls back to the
     * environment-unaware {@code hasWorkspaceScope(workspaceId, scope)} check and is let through -- the gate is right
     * to allow the call, because there is no environment in it to check. What the caller then sees is the body's
     * question, pinned by the test below.
     */
    @Test
    void testDevelopmentOnlyViewerIsAllowedWhenNoEnvironmentIsNamed() {
        List<ProjectDeploymentDTO> result = projectDeploymentFacade.getWorkspaceProjectDeployments(
            WORKSPACE_ID, null, null, null, false);

        assertThat(result).isEmpty();
    }

    /**
     * D4 of {@code docs/superpowers/specs/2026-09-06-environment-scoped-authorization-remaining-families-design.md}.
     * The test above proves the call is allowed; this one proves being allowed is not the same as being shown
     * everything. With no environment named the query returns every environment's deployments, so before the
     * {@code EnvironmentScopeFilter} call in {@code getWorkspaceProjectDeployments} the Production row reached a member
     * holding nothing in Production -- and no gate could have stopped it, because the request named no environment for
     * a gate to check.
     *
     * <p>
     * The Development row is asserted present, not merely the Production row absent: "returns only Development" and
     * "returns nothing at all" both satisfy an assertion that only denies Production, and the second would be a filter
     * that empties the page for every member instead of narrowing it.
     */
    @Test
    void testDevelopmentOnlyViewerSeesOnlyDevelopmentRowsWhenNoEnvironmentIsNamed() {
        ProjectDeployment developmentDeployment = new ProjectDeployment();

        developmentDeployment.setId(DEVELOPMENT_DEPLOYMENT_ID);
        developmentDeployment.setName("Customer Sync (Development)");
        developmentDeployment.setProjectId(PROJECT_ID);
        developmentDeployment.setEnvironment(Environment.DEVELOPMENT);

        ProjectDeployment productionDeployment = new ProjectDeployment();

        productionDeployment.setId(PRODUCTION_DEPLOYMENT_ID);
        productionDeployment.setName("Customer Sync (Production)");
        productionDeployment.setProjectId(PROJECT_ID);
        productionDeployment.setEnvironment(Environment.PRODUCTION);

        // Stubbed here rather than in @BeforeEach on purpose: the R1 test above asserts an EMPTY result, and it can
        // only mean "allowed, nothing to show" while the null-environment query returns nothing.
        when(
            projectDeploymentService.getProjectDeployments(
                eq(false), isNull(), isNull(), isNull(), eq(WORKSPACE_ID)))
                    .thenReturn(List.of(developmentDeployment, productionDeployment));

        List<ProjectDeploymentDTO> result = projectDeploymentFacade.getWorkspaceProjectDeployments(
            WORKSPACE_ID, null, null, null, false);

        assertThat(result)
            .extracting(ProjectDeploymentDTO::id)
            .containsExactly(DEVELOPMENT_DEPLOYMENT_ID);
    }

    // @SpringBootConfiguration (not @TestConfiguration) because @SpringBootTest(classes = Config.class) requires a
    // primary Spring Boot configuration class -- see the identical reasoning documented on
    // PreAuthorizeProxyEnforcementIntTest and RealImplProxyEnforcementIntTest.
    @SpringBootConfiguration
    @EnableMethodSecurity
    @ImportAutoConfiguration(AutomationMethodSecurityConfiguration.class)
    static class Config {

        // Constructed explicitly here (rather than via @Import(ProjectDeploymentFacadeImpl.class) + implicit
        // constructor autowiring) so a List<ProjectDeploymentDeleteEventListener> collaborator with no registered
        // beans cannot become an ambiguous autowiring question -- the same reasoning
        // PreAuthorizeProxyEnforcementIntTest documents for its two REAL sharing facades. Method security still
        // proxies a @Bean-factory-constructed instance exactly as it does one registered via @Import; that test's
        // ProjectSharingFacadeImpl/WorkspaceConnectionFacadeImpl assertions are the proof.
        @Bean
        ProjectDeploymentFacade projectDeploymentFacade(
            ApplicationEventPublisher applicationEventPublisher, ConnectionService connectionService,
            Evaluator evaluator, EnvironmentScopeFilter environmentScopeFilter,
            EnvironmentService environmentService, PrincipalJobFacade principalJobFacade,
            PrincipalJobService principalJobService, JobFacade jobFacade, JobService jobService,
            ProjectDeploymentService projectDeploymentService,
            ProjectDeploymentWorkflowService projectDeploymentWorkflowService, ProjectService projectService,
            ProjectVisibilityFilter projectVisibilityFilter, ProjectWorkflowService projectWorkflowService,
            TagService tagService, TriggerDefinitionService triggerDefinitionService,
            TriggerExecutionService triggerExecutionService, TriggerLifecycleFacade triggerLifecycleFacade,
            ApplicationProperties applicationProperties, ComponentConnectionFacade componentConnectionFacade,
            WorkflowService workflowService) {

            return new ProjectDeploymentFacadeImpl(
                applicationEventPublisher, connectionService, evaluator, environmentScopeFilter, environmentService,
                principalJobFacade,
                principalJobService, jobFacade, jobService, List.<ProjectDeploymentDeleteEventListener>of(),
                projectDeploymentService, projectDeploymentWorkflowService, projectService, projectVisibilityFilter,
                projectWorkflowService, tagService, triggerDefinitionService, triggerExecutionService,
                triggerLifecycleFacade, applicationProperties, componentConnectionFacade, workflowService);
        }

        @Bean
        WorkspaceUserRepository workspaceUserRepository() {
            WorkspaceUserRepository workspaceUserRepository = mock(WorkspaceUserRepository.class);

            // The fixture: EXPLICIT mode, one row naming DEVELOPMENT, holding VIEWER (which carries
            // DEPLOYMENT_VIEW) -- and deliberately NO environment-null row, so the member holds nothing in
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

            when(permissionScopeRegistry.getScopeNames(WorkspaceRole.VIEWER)).thenReturn(Set.of("DEPLOYMENT_VIEW"));

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

        // The bean is the REAL PermissionServiceImpl -- not a mock -- so the environment-unaware fallback this test
        // reproduces runs for real. The return type is the PermissionService interface, matching every other
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
        ConnectionService connectionService() {
            return mock(ConnectionService.class);
        }

        @Bean
        Evaluator evaluator() {
            return mock(Evaluator.class);
        }

        @Bean
        EnvironmentService environmentService() {
            return mock(EnvironmentService.class);
        }

        @Bean
        PrincipalJobFacade principalJobFacade() {
            return mock(PrincipalJobFacade.class);
        }

        @Bean
        PrincipalJobService principalJobService() {
            return mock(PrincipalJobService.class);
        }

        @Bean
        JobFacade jobFacade() {
            return mock(JobFacade.class);
        }

        @Bean
        JobService jobService() {
            return mock(JobService.class);
        }

        @Bean
        ProjectDeploymentService projectDeploymentService() {
            return mock(ProjectDeploymentService.class);
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
        ProjectVisibilityFilter projectVisibilityFilter() {
            return mock(ProjectVisibilityFilter.class);
        }

        @Bean
        ProjectWorkflowService projectWorkflowService() {
            return mock(ProjectWorkflowService.class);
        }

        @Bean
        TagService tagService() {
            return mock(TagService.class);
        }

        @Bean
        TriggerDefinitionService triggerDefinitionService() {
            return mock(TriggerDefinitionService.class);
        }

        @Bean
        TriggerExecutionService triggerExecutionService() {
            return mock(TriggerExecutionService.class);
        }

        @Bean
        TriggerLifecycleFacade triggerLifecycleFacade() {
            return mock(TriggerLifecycleFacade.class);
        }

        @Bean
        ApplicationProperties applicationProperties() {
            return new ApplicationProperties();
        }

        @Bean
        ComponentConnectionFacade componentConnectionFacade() {
            return mock(ComponentConnectionFacade.class);
        }

        @Bean
        WorkflowService workflowService() {
            return mock(WorkflowService.class);
        }

        private static ResourceVisibilityResolver permissiveResolver() {
            return (resourceType, workspaceId, candidates) -> candidates.stream()
                .map(VisibilityRecord::id)
                .collect(Collectors.toSet());
        }

        /**
         * The REAL {@link EnvironmentScopeFilter} over the REAL {@code PermissionService} bean, so the per-environment
         * narrowing of the nullable listing runs for real rather than being simulated.
         */
        @Bean
        EnvironmentScopeFilter environmentScopeFilter(ObjectProvider<PermissionService> permissionServiceProvider) {
            return new EnvironmentScopeFilter(permissionServiceProvider);
        }

    }
}
