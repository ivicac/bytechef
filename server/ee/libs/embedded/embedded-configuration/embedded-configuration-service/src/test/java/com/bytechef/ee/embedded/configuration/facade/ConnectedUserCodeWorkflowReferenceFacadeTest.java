/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProject;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.dto.AutomationWorkflowProjectDTO;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserWorkflowTemplateDTO;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.exception.MissingInputException;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserReferenceDeploymentManager.ReferenceResolution;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserReferenceDeploymentManager.RowSpec;
import com.bytechef.ee.embedded.configuration.repository.ConnectUserProjectRepository;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowRepository;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.lang.reflect.Method;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.annotation.Transactional;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
class ConnectedUserCodeWorkflowReferenceFacadeTest {

    private static final String CATALOG_UUID = "catalog-uuid";
    private static final String CATALOG_WORKFLOW_ID = "catalog-wf-1";

    @Mock
    private AutomationWorkflowProjectFacade automationWorkflowProjectFacade;

    @Mock
    private ConnectUserProjectRepository connectUserProjectRepository;

    @Mock
    private ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager;

    @Mock
    private ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;

    @Mock
    private ConnectedUserReferenceDeploymentManager connectedUserReferenceDeploymentManager;

    @Mock
    private ConnectedUserReferenceRolloutService connectedUserReferenceRolloutService;

    @Mock
    private ConnectedUserService connectedUserService;

    private ConnectedUserCodeWorkflowReferenceFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new ConnectedUserCodeWorkflowReferenceFacadeImpl(
            automationWorkflowProjectFacade, connectUserProjectRepository, connectedUserProjectWorkflowManager,
            connectedUserProjectWorkflowRepository, connectedUserReferenceDeploymentManager,
            connectedUserReferenceRolloutService, connectedUserService);

        // Every case in this class provisions a template the connected user IS permitted to see; the rejections are
        // covered by ConnectedUserCodeWorkflowReferenceFacadeAuthorizationTest. Lenient because the cases that do not
        // provision (existing reference, enable/disable, dangling) never reach the catalog lookup.
        Mockito.lenient()
            .when(automationWorkflowProjectFacade.getPublishedProjects(Mockito.anyString(), Mockito.any()))
            .thenReturn(List.of(publishedCatalogProject(CATALOG_UUID)));

