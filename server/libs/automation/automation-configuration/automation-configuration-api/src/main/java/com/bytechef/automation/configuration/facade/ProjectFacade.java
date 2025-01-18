/*
 * Copyright 2023-present ByteChef Inc.
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

package com.bytechef.automation.configuration.facade;

import com.bytechef.automation.configuration.domain.ProjectVersion.Status;
import com.bytechef.automation.configuration.dto.ProjectDTO;
import com.bytechef.automation.configuration.dto.ProjectWorkflowDTO;
import com.bytechef.platform.category.domain.Category;
import com.bytechef.platform.tag.domain.Tag;
import java.util.List;
import org.springframework.lang.NonNull;

/**
 * @author Ivica Cardic
 */
public interface ProjectFacade {

    long addWorkflow(long id, @NonNull String definition);

    long createProject(@NonNull ProjectDTO projectDTO);

    void deleteProject(long id);

    void deleteWorkflow(@NonNull String workflowId);

    ProjectDTO duplicateProject(long id);

    String duplicateWorkflow(long id, @NonNull String workflowId);

    ProjectDTO getProject(long id);

    List<Category> getProjectCategories();

    List<Tag> getProjectTags();

    ProjectWorkflowDTO getProjectWorkflow(String workflowId);

    ProjectWorkflowDTO getProjectWorkflow(long projectWorkflowId);

    List<ProjectWorkflowDTO> getProjectWorkflows();

    List<ProjectWorkflowDTO> getProjectWorkflows(long id);

    List<ProjectWorkflowDTO> getProjectVersionWorkflows(long id, int projectVersion, boolean includeAllFields);

    List<ProjectDTO> getProjects(Long categoryId, boolean projectDeployments, Long tagId, Status status);

    List<ProjectDTO> getWorkspaceProjects(
        long workspaceId, Long categoryId, boolean projectDeployments, Long tagId, Status status,
        boolean includeAllFields);

    void publishProject(long id, String description);

    void updateProject(@NonNull ProjectDTO projectDTO);

    void updateProjectTags(long id, @NonNull List<Tag> tags);

    void updateWorkflow(String workflowId, String definition, int version);
}
