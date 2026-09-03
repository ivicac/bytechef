/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.spend;

import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewaySpendService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewaySpendSummary;
import com.bytechef.ee.platform.ai.llm.usage.AiLlmUsage;
import com.bytechef.ee.platform.ai.llm.usage.LlmUsageSource;
import com.bytechef.ee.platform.ai.llm.usage.service.AiLlmUsageService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly rollup of raw {@code ai_llm_usage} rows into {@code ai_gateway_spend_summary} — the writer the budget checker
 * and spend dashboards read from (the read stack existed without any producer before this job). Each run aggregates the
 * previous full hour per workspace, grouped by (provider, model, apiKeyId, projectId, connectedUserId), and writes
 * through {@link WorkspaceAiGatewaySpendService#createInWorkspace} so the summary carries its owning workspace.
 *
 * <p>
 * {@code connectedUserId} is read from {@link AiLlmUsage#getUserId()}, but {@code userId} is NOT exclusively a
 * connected-user column — it is one column shared by every {@link AiLlmUsage} writer, and {@code ai_llm_usage} holds
 * rows from more than just the gateway. {@code AiGatewayFacadeImpl} stamps the already-resolved embedded identity there
 * on every row it writes (spec §7); today its five construction sites build rows via {@code new AiLlmUsage(...)}
 * directly and never call {@code setSource}, leaving {@link AiLlmUsage#getSource()} {@code null}, though
 * {@link AiLlmUsage#forSuccess} / {@link AiLlmUsage#forError} are the documented gateway factories and DO stamp
 * {@code source = AI_GATEWAY}. But {@code AiLlmUsageServiceImpl#recordLlm} — the entry point AI Hub and other
 * {@code LlmUsageRecorder} callers write through — stamps {@code userId} with a PLATFORM user id instead (AI Hub:
 * {@code AiHubStateKeys.AUTHENTICATED_USER_ID}, source {@code AI_HUB}), and {@code getRequestLogsByWorkspace} applies
 * no source filter, so a workspace's rows are a mix of both. Connected-user ids and platform-user ids are independent
 * sequences on independent tables with no FK between them, so low ids collide with near-certainty — {@link #toGroupKey}
 * therefore only carries {@code userId} through for a gateway-originated row (see {@code connectedUserIdOf}'s javadoc
 * for why that check covers both the null-source row shape gateway rows have today AND the {@code AI_GATEWAY}-source
 * shape the documented factories produce), never unconditionally. Getting this wrong silently attributes one
 * population's money to another — a platform user's AI Hub spend rejecting a same-numbered customer's gateway request,
 * or vice versa — which is exactly the failure spec ⚑3 already named for the {@code apiKeyId} case, arriving through a
 * different column.
 *
 * <p>
 * {@code connectedUserId}, once carried through, is copied onto the summary verbatim, as a group key component and not
 * merely a display field. Without it in the group key, two connected users sharing one vendor {@code apiKeyId} would
 * collapse into a single summary row whose {@code connectedUserId} could only pick one of them, silently misattributing
 * the other's spend. It is deliberately never derived from {@code apiKeyId}: that column identifies the vendor's own
 * gateway key, not the customer the call was made for, and the two diverge as soon as a vendor reuses one key across
 * customers (⚑3).
 *
 * <p>
 * Idempotency: a workspace-hour that already has a summary row with the same period start is skipped, so restarts don't
 * double-count. In an HA deployment two nodes can race past that check between read and write; the window is one
 * scheduler tick and the duplicate would only overstate spend (never understate a budget), so a distributed lock is
 * deliberately not introduced yet.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class AiGatewaySpendRollupJob {

    private static final Logger log = LoggerFactory.getLogger(AiGatewaySpendRollupJob.class);

    private static final String UNKNOWN = "unknown";

    private final AiLlmUsageService aiLlmUsageService;
    private final WorkspaceAiGatewaySpendService workspaceAiGatewaySpendService;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AiGatewaySpendRollupJob(
        AiLlmUsageService aiLlmUsageService, WorkspaceAiGatewaySpendService workspaceAiGatewaySpendService) {

        this.aiLlmUsageService = aiLlmUsageService;
        this.workspaceAiGatewaySpendService = workspaceAiGatewaySpendService;
    }

    @Scheduled(cron = "0 5 * * * *")
    public void rollUpPreviousHour() {
        Instant periodEnd = Instant.now()
            .truncatedTo(ChronoUnit.HOURS);

        rollUp(periodEnd.minus(1, ChronoUnit.HOURS), periodEnd);
    }

    void rollUp(Instant periodStart, Instant periodEnd) {
        for (Long workspaceId : aiLlmUsageService.findDistinctWorkspaceIds()) {
            try {
                rollUpWorkspace(workspaceId, periodStart, periodEnd);
            } catch (RuntimeException exception) {
                // One broken workspace must not stop the rollup for the rest.
                log.warn("Spend rollup failed for workspace {}", workspaceId, exception);
            }
        }
    }

    private void rollUpWorkspace(long workspaceId, Instant periodStart, Instant periodEnd) {
        boolean alreadyRolledUp = workspaceAiGatewaySpendService
            .getSpendSummariesByWorkspaceId(workspaceId, periodStart, periodEnd)
            .stream()
            .anyMatch(summary -> periodStart.equals(summary.getPeriodStart()));

        if (alreadyRolledUp) {
            return;
        }

        List<AiLlmUsage> usages = aiLlmUsageService.getRequestLogsByWorkspace(workspaceId, periodStart, periodEnd);

        if (usages.isEmpty()) {
            return;
        }

        Map<GroupKey, List<AiLlmUsage>> groups = usages.stream()
            .collect(Collectors.groupingBy(AiGatewaySpendRollupJob::toGroupKey));

        for (Map.Entry<GroupKey, List<AiLlmUsage>> group : groups.entrySet()) {
            AiGatewaySpendSummary summary = new AiGatewaySpendSummary(periodStart, periodEnd);

            GroupKey groupKey = group.getKey();

            summary.setProvider(groupKey.provider());
            summary.setModel(groupKey.model());
            summary.setApiKeyId(groupKey.apiKeyId());
            summary.setProjectId(groupKey.projectId());
            summary.setConnectedUserId(groupKey.connectedUserId());

            List<AiLlmUsage> groupUsages = group.getValue();

            summary.setRequestCount(groupUsages.size());
            summary.setTotalInputTokens(
                groupUsages.stream()
                    .map(AiLlmUsage::getInputTokens)
                    .filter(Objects::nonNull)
                    .mapToLong(Integer::longValue)
                    .sum());
            summary.setTotalOutputTokens(
                groupUsages.stream()
                    .map(AiLlmUsage::getOutputTokens)
                    .filter(Objects::nonNull)
                    .mapToLong(Integer::longValue)
                    .sum());
            // Null costs (cancelled streams with unknown usage) are excluded from the sum rather than treated as $0;
            // the row still contributes to request/token counts.
            summary.setTotalCost(
                groupUsages.stream()
                    .map(AiLlmUsage::getCost)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));

            workspaceAiGatewaySpendService.createInWorkspace(summary, workspaceId);
        }

        if (log.isDebugEnabled()) {
            log.debug(
                "Rolled up {} usage rows into {} spend summaries for workspace {} ({} - {})",
                usages.size(), groups.size(), workspaceId, periodStart, periodEnd);
        }
    }

    private static GroupKey toGroupKey(AiLlmUsage usage) {
        String model = usage.getRoutedModel() != null ? usage.getRoutedModel() : usage.getRequestedModel();

        return new GroupKey(
            usage.getRoutedProvider() != null ? usage.getRoutedProvider() : UNKNOWN,
            model != null ? model : UNKNOWN,
            usage.getApiKeyId(), usage.getProjectId(), connectedUserIdOf(usage));
    }

    /**
     * Returns {@link AiLlmUsage#getUserId()} ONLY for a gateway-originated row, {@code null} otherwise — see the class
     * javadoc for why {@code userId} alone cannot be trusted as a connected user id. This is a POSITIVE, gateway-only
     * allowlist ({@code source == null OR source == AI_GATEWAY}), deliberately not a blocklist of {@code AI_HUB}: a
     * blocklist would silently readmit every source added after this method was written (a future
     * {@code LlmUsageSource} value, or a writer that reuses an existing one), which is how this exact bug would arrive
     * a second time.
     *
     * <p>
     * Both disjuncts are needed, not just {@code source == null}. Today {@code AiGatewayFacadeImpl}'s five construction
     * sites build every row via {@code new AiLlmUsage(...)} directly and never call {@code setSource}, so gateway rows
     * leave the column null — but {@link AiLlmUsage#forSuccess} / {@link AiLlmUsage#forError} are the factories the
     * class's OWN javadoc and {@code AiLlmUsageServiceImpl#recordLlm}'s javadoc both already describe the gateway as
     * using, and both factories stamp {@code source = AI_GATEWAY} explicitly. A maintainer who reconciles the facade
     * with its documented contract by migrating those five sites to the factories would flip every gateway row's source
     * from null to {@code AI_GATEWAY} — a {@code source == null}-only guard would then return {@code null} for all of
     * them and silently zero out connected-user attribution with no error anywhere. {@code AI_AGENT} rows happen to
     * carry a null {@code userId} today (see {@code WorkflowLlmUsageEventListener}), so they would be harmless either
     * way — but this method does not rely on that being true tomorrow.
     */
    private static @Nullable Long connectedUserIdOf(AiLlmUsage usage) {
        LlmUsageSource source = usage.getSource();

        return source == null || source == LlmUsageSource.AI_GATEWAY ? usage.getUserId() : null;
    }

    private record GroupKey(String provider, String model, Long apiKeyId, Long projectId, Long connectedUserId) {
    }
}
