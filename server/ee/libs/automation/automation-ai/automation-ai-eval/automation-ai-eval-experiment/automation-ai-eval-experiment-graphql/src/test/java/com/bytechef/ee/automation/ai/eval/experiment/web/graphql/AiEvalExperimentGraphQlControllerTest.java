/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.eval.experiment.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.AggregateScoreDelta;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.AiEvalExperimentRunView;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.AiEvalExperimentView;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentComparisonRow;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentComparisonView;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentRunPoint;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentScoreAverage;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentSummary;
import com.bytechef.ee.platform.ai.eval.domain.AiEvalScore;
import com.bytechef.ee.platform.ai.eval.domain.AiEvalScoreSource;
import com.bytechef.ee.platform.ai.eval.experiment.domain.AiEvalExperiment;
import com.bytechef.ee.platform.ai.eval.experiment.domain.AiEvalExperimentRun;
import com.bytechef.ee.platform.ai.eval.experiment.domain.AiEvalExperimentRunStatus;
import com.bytechef.ee.platform.ai.eval.experiment.service.AiEvalExperimentRunService;
import com.bytechef.ee.platform.ai.eval.experiment.service.AiEvalExperimentService;
import com.bytechef.ee.platform.ai.eval.service.AiEvalScoreService;
import com.bytechef.ee.platform.ai.gateway.exception.AiScoreWorkspaceBoundaryException;
import com.bytechef.platform.security.constant.AuthorityConstants;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the comparison-view shape: two experiments sharing three dataset items produce two summaries, three rows, and a
 * numeric-score aggregate per score name.
 *
 * @author Ivica Cardic
 * @version ee
 */
@ExtendWith(MockitoExtension.class)
class AiEvalExperimentGraphQlControllerTest {

    private static final long EXPERIMENT_A_ID = 10L;
    private static final long EXPERIMENT_B_ID = 20L;
    private static final long WORKSPACE_ID = 1L;
    private static final long DATASET_ITEM_1 = 101L;
    private static final long DATASET_ITEM_2 = 102L;
    private static final long DATASET_ITEM_3 = 103L;

    @Mock
    private AiEvalExperimentService aiEvalExperimentService;

    @Mock
    private com.bytechef.ee.automation.ai.eval.experiment.service.WorkspaceAiEvalExperimentService workspaceAiEvalExperimentService;

    @Mock
    private AiEvalExperimentRunService aiEvalExperimentRunService;

    @Mock
    private AiEvalScoreService aiEvalScoreService;

    @Mock
    private PermissionService permissionService;

    private AiEvalExperimentGraphQlController controller;

    @BeforeEach
    void setUp() {
        controller = new AiEvalExperimentGraphQlController(
            aiEvalExperimentService, workspaceAiEvalExperimentService, aiEvalExperimentRunService,
            aiEvalScoreService, permissionService);

        // Default to allowing access; cross-workspace test overrides this for the offending workspace. Lenient because
        // the empty-id test path never reaches the permission check.
        lenient()
            .when(permissionService.hasWorkspaceRole(anyLong(), eq("VIEWER")))
            .thenReturn(true);
    }

