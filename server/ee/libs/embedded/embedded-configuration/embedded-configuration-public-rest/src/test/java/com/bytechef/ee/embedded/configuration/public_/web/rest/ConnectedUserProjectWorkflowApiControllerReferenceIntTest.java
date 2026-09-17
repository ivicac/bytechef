/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.public_.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserProjectWorkflowDTO;
import com.bytechef.ee.embedded.configuration.exception.CatalogWorkflowTemplateNotVisibleException;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.exception.MissingInputException;
import com.bytechef.ee.embedded.configuration.facade.AutomationWorkflowProjectFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserCodeWorkflowReferenceFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.configuration.public_.web.rest.config.EmbeddedConfigurationPublicRestSharedMocks;
import com.bytechef.ee.embedded.configuration.public_.web.rest.config.EmbeddedConfigurationPublicRestTestConfiguration;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.dto.WorkflowDTO;
import com.bytechef.platform.configuration.service.EnvironmentService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * @version ee
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = EmbeddedConfigurationPublicRestTestConfiguration.class)
@TestPropertySource(properties = "bytechef.edition=ee")
@WebMvcTest(ConnectedUserProjectWorkflowApiController.class)
@EmbeddedConfigurationPublicRestSharedMocks
public class ConnectedUserProjectWorkflowApiControllerReferenceIntTest {

    private static final String EXTERNAL_USER_ID = "ext-user-1";
    private static final String HIDDEN_WORKFLOW_UUID = "hidden-catalog-uuid";
    private static final String UNKNOWN_WORKFLOW_UUID = "no-such-uuid";
    private static final String WORKFLOW_UUID = "catalog-uuid-1";

