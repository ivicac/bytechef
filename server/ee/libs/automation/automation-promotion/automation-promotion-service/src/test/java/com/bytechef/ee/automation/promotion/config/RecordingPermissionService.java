/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.promotion.config;

import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.platform.configuration.domain.Environment;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The {@link PermissionService} every {@code hasPermission(...)} guard in the promotion integration-test context
 * resolves to. It records each {@link #hasResourceScope} call so a test can assert on the id the
 * {@code @promotionAuthorizer} bean reference produced, and answers every check with a single switchable verdict.
 *
 * <p>
 * <b>A hand-written {@code @Component} rather than a {@code @Bean}-supplied Mockito mock, deliberately.</b>
 * {@code AutomationMethodSecurityConfiguration} — the auto-configuration that contributes
 * {@code AutomationPermissionEvaluator} and the expression handler — is {@code @ConditionalOnBean(PermissionService
 * .class)}, and Spring Boot evaluates that condition while SELECTING auto-configurations, long before any {@code @Bean}
 * method of the test configuration has been registered. A component-scanned definition exists by then; a {@code @Bean}
 * one does not. With the auto-configuration filtered out, method security silently falls back to Spring's deny-all
 * permission evaluator and every guarded call in the context refuses.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component("permissionService")
public class RecordingPermissionService implements PermissionService {

    /**
     * One {@code hasPermission(id, resourceType, scope)} evaluation, as it reached the permission evaluator.
     */
    public record ResourceScopeCheck(Serializable id, String resourceType, String scope) {
    }

    private final List<ResourceScopeCheck> resourceScopeChecks = new ArrayList<>();

    private boolean granted = true;

    public List<ResourceScopeCheck> getResourceScopeChecks() {
        return List.copyOf(resourceScopeChecks);
    }

    public void reset() {
        resourceScopeChecks.clear();

        granted = true;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
    }

    @Override
    public void evictWorkspaceScopeCache(long userId, long workspaceId) {
        // Deliberately a no-op. Clearing the recording here would let an eviction fired from somewhere inside a
        // promotion path wipe the evidence the authorization test asserts on, failing it for a reason that has
        // nothing to do with what it checks. Only reset() clears the recording.
    }

    @Override
    public void evictAllWorkspaceScopeCache() {
        // Deliberately a no-op, for the reason given on evictWorkspaceScopeCache.
    }

    @Override
    public Set<String> getMyWorkspaceScopes(long workspaceId) {
        return Set.of();
    }

    /**
     * Follows {@code granted} like the boolean checks do, in its set-valued form: every environment when granted, none
     * when not. Returning an empty set while granted would make a filtered listing come back empty in a test that had
     * asked for permission to be given.
     */
    @Override
    public Set<Environment> getMyWorkspaceScopeEnvironments(long workspaceId, String scope) {
        return granted ? EnumSet.allOf(Environment.class) : Set.of();
    }

    @Override
    public @Nullable String getMyWorkspaceRole(long workspaceId) {
        return null;
    }

    @Override
    public boolean hasResourceRole(long id, String resourceType, String minimumRole) {
        return granted;
    }

    @Override
    public boolean hasResourceScope(Serializable id, String resourceType, String scope) {
        resourceScopeChecks.add(new ResourceScopeCheck(id, resourceType, scope));

        return granted;
    }

    /**
     * Recorded into the same {@link #resourceScopeChecks} list as {@link #hasResourceScope}, dropping
     * {@code environment} -- {@link ResourceScopeCheck} carries no environment field, and
     * {@code testPromotionAuthorizerBeanReferenceResolvesAndGates} (the only production caller of this overload today,
     * via the two {@code 'Project'} promotion handlers' {@code hasResourceScopeInEnvironment(...)} expression) asserts
     * on the {@code (id, resourceType, scope)} tuple the {@code @promotionAuthorizer} bean reference produced, not on
     * which overload carried it.
     */
    @Override
    public boolean hasResourceScopeInEnvironment(
        Serializable id, String resourceType, String scope,
        Environment environment) {

        resourceScopeChecks.add(new ResourceScopeCheck(id, resourceType, scope));

        return granted;
    }

    @Override
    public boolean hasWorkflowScope(String workflowId, String scope) {
        return granted;
    }

    @Override
    public boolean hasWorkflowScope(String workflowId, String scope, Environment environment) {
        return granted;
    }

    @Override
    public boolean hasWorkspaceRole(long workspaceId, String minimumRole) {
        return granted;
    }

    @Override
    public boolean hasWorkspaceScope(long workspaceId, String scope) {
        return granted;
    }

    @Override
    public boolean hasWorkspaceScope(long workspaceId, String scope, Environment environment) {
        return granted;
    }

    @Override
    public boolean hasWorkspaceScopeInEveryEnvironment(long workspaceId, String scope) {
        return granted;
    }

    @Override
    public boolean hasWorkspaceScopeForProject(long projectId, String scope) {
        return granted;
    }

    @Override
    public boolean hasWorkspaceScopeForProject(long projectId, String scope, Environment environment) {
        return granted;
    }

    @Override
    public boolean isCurrentUser(long userId) {
        return granted;
    }

    @Override
    public boolean isResourceOwner(String resourceType, long id) {
        return granted;
    }

    @Override
    public boolean isTenantAdmin() {
        return granted;
    }
}
