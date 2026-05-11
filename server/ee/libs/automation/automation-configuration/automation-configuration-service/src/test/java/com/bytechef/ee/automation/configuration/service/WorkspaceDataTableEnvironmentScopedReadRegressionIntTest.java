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

import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.security.AutomationAuthorizationContext;
import com.bytechef.automation.configuration.security.AutomationMethodSecurityConfiguration;
import com.bytechef.automation.configuration.security.ResourceOwnershipResolver;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver;
import com.bytechef.automation.data.table.configuration.facade.WorkspaceDataTableFacade;
import com.bytechef.automation.data.table.configuration.facade.WorkspaceDataTableFacadeImpl;
import com.bytechef.automation.data.table.configuration.service.WorkspaceDataTableService;
import com.bytechef.ee.automation.configuration.domain.WorkspaceUser;
import com.bytechef.ee.automation.configuration.repository.WorkspaceUserRepository;
import com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.configuration.service.DataTableTagService;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService.Webhook;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.platform.data.table.execution.service.DataTableStorageService;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The discriminating pair Task 2 requires for {@code WorkspaceDataTableFacadeImpl#listWebhooks}, now gated by
 * {@code hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW', #environmentId)} instead of the
 * environment-unaware {@code hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW')}: unlike {@code 'Workspace'}
 * or {@code 'Connection'}, {@code 'DataTable'} carries a real environment (each table's rows live in one) but has no
 * registered {@code ResourceEnvironmentResolver} for it to be read off the id, so the environment can only come from
 * the caller-supplied {@code environmentId} argument. A member holding {@code DATA_TABLE_VIEW} in DEVELOPMENT only,
 * naming DEVELOPMENT explicitly, must be let through -- and the SAME member naming PRODUCTION explicitly must be
 * denied. Neither assertion alone is evidence: the first also passes if the gate were removed outright, and the second
 * also passes if every environment were denied by accident. Both cases go through the three-argument
 * {@code WorkspaceScopeCacheService.getWorkspaceScopes(long, long, Environment)} overload, never the
 * environment-unaware union, because {@code hasResourceScopeInEnvironmentId} is given a non-null ordinal in both.
 *
 * <p>
 * Wires the REAL {@code PermissionServiceImpl} and the REAL {@code WorkspaceScopeCacheService} behind the
 * {@code PermissionService} bean, with only {@link WorkspaceUserRepository} stubbed -- the same shape
 * {@code WorkspaceConnectionEnvironmentScopedReadRegressionIntTest} uses. {@code @EnableMethodSecurity} is genuinely
 * active, so the {@code @PreAuthorize} annotation on {@code WorkspaceDataTableFacadeImpl.listWebhooks} is evaluated by
 * the real Spring Security proxy, not simulated. The {@code ResourceOwnershipResolver} for {@code 'DataTable'} is a
 * minimal stand-in mapping {@link #DATA_TABLE_ID} to {@link #WORKSPACE_ID} -- the real
 * {@code DataTableOwnershipResolver} lives in {@code automation-data-table-graphql}, a module this test has no reason
 * to depend on, since ownership resolution itself is not what this test is proving.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@SpringBootTest(
    classes = WorkspaceDataTableEnvironmentScopedReadRegressionIntTest.Config.class,
    properties = "bytechef.edition=ee")
class WorkspaceDataTableEnvironmentScopedReadRegressionIntTest {

    private static final String LOGIN = "alice";
    private static final long USER_ID = 42L;
    private static final long WORKSPACE_ID = 7L;
    private static final long DATA_TABLE_ID = 21L;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WorkspaceDataTableFacade workspaceDataTableFacade;

    @Autowired
    private DataTableWebhookService dataTableWebhookService;

    @BeforeEach
    void authenticateAsDevelopmentOnlyViewer() {
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    LOGIN, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        when(dataTableWebhookService.listWebhooks(DATA_TABLE_ID, Environment.DEVELOPMENT.ordinal()))
            .thenReturn(List.of());
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
    void testDevelopmentOnlyViewerIsAllowedWhenNamingDevelopment() {
        // Empty proves the call was not denied, not a data shape: the webhook service is stubbed and returns nothing.
        // Reaching the return at all is the assertion -- a denial would have thrown before this line.
        List<Webhook> result = workspaceDataTableFacade.listWebhooks(
            DATA_TABLE_ID, Environment.DEVELOPMENT.ordinal());

        assertThat(result).isEmpty();
    }

    @Test
    void testDevelopmentOnlyViewerIsDeniedWhenNamingProduction() {
        assertThatThrownBy(
            () -> workspaceDataTableFacade.listWebhooks(DATA_TABLE_ID, Environment.PRODUCTION.ordinal()))
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
        WorkspaceDataTableFacade workspaceDataTableFacade(
            DataTableRowService dataTableRowService, DataTableService dataTableService,
            DataTableStorageService dataTableStorageService, DataTableTagService dataTableTagService,
            DataTableWebhookService dataTableWebhookService, WorkspaceDataTableService workspaceDataTableService) {

            return new WorkspaceDataTableFacadeImpl(
                dataTableRowService, dataTableService, dataTableStorageService, dataTableTagService,
                dataTableWebhookService, workspaceDataTableService);
        }

        @Bean
        WorkspaceUserRepository workspaceUserRepository() {
            WorkspaceUserRepository workspaceUserRepository = mock(WorkspaceUserRepository.class);

            // The fixture: EXPLICIT mode, one row naming DEVELOPMENT, holding VIEWER (which carries
            // DATA_TABLE_VIEW) -- and deliberately NO environment-null row, so the member holds nothing in
            // PRODUCTION and nothing implicit to fall back to.
            when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironmentIsNull(USER_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());
            when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironment(
                USER_ID, WORKSPACE_ID, Environment.DEVELOPMENT.ordinal()))
                    .thenReturn(
                        Optional.of(
                            WorkspaceUser.forRole(
                                USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER, Environment.DEVELOPMENT)));
            when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironment(
                USER_ID, WORKSPACE_ID, Environment.PRODUCTION.ordinal()))
                    .thenReturn(Optional.empty());
            // Stubbed for the same reason the connection regression test stubs it: this is the row the
            // environment-UNAWARE union (WorkspaceScopeCacheService#getWorkspaceScopes(long, long)) would read if
            // the gate under test ever regressed back to hasPermission(#dataTableId, 'DataTable', ...) -- with this
            // row present, that union grants DATA_TABLE_VIEW regardless of the environment named, which is
            // precisely the bug the fix closes. It is not exercised by the fixed gate, which only ever reaches the
            // three-argument, environment-specific overload above.
            when(workspaceUserRepository.findAllByUserIdAndWorkspaceId(USER_ID, WORKSPACE_ID))
                .thenReturn(
                    List.of(
                        WorkspaceUser.forRole(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER, Environment.DEVELOPMENT)));

            return workspaceUserRepository;
        }

        @Bean
        PermissionScopeRegistry permissionScopeRegistry() {
            PermissionScopeRegistry permissionScopeRegistry = mock(PermissionScopeRegistry.class);

            when(permissionScopeRegistry.getScopeNames(WorkspaceRole.VIEWER)).thenReturn(Set.of("DATA_TABLE_VIEW"));

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
                workspaceUserRepository, List.of(dataTableOwnershipResolver()), List.of(),
                mock(ResourceVisibilityResolver.class), List.of(), mock(ObjectProvider.class));
        }

        @Bean
        DataTableRowService dataTableRowService() {
            return mock(DataTableRowService.class);
        }

        @Bean
        DataTableService dataTableService() {
            return mock(DataTableService.class);
        }

        @Bean
        DataTableStorageService dataTableStorageService() {
            return mock(DataTableStorageService.class);
        }

        @Bean
        DataTableTagService dataTableTagService() {
            return mock(DataTableTagService.class);
        }

        @Bean
        DataTableWebhookService dataTableWebhookService() {
            return mock(DataTableWebhookService.class);
        }

        @Bean
        WorkspaceDataTableService workspaceDataTableService() {
            return mock(WorkspaceDataTableService.class);
        }

        // No ResourceVisibilityProvider is registered for 'DataTable' (the empty List.of() above), so
        // isResourceVisible short-circuits to true without ever consulting the ResourceVisibilityResolver bean --
        // it is only a required constructor argument, never exercised by this test.
        private static ResourceOwnershipResolver dataTableOwnershipResolver() {
            return new ResourceOwnershipResolver() {

                @Override
                public String resourceType() {
                    return "DataTable";
                }

                @Override
                public ResourceOwner resolveOwner(long id) {
                    return id == DATA_TABLE_ID ? ResourceOwner.ofWorkspace(WORKSPACE_ID) : ResourceOwner.unknown();
                }
            };
        }
    }
}
