/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.web.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.ee.embedded.configuration.facade.AppEventFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.configuration.facade.IntegrationFacade;
import com.bytechef.ee.embedded.configuration.facade.IntegrationInstanceConfigurationFacade;
import com.bytechef.ee.embedded.configuration.facade.IntegrationInstanceFacade;
import com.bytechef.ee.embedded.configuration.facade.IntegrationWorkflowFacade;
import com.bytechef.ee.embedded.configuration.service.AppEventService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.ee.embedded.configuration.service.IntegrationService;
import com.bytechef.ee.embedded.configuration.web.rest.config.EmbeddedConfigurationRestConfigurationSharedMocks;
import com.bytechef.ee.embedded.configuration.web.rest.config.EmbeddedConfigurationRestTestConfiguration;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import com.bytechef.platform.configuration.facade.WorkflowFacade;
import com.bytechef.platform.configuration.service.EnvironmentService;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.MockMvcHttpConnector;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = EmbeddedConfigurationRestTestConfiguration.class)
@Import(ConnectedUserProjectWorkflowApiControllerIntTest.SecurityTestConfiguration.class)
@WebMvcTest(ConnectedUserProjectWorkflowApiController.class)
@EmbeddedConfigurationRestConfigurationSharedMocks
class ConnectedUserProjectWorkflowApiControllerIntTest {

    private static final String EXTERNAL_USER_ID = "externalUser1";

    private static final String LOGIN = "connected-user-1";
    private static final String WORKFLOW_UUID = "workflow-uuid-1";

    @MockitoBean
    private AppEventFacade appEventFacade;

    @MockitoBean
    private AppEventService appEventService;

    @MockitoBean
    private ComponentConnectionFacade componentConnectionFacade;

    @MockitoBean
    private ConnectedUserProjectFacade connectedUserProjectFacade;

    @MockitoBean
    private EnvironmentService environmentService;

    @MockitoBean
    private IntegrationFacade integrationFacade;

    @MockitoBean
    private IntegrationInstanceConfigurationFacade integrationInstanceConfigurationFacade;

    @MockitoBean
    private IntegrationInstanceFacade integrationInstanceFacade;

    @MockitoBean
    private IntegrationInstanceService integrationInstanceService;

    @MockitoBean
    private IntegrationService integrationService;

    @MockitoBean
    private IntegrationWorkflowFacade integrationWorkflowFacade;

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "X-Environment [{0}]")
    @ValueSource(strings = {
        "development", "Development", " development "
    })
    void testGetConnectedUserProjectWorkflowAcceptsAnyCaseOfTheEnvironmentHeader(String requestEnvironment)
        throws Exception {

        when(environmentService.getEnvironment("DEVELOPMENT")).thenReturn(Environment.DEVELOPMENT);

        mockMvc
            .perform(
                get("/internal/connected-user-project-workflows/{workflowUuid}", WORKFLOW_UUID)
                    .header("X-Environment", requestEnvironment)
                    .with(user(EXTERNAL_USER_ID)))
            .andExpect(status().isOk());

        verify(connectedUserProjectFacade).getConnectedUserProjectWorkflow(
            EXTERNAL_USER_ID, WORKFLOW_UUID, (long) Environment.DEVELOPMENT.ordinal());
    }

    @ParameterizedTest(name = "X-Environment [{0}]")
    @ValueSource(strings = {
        "", "   "
    })
    void testGetConnectedUserProjectWorkflowTreatsABlankEnvironmentHeaderAsAbsent(String requestEnvironment)
        throws Exception {

        when(environmentService.getEnvironment((String) null)).thenReturn(Environment.PRODUCTION);

        mockMvc
            .perform(
                get("/internal/connected-user-project-workflows/{workflowUuid}", WORKFLOW_UUID)
                    .header("X-Environment", requestEnvironment)
                    .with(user(EXTERNAL_USER_ID)))
            .andExpect(status().isOk());

        verify(connectedUserProjectFacade).getConnectedUserProjectWorkflow(
            EXTERNAL_USER_ID, WORKFLOW_UUID, (long) Environment.PRODUCTION.ordinal());
    }

    @Test
    void testGetConnectedUserProjectWorkflowRejectsAnUnknownEnvironmentHeader() throws Exception {
        mockMvc
            .perform(
                get("/internal/connected-user-project-workflows/{workflowUuid}", WORKFLOW_UUID)
                    .header("X-Environment", "staging-x")
                    .with(user(EXTERNAL_USER_ID)))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(connectedUserProjectFacade, environmentService);
    }

    private WebTestClient webTestClient;

    @MockitoBean
    private WorkflowFacade workflowFacade;

    @MockitoBean
    private WorkflowService workflowService;

    @BeforeEach
    void beforeEach() {
        webTestClient = MockMvcWebTestClient.bindTo(mockMvc)
            .build();

        when(environmentService.getEnvironment(Environment.PRODUCTION.name())).thenReturn(Environment.PRODUCTION);
    }

    @Test
    void testFallsBackToHeaderWhenPrincipalCarriesNoEnvironment() {
        enableConnectedUserProjectWorkflow(
            new UsernamePasswordAuthenticationToken(LOGIN, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        verify(connectedUserProjectFacade).enableProjectWorkflow(
            LOGIN, WORKFLOW_UUID, true, (long) Environment.PRODUCTION.ordinal());
    }

    private void enableConnectedUserProjectWorkflow(Authentication principalAuthentication) {
        webTestClient
            .mutateWith(authenticatedAs(principalAuthentication))
            .patch()
            .uri("/internal/connected-user-project-workflows/{workflowUuid}/enable/{enable}", WORKFLOW_UUID, true)
            .header("X-Environment", Environment.PRODUCTION.name())
            .exchange()
            .expectStatus()
            .isNoContent();
    }

    private static WebTestClientConfigurer authenticatedAs(Authentication principalAuthentication) {
        return (builder, httpHandlerBuilder, connector) -> {
            MockMvcHttpConnector mockMvcHttpConnector = (MockMvcHttpConnector) Objects.requireNonNull(connector);

            builder.clientConnector(
                mockMvcHttpConnector.with(List.of(authentication(principalAuthentication), csrf())));
        };
    }

    @EnableWebSecurity
    static class SecurityTestConfiguration {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
            httpSecurity.authorizeHttpRequests(authorize -> authorize
                .anyRequest()
                .authenticated());

            return httpSecurity.build();
        }
    }

}
