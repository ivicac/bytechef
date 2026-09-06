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

package com.bytechef.automation.configuration.security;

import com.bytechef.automation.configuration.security.ResourceMembershipDecider.Outcome;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.security.web.authentication.PrincipalEnvironment;
import java.io.Serializable;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;

/**
 * Custom {@code @PreAuthorize} SpEL root that adds two ByteChef-specific built-ins on top of the standard Spring
 * Security expression operations ({@code hasPermission}, {@code hasRole}, {@code isAuthenticated}, …):
 *
 * <ul>
 * <li>{@code isCurrentUser(#id)} — grants when the supplied id is the current authenticated user's id.</li>
 * <li>{@code isTenantAdmin()} — grants when the current user is a global tenant administrator.</li>
 * <li>{@code isResourceOwner(#id, 'Type')} — grants when the current user owns the identified resource.</li>
 * <li>{@code isConnectedUser()} — grants when the caller is an embedded connected user rather than a ByteChef one.</li>
 * </ul>
 *
 * @author Ivica Cardic
 */
public final class AutomationMethodSecurityExpressionRoot
    extends SecurityExpressionRoot<MethodInvocation> implements MethodSecurityExpressionOperations {

    private final PermissionService permissionService;
    private final ObjectProvider<ResourceMembershipResolver> resourceMembershipResolverProvider;
    private final Object target;

    private Object filterObject;
    private Object returnObject;

    AutomationMethodSecurityExpressionRoot(
        Supplier<? extends Authentication> authentication, MethodInvocation methodInvocation,
        PermissionService permissionService,
        ObjectProvider<ResourceMembershipResolver> resourceMembershipResolverProvider) {

        super(authentication, methodInvocation);

        this.permissionService = permissionService;
        this.resourceMembershipResolverProvider = resourceMembershipResolverProvider;
        this.target = methodInvocation.getThis();
    }

    /**
     * Returns {@code true} if {@code userId} matches the current authenticated user. Bypassed (returns {@code true})
     * under skip mode; a connected user never reaches that bypass, since {@code SkipAutomationAuthorizationAspect} arms
     * nothing for a principal {@link ResourceMembershipResolver} governs — and a connected user is not any {@code user}
     * table row, having none.
     */
    public boolean isCurrentUser(long userId) {
        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        return permissionService.isCurrentUser(userId);
    }

    /**
     * Returns {@code true} if the current user is a global tenant administrator. Bypassed (returns {@code true}) under
     * skip mode; a connected user never reaches that bypass — a connected user is not a tenant admin, which is what the
     * principal is rather than a policy choice.
     */
    public boolean isTenantAdmin() {
        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        return permissionService.isTenantAdmin();
    }

    /**
     * Returns {@code true} when the caller is an embedded connected user — an end user of a vendor's product, not a
     * ByteChef user. It says only WHICH KIND of principal is calling and nothing about what that principal may touch,
     * so it is never sufficient on its own: every method that admits it also runs its own ownership check for the
     * connected user (see {@code ConnectedUserIntegrationInstanceFacadeImpl.isOwnedByConnectedUser}). Widening a gate
     * with this and nothing else would let ANY connected user in the tenant through.
     *
     * <p>
     * Deliberately NOT bypassed under skip mode, unlike the built-ins above: skip mode arms nothing for a principal
     * {@link ResourceMembershipResolver} governs, and answering "yes, a connected user" for a caller that is not one
     * would widen every expression this appears in.
     */
    public boolean isConnectedUser() {
        ResourceMembershipResolver resourceMembershipResolver = resourceMembershipResolverProvider.getIfAvailable();

        return resourceMembershipResolver != null && resourceMembershipResolver.governsCurrentPrincipal();
    }

    /**
     * Returns {@code true} if the current user owns the resource of {@code resourceType} identified by {@code id},
     * resolved via the registered {@code ResourceOwnershipResolver}. Bypassed (returns {@code true}) under skip mode; a
     * connected user never reaches that bypass, so this still runs for real for one — it is what gates
     * sharing/ownership management (connection credential replacement, connection/project access grants, signing-key
     * ownership), none of which a connected user has.
     */
    public boolean isResourceOwner(long id, String resourceType) {
        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        return permissionService.isResourceOwner(resourceType, id);
    }

    /**
     * Requires {@code scope} in every environment of the workspace. Use it on an operation whose effect is not confined
     * to one environment — a workspace-wide role grant takes effect everywhere at once, so authorising it from a role
     * held in a single environment would be an escalation. Bypassed (returns {@code true}) under skip mode; a connected
     * user never reaches that bypass and has no workspace membership to grant.
     */
    public boolean hasWorkspaceScopeInEveryEnvironment(long workspaceId, String scope) {
        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        return permissionService.hasWorkspaceScopeInEveryEnvironment(workspaceId, scope);
    }

    /**
     * Requires {@code scope} in the environment the operation acts on. The environment is taken from the guarded
     * method's own arguments, never from {@code EnvironmentContext}, which holds the source environment during a
     * promotion and is lost on worker threads. Bypassed (returns {@code true}) under skip mode; a connected user never
     * reaches that bypass and has no workspace membership to grant.
     */
    public boolean hasWorkspaceScopeInEnvironment(long workspaceId, String scope, Environment environment) {
        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        return permissionService.hasWorkspaceScope(workspaceId, scope, environment);
    }

    /**
     * Requires {@code scope} in the environment the caller named, for callers that supply it as a raw ordinal rather
     * than a resolved {@link Environment} — the case {@code hasPermission(#workspaceId, 'Workspace', ...)} cannot
     * express, because a workspace has no environment of its own for a {@code ResourceEnvironmentResolver} to supply
     * and the environment-unaware check therefore unions every environment the caller can reach.
     * <p>
     * <b>Named differently from {@link #hasWorkspaceScopeInEnvironment(long, String, Environment)} on purpose.</b> A
     * same-name, same-arity sibling taking {@code Long} is ambiguous in Java for a {@code null} literal, and in SpEL a
     * null argument matches both by reflection order — selecting the {@code Environment} overload and failing with an
     * NPE on {@code environment.ordinal()}. Do not merge the two.
     * <p>
     * <b>A {@code null} ordinal keeps the environment-unaware check</b> rather than denying or requiring every
     * environment. The listings that pass one use {@code null} as "no environment filter", and the clients routinely
     * send nothing, so denying would refuse ordinary pages to exactly the members per-environment roles protect. This
     * gate closes forgery — naming an environment the caller holds no role in — and a {@code null} names nothing. The
     * unfiltered listing still returns rows from every environment; that is a pre-existing union leak, recorded as a
     * limitation in the spec and not closed here.
     * <p>
     * <b>{@link PrincipalEnvironment#resolveEffectiveEnvironmentId(Long)} is deliberately NOT called</b>, unlike in
     * {@link #hasWorkflowScopeInEnvironment(String, String, Long)}. Substituting a confined principal's own environment
     * would authorise one environment while the guarded method's body, reading the raw argument, acts on another — the
     * exact divergence this gate exists to remove. The substitution is right for a workflow run, which happens in the
     * principal's environment whatever the request said; a listing returns what the argument names. Nothing is lost: a
     * confined principal has no {@code user} row, so the scope check fails closed regardless.
     */
    public boolean hasWorkspaceScopeInEnvironmentId(long workspaceId, String scope, @Nullable Long environmentId) {
        // Consulted for the same reason hasWorkflowScopeInEnvironment consults it, and with more at stake: no
        // resolver claims "Workspace", so a governed principal resolves NOT_APPLICABLE, which the decider turns into
        // DENY. That denial is what hasPermission gave these gates before they moved here, and dropping it would
        // open every workspace surface below to an embedded connected user.
        Outcome outcome = ResourceMembershipDecider.decide(
            resourceMembershipResolverProvider, workspaceId, "Workspace", scope);

        if (outcome == Outcome.DENY) {
            return false;
        }

        // Outcome.GRANT is deliberately NOT short-circuited here, unlike in hasWorkflowScopeInEnvironment. Membership
        // answers whose resource this is, never which environment it may be reached in, and returning granted on the
        // spot would skip the environment check this method exists to perform -- reopening the gap for exactly the
        // principals a resolver governs. The sibling can short-circuit because it substitutes the principal's own
        // environment first, so range is all it has left to verify; this method checks the ordinal the caller sent and
        // therefore still has the real question to answer. A GRANT falls through and is answered by the ordinary
        // per-environment check below, which is fail-CLOSED relative to the sibling. The branch is unreachable today
        // (no resolver claims "Workspace"), so this is a rule for whoever adds one, not a live path.
        if (environmentId == null) {
            if (AutomationAuthorizationContext.isSkipChecks()) {
                return true;
            }

            return permissionService.hasWorkspaceScope(workspaceId, scope);
        }

        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        Environment[] environments = Environment.values();

        if (environmentId < 0 || environmentId >= environments.length) {
            return false;
        }

        return permissionService.hasWorkspaceScope(workspaceId, scope, environments[environmentId.intValue()]);
    }

    /**
     * Requires {@code scope} in the environment the operation acts on, for the workspace owning {@code workflowId}. A
     * workflow has no environment of its own, so no {@code ResourceEnvironmentResolver} can supply one and
     * {@code hasPermission(#id, 'Workflow', ...)} necessarily unions the environments the caller can reach — a member
     * who is editor in Development would pass and could then act in Production. Use this wherever the caller supplies
     * the environment to run in.
     * <p>
     * <b>Also correct for reads, not just runs</b> — traced end to end against
     * {@code WorkflowNodeOutputFacadeImpl#getWorkflowNodeOutput} (platform-configuration-service). That method's body
     * calls {@code PrincipalEnvironment.resolveEffectiveEnvironmentId(environmentId)} itself (line 151) and uses the
     * resulting {@code effectiveEnvironmentId} for every downstream lookup (lines 160, 172) — the identical static call
     * this gate makes below. For a confined (api-key) principal naming an environment X while confined to Y, both the
     * gate and the body resolve to Y, because {@code PrincipalEnvironment.resolveEffectiveEnvironmentId} always prefers
     * the principal's own environment over whatever was requested (see {@code PrincipalEnvironment}'s Javadoc). Gate
     * and body therefore never diverge: there is no case where this authorises one environment and the body reads
     * another. The two sibling methods that deliberately do NOT resolve internally
     * ({@code getPreviousWorkflowNodeOutputs}/{@code getPreviousWorkflowNodeSampleOutputs}, kept unresolved only
     * because {@code @Cacheable} builds its key before the method body runs) rely on every caller resolving before
     * calling in — so the same conclusion holds one hop earlier rather than being an exception to it. Consequence: the
     * ~32 {@code 'Workflow'} sites this gate will replace do not need the non-substituting
     * {@link #hasResourceScopeInEnvironmentId(Serializable, String, String, Long)} form; this method serves runs and
     * reads alike.
     * <p>
     * {@code environmentId} is the caller's own ordinal and is deliberately not trusted. For a principal confined to a
     * single environment (an api-key caller: embedded connected user, embedded MCP) it is ignored outright in favour of
     * the principal's own — see {@link PrincipalEnvironment#resolveEffectiveEnvironmentId(Long)} — and the callers that
     * execute afterwards resolve the same way, so no caller can be authorised for one environment and execute in
     * another. For a session principal it is honoured, since such a caller genuinely chooses per request; absent or out
     * of range then denies rather than falling back to a default, because an environment that cannot be identified
     * cannot be authorised.
     * <p>
     * For a principal {@link ResourceMembershipResolver} does not govern, the skip check still runs before the ordinal
     * is validated, matching the environment-unaware gate; validating the ordinal ahead of it would deny requests skip
     * mode permits. A governed principal is answered from its membership ahead of the skip check instead — see
     * {@link ResourceMembershipDecider}.
     */
    public boolean hasWorkflowScopeInEnvironment(String workflowId, String scope, Long environmentId) {
        // Ticket 1051: this built-in reads the skip state itself and never reaches hasResourceScope(...), so wiring
        // the decider at those two places does not cover it. It has to be consulted here as well, or the embedded
        // builder's Run button (WorkflowTestApiController.startWorkflowTest, the only production gate using this
        // built-in) would be decided by the short circuit below rather than by membership.
        Outcome outcome = ResourceMembershipDecider.decide(
            resourceMembershipResolverProvider, workflowId, "Workflow", scope);

        if (outcome == Outcome.DENY) {
            return false;
        }

        // Resolved once, and used for BOTH the range check and the scope check below, so the environment authorised
        // here is the one the caller is actually confined to. WorkflowTestApiController and AiAgentTestApiController
        // resolve the same way for the execution that follows -- authorising one environment and executing in another
        // is the whole bug.
        Long effectiveEnvironmentId = PrincipalEnvironment.resolveEffectiveEnvironmentId(environmentId);

        Environment[] environments = Environment.values();
        boolean environmentIdResolvable =
            effectiveEnvironmentId != null && effectiveEnvironmentId >= 0
                && effectiveEnvironmentId < environments.length;

        // Membership answers whose workflow this is, never which environment it may run in -- but for a principal
        // CONFINED to one environment the caller-supplied ordinal is not a choice to validate, it is a degree of
        // freedom that should not exist. resolveEffectiveEnvironmentId substitutes the principal's own, so the two
        // cannot disagree and the range check below is all that is left to do. Validating the parameter instead was
        // tried and reverted: the two ends are independently defaulted client-side and disagree in the default
        // embedded configuration, so the comparison denied callers asking for nothing unusual.
        if (outcome == Outcome.GRANT) {
            return environmentIdResolvable;
        }

        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        if (!environmentIdResolvable) {
            return false;
        }

        return permissionService.hasWorkflowScope(
            workflowId, scope, environments[effectiveEnvironmentId.intValue()]);
    }

    /**
     * Requires {@code scope} for the resource in the environment the caller named, for {@code hasPermission(#id,
     * 'Type', ...)} families whose environment is an argument of the operation rather than a property of the resource —
     * {@code 'DataTable'} and {@code 'Project'} among them. A resource of one of these types has no environment of its
     * own for a {@code ResourceEnvironmentResolver} to supply, so the plain {@code hasPermission(#id, 'Type', ...)}
     * necessarily unions the environments the caller can reach — exactly the gap
     * {@link #hasWorkspaceScopeInEnvironmentId(long, String, Long)} closes one level up for {@code 'Workspace'}, and
     * {@link #hasWorkflowScopeInEnvironment(String, String, Long)} closes for {@code 'Workflow'}.
     * <p>
     * Three branches, exactly mirroring {@link #hasWorkspaceScopeInEnvironmentId(long, String, Long)}:
     * <ul>
     * <li>{@code environmentId == null} — the environment-unaware
     * {@link PermissionService#hasResourceScope(Serializable, String, String)}. The listings that pass one use
     * {@code null} as "no environment filter", and the clients routinely send nothing, so denying would refuse ordinary
     * pages to exactly the members per-environment roles protect.</li>
     * <li>an ordinal outside {@link Environment#values()} — denied. An environment that cannot be identified cannot be
     * authorised.</li>
     * <li>a resolvable ordinal —
     * {@link PermissionService#hasResourceScopeInEnvironment(Serializable, String, String, Environment)}, checked
     * against that environment alone, never unioned with the environment-unaware overload.</li>
     * </ul>
     * <p>
     * <b>Does not call {@link PrincipalEnvironment#resolveEffectiveEnvironmentId(Long)}</b>, unlike
     * {@link #hasWorkflowScopeInEnvironment(String, String, Long)}. That substitution is right for a workflow RUN,
     * which happens in the principal's own environment whatever the request said; a by-id read of a {@code DataTable}
     * or {@code Project} returns what the argument names, so substituting here would authorise one environment while
     * the guarded method's body, reading the raw argument, acts on another — the exact divergence this whole effort
     * exists to remove. See {@link #hasWorkflowScopeInEnvironment(String, String, Long)}'s own Javadoc for the
     * read-vs-run trace that settled this.
     * <p>
     * <b>Does not consult {@link ResourceMembershipDecider} directly</b>, unlike
     * {@link #hasWorkspaceScopeInEnvironmentId(long, String, Long)}. That method must, because no resolver claims
     * {@code "Workspace"} and {@code hasWorkspaceScope} never consults the decider itself. Every {@code resourceType}
     * this method serves instead routes through {@code hasResourceScope}/{@code hasResourceScopeInEnvironment}, and
     * both already consult the decider ahead of their own skip-checks and tenant-admin bypasses — consulting it again
     * here would only double-answer a governed principal, never change the outcome.
     */
    public boolean hasResourceScopeInEnvironmentId(
        Serializable id, String resourceType, String scope, @Nullable Long environmentId) {

        if (environmentId == null) {
            if (AutomationAuthorizationContext.isSkipChecks()) {
                return true;
            }

            return permissionService.hasResourceScope(id, resourceType, scope);
        }

        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        Environment[] environments = Environment.values();

        if (environmentId < 0 || environmentId >= environments.length) {
            return false;
        }

        return permissionService.hasResourceScopeInEnvironment(
            id, resourceType, scope, environments[environmentId.intValue()]);
    }

    /**
     * Requires {@code scope} for the resource in {@code environment}, for a caller that already holds a resolved
     * {@link Environment} rather than a raw ordinal — a promotion handler's {@code targetEnvironment} parameter among
     * them. Exists for the same reason {@link #hasWorkspaceScopeInEnvironment(long, String, Environment)} exists one
     * level up for {@code 'Workspace'}: an ordinal call such as {@code #targetEnvironment.ordinal()} inside a SpEL
     * string is easy to mistype and cannot be checked at compile time, where a resolved-{@link Environment} parameter
     * both reads directly off the method's own argument and fails to compile if the argument is renamed.
     * <p>
     * The environment must come from the guarded method's own arguments, never from {@code EnvironmentContext} — during
     * a promotion that thread-local holds the SOURCE environment, not the target one this expression exists to
     * authorise, and substituting it here would authorise one environment while the guarded method's body, reading its
     * own {@code targetEnvironment} argument, writes into another.
     * <p>
     * Delegates straight to
     * {@link PermissionService#hasResourceScopeInEnvironment(Serializable, String, String, Environment)}, which already
     * consults {@link ResourceMembershipDecider} on its own account — see that method's Javadoc and its
     * implementation's comment ("A new Environment-taking overload must do the same"). This wrapper does not consult
     * the decider a second time, matching {@link #hasWorkspaceScopeInEnvironment(long, String, Environment)}, which
     * delegates to {@link PermissionService#hasWorkspaceScope(long, String, Environment)} the same way and for the same
     * reason.
     * <p>
     * Bypassed (returns {@code true}) under skip mode; a connected user never reaches that bypass, since
     * {@code SkipAutomationAuthorizationAspect} arms nothing for a principal {@link ResourceMembershipResolver}
     * governs.
     */
    public boolean hasResourceScopeInEnvironment(
        Serializable id, String resourceType, String scope, @Nullable Environment environment) {

        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        // A null environment keeps the environment-unaware check, exactly as the null branch of
        // hasResourceScopeInEnvironmentId does, and for the same reason: an argument that names no environment gives
        // this gate nothing to check, and denying would refuse ordinary calls. Guarding here rather than leaving it to
        // the caller is what makes the expression safe to point at a caller-supplied Environment: without it, the path
        // below reaches environment.ordinal() inside WorkspaceScopeCacheService and a null turns a 403 into a 500.
        if (environment == null) {
            return permissionService.hasResourceScope(id, resourceType, scope);
        }

        return permissionService.hasResourceScopeInEnvironment(id, resourceType, scope, environment);
    }

    @Override
    public Object getFilterObject() {
        return filterObject;
    }

    @Override
    public void setFilterObject(Object filterObject) {
        this.filterObject = filterObject;
    }

    @Override
    public Object getReturnObject() {
        return returnObject;
    }

    @Override
    public void setReturnObject(Object returnObject) {
        this.returnObject = returnObject;
    }

    @Override
    public Object getThis() {
        return target;
    }
}
