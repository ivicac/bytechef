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

package com.bytechef.automation.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.dto.ProjectDeploymentDTO;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class PromoteWorkflowToolCallbackTest {

    private final JsonMapper jsonMapper = new JsonMapper();
    private final ProjectDeploymentFacade projectDeploymentFacade = mock(ProjectDeploymentFacade.class);
    private final PromoteWorkflowToolCallback toolCallback = new PromoteWorkflowToolCallback(projectDeploymentFacade);

    @Test
    void testDescriptionPointsToDeployProjectForAppDeployments() {
        assertThat(toolCallback.getToolDefinition()
            .description()).contains("deployProject");
    }

    @Test
    void testPromoteCreatesDeploymentInTargetEnvironmentAndEnablesIt() throws Exception {
        when(projectDeploymentFacade.createProjectDeployment(any(ProjectDeploymentDTO.class))).thenReturn(99L);

        String result = toolCallback.call(
            "{\"projectId\":\"42\",\"projectVersion\":3,\"targetEnvironment\":\"production\",\"enable\":true}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.get("projectDeploymentId")
            .asLong()).isEqualTo(99L);
        assertThat(node.get("environment")
            .asText()).isEqualTo("PRODUCTION");
        assertThat(node.get("enabled")
            .asBoolean()).isTrue();

        ArgumentCaptor<ProjectDeploymentDTO> projectDeploymentArgumentCaptor =
            ArgumentCaptor.forClass(ProjectDeploymentDTO.class);

        verify(projectDeploymentFacade).createProjectDeployment(projectDeploymentArgumentCaptor.capture());
        verify(projectDeploymentFacade).enableProjectDeployment(99L, true);

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentArgumentCaptor.getValue();

        assertThat(projectDeploymentDTO.projectId()).isEqualTo(42L);
        assertThat(projectDeploymentDTO.projectVersion()).isEqualTo(3);
        assertThat(projectDeploymentDTO.environment()).isEqualTo(Environment.PRODUCTION);
        assertThat(projectDeploymentDTO.enabled()).isFalse();
    }

    @Test
    void testPromoteLeavesDeploymentDisabledByDefault() throws Exception {
        when(projectDeploymentFacade.createProjectDeployment(any(ProjectDeploymentDTO.class))).thenReturn(99L);

        String result =
            toolCallback.call("{\"projectId\":\"42\",\"projectVersion\":3,\"targetEnvironment\":\"STAGING\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.get("enabled")
            .asBoolean()).isFalse();

        verify(projectDeploymentFacade, never()).enableProjectDeployment(anyLong(), anyBoolean());
    }

    @Test
    void testRejectsUnknownTargetEnvironment() throws Exception {
        String result = toolCallback.call("{\"projectId\":\"42\",\"projectVersion\":3,\"targetEnvironment\":\"QA\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.get("error")
            .asText()).contains("Unknown targetEnvironment 'QA'");

        verify(projectDeploymentFacade, never()).createProjectDeployment(any(ProjectDeploymentDTO.class));
    }
}
