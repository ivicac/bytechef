/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.platform.connection.dto.ConnectionDTO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ConnectedUserConnectionFacade {

    long createConnectedUserConnection(long connectedUserId, ConnectionDTO connectionDTO);

    /**
     * Deletes a connection owned by the connected user. Throws {@link java.util.NoSuchElementException} when
     * {@code connectionId} is not owned by {@code connectedUserId} (never surfaces as a permission error, so a
     * connection id cannot be enumerated by a connected user probing ids that belong to someone else).
     */
    void deleteConnectedUserConnection(long connectedUserId, long connectionId);

    /**
     * @param componentName the component to filter by, or {@code null} to return connections across every component
     */
    List<ConnectionDTO> getConnections(
        Long connectedUserId, @Nullable String componentName, List<Long> connectionIds);

    /**
     * The ids of the connections the connected user OWNS, a subset of what {@link #getConnections} returns. The
     * difference is the connections a tenant admin marked shared: entitled to every connected user in the environment,
     * and modifiable by none of them. Callers rendering a connection list need this to avoid offering reconnect or
     * delete on a connection the server will refuse to change.
     */
    Set<Long> getOwnedConnectionIds(long connectedUserId);

    /**
     * Replaces the credentials of a connection owned by the connected user, keeping its id. Throws
     * {@link java.util.NoSuchElementException} when {@code connectionId} is not owned by {@code connectedUserId}.
     */
    void reauthorizeConnectedUserConnection(long connectedUserId, long connectionId, Map<String, ?> parameters);
}
