/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.relational.core.mapping.event.BeforeDeleteEvent;
import org.springframework.data.relational.core.mapping.event.Identifier;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link ConnectedUserBeforeDeleteEventListener}, mirroring {@code ProjectBeforeDeleteEventListenerTest}
 * -- a plain unit test invoking {@code onBeforeDelete} directly, since Spring Data JDBC's relational event publication
 * is infrastructure this listener does not own. The delete-cascade IntTest proves the full real-delete path fires it.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ConnectedUserBeforeDeleteEventListenerTest {

    private static final long CONNECTED_USER_ID = 42L;

    private final AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService =
        mock(AiGatewayRoutingPolicyService.class);

    private final ConnectedUserBeforeDeleteEventListener connectedUserBeforeDeleteEventListener =
        new ConnectedUserBeforeDeleteEventListener(aiGatewayRoutingPolicyService);

    @Test
    void testDeletingAConnectedUserUnbindsItsBoundPolicy() {
        AiGatewayRoutingPolicy policy = new AiGatewayRoutingPolicy("p", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(policy, "id", 7L);

        when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.of(policy));

        connectedUserBeforeDeleteEventListener.onBeforeDelete(event());

        verify(aiGatewayRoutingPolicyService).updateConnectedUserId(eq(7L), isNull());
    }

    @Test
    void testDeletingAConnectedUserWithNoBoundPolicyDoesNothing() {
        when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.empty());

        connectedUserBeforeDeleteEventListener.onBeforeDelete(event());

        verify(aiGatewayRoutingPolicyService, never()).updateConnectedUserId(anyLong(), any());
    }

    @SuppressWarnings("unchecked")
    private static BeforeDeleteEvent<ConnectedUser> event() {
        BeforeDeleteEvent<ConnectedUser> beforeDeleteEvent = mock(BeforeDeleteEvent.class);
        Identifier identifier = mock(Identifier.class);

        when(beforeDeleteEvent.getId()).thenReturn(identifier);
        when(identifier.getValue()).thenReturn(CONNECTED_USER_ID);

        return beforeDeleteEvent;
    }
}
