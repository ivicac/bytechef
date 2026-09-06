/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.automation.configuration.service;

import com.bytechef.platform.configuration.domain.Environment;
import java.io.Serializable;
import java.util.Collection;
import java.util.Set;

/**
 * Central RBAC service backing the {@code @PreAuthorize} SpEL checks used across the automation tier (both the custom
 * root built-ins {@code isCurrentUser}/{@code isTenantAdmin}/{@code isResourceOwner} and the {@code hasPermission(...)}
 * scope/role tokens). The EE implementation enforces real workspace-role, scope and ownership checks; the CE
 * implementation is a permissive pass-through (except {@link #isTenantAdmin()}). EE checks short-circuit to
 * {@code true} when the user is a tenant admin, or when {@code AutomationAuthorizationContext} skip mode is active,
 * which bypasses every check for the duration of a delegation. An embedded connected user reaches neither
 * short-circuit: every resource-scoped check for such a principal is answered by {@code ResourceMembershipDecider}
 * ahead of both. See {@code AutomationAuthorizationContext}'s Javadoc.
 *
 * @author Ivica Cardic
 */
public interface PermissionService {

    /**
     * Evicts the cached scope grants for a single user/workspace pair, forcing the next scope check to recompute.
     *
     * @param userId      the user whose cached scopes are invalidated
     * @param workspaceId the workspace whose cached scopes are invalidated
     */
    void evictWorkspaceScopeCache(long userId, long workspaceId);

    /**
     * Evicts the cached scope grants for several user/workspace pairs.
     *
     * @param userWorkspacePairs the user/workspace pairs whose cached scopes are invalidated
     */
    default void evictWorkspaceScopeCaches(Collection<UserWorkspacePair> userWorkspacePairs) {
        for (UserWorkspacePair pair : userWorkspacePairs) {
            evictWorkspaceScopeCache(pair.userId(), pair.workspaceId());
        }
    }

    /**
     * Evicts the entire workspace scope cache for all users and workspaces.
     */
    void evictAllWorkspaceScopeCache();

    /**
     * Returns whether the current user holds at least {@code minimumRole} (by rank) in the workspace.
     *
     * @param workspaceId the workspace to check membership in
     * @param minimumRole the minimum {@link WorkspaceRoleType} name required
     * @return {@code true} if the current user's workspace role is at least {@code minimumRole}
     */
    boolean hasWorkspaceRole(long workspaceId, String minimumRole);

    /**
     * Returns whether the current user has been granted {@code scope} in the workspace.
     *
     * @param workspaceId the workspace whose scope grants are inspected
     * @param scope       the scope name the user must hold
     * @return {@code true} if the current user holds {@code scope} in the workspace
     */
    boolean hasWorkspaceScope(long workspaceId, String scope);

    /**
     * Returns whether the current user has been granted {@code scope} in the workspace, <em>in the environment being
     * acted on</em>.
     *
     * <p>
     * The environment is always passed explicitly and is never read from {@code EnvironmentContext}. That thread-local
     * holds the <em>source</em> environment during a promotion, and is already known to be lost on worker threads and
     * in agent tool calls, so an implicit read would fail open in precisely the case this overload exists for.
     *
     * @param workspaceId the workspace whose scope grants are inspected
     * @param scope       the scope name the user must hold
     * @param environment the environment the caller intends to act on
     * @return {@code true} if the current user holds {@code scope} in the workspace for that environment
     */
    boolean hasWorkspaceScope(long workspaceId, String scope, Environment environment);

    /**
     * Returns whether the current user has been granted {@code scope} in <em>every</em> environment of the workspace.
     *
     * <p>
     * This is the check for an operation whose effect is not confined to one environment — granting a workspace-wide
     * role, for instance, takes effect everywhere at once. The environment-unaware
     * {@link #hasWorkspaceScope(long, String)} is a union across the environments a member can reach, so using it here
     * would let a member who administers only Development grant themselves Production.
     *
     * @param workspaceId the workspace whose scope grants are inspected
     * @param scope       the scope name the user must hold in every environment
     * @return {@code true} if the current user holds {@code scope} in every environment
     */
    boolean hasWorkspaceScopeInEveryEnvironment(long workspaceId, String scope);

