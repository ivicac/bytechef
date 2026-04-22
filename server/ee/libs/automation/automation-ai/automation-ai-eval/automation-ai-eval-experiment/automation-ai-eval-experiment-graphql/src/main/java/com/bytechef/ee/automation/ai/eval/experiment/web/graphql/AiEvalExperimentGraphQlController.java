/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.eval.experiment.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.ee.automation.ai.eval.experiment.service.WorkspaceAiEvalExperimentService;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.AggregateScoreDelta;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.AiEvalExperimentRunView;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.AiEvalExperimentView;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentComparisonRow;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentComparisonView;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentRunPoint;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentScoreAverage;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ExperimentSummary;
import com.bytechef.ee.automation.ai.eval.experiment.web.graphql.dto.ScorePoint;
import com.bytechef.ee.platform.ai.eval.domain.AiEvalScore;
import com.bytechef.ee.platform.ai.eval.domain.AiEvalScoreDataType;
import com.bytechef.ee.platform.ai.eval.domain.AiEvalScoreValue;
import com.bytechef.ee.platform.ai.eval.experiment.domain.AiEvalExperiment;
import com.bytechef.ee.platform.ai.eval.experiment.domain.AiEvalExperimentRun;
import com.bytechef.ee.platform.ai.eval.experiment.domain.AiEvalExperimentRunStatus;
import com.bytechef.ee.platform.ai.eval.experiment.service.AiEvalExperimentRunService;
import com.bytechef.ee.platform.ai.eval.experiment.service.AiEvalExperimentService;
import com.bytechef.ee.platform.ai.eval.service.AiEvalScoreService;
import com.bytechef.ee.platform.ai.gateway.exception.AiScoreTargetType;
import com.bytechef.ee.platform.ai.gateway.exception.AiScoreWorkspaceBoundaryException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.constant.AuthorityConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * Exposes the {@code experimentComparison} GraphQL query — a side-by-side view of multiple experiments that ran against
 * the same dataset. Returns per-experiment run summaries, per dataset item run diffs (with trace-scoped eval scores
 * attached), and per-score-name numeric averages for each experiment.
 *
 * <p>
 * Aggregation scope: only NUMERIC scores contribute to {@link AggregateScoreDelta#deltas()} — aggregating BOOLEAN (as
 * "% true" vs "mean of 0/1") and CATEGORICAL (mode? histogram?) is deferred until product nails the semantics.
 *
 * <p>
 * The response returns per-experiment averages rather than pairwise diffs so the payload stays symmetric regardless of
 * the order of {@code experimentIds} in the request. The UI computes deltas client-side.
 *
 * @author Ivica Cardic
 * @version ee
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
@ConditionalOnCoordinator
class AiEvalExperimentGraphQlController {

    /**
     * Minimum workspace role required to view experiments. Must stay in lockstep with
     * {@code com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole#VIEWER}. Adding an EE module dep
     * here to use the typed enum directly via {@code hasWorkspaceRoleTyped} would cycle the GraphQL module on EE-only
     * configuration, so the value is duplicated as a string literal.
     *
     * <p>
     * <strong>Important:</strong> renaming {@code WorkspaceRole.VIEWER} will <em>not</em> surface as a compile-time
     * break here. The reflective
     * {@code AiEvalExperimentGraphQlControllerViewerRolePinTest#testMinimumViewerRoleMatchesEnum} unit test pins this
     * constant against {@code WorkspaceRole.VIEWER.name()} so the drift is caught at test time instead.
     */
    private static final String MINIMUM_VIEWER_ROLE = "VIEWER";

    private final AiEvalExperimentService aiEvalExperimentService;
    private final WorkspaceAiEvalExperimentService workspaceAiEvalExperimentService;
    private final AiEvalExperimentRunService aiEvalExperimentRunService;
    private final AiEvalScoreService aiEvalScoreService;
    private final PermissionService permissionService;

