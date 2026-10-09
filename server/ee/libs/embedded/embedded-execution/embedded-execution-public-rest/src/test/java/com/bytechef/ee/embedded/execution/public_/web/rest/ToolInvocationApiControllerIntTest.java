/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.execution.public_.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.embedded.execution.public_.web.rest.config.EmbeddedExecutionPublicRestTestConfiguration;
import com.bytechef.ee.platform.tool.invocation.log.ToolInvocationLog;
import com.bytechef.ee.platform.tool.invocation.log.ToolInvocationLogService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.tool.execution.ToolExecutionKind;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = EmbeddedExecutionPublicRestTestConfiguration.class)
@TestPropertySource(properties = "bytechef.edition=ee")
@WebMvcTest(ToolInvocationApiController.class)
public class ToolInvocationApiControllerIntTest {

    private static final long CONNECTED_USER_ID = 500L;
    private static final Instant CREATED_DATE = Instant.parse("2026-07-18T10:30:00Z");
    private static final Instant END_DATE = Instant.parse("2026-07-18T11:00:00Z");
    private static final String EXTERNAL_USER_ID = "externalUser1";
    private static final PageRequest PAGE_REQUEST = PageRequest.of(0, 20);
    private static final Instant START_DATE = Instant.parse("2026-07-18T10:00:00Z");

    @MockitoBean
    private ConnectedUserService connectedUserService;

    @MockitoBean
    private EnvironmentService environmentService;

