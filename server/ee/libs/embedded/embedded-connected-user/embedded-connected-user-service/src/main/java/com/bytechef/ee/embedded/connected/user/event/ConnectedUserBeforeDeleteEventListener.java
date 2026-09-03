/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.event;

import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayConnectedUserSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.relational.core.mapping.event.AbstractRelationalEventListener;
import org.springframework.data.relational.core.mapping.event.BeforeDeleteEvent;
import org.springframework.data.relational.core.mapping.event.Identifier;
import org.springframework.stereotype.Component;

/**
 * Removes a connected user's AI Gateway state before the connected user row is removed: deletes its settings row
 * (assigned plan and spend cap) and disables -- never releases -- its own provider credentials. Releasing a credential
 * by clearing {@code connected_user_id} would make the key a tenant provider every other customer routes through;
 * deleting it automatically would destroy a credential an admin may still need to audit or rotate. The assigned plan
 * itself is a shared, vendor-owned routing policy and is untouched.
 *
 * <p>
 * A Spring Data JDBC relational event hook rather than a database foreign key, because the platform tier's schema does
 * not reference the embedded tier's {@code connected_user} table.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class ConnectedUserBeforeDeleteEventListener extends AbstractRelationalEventListener<ConnectedUser> {

    private final AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;
    private final AiGatewayProviderService aiGatewayProviderService;

    @SuppressFBWarnings("EI")
    public ConnectedUserBeforeDeleteEventListener(
        AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService,
        AiGatewayProviderService aiGatewayProviderService) {

        this.aiGatewayConnectedUserSettingsService = aiGatewayConnectedUserSettingsService;
        this.aiGatewayProviderService = aiGatewayProviderService;
    }

    @Override
    protected void onBeforeDelete(BeforeDeleteEvent<ConnectedUser> event) {
        Identifier identifier = event.getId();

        long connectedUserId = (Long) identifier.getValue();

        aiGatewayConnectedUserSettingsService.deleteByConnectedUserId(connectedUserId);
        aiGatewayProviderService.disableByConnectedUserId(connectedUserId);
    }
}
