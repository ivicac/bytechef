/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.facade;

import static org.mockito.Mockito.mock;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacadeImpl;
import com.bytechef.automation.configuration.repository.ProjectDeploymentRepository;
import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.security.AutomationMethodSecurityConfiguration;
import com.bytechef.automation.configuration.security.EnvironmentScopeFilter;
import com.bytechef.automation.configuration.security.ProjectDeploymentEnvironmentResolver;
import com.bytechef.automation.configuration.security.ProjectDeploymentOwnershipResolver;
import com.bytechef.automation.configuration.security.ProjectVisibilityFilter;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.ee.automation.apiplatform.configuration.audit.ApiCollectionAuditPublisher;
import com.bytechef.ee.automation.apiplatform.configuration.permission.ApiCollectionEndpointEnvironmentResolver;
import com.bytechef.ee.automation.apiplatform.configuration.permission.ApiCollectionEndpointOwnershipResolver;
import com.bytechef.ee.automation.apiplatform.configuration.permission.ApiCollectionEnvironmentResolver;
import com.bytechef.ee.automation.apiplatform.configuration.permission.ApiCollectionOwnershipResolver;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionEndpointService;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionService;
import com.bytechef.ee.automation.configuration.repository.WorkspaceUserRepository;
import com.bytechef.ee.automation.configuration.security.scope.ApiPlatformPermissionScopeProvider;
import com.bytechef.ee.automation.configuration.security.scope.DeploymentPermissionScopeProvider;
import com.bytechef.ee.automation.configuration.service.CurrentUserResolver;
import com.bytechef.ee.automation.configuration.service.CustomRoleScopeResolver;
import com.bytechef.ee.automation.configuration.service.PermissionScopeRegistry;
import com.bytechef.ee.automation.configuration.service.PermissionServiceImpl;
import com.bytechef.ee.automation.configuration.service.ResourceVisibilityResolverImpl;
import com.bytechef.ee.automation.configuration.service.WorkspaceScopeCacheService;
import com.bytechef.ee.platform.resource.grant.service.ResourceGrantService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.security.domain.ResourceVisibilityPolicy;
import com.bytechef.platform.security.domain.ResourceVisibilityPolicyRegistry;
import com.bytechef.platform.tag.service.TagService;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import com.bytechef.platform.workflow.execution.facade.TriggerLifecycleFacade;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import com.bytechef.platform.workflow.execution.service.TriggerExecutionService;
import com.bytechef.platform.workflow.execution.service.TriggerStateService;
import java.util.List;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Assembles {@link ApiCollectionFacadeImpl} behind the REAL method-security chain: {@link EnableMethodSecurity} with
 * the production {@link AutomationMethodSecurityConfiguration}, the EE {@link PermissionServiceImpl} with its scope
 * registry fed by the production {@link ApiPlatformPermissionScopeProvider} and
 * {@link DeploymentPermissionScopeProvider}, and the production API collection, API collection endpoint and project
 * deployment ownership and environment resolvers. The {@link ProjectDeploymentFacade} it delegates to is the REAL
 * {@link ProjectDeploymentFacadeImpl}, proxied by the same chain, so its own gates run too. Only the data underneath is
 * stubbed.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@EnableMethodSecurity
@Import({
    ApiCollectionEndpointEnvironmentResolver.class, ApiCollectionEndpointOwnershipResolver.class,
    ApiCollectionEnvironmentResolver.class, ApiCollectionFacadeImpl.class, ApiCollectionOwnershipResolver.class,
    ApiPlatformPermissionScopeProvider.class, CurrentUserResolver.class, DeploymentPermissionScopeProvider.class,
    PermissionScopeRegistry.class, PermissionServiceImpl.class, ProjectDeploymentEnvironmentResolver.class,
    ProjectDeploymentOwnershipResolver.class, ResourceVisibilityResolverImpl.class, WorkspaceScopeCacheService.class
})
@ImportAutoConfiguration(AutomationMethodSecurityConfiguration.class)
public class ApiCollectionFacadeIntTestConfiguration {

