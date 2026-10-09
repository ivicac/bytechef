/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.tool.invocation.log;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.tenant.TenantContext;
import com.bytechef.tenant.service.TenantService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes tool invocation log rows older than {@code bytechef.tool-invocation-log.retention-days} (default 90). Runs
 * daily at 02:30 local time to limit growth of {@code tool_invocation_log}.
 *
 * <p>
 * Every tenant is swept under its own context, because the delete runs on the tenant's schema; a tenant whose sweep
 * fails is logged and the remaining tenants are still swept. A deployment without a tenant service (the distributed
 * {@code execution-app} carries none) sweeps the scheduler thread's current tenant.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class ToolInvocationLogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ToolInvocationLogRetentionJob.class);

    private final long retentionDays;
    private final ObjectProvider<TenantService> tenantServiceObjectProvider;
    private final ToolInvocationLogService toolInvocationLogService;

    @SuppressFBWarnings("EI")
    public ToolInvocationLogRetentionJob(
        @Value("${bytechef.tool-invocation-log.retention-days:90}") long retentionDays,
        ToolInvocationLogService toolInvocationLogService,
        ObjectProvider<TenantService> tenantServiceObjectProvider) {

        this.retentionDays = retentionDays;
        this.tenantServiceObjectProvider = tenantServiceObjectProvider;
        this.toolInvocationLogService = toolInvocationLogService;
    }

    @Scheduled(cron = "${bytechef.tool-invocation-log.retention-cron:0 30 2 * * *}")
    public void purgeExpiredLogs() {
        Instant cutoff = Instant.now()
            .minus(retentionDays, ChronoUnit.DAYS);

        TenantService tenantService = tenantServiceObjectProvider.getIfAvailable();

        if (tenantService == null) {
            purgeExpiredLogsForCurrentTenant(cutoff);

            return;
        }

        for (String tenantId : tenantService.getTenantIds()) {
            try {
                TenantContext.runWithTenantId(tenantId, () -> purgeExpiredLogsForCurrentTenant(cutoff));
            } catch (RuntimeException exception) {
                log.warn("Tool invocation log retention sweep failed for tenant {}", tenantId, exception);
            }
        }
    }

    private void purgeExpiredLogsForCurrentTenant(Instant cutoff) {
        int deleted = toolInvocationLogService.deleteToolInvocationLogsCreatedBefore(cutoff);

        if (deleted > 0) {
            log.info(
                "Tool invocation log retention: deleted {} row(s) older than {} for tenant {}", deleted, cutoff,
                TenantContext.getCurrentTenantId());
        } else {
            log.debug(
                "Tool invocation log retention: no rows older than {} for tenant {}", cutoff,
                TenantContext.getCurrentTenantId());
        }
    }
}