        // The row lock answers the connected user's project it was asked for; markDanglingReferences never takes it.
        Mockito.lenient()
            .when(connectUserProjectRepository.findByIdForUpdate(Mockito.anyLong()))
            .thenAnswer(invocation -> {
                ConnectedUserProject connectedUserProject = new ConnectedUserProject();

                connectedUserProject.setId(invocation.getArgument(0));

                return Optional.of(connectedUserProject);
            });
    }

    /**
     * Each connected user's reference is resolved against that user's own connected-user id and written into that
     * user's own deployment -- two users referencing the same catalog workflow never share or overwrite wiring.
     */
    @Test
    void testTwoUsersReferencingTheSameCatalogWorkflowGetIndependentWiring() {
        givenConnectedUserProject("userA", 10L);
        givenConnectedUserProject("userB", 20L);
        givenConnectedUser("userA", 101L);
        givenConnectedUser("userB", 102L);
        givenNoExistingReference();
        givenDeployment("userA", Environment.PRODUCTION, 900L);
        givenDeployment("userB", Environment.PRODUCTION, 901L);

        RowSpec userARowSpec = rowSpec(true, new ProjectDeploymentWorkflowConnection(1L, "slack", "t1"));
        RowSpec userBRowSpec = rowSpec(true, new ProjectDeploymentWorkflowConnection(2L, "slack", "t1"));

        givenResolution(101L, true, new ReferenceResolution(userARowSpec, null, null));
        givenResolution(102L, true, new ReferenceResolution(userBRowSpec, null, null));

        facade.getOrCreateReference("userA", CATALOG_UUID, Environment.PRODUCTION);
        facade.getOrCreateReference("userB", CATALOG_UUID, Environment.PRODUCTION);

        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(900L, 1, Map.of(CATALOG_UUID, userARowSpec));
        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(901L, 1, Map.of(CATALOG_UUID, userBRowSpec));
    }

    @Test
    void testGetOrCreateReferenceProvisionsDistinctDeploymentsPerEnvironment() {
        givenConnectedUserProject("userA", Environment.PRODUCTION, 10L);
        givenConnectedUserProject("userA", Environment.DEVELOPMENT, 11L);
        givenConnectedUser("userA", 101L);
        givenNoExistingReference();
        givenDeployment("userA", Environment.PRODUCTION, 900L);
        givenDeployment("userA", Environment.DEVELOPMENT, 901L);

        RowSpec enabledRowSpec = rowSpec(true);

        givenResolution(101L, true, new ReferenceResolution(enabledRowSpec, null, null));

        ConnectedUserProjectWorkflow productionReference = facade.getOrCreateReference(
            "userA", CATALOG_UUID, Environment.PRODUCTION);
        ConnectedUserProjectWorkflow developmentReference = facade.getOrCreateReference(
            "userA", CATALOG_UUID, Environment.DEVELOPMENT);

        Assertions.assertEquals(900L, productionReference.getProjectDeploymentId());
        Assertions.assertEquals(901L, developmentReference.getProjectDeploymentId());
    }

    @Test
    void testGetOrCreateReferenceReturnsExistingRowWithoutReprovisioning() {
        givenConnectedUserProject("userA", 10L);

        ConnectedUserProjectWorkflow existingReference = reference(1L, 900L, false);

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(10L, CATALOG_UUID))
            .thenReturn(Optional.of(existingReference));

        ConnectedUserProjectWorkflow result = facade.getOrCreateReference(
            "userA", CATALOG_UUID, Environment.PRODUCTION);

        Assertions.assertSame(existingReference, result);

        // automationWorkflowProjectFacade included deliberately: the provisioning-time permission check must not run
        // for an already-provisioned reference, so a narrowed permission expression cannot break a running automation.
        Mockito.verifyNoInteractions(
            automationWorkflowProjectFacade, connectedUserService, connectedUserReferenceDeploymentManager);
    }

    /**
     * Repeating the call with requested connections is how an existing reference's connections are changed: its row is
     * re-resolved with them and rewritten, keeping the reference's enabled state as the requested one.
     */
    @Test
    void testGetOrCreateReferenceReResolvesAnExistingReferenceWithRequestedConnections() {
        givenConnectedUserProject("userA", 10L);
        givenConnectedUser("userA", 101L);
        givenDeploymentAtVersionOne(900L);
        givenNoCurrentRow(900L);

        ConnectedUserProjectWorkflow existingReference = reference(1L, 900L, true);

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(10L, CATALOG_UUID))
            .thenReturn(Optional.of(existingReference));
        Mockito.when(connectedUserProjectWorkflowRepository.save(Mockito.any()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        RowSpec rowSpec = rowSpec(true, new ProjectDeploymentWorkflowConnection(7L, "slack", "t1"));

        Mockito.when(connectedUserReferenceDeploymentManager.resolveReference(
            101L, CATALOG_WORKFLOW_ID, true, Map.of("slack", 7L), List.of(), Map.of()))
            .thenReturn(new ReferenceResolution(rowSpec, null, null));

        facade.getOrCreateReference("userA", CATALOG_UUID, Environment.PRODUCTION, Map.of("slack", 7L));

        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(900L, 1, Map.of(CATALOG_UUID, rowSpec));
        Mockito.verifyNoInteractions(automationWorkflowProjectFacade);
    }

    /**
     * A component with no matching connection for the connected user must not abort provisioning outright: the
     * reference row and its deployment row are still written -- disabled -- and {@link MissingConnectionException} is
     * rethrown afterward so the caller can surface which connection is missing.
     */
    @Test
    void testMissingConnectionStillCreatesDisabledReferenceAndRethrows() {
        givenConnectedUserProject("userA", 10L);
        givenConnectedUser("userA", 101L);
        givenNoExistingReference();
        givenDeployment("userA", Environment.PRODUCTION, 900L);

        RowSpec disabledRowSpec = new RowSpec(
            new ResolvedWorkflowConnections(List.of(), List.of("slack")), false, null);

        givenResolution(101L, true, new ReferenceResolution(disabledRowSpec, "slack", null));

        MissingConnectionException thrown = Assertions.assertThrows(
            MissingConnectionException.class,
            () -> facade.getOrCreateReference("userA", CATALOG_UUID, Environment.PRODUCTION));

        Assertions.assertEquals("slack", thrown.getComponentName());

        ConnectedUserProjectWorkflow saved = lastSavedReference();

        Assertions.assertFalse(saved.isEnabled());
        Assertions.assertEquals(CATALOG_UUID, saved.getCatalogWorkflowUuid());
        Assertions.assertNull(saved.getProjectWorkflowId());

        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(900L, 1, Map.of(CATALOG_UUID, disabledRowSpec));
    }

    /**
     * Inputs are written after provisioning in both the hub and the API flow, so a required input without a value does
     * not fail provisioning: the reference is simply left disabled.
     */
    @Test
    void testGetOrCreateReferenceLeavesReferenceDisabledWithoutErrorWhenARequiredInputHasNoValue() {
        givenConnectedUserProject("userA", 10L);
        givenConnectedUser("userA", 101L);
        givenNoExistingReference();
        givenDeployment("userA", Environment.PRODUCTION, 900L);

        RowSpec disabledRowSpec = rowSpec(false);

        givenResolution(101L, true, new ReferenceResolution(disabledRowSpec, null, "channel"));

        ConnectedUserProjectWorkflow reference = facade.getOrCreateReference(
            "userA", CATALOG_UUID, Environment.PRODUCTION);

        Assertions.assertFalse(reference.isEnabled());
        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(900L, 1, Map.of(CATALOG_UUID, disabledRowSpec));
    }

    @Test
    void testEnableReferenceTogglesEnabledFlagAndProjectDeploymentWorkflow() {
        givenConnectedUserProject("userA", 10L);
        givenConnectedUser("userA", 101L);
        givenDeploymentAtVersionOne(900L);
        givenNoCurrentRow(900L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, false);

        RowSpec enabledRowSpec = rowSpec(true, new ProjectDeploymentWorkflowConnection(1L, "slack", "t1"));

        givenResolution(101L, true, new ReferenceResolution(enabledRowSpec, null, null));

        facade.enableReference("userA", CATALOG_UUID, true, Environment.PRODUCTION);

        Assertions.assertTrue(reference.isEnabled());
        Mockito.verify(connectedUserProjectWorkflowRepository)
            .save(reference);
        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(900L, 1, Map.of(CATALOG_UUID, enabledRowSpec));
    }

    /**
     * Provisioning previously failed with {@link MissingConnectionException}. The connected user has since created the
     * missing connection, so re-enabling re-resolves against the row's current connections and writes the new wiring
     * together with the enabled flag.
     */
    @Test
    void testEnableReferenceRewiresConnectionsWhenPreviouslyMissingConnectionWasFixed() {
        givenConnectedUserProject("userA", 10L);
        givenConnectedUser("userA", 101L);
        givenDeploymentAtVersionOne(900L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, false);

        ProjectDeploymentWorkflow currentRow = new ProjectDeploymentWorkflow();
        List<ProjectDeploymentWorkflowConnection> currentConnections = List.of(
            new ProjectDeploymentWorkflowConnection(41L, "slack", "t1"));

        currentRow.setConnections(currentConnections);
        currentRow.setInputs(Map.of("channel", "general"));

        Mockito.when(connectedUserReferenceDeploymentManager.fetchWorkflowRow(900L, CATALOG_WORKFLOW_ID))
            .thenReturn(Optional.of(currentRow));

        RowSpec enabledRowSpec = rowSpec(true, new ProjectDeploymentWorkflowConnection(42L, "slack", "t1"));

        Mockito.when(connectedUserReferenceDeploymentManager.resolveReference(
            101L, CATALOG_WORKFLOW_ID, true, Map.of(), currentConnections, Map.of("channel", "general")))
            .thenReturn(new ReferenceResolution(enabledRowSpec, null, null));

        facade.enableReference("userA", CATALOG_UUID, true, Environment.PRODUCTION);

        Assertions.assertTrue(reference.isEnabled());
        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(900L, 1, Map.of(CATALOG_UUID, enabledRowSpec));
    }

    /**
     * The connected user has NOT created the missing connection yet: enabling throws
     * {@link MissingConnectionException}, and both the reference and its row are written disabled first.
     */
    @Test
    void testEnableReferenceStillThrowsWhenConnectionIsStillMissing() {
        givenConnectedUserProject("userA", 10L);
        givenConnectedUser("userA", 101L);
        givenDeploymentAtVersionOne(900L);
        givenNoCurrentRow(900L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, false);

        RowSpec disabledRowSpec = new RowSpec(
            new ResolvedWorkflowConnections(List.of(), List.of("slack")), false, null);

        givenResolution(101L, true, new ReferenceResolution(disabledRowSpec, "slack", null));

        MissingConnectionException thrown = Assertions.assertThrows(
            MissingConnectionException.class,
            () -> facade.enableReference("userA", CATALOG_UUID, true, Environment.PRODUCTION));

        Assertions.assertEquals("slack", thrown.getComponentName());
        Assertions.assertFalse(reference.isEnabled());

        Mockito.verify(connectedUserProjectWorkflowRepository)
            .save(reference);
        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(900L, 1, Map.of(CATALOG_UUID, disabledRowSpec));
    }

    @Test
    void testEnableReferenceThrowsMissingInputWhenARequiredInputHasNoValue() {
        givenConnectedUserProject("userA", 10L);
        givenConnectedUser("userA", 101L);
        givenDeploymentAtVersionOne(900L);
        givenNoCurrentRow(900L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, false);

        RowSpec disabledRowSpec = rowSpec(false);

        givenResolution(101L, true, new ReferenceResolution(disabledRowSpec, null, "channel"));

        MissingInputException thrown = Assertions.assertThrows(
            MissingInputException.class,
            () -> facade.enableReference("userA", CATALOG_UUID, true, Environment.PRODUCTION));

        Assertions.assertEquals("channel", thrown.getInputName());
        Assertions.assertFalse(reference.isEnabled());

        Mockito.verify(connectedUserProjectWorkflowRepository)
            .save(reference);
        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(900L, 1, Map.of(CATALOG_UUID, disabledRowSpec));
    }

    @Test
    void testEnableReferenceRefusesToEnableADanglingReference() {
        givenConnectedUserProject("userA", 10L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, false);

        reference.setDangling(true);

        Assertions.assertThrows(
            ConfigurationException.class,
            () -> facade.enableReference("userA", CATALOG_UUID, true, Environment.PRODUCTION));

        Mockito.verifyNoInteractions(connectedUserReferenceDeploymentManager, connectedUserReferenceRolloutService);
    }

    /**
     * The lazy catch-up can itself find the template removed and write the reference dangling. Enabling is then
     * refused, but with an exception {@code noRollbackFor} names, so that catch-up is not rolled back with it.
     */
    @Test
    void testEnableReferenceRefusedBecauseTheCatchUpLeftItDanglingKeepsTheCatchUp() throws NoSuchMethodException {
        givenConnectedUserProject("userA", 10L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, false);
        ConnectedUserProjectWorkflow caughtUpReference = reference(1L, 900L, false);

        caughtUpReference.setDangling(true);

        Mockito.when(connectedUserReferenceRolloutService.rollOutDeploymentIfBehind(900L))
            .thenReturn(true);
        Mockito.when(connectedUserProjectWorkflowRepository.findById(reference.getId()))
            .thenReturn(Optional.of(caughtUpReference));

        DanglingReferenceException danglingReferenceException = Assertions.assertThrows(
            DanglingReferenceException.class,
            () -> facade.enableReference("userA", CATALOG_UUID, true, Environment.PRODUCTION));

        Method enableReferenceMethod = ConnectedUserCodeWorkflowReferenceFacadeImpl.class.getMethod(
            "enableReference", String.class, String.class, boolean.class, Environment.class);
        Transactional transactional = enableReferenceMethod.getAnnotation(Transactional.class);

        Assertions.assertInstanceOf(ConfigurationException.class, danglingReferenceException);
        Assertions.assertTrue(List.of(transactional.noRollbackFor())
            .contains(DanglingReferenceException.class));
        Mockito.verify(connectedUserReferenceDeploymentManager, Mockito.never())
            .putWorkflows(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyMap());
    }

    /**
     * Turning a reference off never depends on moving its deployment to another version, which can fail -- e.g. when a
     * sibling template's trigger cannot be re-registered: the row is disabled at the deployment's current version.
     */
    @Test
    void testDisableReferenceSkipsTheCatchUpAndDisablesTheRowAtTheCurrentVersion() {
        givenConnectedUserProject("userA", 10L);
        givenConnectedUser("userA", 101L);
        givenDeploymentAtVersionOne(900L);
        givenNoCurrentRow(900L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, true);

        Mockito.lenient()
            .when(connectedUserReferenceRolloutService.rollOutDeploymentIfBehind(900L))
            .thenThrow(new IllegalStateException("Trigger registration failed"));

        RowSpec disabledRowSpec = rowSpec(false);

        givenResolution(101L, false, new ReferenceResolution(disabledRowSpec, null, null));

        Assertions.assertDoesNotThrow(
            () -> facade.enableReference("userA", CATALOG_UUID, false, Environment.PRODUCTION));

        Assertions.assertFalse(reference.isEnabled());
        Mockito.verify(connectedUserReferenceDeploymentManager)
            .putWorkflows(900L, 1, Map.of(CATALOG_UUID, disabledRowSpec));
        Mockito.verify(connectedUserReferenceRolloutService, Mockito.never())
            .rollOutDeploymentIfBehind(Mockito.anyLong());
    }

    /**
     * A code-workflow redeploy marks a reference dangling before the rollout drops its row. Disabling it removes that
     * still-running row -- a dangling template can never be enabled again -- and leaves the reference dangling.
     */
    @Test
    void testDisableDanglingReferenceRemovesItsRow() {
        givenConnectedUserProject("userA", 10L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, false);

        reference.setDangling(true);

        facade.enableReference("userA", CATALOG_UUID, false, Environment.PRODUCTION);

        Assertions.assertTrue(reference.isDangling());
        Assertions.assertFalse(reference.isEnabled());
        Mockito.verify(connectedUserReferenceDeploymentManager)
            .removeWorkflow(900L, CATALOG_UUID);
        Mockito.verify(connectedUserReferenceDeploymentManager, Mockito.never())
            .putWorkflows(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyMap());
        Mockito.verifyNoInteractions(connectedUserReferenceRolloutService);
    }

    @Test
    void testEnableReferenceThrowsWhenNoReferenceExists() {
        givenConnectedUserProject("userA", 10L);

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(10L, CATALOG_UUID))
            .thenReturn(Optional.empty());

        Assertions.assertThrows(
            ConfigurationException.class,
            () -> facade.enableReference("userA", CATALOG_UUID, true, Environment.PRODUCTION));
    }

    @Test
    void testDeleteReferenceRemovesItsDeploymentRowAndTheReferenceRow() {
        givenConnectedUserProject("userA", 10L);

        givenExistingReference(10L, true);

        facade.deleteReference("userA", CATALOG_UUID, Environment.PRODUCTION);

        Mockito.verify(connectedUserReferenceDeploymentManager)
            .removeWorkflow(900L, CATALOG_UUID);
        Mockito.verify(connectedUserProjectWorkflowRepository)
            .deleteById(1L);
    }

    /**
     * A dangling reference's row may still exist -- a code-workflow redeploy marks references dangling before the
     * rollout drops their rows -- so deleting it removes the row too (the manager tolerates a row or deployment that is
     * already gone).
     */
    @Test
    void testDeleteDanglingReferenceAlsoRemovesItsRow() {
        givenConnectedUserProject("userA", 10L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, false);

        reference.setDangling(true);

        facade.deleteReference("userA", CATALOG_UUID, Environment.PRODUCTION);

        Mockito.verify(connectedUserReferenceDeploymentManager)
            .removeWorkflow(900L, CATALOG_UUID);
        Mockito.verify(connectedUserProjectWorkflowRepository)
            .deleteById(1L);
    }

    @Test
    void testUpdateReferenceInputsWritesTheRowUnderTheConnectedUsersLock() {
        givenConnectedUserProject("userA", 10L);
        givenExistingReference(10L, true);

        facade.updateReferenceInputs("userA", CATALOG_UUID, Map.of("channel", "#alerts"), Environment.PRODUCTION);

        InOrder inOrder = Mockito.inOrder(connectUserProjectRepository, connectedUserReferenceDeploymentManager);

        inOrder.verify(connectUserProjectRepository)
            .findByIdForUpdate(10L);
        inOrder.verify(connectedUserReferenceDeploymentManager)
            .updateInputs(900L, CATALOG_UUID, Map.of("channel", "#alerts"));
    }

    @Test
    void testUpdateReferenceInputsRefusesADanglingReference() {
        givenConnectedUserProject("userA", 10L);

        ConnectedUserProjectWorkflow reference = givenExistingReference(10L, false);

        reference.setDangling(true);

        DanglingReferenceException danglingReferenceException = Assertions.assertThrows(
            DanglingReferenceException.class,
            () -> facade.updateReferenceInputs(
                "userA", CATALOG_UUID, Map.of("channel", "#alerts"), Environment.PRODUCTION));

        Assertions.assertEquals(
            "Reference to catalog workflow " + CATALOG_UUID + " is dangling", danglingReferenceException.getMessage());

        Mockito.verify(connectedUserReferenceDeploymentManager, Mockito.never())
            .updateInputs(Mockito.anyLong(), Mockito.anyString(), Mockito.anyMap());
    }

    @Test
    void testReferenceWritesLockTheConnectedUsersProjectRow() {
        givenConnectedUserProject("userA", 10L);
        givenExistingReference(10L, false);

        facade.deleteReference("userA", CATALOG_UUID, Environment.PRODUCTION);

        Mockito.verify(connectUserProjectRepository)
            .findByIdForUpdate(10L);
    }

    /**
     * The uuid-stability fix means a redeploy that keeps every workflow name unchanged must not dangle any reference:
     * only a reference whose catalog workflow was genuinely removed from the artifact gets flagged, and disabled.
     */
    @Test
    void testMarkDanglingReferencesFlagsOnlyReferencesRemovedFromTheCatalog() {
        ConnectedUserProjectWorkflow stillPresent = referenceRow(1L, "uuid-present");
        ConnectedUserProjectWorkflow removed = referenceRow(2L, "uuid-removed");
        ConnectedUserProjectWorkflow copyModeRow = new ConnectedUserProjectWorkflow();

        removed.setEnabled(true);

        copyModeRow.setId(3L);
        copyModeRow.setConnectedUserProjectId(30L);
        copyModeRow.setProjectWorkflowId(999L);

        Mockito.when(connectedUserProjectWorkflowRepository.findAll())
            .thenReturn(List.of(stillPresent, removed, copyModeRow));

        // Redeploy that carries every workflow name (and therefore uuid) forward unchanged.
        facade.markDanglingReferences(500L, Set.of("uuid-present", "uuid-removed"), Set.of("uuid-present"));

        Assertions.assertFalse(stillPresent.isDangling());
        Mockito.verify(connectedUserProjectWorkflowRepository, Mockito.never())
            .save(stillPresent);

        Assertions.assertTrue(removed.isDangling());
        Assertions.assertFalse(removed.isEnabled());
        Assertions.assertEquals("Removed from the catalog project on redeploy", removed.getDanglingReason());
        Mockito.verify(connectedUserProjectWorkflowRepository)
            .save(removed);

        // A copy-mode row (no catalogWorkflowUuid) must never be touched by dangling detection.
        Mockito.verify(connectedUserProjectWorkflowRepository, Mockito.never())
            .save(copyModeRow);
    }

    /**
     * {@code markDanglingReferences} must dangle exactly {@code previous \ current} for THIS catalog project, never
     * guessing off the repository-wide set of all references. A reference belonging to a different catalog project must
     * never be touched by a redeploy of another catalog project, even though both rows are returned by the same
     * {@code findAll()} call.
     */
    @Test
    void testMarkDanglingReferencesNeverTouchesAnotherCatalogProjectsReferences() {
        ConnectedUserProjectWorkflow billingRemoved = referenceRow(1L, "billing-uuid-removed");
        ConnectedUserProjectWorkflow billingStillPresent = referenceRow(2L, "billing-uuid-present");
        ConnectedUserProjectWorkflow crmReference = referenceRow(3L, "crm-uuid-untouched");

        Mockito.when(connectedUserProjectWorkflowRepository.findAll())
            .thenReturn(List.of(billingRemoved, billingStillPresent, crmReference));

        facade.markDanglingReferences(
            500L, Set.of("billing-uuid-removed", "billing-uuid-present"), Set.of("billing-uuid-present"));

        Assertions.assertTrue(billingRemoved.isDangling());
        Mockito.verify(connectedUserProjectWorkflowRepository)
            .save(billingRemoved);

        Assertions.assertFalse(billingStillPresent.isDangling());
        Mockito.verify(connectedUserProjectWorkflowRepository, Mockito.never())
            .save(billingStillPresent);

        Assertions.assertFalse(crmReference.isDangling());
        Mockito.verify(connectedUserProjectWorkflowRepository, Mockito.never())
            .save(crmReference);
    }

    private void givenConnectedUser(String externalUserId, long connectedUserId) {
        Mockito.when(connectedUserService.getConnectedUser(Mockito.eq(externalUserId), Mockito.any()))
            .thenReturn(new ConnectedUser(Map.of(), null, true, externalUserId, connectedUserId, null, 0));
    }

    private void givenConnectedUserProject(String externalUserId, long connectedUserProjectId) {
        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(connectedUserProjectId);

        Mockito.when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(
            Mockito.eq(externalUserId), Mockito.any()))
            .thenReturn(connectedUserProject);
    }

    private void givenConnectedUserProject(
        String externalUserId, Environment environment, long connectedUserProjectId) {

        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(connectedUserProjectId);

        Mockito.when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(externalUserId, environment))
            .thenReturn(connectedUserProject);
    }

    /**
     * Stubs a newly created deployment for (catalog project 500, user, environment) at version 1 -- the catalog
     * project's last published version -- and an empty row for the template.
     */
    private void givenDeployment(String externalUserId, Environment environment, long projectDeploymentId) {
        Mockito.when(connectedUserReferenceDeploymentManager.getOrCreateDeployment(
            500L, externalUserId, environment))
            .thenReturn(projectDeploymentId);

        givenDeploymentAtVersionOne(projectDeploymentId);
        givenNoCurrentRow(projectDeploymentId);
    }

    private void givenDeploymentAtVersionOne(long projectDeploymentId) {
        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setProjectId(500L);
        projectDeployment.setProjectVersion(1);

        Mockito.when(connectedUserReferenceDeploymentManager.getDeployment(projectDeploymentId))
            .thenReturn(projectDeployment);
        Mockito.lenient()
            .when(connectedUserReferenceDeploymentManager.getWorkflowId(500L, 1, CATALOG_UUID))
            .thenReturn(CATALOG_WORKFLOW_ID);
    }

    private ConnectedUserProjectWorkflow givenExistingReference(long connectedUserProjectId, boolean enabled) {
        ConnectedUserProjectWorkflow reference = reference(1L, 900L, enabled);

        reference.setConnectedUserProjectId(connectedUserProjectId);

        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(connectedUserProjectId, CATALOG_UUID))
            .thenReturn(Optional.of(reference));
        Mockito.lenient()
            .when(connectedUserProjectWorkflowRepository.save(Mockito.any()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        return reference;
    }

    private void givenNoCurrentRow(long projectDeploymentId) {
        Mockito.when(connectedUserReferenceDeploymentManager.fetchWorkflowRow(projectDeploymentId, CATALOG_WORKFLOW_ID))
            .thenReturn(Optional.empty());
    }

    private void givenNoExistingReference() {
        Mockito.when(connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(Mockito.anyLong(), Mockito.eq(CATALOG_UUID)))
            .thenReturn(Optional.empty());
        Mockito.when(connectedUserProjectWorkflowRepository.save(Mockito.any()))
            .thenAnswer(withGeneratedId());
    }

    private void givenResolution(long connectedUserId, boolean enable, ReferenceResolution referenceResolution) {
        Mockito.when(connectedUserReferenceDeploymentManager.resolveReference(
            connectedUserId, CATALOG_WORKFLOW_ID, enable, Map.of(), List.of(), Map.of()))
            .thenReturn(referenceResolution);
    }

    private ConnectedUserProjectWorkflow lastSavedReference() {
        ArgumentCaptor<ConnectedUserProjectWorkflow> referenceCaptor =
            ArgumentCaptor.forClass(ConnectedUserProjectWorkflow.class);

        Mockito.verify(connectedUserProjectWorkflowRepository, Mockito.atLeastOnce())
            .save(referenceCaptor.capture());

        return referenceCaptor.getValue();
    }

    private static AutomationWorkflowProjectDTO publishedCatalogProject(String workflowUuid) {
        return new AutomationWorkflowProjectDTO(
            500L, "Catalog", "", null, List.of(), true, 1, 1,
            List.of(
                new ConnectedUserWorkflowTemplateDTO(
                    workflowUuid, "Label", "Description", null, List.of(), List.of(), List.of(), null)),
            null, true, true);
    }

    private static ConnectedUserProjectWorkflow reference(long id, long projectDeploymentId, boolean enabled) {
        ConnectedUserProjectWorkflow reference = new ConnectedUserProjectWorkflow();

        reference.setId(id);
        reference.setCatalogWorkflowUuid(CATALOG_UUID);
        reference.setEnabled(enabled);
        reference.setProjectDeploymentId(projectDeploymentId);

        return reference;
    }

    private static ConnectedUserProjectWorkflow referenceRow(long id, String catalogWorkflowUuid) {
        ConnectedUserProjectWorkflow connectedUserProjectWorkflow = new ConnectedUserProjectWorkflow();

        connectedUserProjectWorkflow.setId(id);
        connectedUserProjectWorkflow.setConnectedUserProjectId(10L);
        connectedUserProjectWorkflow.setCatalogWorkflowUuid(catalogWorkflowUuid);

        return connectedUserProjectWorkflow;
    }

    private static RowSpec rowSpec(boolean enabled, ProjectDeploymentWorkflowConnection... connections) {
        return new RowSpec(new ResolvedWorkflowConnections(List.of(connections), List.of()), enabled, null);
    }

    /**
     * Simulates the id a real {@code save(...)} call would generate.
     */
    private static Answer<ConnectedUserProjectWorkflow> withGeneratedId() {
        AtomicLong nextId = new AtomicLong(1L);

        return invocation -> {
            ConnectedUserProjectWorkflow connectedUserProjectWorkflow = invocation.getArgument(0);

            connectedUserProjectWorkflow.setId(nextId.getAndIncrement());

            return connectedUserProjectWorkflow;
        };
    }
}
