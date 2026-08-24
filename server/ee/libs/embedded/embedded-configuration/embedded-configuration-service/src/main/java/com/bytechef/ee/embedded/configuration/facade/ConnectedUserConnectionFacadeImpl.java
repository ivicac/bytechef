/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstance;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceConfiguration;
import com.bytechef.ee.embedded.configuration.security.ConnectedUserConnectionMembership;
import com.bytechef.ee.embedded.configuration.service.ConnectedUserConnectionService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.component.domain.ConnectionDefinition;
import com.bytechef.platform.component.service.ConnectionDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.domain.BaseProperty;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
public class ConnectedUserConnectionFacadeImpl implements ConnectedUserConnectionFacade {

    private static final Logger log = LoggerFactory.getLogger(ConnectedUserConnectionFacadeImpl.class);

    private final ConnectedUserConnectionMembership connectedUserConnectionMembership;
    private final ConnectedUserConnectionService connectedUserConnectionService;
    private final ConnectedUserService connectedUserService;
    private final ConnectionDefinitionService connectionDefinitionService;
    private final ConnectionFacade connectionFacade;
    private final IntegrationInstanceConfigurationService integrationInstanceConfigurationService;
    private final IntegrationInstanceService integrationInstanceService;

    @SuppressFBWarnings("EI")
    public ConnectedUserConnectionFacadeImpl(
        ConnectedUserConnectionMembership connectedUserConnectionMembership,
        ConnectedUserConnectionService connectedUserConnectionService, ConnectedUserService connectedUserService,
        ConnectionDefinitionService connectionDefinitionService, ConnectionFacade connectionFacade,
        IntegrationInstanceConfigurationService integrationInstanceConfigurationService,
        IntegrationInstanceService integrationInstanceService) {

        this.connectedUserConnectionMembership = connectedUserConnectionMembership;
        this.connectedUserConnectionService = connectedUserConnectionService;
        this.connectedUserService = connectedUserService;
        this.connectionDefinitionService = connectionDefinitionService;
        this.connectionFacade = connectionFacade;
        this.integrationInstanceConfigurationService = integrationInstanceConfigurationService;
        this.integrationInstanceService = integrationInstanceService;
    }

    @Override
    @PreAuthorize("isTenantAdmin() or isCurrentConnectedUser(#connectedUserId)")
    public long createConnectedUserConnection(long connectedUserId, ConnectionDTO connectionDTO) {
        long connectionId = connectionFacade.create(connectionDTO, PlatformType.EMBEDDED);

        connectedUserConnectionService.create(connectedUserId, connectionId);

        return connectionId;
    }

    @Override
    public void deleteConnectedUserConnection(long connectedUserId, long connectionId) {
        requireOwned(connectedUserId, connectionId);

        connectionFacade.delete(connectionId);
    }

    @Override
    public List<ConnectionDTO> getConnections(
        Long connectedUserId, @Nullable String componentName, List<Long> connectionIds) {

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(connectedUserId);

        Set<Long> entitledConnectionIds = connectedUserConnectionMembership.getConnectionIds(
            connectedUser.getId(), connectedUser.getEnvironment());

        List<IntegrationInstance> integrationInstances = componentName == null
            ? integrationInstanceService.getConnectedUserIntegrationInstances(
                connectedUser.getId(), connectedUser.getEnvironment())
            : integrationInstanceService.getIntegrationInstances(
                connectedUser.getId(), componentName, connectedUser.getEnvironment());

        allConnectionIds.addAll(
            integrationInstances.stream()
                .map(IntegrationInstance::getConnectionId)
                .toList());

        return connectionFacade.getConnections(new ArrayList<>(allowedConnectionIds), PlatformType.EMBEDDED)
            .stream()
            .filter(connectionDTO -> componentName == null || componentName.equals(connectionDTO.componentName()))
            .toList();
    }

    /**
     * Replaces the connection's authorization parameters wholesale: a parameter the caller does not resubmit is
     * cleared, not preserved. This deliberately differs from the merge this path used before — both the embedded hub
     * and the workspace surface now carry one semantics — and it is why the reconnect also marks the connection's
     * credentials valid again, which the merge-based path never did.
     */
    @Override
    public void reauthorizeConnectedUserConnection(long connectedUserId, long connectionId, Map<String, ?> parameters) {
        ConnectionDTO connectionDTO = requireOwned(connectedUserId, connectionId);

        connectionFacade.replaceAuthorizationParameters(
            connectionId, withIntegrationCredentials(connectedUserId, connectionDTO, parameters));
    }

    /**
     * A vendor-shared connection is never owned via this path: it never appears in
     * {@code connectedUserConnectionService.getConnectionIds} or the caller's integration instances, and this check
     * deliberately passes an empty {@code connectionIds} list rather than any shared-connection id set, so a shared
     * connection can never satisfy ownership here and is therefore never deletable or reauthorizable by an end user.
     */
    private void requireOwned(long connectedUserId, long connectionId) {
        List<ConnectionDTO> ownedConnectionDTOs = getConnections(connectedUserId, null, List.of());

        return ownedConnectionDTOs.stream()
            .filter(connectionDTO -> Objects.equals(connectionDTO.id(), connectionId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("Connection id=%s not found".formatted(connectionId)));
    }

    private Map<String, ?> withIntegrationCredentials(
        long connectedUserId, ConnectionDTO connectionDTO, Map<String, ?> parameters) {

        long connectionId = Objects.requireNonNull(connectionDTO.id(), "connection id");

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(connectedUserId);

        Optional<IntegrationInstance> integrationInstanceOptional = integrationInstanceService
            .getConnectedUserIntegrationInstances(connectedUserId, connectedUser.getEnvironment())
            .stream()
            .filter(integrationInstance -> integrationInstance.getConnectionId() == connectionId)
            .findFirst();

        if (integrationInstanceOptional.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug(
                    "Connection id={} of connected user id={} is not backed by an integration instance, " +
                        "reauthorizing without the integration configuration credentials",
                    connectionId, connectedUserId);
            }

            return parameters;
        }

        IntegrationInstance integrationInstance = integrationInstanceOptional.get();

        IntegrationInstanceConfiguration integrationInstanceConfiguration = integrationInstanceConfigurationService
            .getIntegrationInstanceConfiguration(integrationInstance.getIntegrationInstanceConfigurationId());

        List<String> authorizationPropertyNames = getAuthorizationPropertyNames(connectionDTO);

        Map<String, ?> connectionParameters = integrationInstanceConfiguration.getConnectionParameters();

        Map<String, Object> mergedParameters = new HashMap<>();

        for (Map.Entry<String, ?> connectionParameterEntry : connectionParameters.entrySet()) {
            if (authorizationPropertyNames.contains(connectionParameterEntry.getKey())) {
                mergedParameters.put(connectionParameterEntry.getKey(), connectionParameterEntry.getValue());
            }
        }

        mergedParameters.putAll(parameters);

        return mergedParameters;
    }

    private List<String> getAuthorizationPropertyNames(ConnectionDTO connectionDTO) {
        ConnectionDefinition connectionDefinition = connectionDefinitionService.getConnectionConnectionDefinition(
            connectionDTO.componentName(), connectionDTO.connectionVersion());

        return connectionDefinition.getAuthorizations()
            .stream()
            .flatMap(authorization -> CollectionUtils.stream(authorization.getProperties()))
            .map(BaseProperty::getName)
            .toList();
    }
}
