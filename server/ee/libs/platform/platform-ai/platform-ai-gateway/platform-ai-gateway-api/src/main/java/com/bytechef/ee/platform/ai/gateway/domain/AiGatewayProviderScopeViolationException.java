/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.domain;

/**
 * Thrown when a model deployment's configured provider (a BYOK — bring-your-own-key — row bound to some connected user)
 * does not belong to the connected user the current request is actually for. This is a server-side misconfiguration,
 * never a caller mistake: a vendor operator pointed a routing policy's model deployment at a different customer's own
 * provider row.
 *
 * <p>
 * Deliberately NOT an {@link IllegalStateException}: this module's REST layer maps every {@code IllegalStateException}
 * to 503 ("Service temporarily unavailable") and, more importantly, the gateway's retry handler treats
 * {@code IllegalStateException} as transient and retries it — three attempts against the offending deployment, then the
 * same against every other deployment in the routing policy, all guaranteed to fail identically, before this
 * exception's precise message is finally buried as the {@code cause} of a generic "All deployments failed after
 * retries" wrapper. A dedicated type lets both the retry handler and the REST layer treat this for what it is:
 * permanent, not transient, and worth surfacing precisely.
 *
 * <p>
 * Carries the three ids as typed fields rather than only in the message, mirroring {@link BudgetExceededException}'s
 * typed-breakdown shape — an operator investigating this in logs or a dashboard should never need to parse a string to
 * find the provider or the two connected users involved.
 *
 * @version ee
 */
public class AiGatewayProviderScopeViolationException extends RuntimeException {

    private final long providerId;
    private final long connectedUserId;
    private final long providerConnectedUserId;

    public AiGatewayProviderScopeViolationException(
        String message, long providerId, long connectedUserId, long providerConnectedUserId) {

        super(message);

        this.providerId = providerId;
        this.connectedUserId = connectedUserId;
        this.providerConnectedUserId = providerConnectedUserId;
    }

    /**
     * The provider row that was wrongly reachable for {@link #getConnectedUserId()}'s request.
     */
    public long getProviderId() {
        return providerId;
    }

    /**
     * The connected user the request was actually for.
     */
    public long getConnectedUserId() {
        return connectedUserId;
    }

    /**
     * The connected user {@link #getProviderId()} is actually bound to — never equal to {@link #getConnectedUserId()}
     * (that case would not throw).
     */
    public long getProviderConnectedUserId() {
        return providerConnectedUserId;
    }
}
