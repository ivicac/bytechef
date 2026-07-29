/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.execution.util;

import com.bytechef.ee.embedded.configuration.domain.IntegrationInstance;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class ConnectionIdHelper {

    private final ConnectedUserService connectedUserService;
    private final IntegrationInstanceService integrationInstanceService;

    @SuppressFBWarnings("EI")
    public ConnectionIdHelper(
        ConnectedUserService connectedUserService, IntegrationInstanceService integrationInstanceService) {

        this.connectedUserService = connectedUserService;
        this.integrationInstanceService = integrationInstanceService;
    }

    /**
     * Resolves the connected user behind {@code externalUserId} in the environment and the connection the call runs
     * with: the given integration instance's, which must belong to that connected user, or else the connection of the
     * connected user's instance of the component, if any.
     */
    public ConnectedUserConnection getConnectedUserConnection(
        String externalUserId, String componentName, @Nullable Long instanceId, Environment environment) {

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(externalUserId, environment);

        Long connectedUserId = connectedUser.getId();

        Long connectionId;

        if (instanceId == null) {
            connectionId = integrationInstanceService
                .fetchIntegrationInstance(connectedUserId, componentName, environment)
                .map(IntegrationInstance::getConnectionId)
                .orElse(null);
        } else {
            IntegrationInstance integrationInstance = integrationInstanceService.getIntegrationInstance(instanceId);

            if (!connectedUserId.equals(integrationInstance.getConnectedUserId())) {
                throw new AccessDeniedException(
                    "Integration instance " + instanceId + " is not owned by the connected user");
            }

            connectionId = integrationInstance.getConnectionId();
        }

        return new ConnectedUserConnection(connectedUserId, connectionId);
    }

    public record ConnectedUserConnection(long connectedUserId, @Nullable Long connectionId) {
    }
}
