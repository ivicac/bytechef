/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.gateway.facade;

/**
 * Vendor-admin management of the AI Gateway routing policy bound to one connected user (embedded phase 2, spec §5).
 * Guarded with the same {@code ROLE_ADMIN} authority the routing-policy facade family already uses ({@code
 * AiGatewayRoutingPolicyFacade}, {@code WorkspaceAiGatewayRoutingPolicyFacade} in {@code automation-ai-gateway}) — AI
 * Gateway configuration is centrally administered infrastructure in this codebase, not delegated to per-tenant admins,
 * and binding an existing policy to a connected user is the same kind of operation as binding one to a workspace.
 *
 * <p>
 * Lives in the embedded tree — alongside {@code ConnectedUserService} — rather than beside
 * {@code AiGatewayRoutingPolicyFacade} in {@code automation-ai-gateway}, because binding must resolve the connected
 * user id within scope, and no automation-side or platform-gateway module may depend on embedded code. This direction
 * (embedded depending on {@code platform-ai-gateway-api}) is the legal one.
 *
 * <p>
 * Deliberately a standalone facade rather than two new methods on {@code ConnectedUserFacade}:
 * {@code ConnectedUserFacadeImpl} is unconditional ({@code @ConditionalOnEEVersion} only), while
 * {@code AiGatewayRoutingPolicyService} is registered only when {@code bytechef.ai.gateway.enabled=true}. A hard
 * constructor dependency on it from {@code ConnectedUserFacadeImpl} would break every EE deployment running with the
 * gateway disabled — Spring conditionals are per-bean, not per-method. Do not fold this back into
 * {@code ConnectedUserFacade}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ConnectedUserAiGatewayRoutingPolicyFacade {

    /**
     * Binds an existing, unscoped-or-connected-user-scoped routing policy to a connected user. Three checks run before
     * anything is written:
     * <ul>
     * <li>The policy must not already be bound to a workspace ({@code ck_ai_gateway_routing_policy_workspace_
     * connected_user_not_both} forbids both scopes on the same row) — rejected with {@link IllegalArgumentException},
     * never left to surface as an unmapped {@code DataIntegrityViolationException}.
     * <li>The policy must not already be bound to a <em>different</em> connected user — rejected with
     * {@link IllegalArgumentException} rather than silently moved, since the connected user who would lose their
     * routing is a bystander the caller never named. The caller must {@link #unbind} that connected user first.
     * <li>If the <em>target</em> connected user already has a different policy bound, that binding is replaced (the
     * partial unique index {@code uk_ai_gateway_routing_policy_connected_user_id} allows at most one per connected
     * user) — this is the caller's own stated intent, not a side effect on someone else, so no error.
     * </ul>
     * Both ids are resolved within scope before anything is written: an unknown or foreign {@code routingPolicyId} or
     * {@code connectedUserId} is indistinguishable from a missing one and surfaces as {@link IllegalArgumentException}
     * — never a different exception type or message depending on which case applies.
     */
    void bind(long routingPolicyId, long connectedUserId);

    /**
     * Clears the routing policy bound to a connected user, if any. A no-op, not an error, when the connected user has
     * no bound policy.
     */
    void unbind(long connectedUserId);
}