    @SuppressFBWarnings("EI")
    AiEvalExperimentGraphQlController(
        AiEvalExperimentService aiEvalExperimentService,
        WorkspaceAiEvalExperimentService workspaceAiEvalExperimentService,
        AiEvalExperimentRunService aiEvalExperimentRunService,
        AiEvalScoreService aiEvalScoreService, PermissionService permissionService) {

        this.aiEvalExperimentService = aiEvalExperimentService;
        this.workspaceAiEvalExperimentService = workspaceAiEvalExperimentService;
        this.aiEvalExperimentRunService = aiEvalExperimentRunService;
        this.aiEvalScoreService = aiEvalScoreService;
        this.permissionService = permissionService;
    }

    /**
     * Lists experiments for the given workspace with denormalized run-count fields. The caller must have at least
     * {@code MINIMUM_VIEWER_ROLE} on the workspace; failure raises {@link AiScoreWorkspaceBoundaryException} with the
     * uniform "Caller is not authorized for X" message — workspaces the caller cannot see surface as 403, not as an
     * empty list, so existence cannot be probed.
     *
     * <p>
     * Run counts are computed via {@code countByExperimentAndStatus} per experiment — N+1 against the runs table is
     * accepted at this list boundary because the operator console paginates client-side and the alternative (a GROUP BY
     * join) would force a custom repository method for a non-hot path.
     */
    @QueryMapping
    public List<AiEvalExperimentView> aiEvalExperiments(@Argument Long workspaceId) {
        if (workspaceId == null) {
            return List.of();
        }

        if (!permissionService.hasWorkspaceRole(workspaceId, MINIMUM_VIEWER_ROLE)) {
            throw AiScoreWorkspaceBoundaryException.forTarget(
                workspaceId, AiScoreTargetType.WORKSPACE, workspaceId);
        }

        List<AiEvalExperiment> experiments = workspaceAiEvalExperimentService.findAllByWorkspace(workspaceId);

        return experiments.stream()
            .map(this::buildView)
            .toList();
    }

    /**
     * Reverse lookup from a trace id to its source experiment run, for the operator-console Traces tab cross-link.
     * Returns {@code null} when the trace was not generated by an experiment replay (the common case for production API
     * traffic), so the UI can render the link conditionally without a separate "is this an experiment trace" probe. The
     * workspace-boundary check walks up via the run's experiment — same depth-2 pattern used by
     * {@code AiEvalDatasetGraphQlController.aiEvalDatasetItems}.
     */
    @QueryMapping
    public AiEvalExperimentRunView aiEvalExperimentRunByTraceId(@Argument Long traceId) {
        if (traceId == null) {
            return null;
        }

        return aiEvalExperimentRunService.findByTraceId(traceId)
            .map(run -> {
                AiEvalExperiment experiment = aiEvalExperimentService.getExperiment(run.getExperimentId());

                Long workspaceId = workspaceAiEvalExperimentService.getWorkspaceId(experiment.getId());

                if (workspaceId == null || !permissionService.hasWorkspaceRole(workspaceId, MINIMUM_VIEWER_ROLE)) {
                    // Cross-workspace traces stay hidden: the trace itself is already enforced by the trace
                    // controller's workspace check, so reaching here with a foreign run is a sign of a row drift,
                    // not a probe. Return null rather than throw — the trace-detail UI uses the absence of a
                    // run to decide whether to show the cross-link, and a thrown error here would surface as a
                    // toast on a perfectly valid trace view.
                    return null;
                }

                return toRunView(run);
            })
            .orElse(null);
    }

