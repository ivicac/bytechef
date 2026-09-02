/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.security;

import com.bytechef.ee.embedded.configuration.domain.IntegrationInstance;
import com.bytechef.ee.embedded.configuration.service.ConnectedUserConnectionService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.constant.PlatformType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single definition of which connections an embedded connected user is entitled to, in one environment.
 *
 * <p>
 * It exists because that question used to be answered twice, by two hand-written unions of the same two sources --
 * {@code ConnectedUserConnectionFacadeImpl.getConnections}, which decides what the connection picker SHOWS, and
 * {@code ConnectedUserResourceMembershipResolver.resolveConnection}, which decides what authorization GRANTS. Two
 * copies of an entitlement rule drift, and the shape the drift takes is a connection that appears in the picker and
 * then 403s when it is used. Both now call this, so they cannot disagree: the picker's result is this set filtered by
 * component name, and the resolver's grant is membership of this set.
 *
 * <p>
 * The two public methods are one definition, not two: {@link #getOwnedConnectionIds(long, Environment)} is the base and
 * {@link #getConnectionIds(long, Environment)} is that base plus source 3, both built from the same private overload
 * over the same instance list. {@code owned} is therefore a subset of {@code entitled} structurally rather than by
 * convention, and an edit to one cannot leave the other behind.
 *
 * <p>
 * Three sources, unioned:
 * <ol>
 * <li>the connection on each {@link IntegrationInstance} the connected user owns;</li>
 * <li>the connections the connected user created for themselves, via {@link ConnectedUserConnectionService};</li>
 * <li>the connections a tenant admin marked {@code shared}, in this environment.</li>
 * </ol>
 *
 * <p>
 * The third source replaces both the host's {@code sharedConnectionIds} request parameter and the configuration-binding
 * derivation that briefly stood in for it. The parameter was a caller assertion the server could not verify and is now
 * ignored; the derivation could not express a connection no configuration binds -- a vendor's house connection -- and
 * left sharing inferred rather than stated. A column on the row is both verifiable and legible to the admin who sets
 * it, and unticking it revokes the entitlement on the next request.
 *
 * <p>
 * Source 3 is deliberately absent from {@link #getOwnedConnectionIds(long, Environment)}. A shared connection is
 * entitled to every connected user in the environment, so deleting or reauthorizing it through one end user's
 * credentials would act on all of them at once.
 *
 * <p>
 * One caveat on the cannot-disagree claim above, which is about the RULE and not about the inputs. The picker reaches
 * this class with the environment on the {@code ConnectedUser} the {@code X-Environment} header selected, while the
 * resolver reaches it with the environment on the authenticated principal. Both are the caller's environment on every
 * path that exists today, but they are read from different places, so a deployment that let them differ would have the
 * picker over-list and the resolver deny -- the deny-safe direction, and a pre-existing property of those two entry
 * points rather than of this computation. What is guaranteed here is that for the SAME environment the two get the same
 * set.
 *
 * <p>
 * The environment axis is carried by source 1 through the instance lookup, and by source 3 directly: it passes
 * {@code environment.ordinal()} into its own query rather than inheriting the filter from source 1. A caller confined
 * to DEVELOPMENT therefore cannot reach a PRODUCTION connection through either source -- source 3's query itself
 * excludes it, independently of what the caller's instances happen to carry.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class ConnectedUserConnectionMembership {

    private final ConnectedUserConnectionService connectedUserConnectionService;
    private final ConnectionService connectionService;
    private final IntegrationInstanceService integrationInstanceService;

    @SuppressFBWarnings("EI")
    public ConnectedUserConnectionMembership(
        ConnectedUserConnectionService connectedUserConnectionService, ConnectionService connectionService,
        IntegrationInstanceService integrationInstanceService) {

        this.connectedUserConnectionService = connectedUserConnectionService;
        this.connectionService = connectionService;
        this.integrationInstanceService = integrationInstanceService;
    }

    /**
     * Every connection id this connected user is entitled to in {@code environment}: the three sources above, unioned.
     * Insertion-ordered so the picker's listing stays stable across requests; callers that only need membership can
     * treat it as a plain set.
     */
    @Transactional(readOnly = true)
    public Set<Long> getConnectionIds(long connectedUserId, Environment environment) {
        List<IntegrationInstance> integrationInstances =
            integrationInstanceService.getConnectedUserIntegrationInstances(connectedUserId, environment);

        Set<Long> connectionIds = getOwnedConnectionIds(connectedUserId, integrationInstances);

        for (Connection connection : connectionService.getSharedConnections(
            environment.ordinal(), PlatformType.EMBEDDED)) {

            connectionIds.add(connection.getId());
        }

        return connectionIds;
    }

    /**
     * The subset the connected user OWNS -- sources 1 and 2 only, never the {@code shared} connections of source 3.
     *
     * <p>
     * Entitlement and ownership are deliberately different sets, and this is the method that keeps them apart. A shared
     * connection is entitled to every connected user in the environment, so deleting or reauthorizing it through an end
     * user's own credentials would act on all of them at once; it belongs to the tenant admin who marked it shared.
     * Callers that MUTATE a connection must use this, callers that merely list or authorize a read must use
     * {@link #getConnectionIds(long, Environment)}.
     */
    @Transactional(readOnly = true)
    public Set<Long> getOwnedConnectionIds(long connectedUserId, Environment environment) {
        return getOwnedConnectionIds(
            connectedUserId,
            integrationInstanceService.getConnectedUserIntegrationInstances(connectedUserId, environment));
    }

    private Set<Long> getOwnedConnectionIds(long connectedUserId, List<IntegrationInstance> integrationInstances) {
        Set<Long> connectionIds = new LinkedHashSet<>();

        for (IntegrationInstance integrationInstance : integrationInstances) {
            connectionIds.add(integrationInstance.getConnectionId());
        }

        connectionIds.addAll(connectedUserConnectionService.getConnectionIds(connectedUserId));

        return connectionIds;
    }
}