    @Test
    void testExperimentComparisonReturnsExpectedShape() {
        AiEvalExperiment experimentA = newExperiment(EXPERIMENT_A_ID, "gpt-4");
        AiEvalExperiment experimentB = newExperiment(EXPERIMENT_B_ID, "gpt-4o");

        AiEvalExperimentRun runA1 = completedRun(1L, EXPERIMENT_A_ID, DATASET_ITEM_1, 501L, 120, "0.01");
        AiEvalExperimentRun runA2 = completedRun(2L, EXPERIMENT_A_ID, DATASET_ITEM_2, 502L, 140, "0.02");
        AiEvalExperimentRun runA3 = completedRun(3L, EXPERIMENT_A_ID, DATASET_ITEM_3, 503L, 100, "0.015");

        AiEvalExperimentRun runB1 = completedRun(4L, EXPERIMENT_B_ID, DATASET_ITEM_1, 601L, 80, "0.02");
        AiEvalExperimentRun runB2 = completedRun(5L, EXPERIMENT_B_ID, DATASET_ITEM_2, 602L, 90, "0.03");
        AiEvalExperimentRun runB3 = completedRun(6L, EXPERIMENT_B_ID, DATASET_ITEM_3, 603L, 70, "0.025");

        when(aiEvalExperimentService.getExperiment(EXPERIMENT_A_ID)).thenReturn(experimentA);
        when(aiEvalExperimentService.getExperiment(EXPERIMENT_B_ID)).thenReturn(experimentB);

        when(aiEvalExperimentRunService.findAllByExperiment(EXPERIMENT_A_ID))
            .thenReturn(List.of(runA1, runA2, runA3));
        when(aiEvalExperimentRunService.findAllByExperiment(EXPERIMENT_B_ID))
            .thenReturn(List.of(runB1, runB2, runB3));

        when(aiEvalExperimentRunService.countByExperiment(EXPERIMENT_A_ID)).thenReturn(3L);
        when(aiEvalExperimentRunService.countByExperiment(EXPERIMENT_B_ID)).thenReturn(3L);
        when(aiEvalExperimentRunService.countByExperimentAndStatus(anyLong(), eq(AiEvalExperimentRunStatus.COMPLETED)))
            .thenReturn(3L);
        when(aiEvalExperimentRunService.countByExperimentAndStatus(anyLong(), eq(AiEvalExperimentRunStatus.FAILED)))
            .thenReturn(0L);

        // Each trace has the same NUMERIC score name "accuracy" so we can verify aggregation.
        when(aiEvalScoreService.getScoresByTrace(501L)).thenReturn(List.of(numericScore(501L, "accuracy", "0.9")));
        when(aiEvalScoreService.getScoresByTrace(502L)).thenReturn(List.of(numericScore(502L, "accuracy", "0.8")));
        when(aiEvalScoreService.getScoresByTrace(503L)).thenReturn(List.of(numericScore(503L, "accuracy", "0.7")));
        when(aiEvalScoreService.getScoresByTrace(601L)).thenReturn(List.of(numericScore(601L, "accuracy", "0.95")));
        when(aiEvalScoreService.getScoresByTrace(602L)).thenReturn(List.of(numericScore(602L, "accuracy", "0.85")));
        when(aiEvalScoreService.getScoresByTrace(603L)).thenReturn(List.of(numericScore(603L, "accuracy", "0.9")));

        ExperimentComparisonView view = controller.experimentComparison(
            List.of(EXPERIMENT_A_ID, EXPERIMENT_B_ID));

        assertThat(view.experiments()).hasSize(2);
        assertThat(view.rows()).hasSize(3);

        ExperimentSummary summaryA = view.experiments()
            .getFirst();

        assertThat(summaryA.id()).isEqualTo(EXPERIMENT_A_ID);
        assertThat(summaryA.model()).isEqualTo("gpt-4");
        assertThat(summaryA.totalRuns()).isEqualTo(3L);
        assertThat(summaryA.completedRuns()).isEqualTo(3L);
        assertThat(summaryA.failedRuns()).isEqualTo(0L);
        assertThat(summaryA.totalCost()).isEqualByComparingTo("0.045");
        assertThat(summaryA.averageLatencyMs()).isEqualTo(120);

        ExperimentComparisonRow firstRow = view.rows()
            .getFirst();

        assertThat(firstRow.datasetItemId()).isEqualTo(DATASET_ITEM_1);
        assertThat(firstRow.runsByExperiment()).hasSize(2);

        ExperimentRunPoint pointA = firstRow.runsByExperiment()
            .getFirst();

        assertThat(pointA.experimentId()).isEqualTo(EXPERIMENT_A_ID);
        assertThat(pointA.traceId()).isEqualTo(501L);
        assertThat(pointA.status()).isEqualTo("COMPLETED");
        assertThat(pointA.scores()).hasSize(1);
        assertThat(pointA.scores()
            .getFirst()
            .name()).isEqualTo("accuracy");

        assertThat(view.aggregateScoreDeltas()).hasSize(1);

        AggregateScoreDelta delta = view.aggregateScoreDeltas()
            .getFirst();

        assertThat(delta.scoreName()).isEqualTo("accuracy");
        assertThat(delta.deltas()).hasSize(2);

        ExperimentScoreAverage averageA = delta.deltas()
            .stream()
            .filter(entry -> entry.experimentId() == EXPERIMENT_A_ID)
            .findFirst()
            .orElseThrow();

        assertThat(averageA.count()).isEqualTo(3L);
        assertThat(averageA.average()
            .doubleValue()).isEqualTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void testExperimentComparisonSkipsBooleanAndCategoricalForAggregates() {
        AiEvalExperiment experiment = newExperiment(EXPERIMENT_A_ID, "gpt-4");

        AiEvalExperimentRun run = completedRun(1L, EXPERIMENT_A_ID, DATASET_ITEM_1, 501L, 100, "0.01");

        when(aiEvalExperimentService.getExperiment(EXPERIMENT_A_ID)).thenReturn(experiment);
        when(aiEvalExperimentRunService.findAllByExperiment(EXPERIMENT_A_ID)).thenReturn(List.of(run));
        when(aiEvalExperimentRunService.countByExperiment(EXPERIMENT_A_ID)).thenReturn(1L);
        when(aiEvalExperimentRunService.countByExperimentAndStatus(anyLong(), any()))
            .thenReturn(0L);

        when(aiEvalScoreService.getScoresByTrace(501L)).thenReturn(List.of(
            AiEvalScore.bool(501L, "passed", AiEvalScoreSource.MANUAL, true),
            AiEvalScore.categorical(501L, "sentiment", AiEvalScoreSource.MANUAL, "positive"),
            numericScore(501L, "accuracy", "0.9")));

        ExperimentComparisonView view = controller.experimentComparison(List.of(EXPERIMENT_A_ID));

        assertThat(view.rows()).hasSize(1);
        assertThat(view.rows()
            .getFirst()
            .runsByExperiment()
            .getFirst()
            .scores()).hasSize(3);

        // Only the NUMERIC score appears in aggregates.
        assertThat(view.aggregateScoreDeltas()).hasSize(1);
        assertThat(view.aggregateScoreDeltas()
            .getFirst()
            .scoreName()).isEqualTo("accuracy");
    }

    @Test
    void testEmptyExperimentIdsReturnsEmptyView() {
        ExperimentComparisonView view = controller.experimentComparison(List.of());

        assertThat(view.experiments()).isEmpty();
        assertThat(view.rows()).isEmpty();
        assertThat(view.aggregateScoreDeltas()).isEmpty();
    }

    @Test
    void testExperimentComparisonRejectsCrossWorkspaceExperiment() {
        // Caller is an admin (the @PreAuthorize gate is satisfied) but is NOT a member of workspace 99 — passing an
        // experiment id from that workspace must be rejected before any data is read.
        // The error message MUST NOT include the resolved workspace id of the foreign experiment: leaking that lets
        // a workspace-A admin enumerate which workspace any guessed experiment id belongs to. Both the not-found
        // and the wrong-workspace cases throw with the same prefix and only the requested experiment id.
        long crossWorkspaceId = 99L;
        AiEvalExperiment crossWorkspaceExperiment =
            newExperimentInWorkspace(EXPERIMENT_A_ID, "gpt-4", crossWorkspaceId);

        when(aiEvalExperimentService.getExperiment(EXPERIMENT_A_ID)).thenReturn(crossWorkspaceExperiment);
        when(permissionService.hasWorkspaceRole(crossWorkspaceId, "VIEWER")).thenReturn(false);

        assertThatThrownBy(() -> controller.experimentComparison(List.of(EXPERIMENT_A_ID)))
            .isInstanceOf(AiScoreWorkspaceBoundaryException.class)
            .hasMessageContaining("experiment " + EXPERIMENT_A_ID)
            .hasMessageNotContaining("workspace " + crossWorkspaceId);
    }

    @Test
    void testExperimentComparisonReturnsSameErrorShapeForNotFoundAndCrossWorkspace() {
        // Pins the uniform-error contract: a workspace-A admin probing experiment ids must NOT be able to
        // distinguish "id does not exist" from "id exists in workspace B" — both must throw
        // AiScoreWorkspaceBoundaryException with the same prefix. Without this collapse, the not-found path
        // throws IllegalArgumentException ("not found") while the wrong-workspace path throws the boundary
        // exception with workspace details, enabling cross-workspace id enumeration.
        long missingId = 9_999L;
        long foreignId = EXPERIMENT_A_ID;
        long foreignWorkspaceId = 99L;

        AiEvalExperiment foreignExperiment = newExperimentInWorkspace(foreignId, "gpt-4", foreignWorkspaceId);

        when(aiEvalExperimentService.getExperiment(missingId))
            .thenThrow(new IllegalArgumentException("Experiment " + missingId + " not found"));
        when(aiEvalExperimentService.getExperiment(foreignId)).thenReturn(foreignExperiment);
        when(permissionService.hasWorkspaceRole(foreignWorkspaceId, "VIEWER")).thenReturn(false);

        Throwable missingThrown =
            org.assertj.core.api.Assertions.catchThrowable(() -> controller.experimentComparison(List.of(missingId)));
        Throwable foreignThrown =
            org.assertj.core.api.Assertions.catchThrowable(() -> controller.experimentComparison(List.of(foreignId)));

        assertThat(missingThrown).isInstanceOf(AiScoreWorkspaceBoundaryException.class);
        assertThat(foreignThrown).isInstanceOf(AiScoreWorkspaceBoundaryException.class);

        // Assert the EXACT message (no extra fields, no quoted name, no source-of-failure hint), with the
        // only difference being the id the caller passed in. A subtle regression that appended a quoted name
        // on one path but `id=...` on the other would still pass a startsWith / doesNotContain pair, but
        // would fail this isEqualTo + the symmetric structural check below.
        assertThat(missingThrown.getMessage())
            .isEqualTo("Caller is not authorized for experiment " + missingId);
        assertThat(foreignThrown.getMessage())
            .isEqualTo("Caller is not authorized for experiment " + foreignId);

        // Structural symmetry: stripping the id from each message must yield identical strings — proving the
        // two paths emit the same shape rather than two coincidentally similar prefixes.
        assertThat(missingThrown.getMessage()
            .replace(Long.toString(missingId), "<id>"))
                .isEqualTo(foreignThrown.getMessage()
                    .replace(Long.toString(foreignId), "<id>"));
    }

    @Test
    void testAiEvalExperimentsReturnsListWithDenormalizedRunCounts() {
        AiEvalExperiment experimentA = newExperiment(EXPERIMENT_A_ID, "gpt-4");
        AiEvalExperiment experimentB = newExperiment(EXPERIMENT_B_ID, "gpt-4o");

        when(workspaceAiEvalExperimentService.findAllByWorkspace(WORKSPACE_ID))
            .thenReturn(List.of(experimentA, experimentB));

        when(aiEvalExperimentRunService.countByExperiment(EXPERIMENT_A_ID)).thenReturn(3L);
        when(aiEvalExperimentRunService.countByExperiment(EXPERIMENT_B_ID)).thenReturn(2L);
        when(
            aiEvalExperimentRunService.countByExperimentAndStatus(EXPERIMENT_A_ID, AiEvalExperimentRunStatus.COMPLETED))
                .thenReturn(2L);
        when(aiEvalExperimentRunService.countByExperimentAndStatus(EXPERIMENT_A_ID, AiEvalExperimentRunStatus.FAILED))
            .thenReturn(1L);
        when(
            aiEvalExperimentRunService.countByExperimentAndStatus(EXPERIMENT_B_ID, AiEvalExperimentRunStatus.COMPLETED))
                .thenReturn(2L);
        when(aiEvalExperimentRunService.countByExperimentAndStatus(EXPERIMENT_B_ID, AiEvalExperimentRunStatus.FAILED))
            .thenReturn(0L);

        List<AiEvalExperimentView> views = controller.aiEvalExperiments(WORKSPACE_ID);

        assertThat(views).hasSize(2);
        assertThat(views.get(0)
            .id()).isEqualTo(EXPERIMENT_A_ID);
        assertThat(views.get(0)
            .totalRuns()).isEqualTo(3L);
        assertThat(views.get(0)
            .completedRuns()).isEqualTo(2L);
        assertThat(views.get(0)
            .failedRuns()).isEqualTo(1L);
        assertThat(views.get(1)
            .id()).isEqualTo(EXPERIMENT_B_ID);
        assertThat(views.get(1)
            .totalRuns()).isEqualTo(2L);
    }

    @Test
    void testAiEvalExperimentsReturnsEmptyListWhenWorkspaceIdIsNull() {
        // Defense-in-depth check: a null workspaceId from a malformed GraphQL request must NOT reach the
        // permission service (which could throw or, depending on implementation, return false and emit a
        // misleading WORKSPACE/null boundary exception). Returning an empty list early matches the
        // null-id behaviour on the singleton lookups elsewhere in the gateway.
        List<AiEvalExperimentView> views = controller.aiEvalExperiments(null);

        assertThat(views).isEmpty();
    }

    @Test
    void testAiEvalExperimentsRejectsCallerWithoutWorkspaceViewerRole() {
        // Same security-boundary pattern as experimentComparison: a caller who is a workspace member but
        // lacks the VIEWER role on the requested workspace must NOT receive an empty list (which would
        // be indistinguishable from "workspace exists but has no experiments"); they must hit the
        // uniform AiScoreWorkspaceBoundaryException so existence cannot be probed cross-workspace.
        long foreignWorkspaceId = 99L;

        when(permissionService.hasWorkspaceRole(foreignWorkspaceId, "VIEWER")).thenReturn(false);

        assertThatThrownBy(() -> controller.aiEvalExperiments(foreignWorkspaceId))
            .isInstanceOf(AiScoreWorkspaceBoundaryException.class)
            .hasMessageContaining("workspace " + foreignWorkspaceId);
    }

    @Test
    void testAiEvalExperimentRunsReturnsRunsForVisibleExperiment() {
        AiEvalExperiment experiment = newExperiment(EXPERIMENT_A_ID, "gpt-4");

        AiEvalExperimentRun runOne = completedRun(1L, EXPERIMENT_A_ID, DATASET_ITEM_1, 501L, 120, "0.01");
        AiEvalExperimentRun runTwo = completedRun(2L, EXPERIMENT_A_ID, DATASET_ITEM_2, 502L, 140, "0.02");

        when(aiEvalExperimentService.getExperiment(EXPERIMENT_A_ID)).thenReturn(experiment);
        when(aiEvalExperimentRunService.findAllByExperiment(EXPERIMENT_A_ID)).thenReturn(List.of(runOne, runTwo));

        List<AiEvalExperimentRunView> runs = controller.aiEvalExperimentRuns(EXPERIMENT_A_ID);

        assertThat(runs).hasSize(2);
        assertThat(runs.get(0)
            .id()).isEqualTo(1L);
        assertThat(runs.get(0)
            .traceId()).isEqualTo(501L);
        assertThat(runs.get(0)
            .status()).isEqualTo("COMPLETED");
        assertThat(runs.get(0)
            .latencyMs()).isEqualTo(120);
        assertThat(runs.get(0)
            .cost()).isEqualByComparingTo("0.01");
        assertThat(runs.get(1)
            .id()).isEqualTo(2L);
    }

    @Test
    void testAiEvalExperimentRunsReturnsEmptyListWhenExperimentIdIsNull() {
        List<AiEvalExperimentRunView> runs = controller.aiEvalExperimentRuns(null);

        assertThat(runs).isEmpty();
    }

    @Test
    void testAiEvalExperimentRunsCollapsesNotFoundOntoBoundaryException() {
        // Uniform-error contract for the runs query: a missing experiment id must NOT throw the
        // service-layer IllegalArgumentException ("Experiment X not found"). Distinguishable error
        // messages between "exists but in another workspace" and "does not exist" let workspace-A
        // probe workspace-B's experiment ids.
        long missingId = 9_999L;

        when(aiEvalExperimentService.getExperiment(missingId))
            .thenThrow(new IllegalArgumentException("Experiment " + missingId + " not found"));

        assertThatThrownBy(() -> controller.aiEvalExperimentRuns(missingId))
            .isInstanceOf(AiScoreWorkspaceBoundaryException.class)
            .hasMessageContaining("experiment " + missingId);
    }

    @Test
    void testAiEvalExperimentRunsRejectsCrossWorkspaceExperiment() {
        long foreignWorkspaceId = 99L;

        AiEvalExperiment foreignExperiment = newExperimentInWorkspace(EXPERIMENT_A_ID, "gpt-4", foreignWorkspaceId);

        when(aiEvalExperimentService.getExperiment(EXPERIMENT_A_ID)).thenReturn(foreignExperiment);
        when(permissionService.hasWorkspaceRole(foreignWorkspaceId, "VIEWER")).thenReturn(false);

        assertThatThrownBy(() -> controller.aiEvalExperimentRuns(EXPERIMENT_A_ID))
            .isInstanceOf(AiScoreWorkspaceBoundaryException.class)
            .hasMessageContaining("experiment " + EXPERIMENT_A_ID)
            .hasMessageNotContaining("workspace " + foreignWorkspaceId);
    }

    @Test
    void testAiEvalExperimentRunsReturnsSameErrorShapeForNotFoundAndCrossWorkspace() {
        // Structural symmetry: stripping the id from each message must yield identical strings — a
        // regression that distinguishes the two paths (e.g. one path appends "(not found)" while the
        // other appends a workspace hint) would still pass startsWith() but fails this isEqualTo +
        // strip-id symmetric check. Companion to
        // testExperimentComparisonReturnsSameErrorShapeForNotFoundAndCrossWorkspace.
        long missingId = 9_999L;
        long foreignId = EXPERIMENT_A_ID;
        long foreignWorkspaceId = 99L;

        AiEvalExperiment foreignExperiment = newExperimentInWorkspace(foreignId, "gpt-4", foreignWorkspaceId);

        when(aiEvalExperimentService.getExperiment(missingId))
            .thenThrow(new IllegalArgumentException("Experiment " + missingId + " not found"));
        when(aiEvalExperimentService.getExperiment(foreignId)).thenReturn(foreignExperiment);
        when(permissionService.hasWorkspaceRole(foreignWorkspaceId, "VIEWER")).thenReturn(false);

        Throwable missingThrown =
            org.assertj.core.api.Assertions.catchThrowable(() -> controller.aiEvalExperimentRuns(missingId));
        Throwable foreignThrown =
            org.assertj.core.api.Assertions.catchThrowable(() -> controller.aiEvalExperimentRuns(foreignId));

        assertThat(missingThrown).isInstanceOf(AiScoreWorkspaceBoundaryException.class);
        assertThat(foreignThrown).isInstanceOf(AiScoreWorkspaceBoundaryException.class);
        assertThat(missingThrown.getMessage()
            .replace(Long.toString(missingId), "<id>"))
                .isEqualTo(foreignThrown.getMessage()
                    .replace(Long.toString(foreignId), "<id>"));
    }

    @Test
    void testExperimentComparisonIsAdminOnly() throws NoSuchMethodException {
        // Static check that @PreAuthorize("hasAuthority(ROLE_ADMIN)") is present on the only public query
        // method. The full controller test runs under @ExtendWith(MockitoExtension.class) without Spring's
        // method-security AOP — so a regression that drops or weakens the annotation would silently make the
        // endpoint reachable by non-admins. This reflective assertion fails fast in that case.
        Method method = AiEvalExperimentGraphQlController.class.getDeclaredMethod("experimentComparison", List.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("experimentComparison must be guarded by @PreAuthorize")
            .isNotNull();
        assertThat(preAuthorize.value())
            .as("@PreAuthorize must require ROLE_ADMIN")
            .contains(AuthorityConstants.ADMIN);
    }

    private AiEvalExperiment newExperiment(long id, String model) {
        return newExperimentInWorkspace(id, model, WORKSPACE_ID);
    }

    private AiEvalExperiment newExperimentInWorkspace(long id, String model, long workspaceId) {
        AiEvalExperiment experiment = new AiEvalExperiment(1L);

        experiment.setModel(model);

        ReflectionTestUtils.setField(experiment, "id", id);

        // Workspace assignment lives on workspace_ai_eval_experiment now; stub the service helper that the boundary
        // check
        // queries.
        lenient()
            .when(workspaceAiEvalExperimentService.getWorkspaceId(id))
            .thenReturn(workspaceId);

        return experiment;
    }

    private static AiEvalExperimentRun completedRun(
        long runId, long experimentId, long datasetItemId, Long traceId, Integer latencyMs, String cost) {

        AiEvalExperimentRun run = new AiEvalExperimentRun(experimentId, datasetItemId);

        // AiEvalExperimentRun.complete() requires the run to be RUNNING; transition it first so the test fixture
        // matches
        // the real executor's call sequence (markRunning → complete).
        run.markRunning();
        run.complete(traceId, latencyMs, new BigDecimal(cost));

        ReflectionTestUtils.setField(run, "id", runId);

        return run;
    }

    private static AiEvalScore numericScore(long traceId, String name, String value) {
        return AiEvalScore.numeric(traceId, name, AiEvalScoreSource.LLM_JUDGE, new BigDecimal(value));
    }
}