    /**
     * Lists runs for a single experiment after a workspace-boundary check on the experiment. Same uniform-error rule as
     * {@code experimentComparison}: a missing experiment id and a wrong-workspace experiment id produce the same
     * {@link AiScoreWorkspaceBoundaryException} payload to prevent existence probing.
     */
    @QueryMapping
    public List<AiEvalExperimentRunView> aiEvalExperimentRuns(@Argument Long experimentId) {
        if (experimentId == null) {
            return List.of();
        }

        AiEvalExperiment experiment;

        try {
            experiment = aiEvalExperimentService.getExperiment(experimentId);
        } catch (IllegalArgumentException notFound) {
            throw AiScoreWorkspaceBoundaryException.forTarget(
                null, AiScoreTargetType.EXPERIMENT, experimentId, notFound);
        }

        Long workspaceId = workspaceAiEvalExperimentService.getWorkspaceId(experiment.getId());

        if (workspaceId == null || !permissionService.hasWorkspaceRole(workspaceId, MINIMUM_VIEWER_ROLE)) {
            throw AiScoreWorkspaceBoundaryException.forTarget(
                workspaceId, AiScoreTargetType.EXPERIMENT, experimentId);
        }

        return aiEvalExperimentRunService.findAllByExperiment(experimentId)
            .stream()
            .map(AiEvalExperimentGraphQlController::toRunView)
            .toList();
    }

    private AiEvalExperimentView buildView(AiEvalExperiment experiment) {
        long total = aiEvalExperimentRunService.countByExperiment(experiment.getId());
        long completed = aiEvalExperimentRunService.countByExperimentAndStatus(
            experiment.getId(), AiEvalExperimentRunStatus.COMPLETED);
        long failed = aiEvalExperimentRunService.countByExperimentAndStatus(
            experiment.getId(), AiEvalExperimentRunStatus.FAILED);

        return new AiEvalExperimentView(
            experiment.getId(),
            experiment.getDatasetVersionId(),
            experiment.getPromptVersionId(),
            experiment.getModel(),
            experiment.getMetadata(),
            experiment.getStatus()
                .name(),
            experiment.isStopRequested(),
            total,
            completed,
            failed,
            experiment.getCreatedDate(),
            experiment.getStartedDate(),
            experiment.getCompletedDate());
    }

