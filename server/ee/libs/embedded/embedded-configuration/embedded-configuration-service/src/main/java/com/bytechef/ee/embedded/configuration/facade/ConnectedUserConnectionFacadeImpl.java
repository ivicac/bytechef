/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.ee.embedded.configuration.security.ConnectedUserConnectionMembership;
import com.bytechef.ee.embedded.configuration.service.ConnectedUserConnectionService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.bytechef.platform.constant.PlatformType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final int MAX_WARNED_CONNECTED_USER_IDS = 1_000;

    private static final Set<Long> WARNED_CONNECTED_USER_IDS = ConcurrentHashMap.newKeySet();

    private final ConnectedUserConnectionMembership connectedUserConnectionMembership;
    private final ConnectedUserConnectionService connectedUserConnectionService;
    private final ConnectedUserService connectedUserService;
    private final ConnectionFacade connectionFacade;

    @SuppressFBWarnings("EI")
    public ConnectedUserConnectionFacadeImpl(
        ConnectedUserConnectionMembership connectedUserConnectionMembership,
        ConnectedUserConnectionService connectedUserConnectionService, ConnectedUserService connectedUserService,
        ConnectionFacade connectionFacade) {

        this.connectedUserConnectionMembership = connectedUserConnectionMembership;
        this.connectedUserConnectionService = connectedUserConnectionService;
        this.connectedUserService = connectedUserService;
        this.connectionFacade = connectionFacade;
    }

    @Override
    @PreAuthorize("isTenantAdmin() or isCurrentConnectedUser(#connectedUserId)")
    public long createConnectedUserConnection(long connectedUserId, ConnectionDTO connectionDTO) {
        long connectionId = connectionFacade.create(connectionDTO, PlatformType.EMBEDDED);

        connectedUserConnectionService.create(connectedUserId, connectionId);

        return connectionId;
    }

    @Override
    @PreAuthorize("isTenantAdmin() or isCurrentConnectedUser(#connectedUserId)")
    public void deleteConnectedUserConnection(long connectedUserId, long connectionId) {
        requireOwned(connectedUserId, connectionId);

        connectedUserConnectionService.deleteByConnectionId(connectionId);

        connectionFacade.delete(connectionId);
    }

    @Override
    @PreAuthorize("isTenantAdmin() or isCurrentConnectedUser(#connectedUserId)")
    public List<ConnectionDTO> getConnectedUserConnections(
        long connectedUserId, @Nullable String componentName, List<Long> connectionIds) {

        return getConnections(connectedUserId, componentName, connectionIds);
    }

    @Override
    @PreAuthorize("#externalUserId == authentication.name")
    public List<ConnectionDTO> getConnectedUserConnections(
        String externalUserId, Environment environment, @Nullable String componentName, List<Long> connectionIds) {

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(externalUserId, environment);

        return getConnections(connectedUser.getId(), componentName, connectionIds);
    }

    @Override
    public List<ConnectionDTO> getConnections(
        Long connectedUserId, @Nullable String componentName, List<Long> connectionIds) {

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(connectedUserId);

        Set<Long> entitledConnectionIds = connectedUserConnectionMembership.getConnectionIds(
            connectedUser.getId(), connectedUser.getEnvironment());

        logUnentitledRequestedConnectionIds(connectedUserId, connectionIds, entitledConnectionIds);

        List<Long> allowedConnectionIds = entitledConnectionIds.stream()
            .filter(connectionId -> connectionIds.isEmpty() || connectionIds.contains(connectionId))
            .toList();

        return connectionFacade.getConnections(new ArrayList<>(allowedConnectionIds), PlatformType.EMBEDDED)
            .stream()
            .filter(connectionDTO -> componentName == null || componentName.equals(connectionDTO.componentName()))
            .toList();
    }

    @Override
    @PreAuthorize("isTenantAdmin() or isCurrentConnectedUser(#connectedUserId)")
    public void reauthorizeConnectedUserConnection(long connectedUserId, long connectionId, Map<String, ?> parameters) {
        requireOwned(connectedUserId, connectionId);

        connectionFacade.replaceAuthorizationParameters(connectionId, parameters);
    }

    @Override
    public Set<Long> getOwnedConnectionIds(long connectedUserId) {
        ConnectedUser connectedUser = connectedUserService.getConnectedUser(connectedUserId);

        return connectedUserConnectionMembership.getOwnedConnectionIds(
            connectedUser.getId(), connectedUser.getEnvironment());
    }

    @Override
    @PreAuthorize("#externalUserId == authentication.name")
    public Set<Long> getOwnedConnectionIds(String externalUserId, Environment environment) {
        ConnectedUser connectedUser = connectedUserService.getConnectedUser(externalUserId, environment);

        return getOwnedConnectionIds(connectedUser.getId());
    }

    @Override
    public Set<Long> getSharedConnectionIds() {
        return connectedUserConnectionService.getSharedConnectionIds();
    }

    private void requireOwned(long connectedUserId, long connectionId) {
        ConnectedUser connectedUser = connectedUserService.getConnectedUser(connectedUserId);

        Set<Long> ownedConnectionIds = connectedUserConnectionMembership.getOwnedConnectionIds(
            connectedUser.getId(), connectedUser.getEnvironment());

        List<ConnectionDTO> ownedConnectionDTOs = connectionFacade.getConnections(
            new ArrayList<>(ownedConnectionIds), PlatformType.EMBEDDED);

        boolean owned = ownedConnectionDTOs.stream()
            .anyMatch(connectionDTO -> Objects.equals(connectionDTO.id(), connectionId));

        if (!owned) {
            throw new NoSuchElementException("Connection id=%s not found".formatted(connectionId));
        }
    }

    /**
     * Reports whether this connected user still deserves a WARN. A host that uses shared connections sends them on
     * every connections fetch -- every editor mount, every hub view -- so warning per request would bury the signal it
     * exists to give. One line per connected user per JVM is enough to identify who depends on the parameter.
     * <p>
     * Once the tracker is full it goes quiet rather than warning unconditionally: a full tracker means the signal has
     * already been given many times over, and the alternative -- resuming per-request logging at exactly the moment the
     * set stops absorbing ids -- turns a bounded diagnostic into a log flood any caller can drive.
     */
    private static boolean shouldWarnFor(Long connectedUserId) {
        if (WARNED_CONNECTED_USER_IDS.size() >= MAX_WARNED_CONNECTED_USER_IDS) {
            return false;
        }

        return WARNED_CONNECTED_USER_IDS.add(connectedUserId);
    }

    /**
     * Logs the requested ids this connected user is not entitled to, rather than rejecting the request outright: a
     * declared id that still lands here is one that is neither one of this user's own connections nor marked shared in
     * this environment -- which is the residual worth an operator's attention: a host declaring a connection it was
     * never actually given.
     */
    private static void logUnentitledRequestedConnectionIds(
        Long connectedUserId, List<Long> connectionIds, Set<Long> entitledConnectionIds) {

        if (connectionIds.isEmpty() || !log.isWarnEnabled()) {
            return;
        }

        List<Long> unentitledConnectionIds = connectionIds.stream()
            .filter(connectionId -> !entitledConnectionIds.contains(connectionId))
            .toList();

        if (!unentitledConnectionIds.isEmpty() && shouldWarnFor(connectedUserId)) {
            log.warn(
                "Ignored {} shared connection id(s) {} requested for connected user id={}: they are neither this " +
                    "user's own connections nor marked shared in this environment",
                unentitledConnectionIds.size(), unentitledConnectionIds, connectedUserId);
        }
    }
}