    /**
     * Returns whether the current user has {@code scope} in the workspace that owns the project.
     *
     * <p>
     * Holding the scope is necessary but not sufficient: the project must also be visible to the caller and resolve to
     * an owning workspace, so this denies where a bare workspace-scope lookup would have allowed — a project the caller
     * cannot see, and one that cannot be resolved at all.
     *
     * @param projectId the project whose owning workspace is checked
     * @param scope     the scope name the user must hold in that workspace
     * @return {@code true} if the current user holds {@code scope} in the project's workspace
     */
    boolean hasWorkspaceScopeForProject(long projectId, String scope);

    /**
     * Returns whether the current user has {@code scope} in the workspace that owns the project, in the environment
     * being acted on. See {@link #hasWorkspaceScope(long, String, Environment)} for why the environment is a parameter.
     *
     * <p>
     * Carries the same visibility and resolvability preconditions as
     * {@link #hasWorkspaceScopeForProject(long, String)}.
     *
     * @param projectId   the project whose owning workspace is checked
     * @param scope       the scope name the user must hold in that workspace
     * @param environment the environment the caller intends to act on
     * @return {@code true} if the current user holds {@code scope} in the project's workspace for that environment
     */
    boolean hasWorkspaceScopeForProject(long projectId, String scope, Environment environment);

    /**
     * Returns whether the current user has {@code scope} for the resource, resolved to its owning workspace via the
     * {@code ResourceOwnershipResolver} registered for {@code resourceType}.
     *
     * @param id           the resource identifier
     * @param resourceType the resource type key used to select the ownership resolver
     * @param scope        the scope name the user must hold
     * @return {@code true} if the current user holds {@code scope} for the resource
     */
    boolean hasResourceScope(Serializable id, String resourceType, String scope);

    /**
     * Returns whether the current user has {@code scope} for the resource, resolved to its owning workspace via the
     * {@code ResourceOwnershipResolver} registered for {@code resourceType}, checked against the caller's role in
     * {@code environment} rather than unioned across every environment they can reach.
     *
     * <p>
     * {@link #hasResourceScope(Serializable, String, String)}'s {@code ResourceEnvironmentResolver} step already
     * answers this for the four resource types that register one ({@code Connection}, {@code ProjectDeployment},
     * {@code McpServer}) by reading the environment off the resource itself. Every other type — {@code DataTable},
     * {@code Project} and {@code Workflow} among them — has no environment of its own: the environment is an argument
     * of the operation, not a property of the row, so no resolver could ever supply it, and
     * {@link #hasResourceScope(Serializable, String, String)} necessarily unions the environments the caller can reach.
     * This overload is that argument-supplied case, one level down from
     * {@link #hasWorkspaceScope(long, String, Environment)} and {@link #hasWorkflowScope(String, String, Environment)}:
     * one general expression for every by-id check that takes an environment argument, rather than a bespoke overload
     * per resource family — see
     * {@code docs/superpowers/specs/2026-09-06-environment-scoped-authorization-remaining-families-design.md} §3.
     *
     * @param id           the resource identifier
     * @param resourceType the resource type key used to select the ownership resolver
     * @param scope        the scope name the user must hold
     * @param environment  the environment the caller intends to act on
     * @return {@code true} if the current user holds {@code scope} for the resource in {@code environment}
     */
    boolean hasResourceScopeInEnvironment(Serializable id, String resourceType, String scope, Environment environment);

    /**
     * Returns whether the current user holds at least {@code minimumRole} in the workspace that owns the resource.
     *
     * <p>
     * Deliberately carries NO visibility precondition, unlike every other by-id check here: this is the owner-or-admin
     * sharing-management posture, so an admin can repair the sharing of a resource they cannot themselves see. See
     * {@code docs/superpowers/specs/2026-08-17-project-visibility-design.md} §17 for the full entry-point audit —
     * extend that table when adding a method to this interface.
     *
     * @param id           the resource identifier
     * @param resourceType the resource type key used to select the ownership resolver
     * @param minimumRole  the minimum {@link WorkspaceRoleType} name required
     * @return {@code true} if the current user's role in the resource's workspace is at least {@code minimumRole}
     */
    boolean hasResourceRole(long id, String resourceType, String minimumRole);

    /**
     * Returns whether the current user has {@code scope} in the workspace that owns the workflow, and may see the
     * project the workflow belongs to. Both editions route this through
     * {@link #hasResourceScope(Serializable, String, String)} so a workflow-keyed check carries the same visibility
     * precondition as every other by-id check; a workflow inside a project the caller cannot see is denied even when
     * the caller holds the scope in that workspace.
     *
     * @param workflowId the workflow whose owning workspace is checked
     * @param scope      the scope name the user must hold in that workspace
     * @return {@code true} if the current user holds {@code scope} in the workflow's workspace
     */
    boolean hasWorkflowScope(String workflowId, String scope);

