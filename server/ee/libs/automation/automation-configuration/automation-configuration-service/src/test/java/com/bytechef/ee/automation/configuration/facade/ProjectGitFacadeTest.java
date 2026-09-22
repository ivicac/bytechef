/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.facade;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.repository.git.GitWorkflowRepository.GitWorkflows;
import com.bytechef.atlas.configuration.repository.git.operations.GitWorkflowOperations.GitInfo;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.domain.ProjectWorkflowType;
import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.facade.ProjectFacade;
import com.bytechef.automation.configuration.facade.ProjectWorkflowFacade;
import com.bytechef.automation.configuration.listener.ProjectContentContributor;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.automation.configuration.domain.ProjectGitConfiguration;
import com.bytechef.ee.automation.configuration.service.ProjectGitConfigurationService;
import com.bytechef.ee.automation.configuration.service.ProjectGitService;
import com.bytechef.ee.automation.configuration.service.WorkspaceService;
import com.bytechef.ee.platform.configuration.dto.GitConfigurationDTO;
import com.bytechef.ee.platform.configuration.facade.GitConfigurationFacade;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * @author Ivica Cardic
 * @version ee
 */
class ProjectGitFacadeTest {

    private static final String AGENTS_DIRECTORY = "agents/";
    private static final String BRANCH = "main";
    private static final long PROJECT_ID = 1L;
    private static final int PROJECT_VERSION = 1;
    private static final long WORKSPACE_ID = 7L;

    private final GitConfigurationFacade gitConfigurationFacade = mock(GitConfigurationFacade.class);
    private final ProjectContentContributor projectContentContributor = mock(ProjectContentContributor.class);
    private final ProjectFacade projectFacade = mock(ProjectFacade.class);
    private final ProjectGitConfigurationService projectGitConfigurationService =
        mock(ProjectGitConfigurationService.class);
    private final ProjectGitService projectGitService = mock(ProjectGitService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectWorkflowFacade projectWorkflowFacade = mock(ProjectWorkflowFacade.class);
    private final ProjectWorkflowService projectWorkflowService = mock(ProjectWorkflowService.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);

    private ProjectGitFacadeImpl projectGitFacade;
    private Workflow ordinaryWorkflow;

    @BeforeEach
    void beforeEach() {
        Project project = mock(Project.class);

        when(project.getLastProjectVersion()).thenReturn(PROJECT_VERSION);
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);

        Workspace workspace = mock(Workspace.class);

        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getProjectWorkspace(PROJECT_ID)).thenReturn(workspace);

        when(gitConfigurationFacade.getGitConfiguration(WORKSPACE_ID))
            .thenReturn(new GitConfigurationDTO("https://git.example.com/repo.git", "user", "secret"));

        ProjectGitConfiguration projectGitConfiguration = mock(ProjectGitConfiguration.class);

        when(projectGitConfiguration.getBranch()).thenReturn(BRANCH);
        when(projectGitConfigurationService.getProjectGitConfiguration(PROJECT_ID))
            .thenReturn(projectGitConfiguration);

        when(projectWorkflowService.getProjectWorkflows(PROJECT_ID, PROJECT_VERSION))
            .thenReturn(
                List.of(
                    new ProjectWorkflow(PROJECT_ID, PROJECT_VERSION, "ordinary-workflow", ProjectWorkflowType.WORKFLOW),
                    new ProjectWorkflow(PROJECT_ID, PROJECT_VERSION, "agent-workflow", ProjectWorkflowType.AI_AGENT)));

        ordinaryWorkflow = workflow("ordinary-workflow", "Ordinary");

        Workflow agentWorkflow = workflow("agent-workflow", "Agent");

        when(workflowService.getWorkflow("ordinary-workflow")).thenReturn(ordinaryWorkflow);
        when(workflowService.getWorkflow("agent-workflow")).thenReturn(agentWorkflow);

        when(projectContentContributor.getContentDirectory()).thenReturn(AGENTS_DIRECTORY);

        projectGitFacade = new ProjectGitFacadeImpl(
            gitConfigurationFacade, projectFacade, projectGitConfigurationService, projectGitService, projectService,
            projectWorkflowFacade, projectWorkflowService, workflowService, workspaceService,
            List.of(projectContentContributor));
    }

    /**
     * The agents' generated workflows never go to the repository as ordinary workflows; the agents travel as their own
     * files, written by the contributor, next to the workflow files.
     */
    @Test
    void testPushProjectToGitWritesAgentFilesButSkipsAgentWorkflows() {
        Map<String, byte[]> agentFiles = Map.of("agents/support-bot.json", bytes("{\"title\":\"Support Bot\"}"));

        when(projectContentContributor.exportProjectContent(PROJECT_ID)).thenReturn(agentFiles);

        projectGitFacade.pushProjectToGit(PROJECT_ID, "commit");

        verify(projectGitService).save(
            eq(List.of(ordinaryWorkflow)), eq(agentFiles), eq(List.of(AGENTS_DIRECTORY)), eq("commit"), anyString(),
            eq(BRANCH), anyString(), anyString());
        verify(workflowService, never()).getWorkflow("agent-workflow");
    }

    @Test
    void testPullProjectFromGitNeverUpdatesAnAgentWorkflowWithTheSameLabel() {
        Workflow pulledWorkflow = mock(Workflow.class);

        when(pulledWorkflow.getLabel()).thenReturn("Agent");
        when(pulledWorkflow.getDefinition()).thenReturn("{\"label\":\"Agent\",\"tasks\":[]}");
        when(projectGitService.getWorkflows(anyString(), eq(BRANCH), anyString(), anyString(), any()))
            .thenReturn(new GitWorkflows(List.of(pulledWorkflow), new GitInfo("abc123", "message")));

        projectGitFacade.pullProjectFromGit(PROJECT_ID);

        verify(projectWorkflowFacade, never()).updateWorkflow(anyString(), anyString(), anyInt());
        verify(projectWorkflowFacade).addWorkflow(PROJECT_ID, "{\"label\":\"Agent\",\"tasks\":[]}");
        verify(workflowService, never()).getWorkflow("agent-workflow");
        verify(projectContentContributor, never()).pullProjectContent(anyLong(), anyMap());
        verify(projectFacade).publishProject(anyLong(), any(), anyBoolean());
    }

    /**
     * The contributor gets only the files under its own directory, and applies them before the publish, which validates
     * the project's agents.
     */
    @Test
    void testPullProjectFromGitHandsAgentFilesToTheContributorBeforePublishing() {
        byte[] agentFile = bytes("{\"title\":\"Support Bot\"}");

        when(projectGitService.getWorkflows(anyString(), eq(BRANCH), anyString(), anyString(),
            eq(List.of(AGENTS_DIRECTORY))))
                .thenReturn(
                    new GitWorkflows(
                        List.of(), new GitInfo("abc123", "message"),
                        Map.of("agents/support-bot.json", agentFile, "other/readme.json", bytes("{}"))));

        projectGitFacade.pullProjectFromGit(PROJECT_ID);

        InOrder inOrder = inOrder(projectContentContributor, projectFacade);

        inOrder.verify(projectContentContributor)
            .pullProjectContent(PROJECT_ID, Map.of("agents/support-bot.json", agentFile));
        inOrder.verify(projectFacade)
            .publishProject(eq(PROJECT_ID), any(), eq(false));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Workflow workflow(String id, String label) {
        Workflow workflow = mock(Workflow.class);

        when(workflow.getId()).thenReturn(id);
        when(workflow.getLabel()).thenReturn(label);

        return workflow;
    }
}