    @Autowired
    private ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade;

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
    public void testExplicitProvisionCreatesAReference() {
        ConnectedUserProjectWorkflow reference = new ConnectedUserProjectWorkflow();

        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), any(Environment.class), eq(Map.of())))
                .thenReturn(reference);

        try {
            webTestClient
                .post()
                .uri("/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision", EXTERNAL_USER_ID,
                    WORKFLOW_UUID)
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
    public void testExplicitProvisionMissingConnectionReturns409() {
        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), any(Environment.class), eq(Map.of())))
                .thenThrow(new MissingConnectionException("slack"));

        try {
            webTestClient
                .post()
                .uri("/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision", EXTERNAL_USER_ID,
                    WORKFLOW_UUID)
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

    /**
     * The {@code {externalUserId}} admin-facing route reaches the same facade, so it must reject the same way: a
     * template hidden by the connected user's permission expression and a uuid that does not exist both leave the HTTP
     * layer as the same bodyless 404.
     */
    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testExplicitProvisionHiddenTemplateIsIndistinguishableFromUnknownUuid() {
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

    /**
     * Only the catalog visibility rejection is a 404. Provisioning also catches the deployment up and validates it, and
     * an {@link IllegalArgumentException} from there is a server-side failure that must not be reported as a missing
     * template. In this {@code @WebMvcTest} slice an unhandled exception surfaces wrapped in a
     * {@code ServletException}.
     */
    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testExplicitProvisionFailureOtherThanVisibilityIsNotReportedAsNotFound() {
        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), any(Environment.class), any()))
                .thenThrow(
                    new IllegalArgumentException(
                        "Catalog workflow " + WORKFLOW_UUID + " is not in version 2 of project id=5"));

        Throwable thrown = catchThrowable(
            () -> mockMvc.perform(
                post(
                    "/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision", EXTERNAL_USER_ID,
                    WORKFLOW_UUID)));

        assertThat(thrown).hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private void expectProvisionNotFoundWithoutBody(String workflowUuid) {
        webTestClient
            .post()
            .uri("/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision", EXTERNAL_USER_ID,
                workflowUuid)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .isEmpty();
    }

    /**
     * In the {@code @WebMvcTest} slice, {@link AccessDeniedException} is wrapped in a {@code ServletException} rather
     * than translated to 403 (no full Spring Security {@code ExceptionTranslationFilter}). The important invariant is
     * that the reference facade is NEVER invoked when the cross-user ownership check fails --
     * {@code SecurityUtils.checkCurrentUserLogin} runs before any provisioning work.
     */
    @Test
    @WithMockUser(username = "someone-else@example.com")
    public void testExplicitProvisionCrossUserIsForbidden() {
        boolean exceptionThrown = false;

        try {
            mockMvc.perform(
                post(
                    "/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision", EXTERNAL_USER_ID,
                    WORKFLOW_UUID));
        } catch (Exception exception) {
            assertThat(exception.getCause()).isInstanceOf(AccessDeniedException.class);
            exceptionThrown = true;
        }

        assertThat(exceptionThrown).isTrue();

        verify(connectedUserCodeWorkflowReferenceFacade, never())
            .getOrCreateReference(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testDeprovisionDeletesTheReference() {
        try {
            webTestClient
                .delete()
                .uri("/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision", EXTERNAL_USER_ID,
                    WORKFLOW_UUID)
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
    @WithMockUser(username = "someone-else@example.com")
    public void testDeprovisionCrossUserIsForbidden() {
        boolean exceptionThrown = false;

        try {
            mockMvc.perform(
                delete(
                    "/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision", EXTERNAL_USER_ID,
                    WORKFLOW_UUID));
        } catch (Exception exception) {
            assertThat(exception.getCause()).isInstanceOf(AccessDeniedException.class);
            exceptionThrown = true;
        }

        assertThat(exceptionThrown).isTrue();

        verify(connectedUserCodeWorkflowReferenceFacade, never())
            .deleteReference(any(), any(), any());
    }

    /**
     * The public enable/disable endpoints route to {@link ConnectedUserProjectFacade}'s String-uuid overload of
     * {@code enableProjectWorkflow}, which (after this fix) can resolve {@code workflowUuid} to one of the caller's
     * automation-bridge reference rows and surface the same {@link MissingConnectionException} the explicit provision
     * endpoint above does. This test pins that the enable endpoint maps it to the same 409 shape.
     */
    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testEnableReferenceMissingConnectionReturns409() {
        doThrow(new MissingConnectionException("slack"))
            .when(connectedUserProjectFacade)
            .enableProjectWorkflow(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), eq(true), any());

        try {
            webTestClient
                .post()
                .uri("/v1/automation/workflows/{workflowUuid}/enable", WORKFLOW_UUID)
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
    public void testEnableReferenceSucceeds() {
        doNothing()
            .when(connectedUserProjectFacade)
            .enableProjectWorkflow(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), eq(true), any());

        try {
            webTestClient
                .post()
                .uri("/v1/automation/workflows/{workflowUuid}/enable", WORKFLOW_UUID)
                .exchange()
                .expectStatus()
                .isNoContent();
        } catch (Exception exception) {
            Assertions.fail(exception);
        }

        verify(connectedUserProjectFacade)
            .enableProjectWorkflow(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), eq(true), any());
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testDisableReferenceSucceeds() {
        doNothing()
            .when(connectedUserProjectFacade)
            .enableProjectWorkflow(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), eq(false), any());

        try {
            webTestClient
                .delete()
                .uri("/v1/automation/workflows/{workflowUuid}/enable", WORKFLOW_UUID)
                .exchange()
                .expectStatus()
                .isNoContent();
        } catch (Exception exception) {
            Assertions.fail(exception);
        }

        verify(connectedUserProjectFacade)
            .enableProjectWorkflow(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), eq(false), any());
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testDisableReferenceMissingConnectionReturns409() {
        doThrow(new MissingConnectionException("slack"))
            .when(connectedUserProjectFacade)
            .enableProjectWorkflow(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), eq(false), any());

        try {
            webTestClient
                .delete()
                .uri("/v1/automation/workflows/{workflowUuid}/enable", WORKFLOW_UUID)
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
    public void testEnableReferenceMissingInputReturns409() {
        doThrow(new MissingInputException("channel"))
            .when(connectedUserProjectFacade)
            .enableProjectWorkflow(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), eq(true), any());

        try {
            webTestClient
                .post()
                .uri("/v1/automation/workflows/{workflowUuid}/enable", WORKFLOW_UUID)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.missingInputName")
                .isEqualTo("channel");
        } catch (Exception exception) {
            Assertions.fail(exception);
        }
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testListingReportsAttentionReasonFromTheFacadeDto() {
        ConnectedUserProjectWorkflow reference = new ConnectedUserProjectWorkflow();

        reference.setId(1L);
        reference.setCatalogWorkflowUuid(WORKFLOW_UUID);

        Workflow workflow = new Workflow(
            WORKFLOW_UUID, JsonUtils.write(Map.of("label", "Sync leads", "description", "d")), Workflow.Format.JSON);

        ConnectedUserProjectWorkflowDTO connectedUserProjectWorkflowDTO = ConnectedUserProjectWorkflowDTO.ofReference(
            7L, reference, new WorkflowDTO(workflow, List.of(), List.of()), List.of(), List.of(), Map.of(),
            "MISSING_CONNECTION:slack");

        when(connectedUserProjectFacade.getConnectedUserProjectWorkflows(eq(EXTERNAL_USER_ID), any()))
            .thenReturn(List.of(connectedUserProjectWorkflowDTO));

        try {
            webTestClient
                .get()
                .uri("/v1/automation/workflows")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].attentionReason")
                .isEqualTo("MISSING_CONNECTION:slack");
        } catch (Exception exception) {
            Assertions.fail(exception);
        }
    }

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testExplicitProvisionForwardsRequestedConnectionsFromTheBody() {
        when(connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), any(Environment.class), eq(Map.of("slack", 12L))))
                .thenReturn(new ConnectedUserProjectWorkflow());

        try {
            webTestClient
                .post()
                .uri("/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision", EXTERNAL_USER_ID,
                    WORKFLOW_UUID)
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
    public void testExplicitProvisionConnectionWithoutIdReturns400() {
        try {
            webTestClient
                .post()
                .uri("/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision", EXTERNAL_USER_ID,
                    WORKFLOW_UUID)
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

    @Test
    @WithMockUser(username = EXTERNAL_USER_ID)
    public void testExternalUserEnableReferenceMissingInputReturns409() {
        doThrow(new MissingInputException("channel"))
            .when(connectedUserProjectFacade)
            .enableProjectWorkflow(eq(EXTERNAL_USER_ID), eq(WORKFLOW_UUID), eq(true), any());

        try {
            webTestClient
                .post()
                .uri("/v1/{externalUserId}/automation/workflows/{workflowUuid}/enable", EXTERNAL_USER_ID, WORKFLOW_UUID)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.missingInputName")
                .isEqualTo("channel");
        } catch (Exception exception) {
            Assertions.fail(exception);
        }
    }
}
