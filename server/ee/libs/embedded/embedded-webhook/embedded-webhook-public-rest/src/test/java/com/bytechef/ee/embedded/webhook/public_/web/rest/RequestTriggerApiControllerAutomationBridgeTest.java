/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.webhook.public_.web.rest;

import static org.mockito.Mockito.mock;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstance;
import com.bytechef.ee.embedded.configuration.facade.AutomationWorkflowProjectFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserCodeWorkflowReferenceFacade;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.ee.embedded.configuration.service.IntegrationWorkflowService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.file.storage.token.FileEntryTokens;
import com.bytechef.platform.component.domain.WebhookTriggerFlags;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.file.storage.TempFileStorage;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutor;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
class RequestTriggerApiControllerAutomationBridgeTest {

    @Mock
    private AutomationWorkflowProjectFacade automationWorkflowProjectFacade;

    @Mock
    private ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade;

    @Mock
    private ConnectedUserService connectedUserService;

    @Mock
    private IntegrationInstanceService integrationInstanceService;

    @Mock
    private IntegrationWorkflowService integrationWorkflowService;

    @Mock
    private ProjectWorkflowService projectWorkflowService;

    @Mock
    private WebhookWorkflowExecutor webhookWorkflowExecutor;

    @Mock
    private WorkflowService workflowService;

    @Test
    void testIntegrationWorkflowResolutionIsUnchangedWhenOneExists() {
        RequestTriggerApiController controller = controller();

        Mockito.when(integrationWorkflowService.fetchLastWorkflowId(Mockito.eq("uuid-1"), Mockito.any()))
            .thenReturn(Optional.of("integration-wf-1"));

        // Existing behavior must run through unmodified: connectedUserCodeWorkflowReferenceFacade must never be
        // consulted once an integration workflow is found.
        ConnectedUser connectedUser = new ConnectedUser(Map.of(), "user-1@example.com", true, "ext-1", 1L, "User 1", 0);

        IntegrationInstance integrationInstance = new IntegrationInstance();

        integrationInstance.setId(2L);

        Mockito.when(connectedUserService.getConnectedUser(Mockito.anyString(), Mockito.any()))
            .thenReturn(connectedUser);
        Mockito.when(integrationInstanceService.getIntegrationInstance(1L, "integration-wf-1", Environment.PRODUCTION))
            .thenReturn(integrationInstance);
        Mockito.when(workflowService.getWorkflow("integration-wf-1"))
            .thenReturn(new Workflow(
                "{\"label\":\"Integration Workflow\",\"triggers\":[{\"name\":\"trigger_1\",\"type\":\"request/v1\"}],"
                    + "\"tasks\":[]}",
                Workflow.Format.JSON));

        try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::fetchCurrentUserLogin)
                .thenReturn(Optional.of("user-1"));

            controller.executeWorkflow("uuid-1", null);
        }

        Mockito.verifyNoInteractions(connectedUserCodeWorkflowReferenceFacade);
    }

    @Test
    void testUnknownWorkflowFallsThroughToTheAutomationBridge() {
        RequestTriggerApiController controller = controller();

        Mockito.when(integrationWorkflowService.fetchLastWorkflowId(Mockito.eq("uuid-2"), Mockito.any()))
            .thenReturn(Optional.empty());
        Mockito.when(automationWorkflowProjectFacade.getPublishedProjects())
            .thenReturn(List.of());

        try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::fetchCurrentUserLogin)
                .thenReturn(Optional.of("user-1"));

            ResponseEntity<Object> responseEntity = controller.executeWorkflow("uuid-2", null);

            Assertions.assertEquals(HttpStatus.NOT_FOUND, responseEntity.getStatusCode());
        }
    }

    private RequestTriggerApiController controller() {
        // EnvironmentService.getEnvironment(String) is a default method; delegate to the real implementation so a
        // null xEnvironment resolves to Environment.PRODUCTION as it does in production, instead of Mockito's
        // ordinary null default answer.
        EnvironmentService environmentService = mock(EnvironmentService.class, Mockito.CALLS_REAL_METHODS);

        // Bare-minimum stubs so a request that does reach doProcessTrigger (the integration-workflow test) does not
        // NPE while building the WebhookRequest. Lenient because the automation-bridge test never reaches dispatch.
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);

        Mockito.lenient()
            .when(httpServletRequest.getHeaderNames())
            .thenReturn(Collections.enumeration(List.of()));
        Mockito.lenient()
            .when(httpServletRequest.getParameterMap())
            .thenReturn(Map.of());
        Mockito.lenient()
            .when(httpServletRequest.getMethod())
            .thenReturn("GET");

        Mockito.lenient()
            .when(webhookWorkflowExecutor.getWebhookTriggerFlags(Mockito.any()))
            .thenReturn(new WebhookTriggerFlags(false, false, false, false));

        return new RequestTriggerApiController(
            new ApplicationProperties(), automationWorkflowProjectFacade, connectedUserCodeWorkflowReferenceFacade,
            connectedUserService, environmentService, mock(FileEntryTokens.class),
            httpServletRequest, mock(HttpServletResponse.class), mock(TempFileStorage.class),
            webhookWorkflowExecutor, integrationInstanceService, integrationWorkflowService, projectWorkflowService,
            workflowService);
    }
}