    /**
     * Returns whether the current user has {@code scope} in the workflow's owning workspace <em>for the environment the
     * operation acts on</em>, and may see the project the workflow belongs to.
     * <p>
     * A workflow does not live in an environment — the environment is a parameter of the operation, not a property of
     * the resource — so no {@code ResourceEnvironmentResolver} can supply it and the environment-unaware overload
     * necessarily unions the environments the caller can reach. Use this overload wherever the caller supplies the
     * environment to run in, so that a member who is editor in one environment cannot act in another.
     * <p>
     * The environment must come from the guarded method's own arguments, never from {@code EnvironmentContext}, which
     * holds the source environment during a promotion and is lost on worker threads.
     *
     * @param workflowId  the workflow whose owning workspace is checked
     * @param scope       the scope name the user must hold in that workspace
     * @param environment the environment the operation acts on
     * @return {@code true} if the current user holds {@code scope} in the workflow's workspace for {@code environment}
     */
    boolean hasWorkflowScope(String workflowId, String scope, Environment environment);

    /**
     * Returns the scope names the current user holds in the workspace (all registered scopes for a tenant admin).
     *
     * @param workspaceId the workspace whose scope grants are returned
     * @return the current user's scopes, or an empty set if none / no current user
     */
    Set<String> getMyWorkspaceScopes(long workspaceId);

    /**
     * Returns the environments in which the current user holds {@code scope} in the workspace (every environment for a
     * tenant admin, or while skip mode is active).
     *
     * <p>
     * This is the listing counterpart of {@link #hasWorkspaceScope(long, String, Environment)}: where that answers "may
     * the caller act in this environment", this answers "which environments may the caller see rows from". It exists
     * for the listings whose {@code environmentId} argument is nullable — with no environment named, the gate correctly
     * permits the call, and it is the body that must narrow the result to the environments the caller can reach rather
     * than returning the union across all of them.
     *
     * <p>
     * Call it <em>once per listing</em> and intersect the result with the rows, never once per row: {@link Environment}
     * has three values and the underlying scope lookup is cached per user/workspace/environment, so this costs at most
     * three cache reads — but only as long as it stays outside the loop.
     *
     * <p>
     * Implementations must answer through {@link #hasWorkspaceScope(long, String, Environment)} rather than by reading
     * the caller's membership rows directly. That check resolves an environment row if there is one and otherwise falls
     * back to the member's implicit (environment-less) row, so a member in implicit mode &mdash; the default &mdash;
     * holds their scopes in every environment and is correctly not narrowed at all. Reading the rows directly would
     * return only the explicit ones and narrow such a member to nothing.
     *
     * @param workspaceId the workspace whose scope grants are inspected
     * @param scope       the scope name whose environments are returned
     * @return the environments in which the current user holds {@code scope}, empty if none / no current user
     */
    Set<Environment> getMyWorkspaceScopeEnvironments(long workspaceId, String scope);

    /**
     * Returns the current user's {@link WorkspaceRoleType} name in the workspace, or {@code null} if not a member.
     *
     * @param workspaceId the workspace to look up membership in
     * @return the {@link WorkspaceRoleType} name, or {@code null} when there is no membership
     */
    String getMyWorkspaceRole(long workspaceId);

    /**
     * Returns whether {@code userId} identifies the currently authenticated user.
     *
     * @param userId the user id to compare against the current user
     * @return {@code true} if {@code userId} is the current user
     */
    boolean isCurrentUser(long userId);

    /**
     * Returns whether the current user is the owner (creator) of the resource of the given {@code resourceType}.
     *
     * @param resourceType the resource type key used to select the ownership resolver
     * @param id           the resource identifier
     * @return {@code true} if the current user owns the resource
     */
    boolean isResourceOwner(String resourceType, long id);

    /**
     * Returns whether the current user holds the tenant-admin authority. Enforced in both editions.
     *
     * @return {@code true} if the current user is a tenant admin
     */
    boolean isTenantAdmin();

    /**
     * A user/workspace pair identifying a single entry in the workspace scope cache.
     */
    record UserWorkspacePair(long userId, long workspaceId) {
    }
}
