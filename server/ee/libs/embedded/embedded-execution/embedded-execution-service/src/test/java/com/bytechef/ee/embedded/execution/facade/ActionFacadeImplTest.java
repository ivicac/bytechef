/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.execution.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.execution.util.ConnectionIdHelper;
import com.bytechef.ee.embedded.execution.util.ConnectionIdHelper.ConnectedUserConnection;
import com.bytechef.platform.component.facade.ActionDefinitionFacade;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.tool.execution.ToolExecutionEvent;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @version ee
 */
class ActionFacadeImplTest {

    private final ConnectionIdHelper connectionIdHelper = mock(ConnectionIdHelper.class);
    private final ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);

    private final ActionFacadeImpl actionFacade = new ActionFacadeImpl(
        mock(ActionDefinitionFacade.class), connectionIdHelper, toolExecutionRecorder);

    @Test
    @SuppressWarnings("unchecked")
    void testExecuteActionRecordsTheConnectedUser() {
        when(connectionIdHelper.getConnectedUserConnection("user-1", "slack", 5L, Environment.STAGING))
            .thenReturn(new ConnectedUserConnection(1L, 7L));
        when(toolExecutionRecorder.record(any(ToolExecutionEvent.Builder.class), any(Supplier.class)))
            .thenReturn("ok");

        Object result = actionFacade.executeAction(
            "user-1", "slack", 1, "sendMessage", Map.of(), 5L, Environment.STAGING);

        assertEquals("ok", result);

        ArgumentCaptor<ToolExecutionEvent.Builder> builderArgumentCaptor =
            ArgumentCaptor.forClass(ToolExecutionEvent.Builder.class);

        verify(toolExecutionRecorder).record(builderArgumentCaptor.capture(), any(Supplier.class));

        ToolExecutionEvent.Builder builder = builderArgumentCaptor.getValue();

        ToolExecutionEvent toolExecutionEvent = builder.build();

        assertEquals(ToolExecutionSurface.EMBEDDED_API_ACTION, toolExecutionEvent.surface());
        assertEquals(1L, toolExecutionEvent.connectedUserId());
        assertEquals("user-1", toolExecutionEvent.externalUserId());
        assertEquals(5L, toolExecutionEvent.integrationInstanceId());
        assertEquals(7L, toolExecutionEvent.connectionId());
        assertEquals(Environment.STAGING.ordinal(), toolExecutionEvent.environment());
    }
}