    @MockitoBean
    private ToolInvocationLogService toolInvocationLogService;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void beforeEach() {
        Mockito.reset(connectedUserService, environmentService, toolInvocationLogService);
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testGetToolInvocationsPageScopesToConnectedUserAndMapsModels() throws Exception {
        ConnectedUser connectedUser = mock(ConnectedUser.class);

        when(connectedUser.getId()).thenReturn(CONNECTED_USER_ID);
        when(environmentService.getEnvironment("PRODUCTION")).thenReturn(Environment.PRODUCTION);
        when(connectedUserService.getConnectedUser(EXTERNAL_USER_ID, Environment.PRODUCTION)).thenReturn(connectedUser);
        when(
            toolInvocationLogService.getToolInvocationLogs(
                eq(Environment.PRODUCTION.ordinal()), eq(ToolExecutionSurface.EMBEDDED_API_ACTION),
                eq(ToolExecutionOutcome.SUCCESS), isNull(), eq(CONNECTED_USER_ID), isNull(), isNull(), eq(START_DATE),
                eq(END_DATE), eq(0)))
                    .thenReturn(new PageImpl<>(List.of(createToolInvocationLog()), PAGE_REQUEST, 1));

        mockMvc
            .perform(
                get("/v1/{externalUserId}/tool-invocations", EXTERNAL_USER_ID)
                    .header("X-Environment", "PRODUCTION")
                    .param("surface", "EMBEDDED_API_ACTION")
                    .param("outcome", "SUCCESS")
                    .param("startDate", START_DATE.toString())
                    .param("endDate", END_DATE.toString())
                    .param("pageNumber", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(1))
            .andExpect(jsonPath("$.content[0].surface").value("EMBEDDED_API_ACTION"))
            .andExpect(jsonPath("$.content[0].kind").value("COMPONENT"))
            .andExpect(jsonPath("$.content[0].componentName").value("slack"))
            .andExpect(jsonPath("$.content[0].operationName").value("sendMessage"))
            .andExpect(jsonPath("$.content[0].outcome").value("SUCCESS"))
            .andExpect(jsonPath("$.content[0].durationMs").value(42))
            .andExpect(jsonPath("$.content[0].createdDate").value(CREATED_DATE.toString()));
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testGetToolInvocationsPageDefaultsToProductionEnvironment() throws Exception {
        ConnectedUser connectedUser = mock(ConnectedUser.class);

        when(connectedUser.getId()).thenReturn(CONNECTED_USER_ID);
        when(environmentService.getEnvironment((String) null)).thenReturn(Environment.PRODUCTION);
        when(connectedUserService.getConnectedUser(EXTERNAL_USER_ID, Environment.PRODUCTION)).thenReturn(connectedUser);
        when(
            toolInvocationLogService.getToolInvocationLogs(
                eq(Environment.PRODUCTION.ordinal()), isNull(), isNull(), isNull(), eq(CONNECTED_USER_ID), isNull(),
                isNull(), isNull(), isNull(), eq(0)))
                    .thenReturn(new PageImpl<>(List.of(), PAGE_REQUEST, 0));

        mockMvc
            .perform(get("/v1/{externalUserId}/tool-invocations", EXTERNAL_USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0));

        verify(toolInvocationLogService).getToolInvocationLogs(
            eq(Environment.PRODUCTION.ordinal()), isNull(), isNull(), isNull(), eq(CONNECTED_USER_ID), isNull(),
            isNull(), isNull(), isNull(), eq(0));
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testGetToolInvocationsPageFiltersByTheResolvedEnvironment() throws Exception {
        ConnectedUser connectedUser = mock(ConnectedUser.class);

        when(connectedUser.getId()).thenReturn(CONNECTED_USER_ID);
        when(environmentService.getEnvironment("STAGING")).thenReturn(Environment.STAGING);
        when(connectedUserService.getConnectedUser(EXTERNAL_USER_ID, Environment.STAGING)).thenReturn(connectedUser);
        when(
            toolInvocationLogService.getToolInvocationLogs(
                eq(Environment.STAGING.ordinal()), isNull(), isNull(), isNull(), eq(CONNECTED_USER_ID), isNull(),
                isNull(), isNull(), isNull(), eq(0)))
                    .thenReturn(new PageImpl<>(List.of(), PAGE_REQUEST, 0));

        mockMvc
            .perform(
                get("/v1/{externalUserId}/tool-invocations", EXTERNAL_USER_ID)
                    .header("X-Environment", "STAGING")
                    .param("pageNumber", "0"))
            .andExpect(status().isOk());

        verify(toolInvocationLogService).getToolInvocationLogs(
            eq(Environment.STAGING.ordinal()), isNull(), isNull(), isNull(), eq(CONNECTED_USER_ID), isNull(),
            isNull(), isNull(), isNull(), eq(0));
    }

    @ParameterizedTest(name = "X-Environment [{0}]")
    @ValueSource(strings = {
        "development", "Development", " development "
    })
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testGetToolInvocationsPageAcceptsAnyCaseOfTheEnvironmentHeader(String requestEnvironment)
        throws Exception {

        stubToolInvocationsInEnvironment("DEVELOPMENT", Environment.DEVELOPMENT);

        mockMvc
            .perform(
                get("/v1/{externalUserId}/tool-invocations", EXTERNAL_USER_ID)
                    .header("X-Environment", requestEnvironment))
            .andExpect(status().isOk());

        verify(connectedUserService).getConnectedUser(EXTERNAL_USER_ID, Environment.DEVELOPMENT);
    }

    @ParameterizedTest(name = "X-Environment [{0}]")
    @ValueSource(strings = {
        "", "   "
    })
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testGetToolInvocationsPageTreatsABlankEnvironmentHeaderAsAbsent(String requestEnvironment)
        throws Exception {

        stubToolInvocationsInEnvironment(null, Environment.PRODUCTION);

        mockMvc
            .perform(
                get("/v1/{externalUserId}/tool-invocations", EXTERNAL_USER_ID)
                    .header("X-Environment", requestEnvironment))
            .andExpect(status().isOk());

        verify(connectedUserService).getConnectedUser(EXTERNAL_USER_ID, Environment.PRODUCTION);
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testGetToolInvocationsPageRejectsAnUnknownEnvironmentHeader() throws Exception {
        mockMvc
            .perform(
                get("/v1/{externalUserId}/tool-invocations", EXTERNAL_USER_ID)
                    .header("X-Environment", "staging-x"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(environmentService, connectedUserService, toolInvocationLogService);
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testGetToolInvocationsPageDeniesMismatchedExternalUserId() {
        boolean exceptionThrown = false;

        try {
            mockMvc.perform(get("/v1/{externalUserId}/tool-invocations", "otherUser"));
        } catch (Exception exception) {
            assertThat(exception.getCause()).isInstanceOf(AccessDeniedException.class);

            exceptionThrown = true;
        }

        assertThat(exceptionThrown).isTrue();

        verify(connectedUserService, never()).getConnectedUser(any(), any());
        verify(toolInvocationLogService, never()).getToolInvocationLogs(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    private void stubToolInvocationsInEnvironment(@Nullable String environmentName, Environment environment) {
        ConnectedUser connectedUser = mock(ConnectedUser.class);

        when(connectedUser.getId()).thenReturn(CONNECTED_USER_ID);
        when(environmentService.getEnvironment(environmentName)).thenReturn(environment);
        when(connectedUserService.getConnectedUser(EXTERNAL_USER_ID, environment)).thenReturn(connectedUser);
        when(
            toolInvocationLogService.getToolInvocationLogs(
                eq(environment.ordinal()), isNull(), isNull(), isNull(), eq(CONNECTED_USER_ID), isNull(), isNull(),
                isNull(), isNull(), eq(0)))
                    .thenReturn(new PageImpl<>(List.of(), PAGE_REQUEST, 0));
    }

    private ToolInvocationLog createToolInvocationLog() {
        ToolInvocationLog toolInvocationLog = new ToolInvocationLog();

        toolInvocationLog.setId(1L);
        toolInvocationLog.setSurface(ToolExecutionSurface.EMBEDDED_API_ACTION);
        toolInvocationLog.setKind(ToolExecutionKind.COMPONENT);
        toolInvocationLog.setComponentName("slack");
        toolInvocationLog.setComponentVersion(1);
        toolInvocationLog.setOperationName("sendMessage");
        toolInvocationLog.setOutcome(ToolExecutionOutcome.SUCCESS);
        toolInvocationLog.setDurationMs(42);
        toolInvocationLog.setCreatedDate(CREATED_DATE);

        return toolInvocationLog;
    }
}
