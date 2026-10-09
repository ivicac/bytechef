/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.tool.invocation.log.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.tool.invocation.log.ToolInvocationLog;
import com.bytechef.ee.platform.tool.invocation.log.facade.ToolInvocationLogFacade;
import com.bytechef.ee.platform.tool.invocation.log.web.graphql.config.ToolInvocationLogGraphQlTestConfiguration;
import com.bytechef.platform.tool.execution.ToolExecutionKind;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies the controller maps both queries onto {@link ToolInvocationLogFacade}, which owns their authorization.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = {
    ToolInvocationLogGraphQlTestConfiguration.class,
    ToolInvocationLogGraphQlController.class
})
@GraphQlTest(
    controllers = ToolInvocationLogGraphQlController.class,
    properties = {
        "bytechef.coordinator.enabled=true",
        "bytechef.edition=ee",
        "spring.graphql.schema.locations=classpath*:/graphql/"
    })
class ToolInvocationLogGraphQlControllerIntTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private ToolInvocationLogFacade toolInvocationLogFacade;

    @Test
    void testWorkspaceToolInvocationLogsPassesTheWorkspaceAndEnvironmentToTheFacade() {
        when(
            toolInvocationLogFacade.getWorkspaceToolInvocationLogs(
                eq(7L), eq(2L), eq(ToolExecutionSurface.AUTOMATION_APP_ACTION), isNull(), isNull(), isNull(), isNull(),
                eq(5L), isNull(), isNull(), eq(0)))
                    .thenReturn(new PageImpl<>(List.of(toolInvocationLog()), PageRequest.of(0, 50), 1));

        graphQlTester
            .document("""
                query {
                    workspaceToolInvocationLogs(
                        workspaceId: "7", environmentId: "2", surface: "AUTOMATION_APP_ACTION", appId: 5
                    ) {
                        content {
                            surface
                            kind
                            outcome
                            appId
                            workspaceId
                            createdDate
                        }
                        totalElements
                    }
                }
                """)
            .execute()
            .path("workspaceToolInvocationLogs.content[0].surface")
            .entity(String.class)
            .isEqualTo("AUTOMATION_APP_ACTION")
            .path("workspaceToolInvocationLogs.content[0].appId")
            .entity(Long.class)
            .isEqualTo(5L)
            .path("workspaceToolInvocationLogs.content[0].workspaceId")
            .entity(Long.class)
            .isEqualTo(7L)
            .path("workspaceToolInvocationLogs.content[0].createdDate")
            .entity(Long.class)
            .isEqualTo(1_000L)
            .path("workspaceToolInvocationLogs.totalElements")
            .entity(Integer.class)
            .isEqualTo(1);

        verify(toolInvocationLogFacade, never()).getToolInvocationLogs(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void testToolInvocationLogsUsesTheTenantWideFacadeQuery() {
        when(
            toolInvocationLogFacade.getToolInvocationLogs(
                isNull(), isNull(), isNull(), isNull(), eq(3L), isNull(), isNull(), isNull(), isNull(), eq(0)))
                    .thenReturn(new PageImpl<>(List.of()));

        graphQlTester
            .document("""
                query {
                    toolInvocationLogs(connectedUserId: 3) {
                        totalElements
                    }
                }
                """)
            .execute()
            .path("toolInvocationLogs.totalElements")
            .entity(Integer.class)
            .isEqualTo(0);

        verify(toolInvocationLogFacade, never()).getWorkspaceToolInvocationLogs(
            anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void testToolInvocationLogsPassesTheEnvironmentToTheFacade() {
        when(
            toolInvocationLogFacade.getToolInvocationLogs(
                eq(2L), eq(ToolExecutionSurface.MCP_EMBEDDED), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(0)))
                    .thenReturn(new PageImpl<>(List.of(toolInvocationLog()), PageRequest.of(0, 50), 1));

        graphQlTester
            .document("""
                query {
                    toolInvocationLogs(environmentId: "2", surface: "MCP_EMBEDDED") {
                        content {
                            environment
                        }
                        totalElements
                    }
                }
                """)
            .execute()
            .path("toolInvocationLogs.content[0].environment")
            .entity(Integer.class)
            .isEqualTo(2)
            .path("toolInvocationLogs.totalElements")
            .entity(Integer.class)
            .isEqualTo(1);
    }

    @Test
    void testControllerCarriesNoAuthorizationOfItsOwn() {
        List<Method> guardedMethods = Arrays.stream(ToolInvocationLogGraphQlController.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(PreAuthorize.class))
            .toList();

        assertThat(guardedMethods).isEmpty();
    }

    private static ToolInvocationLog toolInvocationLog() {
        ToolInvocationLog toolInvocationLog = new ToolInvocationLog();

        toolInvocationLog.setId(1L);
        toolInvocationLog.setSurface(ToolExecutionSurface.AUTOMATION_APP_ACTION);
        toolInvocationLog.setKind(ToolExecutionKind.COMPONENT);
        toolInvocationLog.setOutcome(ToolExecutionOutcome.SUCCESS);
        toolInvocationLog.setDurationMs(42);
        toolInvocationLog.setAppId(5L);
        toolInvocationLog.setWorkspaceId(7L);
        toolInvocationLog.setEnvironment(2);
        toolInvocationLog.setCreatedDate(Instant.ofEpochMilli(1_000L));

        return toolInvocationLog;
    }
}
