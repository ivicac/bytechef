/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.public_.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.bytechef.ee.embedded.configuration.facade.AutomationWorkflowProjectFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.configuration.public_.web.rest.config.EmbeddedConfigurationPublicRestSharedMocks;
import com.bytechef.ee.embedded.configuration.public_.web.rest.config.EmbeddedConfigurationPublicRestTestConfiguration;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

/**
 * Covers the HTTP wiring of the workflow-input write endpoints, which the hub's activation wizard and a card's settings
 * dialog both post to. The facade's own scoping is tested separately; what matters here is that the route reaches it
 * with the CALLER's identity rather than with anything the request supplied.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = EmbeddedConfigurationPublicRestTestConfiguration.class)
@TestPropertySource(properties = "bytechef.edition=ee")
@WebMvcTest(ConnectedUserProjectWorkflowApiController.class)
@EmbeddedConfigurationPublicRestSharedMocks
public class ConnectedUserProjectWorkflowApiControllerInputsIntTest {

    private static final String EXTERNAL_USER_ID = "user@example.com";
    private static final String WORKFLOW_UUID = "workflow-uuid-001";

    @Autowired
    private ConnectedUserProjectFacade connectedUserProjectFacade;

    @MockitoBean
    private AutomationWorkflowProjectFacade automationWorkflowProjectFacade;

    @MockitoBean
    private EnvironmentService environmentService;

    @Autowired
    private MockMvc mockMvc;

    private WebTestClient webTestClient;

    @BeforeEach
    void beforeEach() {
        this.webTestClient = MockMvcWebTestClient
            .bindTo(mockMvc)
            .build();

        when(environmentService.getEnvironment(any()))
            .thenReturn(Environment.PRODUCTION);
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testUpdateFrontendProjectWorkflowInputsStoresTheSuppliedValues() {
        try {
            webTestClient
                .put()
                .uri("/v1/automation/workflows/{workflowUuid}/inputs", WORKFLOW_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"inputs\":{\"sheetName\":\"Leads\"}}")
                .exchange()
                .expectStatus()
                .isNoContent();
        } catch (Exception exception) {
            Assertions.fail(exception);
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> captor = ArgumentCaptor.forClass(Map.class);

        // The externalUserId comes from the authenticated principal, never from the request: the frontend route has no
        // place to name a user, which is what keeps one connected user out of another's workflows.
        verify(connectedUserProjectFacade).updateProjectWorkflowInputs(
            eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), captor.capture(), any());

        Map<String, ?> inputs = captor.getValue();

        assertThat(inputs).hasSize(1);
        assertThat((Object) inputs.get("sheetName")).isEqualTo("Leads");
    }

    /**
     * In the {@code @WebMvcTest} slice {@link org.springframework.security.access.AccessDeniedException} is wrapped in
     * a {@code ServletException} rather than translated to 403 (there is no full Spring Security
     * {@code ExceptionTranslationFilter} here). The invariant that matters is that the facade is NEVER reached when the
     * path names somebody else: {@code SecurityUtils.checkCurrentUserLogin} runs before any write.
     */
    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testUpdateProjectWorkflowInputsRefusesAnotherUsersExternalId() {
        boolean exceptionThrown = false;

        try {
            mockMvc.perform(
                put("/v1/{externalUserId}/automation/workflows/{workflowUuid}/inputs", "someone-else", WORKFLOW_UUID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"inputs\":{\"sheetName\":\"Leads\"}}"));
        } catch (Exception exception) {
            assertThat(exception.getCause()).isInstanceOf(AccessDeniedException.class);

            exceptionThrown = true;
        }

        assertThat(exceptionThrown).isTrue();

        verify(connectedUserProjectFacade, never()).updateProjectWorkflowInputs(any(), any(), any(), any());
    }
}
