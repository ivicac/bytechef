/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProject;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflowConnection;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowConnectionRepository;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowRepository;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
class ConnectedUserCodeWorkflowReferenceFacadeTest {

    @Mock
    private ConnectedUserProjectWorkflowConnectionRepository connectedUserProjectWorkflowConnectionRepository;

    @Mock
    private ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;

    @Mock
    private ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager;

    @Mock
    private ConnectedUserWorkflowConnectionResolver connectedUserWorkflowConnectionResolver;

    @Mock
    private ProjectDeploymentFacade projectDeploymentFacade;

    @Mock
    private ProjectDeploymentService projectDeploymentService;

    @Mock
    private ProjectWorkflowService projectWorkflowService;

    @Mock
    private WorkflowService workflowService;

    private ConnectedUserCodeWorkflowReferenceFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new ConnectedUserCodeWorkflowReferenceFacadeImpl(
            connectedUserProjectWorkflowConnectionRepository, connectedUserProjectWorkflowRepository,
            connectedUserProjectWorkflowManager, connectedUserWorkflowConnectionResolver, projectDeploymentFacade,
            projectDeploymentService, projectWorkflowService, workflowService);
    }

    /**
     * This is the test that keeps two connected users' connections from leaking into each other, which is the entire
     * reason {@code WorkflowTestConfiguration} couldn't be reused for reference mode: each user's
     * {@link ConnectedUserCodeWorkflowReferenceFacadeImpl#getOrCreateReference} call must persist its OWN
     * {@link ConnectedUserProjectWorkflowConnection} rows, never sharing or overwriting the other user's wiring.
     */
    @Test
    void testTwoUsersReferencingTheSameCatalogWorkflowGetIndependentConnectionRows() {
        ProjectWorkflow catalogProjectWorkflow = new ProjectWorkflow(500L, 1, "catalog-wf-1");

        Mockito.when(projectWorkflowService.getLastPublishedWorkflowId("catalog-uuid"))
            .thenReturn("catalog-wf-1");
        Mockito.when(projectWorkflowService.getWorkflowProjectWorkflow("catalog-wf-1"))
            .thenReturn(catalogProjectWorkflow);

        Workflow workflow = new Workflow(
            "{\"triggers\":[],\"tasks\":[{\"name\":\"t1\",\"type\":\"slack/v1/postMessage\"}]}", Workflow.Format.JSON);

        Mockito.when(workflowService.getWorkflow("catalog-wf-1"))
            .thenReturn(workflow);

        ConnectedUserProject userAProject = new ConnectedUserProject();

        userAProject.setId(10L);

        ConnectedUserProject userBProject = new ConnectedUserProject();

        userBProject.setId(20L);

        Mockito.when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(
            Mockito.eq("userA"), Mockito.any()))
            .thenReturn(userAProject);
        Mockito.when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(
            Mockito.eq("userB"), Mockito.any()))
            .thenReturn(userBProject);

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(Mockito.anyLong(), Mockito.eq("catalog-uuid")))
            .thenReturn(Optional.empty());
        Mockito.when(connectedUserProjectWorkflowRepository.save(Mockito.any()))
            .thenAnswer(withGeneratedId());

        Mockito.when(connectedUserWorkflowConnectionResolver.resolve(workflow.getDefinition()))
            .thenReturn(Map.of("t1", 1L))
            .thenReturn(Map.of("t1", 2L));

        Mockito.when(projectDeploymentService.fetchProjectDeploymentByName(Mockito.eq(500L), Mockito.anyString()))
            .thenReturn(Optional.empty());
        Mockito.when(projectDeploymentFacade.createProjectDeployment(
            Mockito.any(), Mockito.eq("catalog-wf-1"), Mockito.anyList()))
            .thenReturn(900L, 901L);

        facade.getOrCreateReference("userA", "catalog-uuid", Environment.PRODUCTION);
        facade.getOrCreateReference("userB", "catalog-uuid", Environment.PRODUCTION);

        ArgumentCaptor<ConnectedUserProjectWorkflowConnection> captor =
            ArgumentCaptor.forClass(ConnectedUserProjectWorkflowConnection.class);

        Mockito.verify(connectedUserProjectWorkflowConnectionRepository, Mockito.times(2))
            .save(captor.capture());

        List<Long> wiredConnectionIds = captor.getAllValues()
            .stream()
            .map(ConnectedUserProjectWorkflowConnection::getConnectionId)
            .toList();

        Assertions.assertEquals(List.of(1L, 2L), wiredConnectionIds);
    }

    @Test
    void testGetOrCreateReferenceReturnsExistingRowWithoutReprovisioning() {
        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(10L);

        Mockito.when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(
            Mockito.eq("userA"), Mockito.any()))
            .thenReturn(connectedUserProject);

        ConnectedUserProjectWorkflow existingReference = new ConnectedUserProjectWorkflow();

        existingReference.setId(1L);

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(10L, "catalog-uuid"))
            .thenReturn(Optional.of(existingReference));

        ConnectedUserProjectWorkflow result = facade.getOrCreateReference(
            "userA", "catalog-uuid", Environment.PRODUCTION);

        Assertions.assertSame(existingReference, result);
        Mockito.verifyNoInteractions(
            projectWorkflowService, workflowService, connectedUserWorkflowConnectionResolver, projectDeploymentFacade,
            projectDeploymentService);
    }

    /**
     * A component with no matching connection for the connected user must not abort provisioning outright: the
     * reference row is still created -- left {@code enabled = false} -- and {@link MissingConnectionException} is
     * rethrown afterward so the caller can surface which connection is missing and let the connected user fix it.
     */
    @Test
    void testMissingConnectionStillCreatesDisabledReferenceAndRethrows() {
        ProjectWorkflow catalogProjectWorkflow = new ProjectWorkflow(500L, 1, "catalog-wf-1");

        Mockito.when(projectWorkflowService.getLastPublishedWorkflowId("catalog-uuid"))
            .thenReturn("catalog-wf-1");
        Mockito.when(projectWorkflowService.getWorkflowProjectWorkflow("catalog-wf-1"))
            .thenReturn(catalogProjectWorkflow);

        Workflow workflow = new Workflow(
            "{\"triggers\":[],\"tasks\":[{\"name\":\"t1\",\"type\":\"slack/v1/postMessage\"}]}", Workflow.Format.JSON);

        Mockito.when(workflowService.getWorkflow("catalog-wf-1"))
            .thenReturn(workflow);

        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(10L);

        Mockito.when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(
            Mockito.eq("userA"), Mockito.any()))
            .thenReturn(connectedUserProject);

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(10L, "catalog-uuid"))
            .thenReturn(Optional.empty());
        Mockito.when(connectedUserProjectWorkflowRepository.save(Mockito.any()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Mockito.when(connectedUserWorkflowConnectionResolver.resolve(workflow.getDefinition()))
            .thenThrow(new MissingConnectionException("slack"));

        Mockito.when(projectDeploymentService.fetchProjectDeploymentByName(Mockito.eq(500L), Mockito.anyString()))
            .thenReturn(Optional.empty());
        Mockito.when(projectDeploymentFacade.createProjectDeployment(
            Mockito.any(), Mockito.eq("catalog-wf-1"), Mockito.eq(List.of())))
            .thenReturn(900L);

        MissingConnectionException thrown = Assertions.assertThrows(
            MissingConnectionException.class,
            () -> facade.getOrCreateReference("userA", "catalog-uuid", Environment.PRODUCTION));

        Assertions.assertEquals("slack", thrown.getComponentName());

        ArgumentCaptor<ConnectedUserProjectWorkflow> captor =
            ArgumentCaptor.forClass(ConnectedUserProjectWorkflow.class);

        Mockito.verify(connectedUserProjectWorkflowRepository)
            .save(captor.capture());

        ConnectedUserProjectWorkflow saved = captor.getValue();

        Assertions.assertFalse(saved.isEnabled());
        Assertions.assertEquals("catalog-uuid", saved.getCatalogWorkflowUuid());
        Assertions.assertNull(saved.getProjectWorkflowId());

        Mockito.verify(connectedUserProjectWorkflowConnectionRepository, Mockito.never())
            .save(Mockito.any());
    }

    @Test
    void testEnableReferenceTogglesEnabledFlagAndProjectDeploymentWorkflow() {
        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(10L);

        Mockito.when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(
            Mockito.eq("userA"), Mockito.any()))
            .thenReturn(connectedUserProject);

        ConnectedUserProjectWorkflow reference = new ConnectedUserProjectWorkflow();

        reference.setId(1L);
        reference.setConnectedUserProjectId(10L);
        reference.setCatalogWorkflowUuid("catalog-uuid");
        reference.setProjectDeploymentId(900L);
        reference.setEnabled(false);

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(10L, "catalog-uuid"))
            .thenReturn(Optional.of(reference));

        Mockito.when(projectWorkflowService.getLastPublishedWorkflowId("catalog-uuid"))
            .thenReturn("catalog-wf-1");

        facade.enableReference("userA", "catalog-uuid", true, Environment.PRODUCTION);

        Assertions.assertTrue(reference.isEnabled());
        Mockito.verify(connectedUserProjectWorkflowRepository)
            .save(reference);
        Mockito.verify(projectDeploymentFacade)
            .enableProjectDeploymentWorkflow(900L, "catalog-wf-1", true);
    }

    @Test
    void testEnableReferenceThrowsWhenNoReferenceExists() {
        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(10L);

        Mockito.when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(
            Mockito.eq("userA"), Mockito.any()))
            .thenReturn(connectedUserProject);

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(10L, "catalog-uuid"))
            .thenReturn(Optional.empty());

        Assertions.assertThrows(
            ConfigurationException.class,
            () -> facade.enableReference("userA", "catalog-uuid", true, Environment.PRODUCTION));
    }

    @Test
    void testDeleteReferenceRemovesConnectionsAndReferenceRow() {
        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(10L);

        Mockito.when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(
            Mockito.eq("userA"), Mockito.any()))
            .thenReturn(connectedUserProject);

        ConnectedUserProjectWorkflow reference = new ConnectedUserProjectWorkflow();

        reference.setId(1L);
        reference.setConnectedUserProjectId(10L);
        reference.setCatalogWorkflowUuid("catalog-uuid");

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(10L, "catalog-uuid"))
            .thenReturn(Optional.of(reference));

        ConnectedUserProjectWorkflowConnection connection =
            new ConnectedUserProjectWorkflowConnection(5L, 1L, "t1", 1L, 0);

        Mockito.when(connectedUserProjectWorkflowConnectionRepository.findAllByConnectedUserProjectWorkflowId(1L))
            .thenReturn(List.of(connection));

        facade.deleteReference("userA", "catalog-uuid", Environment.PRODUCTION);

        Mockito.verify(connectedUserProjectWorkflowConnectionRepository)
            .deleteById(5L);
        Mockito.verify(connectedUserProjectWorkflowRepository)
            .deleteById(1L);
    }

    /**
     * The uuid-stability fix means a redeploy that keeps every workflow name unchanged must not dangle any reference:
     * only a reference whose catalog workflow was genuinely removed from the artifact gets flagged.
     */
    @Test
    void testMarkDanglingReferencesFlagsOnlyReferencesRemovedFromTheCatalog() {
        ConnectedUserProjectWorkflow stillPresent = referenceRow(1L, "uuid-present");
        ConnectedUserProjectWorkflow removed = referenceRow(2L, "uuid-removed");
        ConnectedUserProjectWorkflow copyModeRow = new ConnectedUserProjectWorkflow();

        copyModeRow.setId(3L);
        copyModeRow.setConnectedUserProjectId(30L);
        copyModeRow.setProjectWorkflowId(999L);

        Mockito.when(connectedUserProjectWorkflowRepository.findAll())
            .thenReturn(List.of(stillPresent, removed, copyModeRow));

        // Redeploy that carries every workflow name (and therefore uuid) forward unchanged.
        facade.markDanglingReferences(500L, Set.of("uuid-present"));

        Assertions.assertFalse(stillPresent.isDangling());
        Mockito.verify(connectedUserProjectWorkflowRepository, Mockito.never())
            .save(stillPresent);

        Assertions.assertTrue(removed.isDangling());
        Assertions.assertEquals("Removed from the catalog project on redeploy", removed.getDanglingReason());
        Mockito.verify(connectedUserProjectWorkflowRepository)
            .save(removed);

        // A copy-mode row (no catalogWorkflowUuid) must never be touched by dangling detection.
        Mockito.verify(connectedUserProjectWorkflowRepository, Mockito.never())
            .save(copyModeRow);
    }

    /**
     * Simulates the id a real {@code save(...)} call would generate, since the production code reads
     * {@code saved.getId()} right after saving to stamp it onto the per-node connection rows.
     */
    private static Answer<ConnectedUserProjectWorkflow> withGeneratedId() {
        AtomicLong nextId = new AtomicLong(1L);

        return invocation -> {
            ConnectedUserProjectWorkflow connectedUserProjectWorkflow = invocation.getArgument(0);

            connectedUserProjectWorkflow.setId(nextId.getAndIncrement());

            return connectedUserProjectWorkflow;
        };
    }

    private static ConnectedUserProjectWorkflow referenceRow(long id, String catalogWorkflowUuid) {
        ConnectedUserProjectWorkflow connectedUserProjectWorkflow = new ConnectedUserProjectWorkflow();

        connectedUserProjectWorkflow.setId(id);
        connectedUserProjectWorkflow.setConnectedUserProjectId(10L);
        connectedUserProjectWorkflow.setCatalogWorkflowUuid(catalogWorkflowUuid);

        return connectedUserProjectWorkflow;
    }
}
