/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.configuration.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ClusterElementDefinition.ClusterElementType;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.platform.component.domain.ClusterElementDefinition;
import com.bytechef.platform.configuration.dto.ClusterElementOutputDTO;
import com.bytechef.platform.configuration.facade.WorkflowNodeOutputFacade;
import com.bytechef.platform.configuration.web.rest.config.PlatformConfigurationRestTestConfiguration;
import com.bytechef.platform.configuration.web.rest.config.WorkflowConfigurationRestTestConfigurationSharedMocks;
import com.bytechef.platform.security.web.authentication.AbstractApiKeyAuthenticationToken;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

/**
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = PlatformConfigurationRestTestConfiguration.class)
@WebMvcTest(WorkflowNodeOutputApiController.class)
@WorkflowConfigurationRestTestConfigurationSharedMocks
class WorkflowNodeOutputApiControllerIntTest {

    private static final long DEVELOPMENT_ORDINAL = 0L;
    private static final long ENVIRONMENT_ID = 1L;
    private static final long PRODUCTION_ORDINAL = 2L;
    private static final String WORKFLOW_ID = "workflow1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowNodeOutputApiController workflowNodeOutputApiController;

    @Autowired
    private WorkflowNodeOutputFacade workflowNodeOutputFacade;

    private WebTestClient webTestClient;

    @BeforeEach
    void beforeEach() {
        webTestClient = MockMvcWebTestClient.bindTo(mockMvc)
            .build();
    }

    @AfterEach
    void afterEach() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testGetClusterElementOutputPassesTestOutputResponseThrough() {
        stubClusterElementOutput(true);

        getClusterElementOutput()
            .jsonPath("$.testOutputResponse")
            .isEqualTo(true)
            .jsonPath("$.workflowNodeName")
            .isEqualTo("hubspot_1")
            .jsonPath("$.clusterElementDefinition.name")
            .isEqualTo("createContact");
    }

    @Test
    void testGetClusterElementOutputPassesMissingTestOutputResponseThrough() {
        stubClusterElementOutput(false);

        getClusterElementOutput()
            .jsonPath("$.testOutputResponse")
            .isEqualTo(false)
            .jsonPath("$.workflowNodeName")
            .isEqualTo("hubspot_1")
            .jsonPath("$.clusterElementDefinition.name")
            .isEqualTo("createContact");
    }

    @Test
    void testGetClusterElementOutputReturnsNotFoundWhenThereIsNoOutput() {
        webTestClient
            .get()
            .uri(
                "/internal/workflows/{id}/workflow-nodes/{workflowNodeName}/cluster-elements/{clusterElementType}" +
                    "/{clusterElementWorkflowNodeName}/outputs?environmentId={environmentId}",
                WORKFLOW_ID, "aiAgent_1", "tools", "hubspot_1", ENVIRONMENT_ID)
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isNotFound();
    }

    @Test
    void testGetPreviousWorkflowNodeOutputsUsesConfinedPrincipalEnvironmentForBothEvictionAndRead() {
        authenticate(new TestApiKeyAuthenticationToken(PRODUCTION_ORDINAL, user()));

        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(anyString(), anyString(), anyLong()))
            .thenReturn(List.of());

        workflowNodeOutputApiController.getPreviousWorkflowNodeOutputs("workflow-1", DEVELOPMENT_ORDINAL, "node-1");

        assertEvictionAndReadEnvironmentId(PRODUCTION_ORDINAL);
    }

    @Test
    void testGetPreviousWorkflowNodeOutputsHonoursSessionPrincipalRequestedEnvironmentForBoth() {
        authenticate(new UsernamePasswordAuthenticationToken("admin@localhost.com", "n/a", List.of()));

        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(anyString(), anyString(), anyLong()))
            .thenReturn(List.of());

        workflowNodeOutputApiController.getPreviousWorkflowNodeOutputs("workflow-1", DEVELOPMENT_ORDINAL, "node-1");

        assertEvictionAndReadEnvironmentId(DEVELOPMENT_ORDINAL);
    }

    private void assertEvictionAndReadEnvironmentId(long expectedEnvironmentId) {
        ArgumentCaptor<Long> evictionEnvironmentIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> readEnvironmentIdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(workflowNodeOutputFacade).checkWorkflowCache(
            eq("workflow-1"), eq("node-1"), evictionEnvironmentIdCaptor.capture());
        verify(workflowNodeOutputFacade).getPreviousWorkflowNodeOutputs(
            eq("workflow-1"), eq("node-1"), readEnvironmentIdCaptor.capture());

        assertThat(evictionEnvironmentIdCaptor.getValue()).isEqualTo(expectedEnvironmentId);
        assertThat(readEnvironmentIdCaptor.getValue()).isEqualTo(expectedEnvironmentId);
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext()
            .setAuthentication(authentication);
    }

    private static User user() {
        return new User("connected-user-1", "", List.of());
    }

    private WebTestClient.BodyContentSpec getClusterElementOutput() {
        return webTestClient
            .get()
            .uri(
                "/internal/workflows/{id}/workflow-nodes/{workflowNodeName}/cluster-elements/{clusterElementType}" +
                    "/{clusterElementWorkflowNodeName}/outputs?environmentId={environmentId}",
                WORKFLOW_ID, "aiAgent_1", "tools", "hubspot_1", ENVIRONMENT_ID)
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody();
    }

    private void stubClusterElementOutput(boolean testOutputResponse) {
        ClusterElementDefinition clusterElementDefinition = new ClusterElementDefinition(
            ComponentDsl.<Object>clusterElement("createContact")
                .title("Create Contact")
                .type(new ClusterElementType("TOOLS", "tools", "Tools"))
                .object(Object::new),
            "hubspot", 1, null);

        ClusterElementOutputDTO clusterElementOutputDTO = new ClusterElementOutputDTO(
            clusterElementDefinition, null, null, Map.of("id", "contact-42"), false, testOutputResponse, "hubspot_1");

        when(workflowNodeOutputFacade.getClusterElementOutput(
            WORKFLOW_ID, "aiAgent_1", "tools", "hubspot_1", ENVIRONMENT_ID)).thenReturn(clusterElementOutputDTO);
    }

    private static final class TestApiKeyAuthenticationToken extends AbstractApiKeyAuthenticationToken {

        private TestApiKeyAuthenticationToken(long environmentId, User user) {
            super(environmentId, user);
        }
    }
}
