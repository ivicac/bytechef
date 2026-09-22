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

import com.bytechef.automation.ai.tool.model.ProjectPublishInfo;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.facade.ProjectFacade;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectService;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class ProjectToolsTest {

    private final ProjectFacade projectFacade = mock(ProjectFacade.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectTools projectTools = new ProjectTools(
        projectFacade, projectService, mock(ProjectDeploymentService.class));

    /**
     * The facade duplicates every workflow of the published version into the fresh draft; the service alone does not,
     * which would leave the draft without workflows and break every agent in the project.
     */
    @Test
    void testPublishProjectDelegatesToProjectFacade() {
        Project project = mock(Project.class);

        when(project.getId()).thenReturn(7L);
        when(project.getName()).thenReturn("Support");
        when(projectFacade.publishProject(7L, "First release", false)).thenReturn(2);
        when(projectService.getProject(7L)).thenReturn(project);

        ProjectPublishInfo projectPublishInfo = projectTools.publishProject(7L, "First release");

        verify(projectFacade).publishProject(7L, "First release", false);
        verify(projectService, never()).publishProject(anyLong(), any(), anyBoolean());

        assertThat(projectPublishInfo.id()).isEqualTo(7L);
        assertThat(projectPublishInfo.publishedVersion()).isEqualTo(2);
    }

    /**
     * The facade runs the project delete listeners (which remove the project's agents) before deleting the project; the
     * service alone does not.
     */
    @Test
    void testDeleteProjectDelegatesToProjectFacade() {
        Project project = mock(Project.class);

        when(project.getName()).thenReturn("Support");
        when(projectService.getProject(7L)).thenReturn(project);

        String result = projectTools.deleteProject(7L);

        verify(projectFacade).deleteProject(7L);
        verify(projectService, never()).delete(anyLong());

        assertThat(result).contains("Support");
    }
}
