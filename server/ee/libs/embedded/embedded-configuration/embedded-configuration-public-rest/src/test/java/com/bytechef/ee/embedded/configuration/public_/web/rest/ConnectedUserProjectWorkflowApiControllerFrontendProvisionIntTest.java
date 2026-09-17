/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.public_.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.exception.CatalogWorkflowTemplateNotVisibleException;
import com.bytechef.ee.embedded.configuration.exception.ConnectionNotEntitledException;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.facade.AutomationWorkflowProjectFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserCodeWorkflowReferenceFacade;
import com.bytechef.ee.embedded.configuration.public_.web.rest.config.EmbeddedConfigurationPublicRestSharedMocks;
import com.bytechef.ee.embedded.configuration.public_.web.rest.config.EmbeddedConfigurationPublicRestTestConfiguration;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = EmbeddedConfigurationPublicRestTestConfiguration.class)
@TestPropertySource(properties = "bytechef.edition=ee")
@WebMvcTest(ConnectedUserProjectWorkflowApiController.class)
@EmbeddedConfigurationPublicRestSharedMocks
public class ConnectedUserProjectWorkflowApiControllerFrontendProvisionIntTest {

    private static final String EXTERNAL_USER_ID = "ext-user-1";
    private static final String HIDDEN_WORKFLOW_UUID = "hidden-catalog-uuid";
    private static final String UNKNOWN_WORKFLOW_UUID = "no-such-uuid";
    private static final String WORKFLOW_UUID = "catalog-uuid-1";

    @Autowired
    private ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade;

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
    public void testFrontendProvisionUsesThePrincipalAsExternalUserId() {
        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), any(Environment.class), eq(Map.of())))
                .thenReturn(new ConnectedUserProjectWorkflow());

        try {
            webTestClient
                .post()
                .uri("/v1/automation/workflow-templates/{workflowUuid}/provision", WORKFLOW_UUID)
                .exchange()
                .expectStatus()
                .isNoContent();
        } catch (Exception exception) {
            Assertions.fail(exception);
        }

        verify(connectedUserCodeWorkflowReferenceFacade)
            .getOrCreateReference(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), any(Environment.class), eq(Map.of()));
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testFrontendProvisionForwardsRequestedConnectionsFromTheBody() {
        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), any(Environment.class), eq(Map.of("slack", 12L))))
                .thenReturn(new ConnectedUserProjectWorkflow());

        try {
            webTestClient
                .post()
                .uri("/v1/automation/workflow-templates/{workflowUuid}/provision", WORKFLOW_UUID)
                .bodyValue(Map.of("connections", Map.of("slack", 12)))
                .exchange()
                .expectStatus()
                .isNoContent();
        } catch (Exception exception) {
            Assertions.fail(exception);
        }

        verify(connectedUserCodeWorkflowReferenceFacade).getOrCreateReference(
            eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), any(Environment.class), eq(Map.of("slack", 12L)));
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testFrontendProvisionMissingConnectionReturns409() {
        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(any(), any(), any(), any()))
            .thenThrow(new MissingConnectionException("slack"));

        try {
            webTestClient
                .post()
                .uri("/v1/automation/workflow-templates/{workflowUuid}/provision", WORKFLOW_UUID)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.missingConnectionComponentName")
                .isEqualTo("slack");
        } catch (Exception exception) {
            Assertions.fail(exception);
        }
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testFrontendProvisionConnectionNotEntitledReturns400() {
        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(any(), any(), any(), any()))
            .thenThrow(new ConnectionNotEntitledException("slack", 12L));

        try {
            webTestClient
                .post()
                .uri("/v1/automation/workflow-templates/{workflowUuid}/provision", WORKFLOW_UUID)
                .bodyValue(Map.of("connections", Map.of("slack", 12)))
                .exchange()
                .expectStatus()
                .isBadRequest();
        } catch (Exception exception) {
            Assertions.fail(exception);
        }
    }

    /**
     * A template the connected user's permission expression hides and a uuid that does not exist at all both reach the
     * controller as the same {@link CatalogWorkflowTemplateNotVisibleException} from the facade, and must leave the
     * HTTP layer as the same bodyless 404 -- otherwise the response itself would tell the caller which templates exist.
     */
    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testFrontendProvisionHiddenTemplateIsIndistinguishableFromUnknownUuid() {
        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            eq(EXTERNAL_USER_ID), eq(HIDDEN_WORKFLOW_UUID), any(Environment.class), any()))
                .thenThrow(
                    new CatalogWorkflowTemplateNotVisibleException(HIDDEN_WORKFLOW_UUID));
        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            eq(EXTERNAL_USER_ID), eq(UNKNOWN_WORKFLOW_UUID), any(Environment.class), any()))
                .thenThrow(
                    new CatalogWorkflowTemplateNotVisibleException(UNKNOWN_WORKFLOW_UUID));

        try {
            expectProvisionNotFoundWithoutBody(HIDDEN_WORKFLOW_UUID);
            expectProvisionNotFoundWithoutBody(UNKNOWN_WORKFLOW_UUID);
        } catch (Exception exception) {
            Assertions.fail(exception);
        }
    }

    private void expectProvisionNotFoundWithoutBody(String workflowUuid) {
        webTestClient
            .post()
            .uri("/v1/automation/workflow-templates/{workflowUuid}/provision", workflowUuid)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .isEmpty();
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testFrontendDeprovisionDeletesTheReference() {
        try {
            webTestClient
                .delete()
                .uri("/v1/automation/workflow-templates/{workflowUuid}/provision", WORKFLOW_UUID)
                .exchange()
                .expectStatus()
                .isNoContent();
        } catch (Exception exception) {
            Assertions.fail(exception);
        }

        verify(connectedUserCodeWorkflowReferenceFacade)
            .deleteReference(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), any(Environment.class));
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testFrontendProvisionConnectionWithoutIdReturns400() {
        try {
            webTestClient
                .post()
                .uri("/v1/automation/workflow-templates/{workflowUuid}/provision", WORKFLOW_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"connections\":{\"slack\":null}}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        } catch (Exception exception) {
            Assertions.fail(exception);
        }

        verify(connectedUserCodeWorkflowReferenceFacade, never())
            .getOrCreateReference(any(), any(), any(), any());
    }
}
