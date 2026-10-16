/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.facade;

import static org.mockito.Mockito.mock;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.security.AutomationMethodSecurityConfiguration;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.automation.apiplatform.configuration.audit.ApiCollectionAuditPublisher;
import com.bytechef.ee.automation.apiplatform.configuration.permission.ApiCollectionEndpointEnvironmentResolver;
import com.bytechef.ee.automation.apiplatform.configuration.permission.ApiCollectionEndpointOwnershipResolver;
import com.bytechef.ee.automation.apiplatform.configuration.permission.ApiCollectionEnvironmentResolver;
import com.bytechef.ee.automation.apiplatform.configuration.permission.ApiCollectionOwnershipResolver;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionEndpointService;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionService;
import com.bytechef.ee.automation.configuration.repository.WorkspaceUserRepository;
import com.bytechef.ee.automation.configuration.security.scope.ApiPlatformPermissionScopeProvider;
import com.bytechef.ee.automation.configuration.service.CurrentUserResolver;
import com.bytechef.ee.automation.configuration.service.CustomRoleScopeResolver;
import com.bytechef.ee.automation.configuration.service.PermissionScopeRegistry;
import com.bytechef.ee.automation.configuration.service.PermissionServiceImpl;
import com.bytechef.ee.automation.configuration.service.ResourceVisibilityResolverImpl;
import com.bytechef.ee.automation.configuration.service.WorkspaceScopeCacheService;
import com.bytechef.ee.platform.resource.grant.service.ResourceGrantService;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.security.domain.ResourceVisibilityPolicy;
import com.bytechef.platform.security.domain.ResourceVisibilityPolicyRegistry;
import com.bytechef.platform.tag.service.TagService;
import com.bytechef.platform.user.service.UserService;
import java.util.List;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockReset;

/**
 * Assembles {@link ApiCollectionFacadeImpl} behind the REAL method-security chain: {@link EnableMethodSecurity} with
 * the production {@link AutomationMethodSecurityConfiguration}, the EE {@link PermissionServiceImpl} with its scope
 * registry fed by the production {@link ApiPlatformPermissionScopeProvider}, and the production API collection and API
 * collection endpoint ownership and environment resolvers. Only the data underneath is stubbed.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@EnableMethodSecurity
@Import({
    ApiCollectionEndpointEnvironmentResolver.class, ApiCollectionEndpointOwnershipResolver.class,
    ApiCollectionEnvironmentResolver.class, ApiCollectionFacadeImpl.class, ApiCollectionOwnershipResolver.class,
    ApiPlatformPermissionScopeProvider.class, CurrentUserResolver.class, PermissionScopeRegistry.class,
    PermissionServiceImpl.class, ResourceVisibilityResolverImpl.class, WorkspaceScopeCacheService.class
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
    CustomRoleScopeResolver customRoleScopeResolver() {
        return resettingMock(CustomRoleScopeResolver.class);
    }

    @Bean
    EnvironmentService environmentService() {
        return resettingMock(EnvironmentService.class);
    }

    @Bean
    ProjectDeploymentFacade projectDeploymentFacade() {
        return resettingMock(ProjectDeploymentFacade.class);
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