    @Bean
    ApiCollectionAuditPublisher apiCollectionAuditPublisher() {
        return resettingMock(ApiCollectionAuditPublisher.class);
    }

    @Bean
    ApiCollectionEndpointService apiCollectionEndpointService() {
        return resettingMock(ApiCollectionEndpointService.class);
    }

    @Bean
    ApiCollectionService apiCollectionService() {
        return resettingMock(ApiCollectionService.class);
    }

    @Bean
    CacheManager cacheManager() {
        return new ConcurrentMapCacheManager();
    }

    @Bean
    ComponentConnectionFacade componentConnectionFacade() {
        return resettingMock(ComponentConnectionFacade.class);
    }

    @Bean
    CustomRoleScopeResolver customRoleScopeResolver() {
        return resettingMock(CustomRoleScopeResolver.class);
    }

    @Bean
    EnvironmentService environmentService() {
        return resettingMock(EnvironmentService.class);
    }

    @Bean
    ConnectionService connectionService() {
        return resettingMock(ConnectionService.class);
    }

    @Bean
    ProjectDeploymentFacade projectDeploymentFacade(
        ComponentConnectionFacade componentConnectionFacade, ConnectionService connectionService,
        EnvironmentService environmentService, ProjectDeploymentService projectDeploymentService,
        ProjectDeploymentWorkflowService projectDeploymentWorkflowService, ProjectService projectService,
        ProjectWorkflowService projectWorkflowService, TagService tagService, WorkflowService workflowService) {

        return new ProjectDeploymentFacadeImpl(
            mock(ApplicationEventPublisher.class), connectionService, mock(Evaluator.class),
            mock(EnvironmentScopeFilter.class), environmentService, mock(PrincipalJobFacade.class),
            mock(PrincipalJobService.class), mock(JobFacade.class), mock(JobService.class), List.of(),
            projectDeploymentService, projectDeploymentWorkflowService, projectService,
            mock(ProjectVisibilityFilter.class), projectWorkflowService, tagService,
            mock(TriggerDefinitionService.class),
            mock(TriggerExecutionService.class), mock(TriggerLifecycleFacade.class), new ApplicationProperties(),
            componentConnectionFacade, workflowService, null, null, null, mock(PlatformTransactionManager.class),
            mock(TriggerStateService.class));
    }

    @Bean
    ProjectDeploymentRepository projectDeploymentRepository() {
        return resettingMock(ProjectDeploymentRepository.class);
    }

    @Bean
    ProjectDeploymentService projectDeploymentService() {
        return resettingMock(ProjectDeploymentService.class);
    }

    @Bean
    ProjectDeploymentWorkflowService projectDeploymentWorkflowService() {
        return resettingMock(ProjectDeploymentWorkflowService.class);
    }

    @Bean
    ProjectRepository projectRepository() {
        return resettingMock(ProjectRepository.class);
    }

    @Bean
    ProjectService projectService() {
        return resettingMock(ProjectService.class);
    }

    @Bean
    ProjectWorkflowService projectWorkflowService() {
        return resettingMock(ProjectWorkflowService.class);
    }

    @Bean
    ResourceGrantService resourceGrantService() {
        return resettingMock(ResourceGrantService.class);
    }

    @Bean
    ResourceVisibilityPolicyRegistry resourceVisibilityPolicyRegistry(
        List<ResourceVisibilityPolicy> resourceVisibilityPolicies) {

        return new ResourceVisibilityPolicyRegistry(resourceVisibilityPolicies);
    }

    @Bean
    TagService tagService() {
        return resettingMock(TagService.class);
    }

    @Bean
    UserService userService() {
        return resettingMock(UserService.class);
    }

    @Bean
    WorkflowService workflowService() {
        return resettingMock(WorkflowService.class);
    }

    @Bean
    WorkspaceUserRepository workspaceUserRepository() {
        return resettingMock(WorkspaceUserRepository.class);
    }

    private static <T> T resettingMock(Class<T> type) {
        return mock(type, MockReset.withSettings(MockReset.AFTER));
    }
}
