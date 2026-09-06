/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agui.server.LocalAgent;
import com.agui.server.spring.AgUiParameters;
import com.agui.server.spring.AgUiService;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.ai.copilot.web.rest.facade.CopilotChatFacade;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pins that the endpoint reaches the agent through the facade layer, which is where this codebase puts authorization.
 *
 * <p>
 * The controller carries no gate and no agent dispatch of its own and is not meant to:
 * {@code testControllerHoldsNoRunOrAuthorizationCollaborators} is what makes a revert to a locally written check or a
 * direct {@code AgUiService} call fail here rather than pass.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = CopilotApiController.class)
@TestPropertySource(properties = {
    "bytechef.coordinator.enabled=true",
    "bytechef.edition=ee",
    "bytechef.ai.copilot.enabled=true"
})
@WebMvcTest(CopilotApiController.class)
class CopilotApiControllerIntTest {

    @MockitoBean
    private CopilotChatFacade copilotChatFacade;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testChatDelegatesToTheGuardedFacade() throws Exception {
        when(copilotChatFacade.chat(eq("workflow_editor"), any(AgUiParameters.class)))
            .thenReturn(new SseEmitter());

        mockMvc
            .perform(
                post("/internal/ai/chat/{agentId}", "workflow_editor")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"threadId\":\"thread-1\",\"state\":{\"state\":{}}}")
                    .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk());

        verify(copilotChatFacade).chat(eq("workflow_editor"), any(AgUiParameters.class));
    }

    @Test
    void testControllerHoldsNoRunOrAuthorizationCollaborators() {
        assertThat(Arrays.stream(CopilotApiController.class.getDeclaredFields())
            .map(Field::getType))
                .as("authorization and agent dispatch belong on the facade, not on the controller")
                .doesNotContain(
                    PermissionService.class, ProjectWorkflowService.class, AgUiService.class, LocalAgent.class);
    }
}