    private static AiEvalExperimentRunView toRunView(AiEvalExperimentRun run) {
        return new AiEvalExperimentRunView(
            run.getId(),
            run.getExperimentId(),
            run.getDatasetItemId(),
            run.getTraceId(),
            run.getStatus()
                .name(),
            run.getLatencyMs(),
            run.getCost(),
            run.getErrorMessage(),
            run.getCreatedDate());
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ExperimentComparisonView experimentComparison(@Argument List<Long> experimentIds) {
        if (experimentIds == null || experimentIds.isEmpty()) {
            return new ExperimentComparisonView(List.of(), List.of(), List.of());
        }

        // Resolve experiments with a uniform "boundary" failure shape: a missing experiment-id (which
        // {@code getExperiment} surfaces as IllegalArgumentException) and a wrong-workspace experiment must NOT
        // produce distinguishable error messages — distinguishability lets a workspace-A admin enumerate
        // workspace-B experiment ids by probing for the "not found" vs "not authorized" delta. Both cases throw
        // AiScoreWorkspaceBoundaryException with the same prefix and the requested id (no leak of "exists but
        // belongs to workspace N").
        List<AiEvalExperiment> experiments = new ArrayList<>(experimentIds.size());

        for (Long experimentId : experimentIds) {
            AiEvalExperiment experiment;

            try {
                experiment = aiEvalExperimentService.getExperiment(experimentId);
            } catch (IllegalArgumentException notFound) {
                // Preserve the IAE as cause so a validation bug masquerading as a 404 surfaces in server-side logs
                // instead of leaving operators chasing a ghost "missing experiment" ticket.
                throw AiScoreWorkspaceBoundaryException.forTarget(
                    null, AiScoreTargetType.EXPERIMENT, experimentId, notFound);
            }

            Long workspaceId = workspaceAiEvalExperimentService.getWorkspaceId(experiment.getId());

            if (workspaceId == null || !permissionService.hasWorkspaceRole(workspaceId, MINIMUM_VIEWER_ROLE)) {
                throw AiScoreWorkspaceBoundaryException.forTarget(
                    workspaceId, AiScoreTargetType.EXPERIMENT, experimentId);
            }

            experiments.add(experiment);
        }

        // Fetch runs once per experiment so downstream loops can reuse them without re-hitting the service.
        Map<Long, List<AiEvalExperimentRun>> runsByExperiment = new LinkedHashMap<>();

        for (AiEvalExperiment experiment : experiments) {
            runsByExperiment.put(
                experiment.getId(), aiEvalExperimentRunService.findAllByExperiment(experiment.getId()));
        }

        // Cache scores per trace to avoid duplicate lookups between row-building and aggregate-building.
        Map<Long, List<AiEvalScore>> scoresByTrace = new HashMap<>();

        List<ExperimentSummary> summaries = experiments.stream()
            .map(experiment -> buildSummary(experiment, runsByExperiment.get(experiment.getId())))
            .toList();

        Set<Long> allDatasetItemIds = new TreeSet<>();

        for (List<AiEvalExperimentRun> runs : runsByExperiment.values()) {
            for (AiEvalExperimentRun run : runs) {
                allDatasetItemIds.add(run.getDatasetItemId());
            }
        }

        List<ExperimentComparisonRow> rows = allDatasetItemIds.stream()
            .map(itemId -> buildRow(itemId, experiments, runsByExperiment, scoresByTrace))
            .toList();

        List<AggregateScoreDelta> aggregates = buildAggregateDeltas(runsByExperiment, scoresByTrace);

        return new ExperimentComparisonView(summaries, rows, aggregates);
    }

    private ExperimentSummary buildSummary(AiEvalExperiment experiment, List<AiEvalExperimentRun> runs) {
        long total = aiEvalExperimentRunService.countByExperiment(experiment.getId());
        long completed = aiEvalExperimentRunService.countByExperimentAndStatus(
            experiment.getId(), AiEvalExperimentRunStatus.COMPLETED);
        long failed = aiEvalExperimentRunService.countByExperimentAndStatus(
            experiment.getId(), AiEvalExperimentRunStatus.FAILED);

        BigDecimal totalCost = runs.stream()
            .map(AiEvalExperimentRun::getCost)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        long latencySum = 0L;
        int latencyCount = 0;

        for (AiEvalExperimentRun run : runs) {
            Integer latency = run.getLatencyMs();

            if (latency != null) {
                latencySum += latency;
                latencyCount++;
            }
        }

        Integer averageLatencyMs = latencyCount == 0 ? null : (int) (latencySum / latencyCount);

        return new ExperimentSummary(
            experiment.getId(), experiment.getModel(), total, completed, failed, totalCost, averageLatencyMs);
    }

    private ExperimentComparisonRow buildRow(
        Long datasetItemId, List<AiEvalExperiment> experiments, Map<Long, List<AiEvalExperimentRun>> runsByExperiment,
        Map<Long, List<AiEvalScore>> scoresByTrace) {

        List<ExperimentRunPoint> points = new ArrayList<>();

        for (AiEvalExperiment experiment : experiments) {
            AiEvalExperimentRun matchingRun = null;

            for (AiEvalExperimentRun run : runsByExperiment.get(experiment.getId())) {
                if (Objects.equals(run.getDatasetItemId(), datasetItemId)) {
                    matchingRun = run;

                    break;
                }
            }

            if (matchingRun == null) {
                continue;
            }

            List<ScorePoint> scorePoints = loadScores(matchingRun.getTraceId(), scoresByTrace).stream()
                .map(this::toScorePoint)
                .toList();

            points.add(new ExperimentRunPoint(
                experiment.getId(), matchingRun.getId(), matchingRun.getTraceId(),
                matchingRun.getStatus()
                    .name(),
                matchingRun.getCost(), matchingRun.getLatencyMs(), scorePoints));
        }

        return new ExperimentComparisonRow(datasetItemId, points);
    }

    private List<AiEvalScore> loadScores(Long traceId, Map<Long, List<AiEvalScore>> scoresByTrace) {
        if (traceId == null) {
            return List.of();
        }

        return scoresByTrace.computeIfAbsent(traceId, aiEvalScoreService::getScoresByTrace);
    }

    private ScorePoint toScorePoint(AiEvalScore score) {
        AiEvalScoreDataType dataType = score.getDataType();
        // Read via the typed view; flatten back into the GraphQL DTO's (BigDecimal, String) shape. The flattening
        // is necessary because the GraphQL schema cannot express a sealed sum type — the projection has to expose
        // both columns. A corrupt row throws IllegalStateException with the row id, which is the right wire-side
        // behaviour: we'd rather fail the GraphQL query loudly than silently emit a row with both fields null.
        AiEvalScoreValue typedValue = score.getTypedValue();

        BigDecimal numericValue;
        String stringValue;

        if (typedValue instanceof AiEvalScoreValue.Numeric numeric) {
            numericValue = numeric.value();
            stringValue = null;
        } else if (typedValue instanceof AiEvalScoreValue.Bool bool) {
            numericValue = bool.asNumeric();
            stringValue = bool.asString();
        } else if (typedValue instanceof AiEvalScoreValue.Categorical categorical) {
            numericValue = null;
            stringValue = categorical.label();
        } else {
            numericValue = null;
            stringValue = null;
        }

        return new ScorePoint(
            score.getName(), numericValue, stringValue, dataType == null ? null : dataType.name());
    }

    private List<AggregateScoreDelta> buildAggregateDeltas(
        Map<Long, List<AiEvalExperimentRun>> runsByExperiment, Map<Long, List<AiEvalScore>> scoresByTrace) {

        // scoreName -> experimentId -> list of numeric values
        Map<String, Map<Long, List<BigDecimal>>> valuesByNameByExperiment = new LinkedHashMap<>();

        for (Map.Entry<Long, List<AiEvalExperimentRun>> entry : runsByExperiment.entrySet()) {
            Long experimentId = entry.getKey();

            for (AiEvalExperimentRun run : entry.getValue()) {
                List<AiEvalScore> scores = loadScores(run.getTraceId(), scoresByTrace);

                for (AiEvalScore score : scores) {
                    if (score.getDataType() != AiEvalScoreDataType.NUMERIC) {
                        continue;
                    }

                    // Aggregate via the typed view: a corrupt NUMERIC row throws and surfaces in the GraphQL
                    // response — far more useful than the previous "skip silently" branch which would let bad
                    // rows distort experiment averages by being absent from one experiment but present in
                    // another (depending on whether the corruption pre-dated this run).
                    if (score.getTypedValue() instanceof AiEvalScoreValue.Numeric numeric) {
                        valuesByNameByExperiment
                            .computeIfAbsent(score.getName(), key -> new LinkedHashMap<>())
                            .computeIfAbsent(experimentId, key -> new ArrayList<>())
                            .add(numeric.value());
                    }
                }
            }
        }

        List<AggregateScoreDelta> deltas = new ArrayList<>();

        for (Map.Entry<String, Map<Long, List<BigDecimal>>> scoreEntry : valuesByNameByExperiment.entrySet()) {
            String scoreName = scoreEntry.getKey();
            List<ExperimentScoreAverage> averages = new ArrayList<>();

            for (Map.Entry<Long, List<BigDecimal>> perExperiment : scoreEntry.getValue()
                .entrySet()) {

                List<BigDecimal> values = perExperiment.getValue();

                BigDecimal sum = values.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal average = sum.divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL64);

                averages.add(new ExperimentScoreAverage(perExperiment.getKey(), average, values.size()));
            }

            deltas.add(new AggregateScoreDelta(scoreName, Collections.unmodifiableList(averages)));
        }

        return deltas;
    }
}
