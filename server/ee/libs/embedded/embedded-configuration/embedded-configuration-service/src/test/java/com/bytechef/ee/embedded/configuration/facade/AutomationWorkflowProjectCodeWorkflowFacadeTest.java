/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import static org.mockito.Mockito.mock;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.ee.automation.configuration.service.ProjectCodeWorkflowService;
import com.bytechef.ee.platform.codeworkflow.configuration.domain.CodeWorkflowContainer;
import com.bytechef.ee.platform.codeworkflow.configuration.domain.CodeWorkflowContainer.Language;
import com.bytechef.ee.platform.codeworkflow.configuration.facade.CodeWorkflowContainerFacade;
import com.bytechef.platform.constant.PlatformType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

/**
 * Verifies {@link AutomationWorkflowProjectCodeWorkflowFacadeImpl#save}: the first deploy of a code workflow project
 * must resolve/create its catalog {@link Project} through {@link AutomationWorkflowProjectFacade}'s marker convention,
 * rather than through {@link AutomationWorkflowProjectFacade#createProject} being called a second time or a bare
 * {@link ProjectService} lookup.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class AutomationWorkflowProjectCodeWorkflowFacadeTest {

    @Mock
    private AutomationWorkflowProjectFacade automationWorkflowProjectFacade;

    @Mock
    private CodeWorkflowContainerFacade codeWorkflowContainerFacade;

    @Mock
    private ProjectCodeWorkflowService projectCodeWorkflowService;

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectWorkflowService projectWorkflowService;

    private AutomationWorkflowProjectCodeWorkflowFacadeImpl facade;

    @BeforeEach
    void setUp() {
        ApplicationProperties applicationProperties = new ApplicationProperties();

        facade = new AutomationWorkflowProjectCodeWorkflowFacadeImpl(
            applicationProperties, mock(CacheManager.class), automationWorkflowProjectFacade,
            codeWorkflowContainerFacade, projectCodeWorkflowService, projectService, projectWorkflowService);
    }

    @Test
    void testFirstDeployCreatesTheCatalogProject() {
        Mockito.when(automationWorkflowProjectFacade.fetchProjectIdByName("acme-billing"))
            .thenReturn(Optional.empty());
        Mockito.when(automationWorkflowProjectFacade.createProject(
            Mockito.eq("acme-billing"), Mockito.any(), Mockito.isNull(), Mockito.eq(List.of()), Mockito.isNull()))
            .thenReturn(100L);

        Project project = new Project();

        project.setId(100L);

        Mockito.when(projectService.getProject(100L))
            .thenReturn(project);

        CodeWorkflowContainer container = codeWorkflowContainer(Map.of("charge", "wf-1"));

        Mockito.when(codeWorkflowContainerFacade.create(
            Mockito.eq("acme-billing"), Mockito.any(), Mockito.any(), Mockito.eq(Language.JAVASCRIPT),
            Mockito.any(), Mockito.eq(PlatformType.AUTOMATION)))
            .thenReturn(container);

        facade.save(fakeProjectDefinitionBytes("acme-billing"), Language.JAVASCRIPT);

        Mockito.verify(projectWorkflowService)
            .addWorkflow(100L, project.getLastProjectVersion(), "wf-1");
        Mockito.verify(projectService)
            .publishProject(100L, null, false);

        // Publish must go straight through ProjectService, exactly like the plain automation code-workflow deploy
        // path -- NOT through AutomationWorkflowProjectFacade#publishProject, which additionally duplicates workflow
        // rows for the visual-editor versioning story that code workflows don't need.
        Mockito.verify(automationWorkflowProjectFacade, Mockito.never())
            .publishProject(Mockito.anyLong());
    }

    private static CodeWorkflowContainer codeWorkflowContainer(Map<String, String> workflowNameIds) {
        CodeWorkflowContainer codeWorkflowContainer = mock(CodeWorkflowContainer.class);

        Mockito.when(codeWorkflowContainer.getWorkflowNameIds())
            .thenReturn(workflowNameIds);

        return codeWorkflowContainer;
    }

    private static byte[] fakeProjectDefinitionBytes(String name) {
        String source = """
            ({
                name: "%s",
                version: "1",
                description: "A code workflow.",
                workflows: [
                    {
                        name: "charge",
                        label: "Charge",
                        tasks: [
                            {
                                name: "my-task",
                                label: "My Task",
                                perform: function () {
                                    return "hello";
                                }
                            }
                        ]
                    }
                ]
            })
            """.formatted(name);

        return source.getBytes(StandardCharsets.UTF_8);
    }
}
