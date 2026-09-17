/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.ee.embedded.configuration.exception.ConnectionNotEntitledException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.ComponentConnection;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves the per-slot connection wiring for a connected user's reference to a shared catalog workflow. Candidates
 * come ONLY from the connected user's own entitled connections ({@link ConnectedUserConnectionFacade}) -- never from
 * the tenant-wide {@code ConnectionService} -- so two connected users referencing the same workflow can never end up
 * wired to each other's connections. Slots are enumerated by {@link WorkflowConnectionSlots}, the same enumeration
 * {@code ProjectDeploymentFacadeImpl} uses to check that required connections are set.
 *
 * <p>
 * For each slot, a connection is chosen in this order: the caller-requested connection for that component (rejected
 * with {@link ConnectionNotEntitledException} if the connected user is not entitled to it), then the connection
 * currently wired to that component (if the connected user is still entitled to it), then the connected user's first
 * entitled connection for that component. A required slot with no entitled connection is reported in
 * {@link ResolvedWorkflowConnections#missingComponentNames()} rather than failing outright, so a partially resolvable
 * workflow can still surface which component is missing.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class ConnectedUserWorkflowConnectionResolver {

    private final ConnectedUserConnectionFacade connectedUserConnectionFacade;
    private final WorkflowConnectionSlots workflowConnectionSlots;

    @SuppressFBWarnings("EI")
    public ConnectedUserWorkflowConnectionResolver(
        ConnectedUserConnectionFacade connectedUserConnectionFacade, WorkflowConnectionSlots workflowConnectionSlots) {

        this.connectedUserConnectionFacade = connectedUserConnectionFacade;
        this.workflowConnectionSlots = workflowConnectionSlots;
    }

    public ResolvedWorkflowConnections resolve(
        String workflowId, long connectedUserId, Map<String, Long> requestedConnectionIds,
        List<ProjectDeploymentWorkflowConnection> currentConnections) {

        Map<String, List<Long>> entitledConnectionIdsByComponentName = new HashMap<>();
        List<ProjectDeploymentWorkflowConnection> connections = new ArrayList<>();
        Set<String> missingComponentNames = new LinkedHashSet<>();

        for (ComponentConnection slot : workflowConnectionSlots.getSlots(workflowId)) {
            String componentName = slot.componentName();

            List<Long> entitledConnectionIds = entitledConnectionIdsByComponentName.computeIfAbsent(
                componentName, name -> getEntitledConnectionIds(connectedUserId, name));

            Long connectionId = selectConnectionId(
                componentName, entitledConnectionIds, requestedConnectionIds.get(componentName),
                currentConnections);

            if (connectionId == null) {
                if (slot.required()) {
                    missingComponentNames.add(componentName);
                }

                continue;
            }

            connections.add(new ProjectDeploymentWorkflowConnection(connectionId, slot.key(), slot.workflowNodeName()));
        }

        return new ResolvedWorkflowConnections(connections, List.copyOf(missingComponentNames));
    }

    private List<Long> getEntitledConnectionIds(long connectedUserId, String componentName) {
        return connectedUserConnectionFacade.getConnections(connectedUserId, componentName, List.of())
            .stream()
            .map(ConnectionDTO::id)
            .toList();
    }

    @Nullable
    private static Long selectConnectionId(
        String componentName, List<Long> entitledConnectionIds, @Nullable Long requestedConnectionId,
        List<ProjectDeploymentWorkflowConnection> currentConnections) {

        if (requestedConnectionId != null) {
            if (!entitledConnectionIds.contains(requestedConnectionId)) {
                throw new ConnectionNotEntitledException(componentName, requestedConnectionId);
            }

            return requestedConnectionId;
        }

        for (ProjectDeploymentWorkflowConnection currentConnection : currentConnections) {
            if (entitledConnectionIds.contains(currentConnection.getConnectionId())) {
                return currentConnection.getConnectionId();
            }
        }

        return entitledConnectionIds.isEmpty() ? null : entitledConnectionIds.getFirst();
    }
}
