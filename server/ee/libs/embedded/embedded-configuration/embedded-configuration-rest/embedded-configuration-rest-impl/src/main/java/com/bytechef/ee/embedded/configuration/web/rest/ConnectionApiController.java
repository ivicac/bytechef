/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.web.rest;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.commons.util.MapUtils;
import com.bytechef.commons.util.ObfuscateUtils;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserConnectionFacade;
import com.bytechef.ee.embedded.configuration.web.rest.model.ConnectionModel;
import com.bytechef.ee.embedded.configuration.web.rest.model.UpdateConnectionRequestModel;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.tag.domain.Tag;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.Validate;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * This controller carries TWO populations, and the split between them -- not the shared {@code /api/embedded/internal}
 * path -- is what decides the gate on each operation.
 *
 * <p>
 * {@code EmbeddedApiKeySecurityConfigurer} authenticates a connected-user JWT on
 * {@code ^/api/(?:automation|embedded|platform)/internal/.+} whenever an {@code Authorization} header is present --
 * that is how the connected user's embedded builder iframe reaches these endpoints at all. A connected user is
 * therefore a fully authenticated principal here, holding ZERO authorities, and {@code SecurityConfiguration} gates
 * {@code /api/**} at {@code authenticated()} only. So an operation on this controller with no {@code @PreAuthorize} is
 * reachable by every connected user in the tenant, not just by the admin console.
 *
 * <p>
 * <strong>The five tenant-admin operations.</strong> {@code createConnection}, {@code deleteConnection},
 * {@code getConnection}, {@code getConnections} and {@code updateConnection} route straight to the shared
 * {@link ConnectionFacade} with {@link PlatformType#EMBEDDED}, which carries no notion of a connected user: they act on
 * the tenant's whole embedded connection population. Their only client caller is the admin console's
 * {@code /embedded/connections} page. Two of them also write the {@code shared} flag, which is what makes the missing
 * gate exploitable rather than merely untidy -- a shared connection is offered to EVERY connected user in the
 * environment. Ungated, a connected user could {@code POST} a connection with {@code shared: true} and plant a decoy in
 * every other user's picker, or {@code PATCH} their OWN connection to {@code shared: true} and hand their credentials
 * to every other connected user. ({@code ConnectionServiceImpl.validateOwnerOrAdmin} stops them touching another user's
 * row, which is the only reason the second is not outright credential theft.) The design spec's promise that
 * {@code shared} is "settable only from the tenant admin surface" is these five annotations; nothing below the
 * controller enforces it, because the shared facade must stay ungated for its other callers.
 *
 * <p>
 * {@code isTenantAdmin()} is the check, not a placeholder for a finer one: the population that may act on the tenant's
 * embedded connections as a whole IS the tenant admin, and there is no per-connection scope that would say anything
 * narrower without also admitting the connected user this gate exists to exclude.
 *
 * <p>
 * <strong>The two connected-user operations.</strong> {@code createConnectedUserConnection} and
 * {@code getConnectedUserConnections} take a {@code connectedUserId} and route through
 * {@code ConnectedUserConnectionFacade}, which scopes every answer to that user's own entitlement --
 * {@code createConnectedUserConnection} additionally forces {@code shared} off regardless of the request body. They are
 * called by the connected user's builder, so they are deliberately NOT gated on {@code isTenantAdmin()}; adding it
 * there would break the embedded product outright.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@RestController("com.bytechef.ee.embedded.configuration.web.rest.ConnectionApiController")
@RequestMapping("${openapi.openAPIDefinition.base-path.embedded:}/internal")
@ConditionalOnCoordinator
@ConditionalOnEEVersion
public class ConnectionApiController implements ConnectionApi {

    private final ConnectedUserConnectionFacade connectedUserConnectionFacade;
    private final ConnectionFacade connectionFacade;
    private final ConversionService conversionService;

    @SuppressFBWarnings("EI")
    public ConnectionApiController(
        ConnectedUserConnectionFacade connectedUserConnectionFacade, ConnectionFacade connectionFacade,
        ConversionService conversionService) {

        this.connectedUserConnectionFacade = connectedUserConnectionFacade;
        this.connectionFacade = connectionFacade;
        this.conversionService = conversionService;
    }

    @Override
    @PreAuthorize("isTenantAdmin()")
    public ResponseEntity<Long> createConnection(ConnectionModel connectionModel) {
        return ResponseEntity.ok(
            connectionFacade.create(
                conversionService.convert(connectionModel, ConnectionDTO.class), PlatformType.EMBEDDED));
    }

    @Override
    public ResponseEntity<Long> createConnectedUserConnection(Long connectedUserId, ConnectionModel connectionModel) {
        return ResponseEntity.ok(
            connectedUserConnectionFacade.createConnectedUserConnection(
                connectedUserId, conversionService.convert(connectionModel, ConnectionDTO.class)));
    }

    @Override
    @PreAuthorize("isTenantAdmin()")
    public ResponseEntity<Void> deleteConnection(Long id) {
        connectionFacade.delete(id);

        return ResponseEntity.noContent()
            .build();
    }

    @Override
    public ResponseEntity<List<ConnectionModel>> getConnectedUserConnections(
        Long connectedUserId, String componentName, List<Long> connectionIds) {

        return ResponseEntity.ok(
            connectedUserConnectionFacade
                .getConnections(connectedUserId, componentName, connectionIds == null ? List.of() : connectionIds)
                .stream()
                .map(this::toConnectionModel)
                .toList());
    }

    @Override
    @PreAuthorize("isTenantAdmin()")
    public ResponseEntity<ConnectionModel> getConnection(Long id) {
        return ResponseEntity.ok(toConnectionModel(connectionFacade.getConnection(Validate.notNull(id, "id"))));
    }

    @Override
    @PreAuthorize("isTenantAdmin()")
    public ResponseEntity<List<ConnectionModel>> getConnections(
        String componentName, Integer connectionVersion, Long environmentId, Long tagId) {

        return ResponseEntity.ok(
            connectionFacade
                .getConnections(componentName, connectionVersion, List.of(), tagId, environmentId,
                    PlatformType.EMBEDDED)
                .stream()
                .map(this::toConnectionModel)
                .toList());
    }

    @Override
    @PreAuthorize("isTenantAdmin()")
    public ResponseEntity<Void> updateConnection(Long id, UpdateConnectionRequestModel updateConnectionRequestModel) {
        List<Tag> list = updateConnectionRequestModel.getTags()
            .stream()
            .map(tagModel -> conversionService.convert(tagModel, Tag.class))
            .toList();

        connectionFacade.update(
            id, updateConnectionRequestModel.getName(), list, updateConnectionRequestModel.getShared(),
            Objects.requireNonNull(updateConnectionRequestModel.getVersion()));

        return ResponseEntity.noContent()
            .build();
    }

    private ConnectionModel toConnectionModel(ConnectionDTO connection) {
        ConnectionModel connectionModel = conversionService.convert(connection, ConnectionModel.class);

        Objects.requireNonNull(connectionModel)
            .authorizationParameters(
                MapUtils.toMap(
                    connectionModel.getAuthorizationParameters(),
                    Map.Entry::getKey,
                    entry -> ObfuscateUtils.obfuscate(String.valueOf(entry.getValue()), 28, 8)));

        return Validate.notNull(connectionModel, "connectionModel")
            .parameters(null);
    }

}
