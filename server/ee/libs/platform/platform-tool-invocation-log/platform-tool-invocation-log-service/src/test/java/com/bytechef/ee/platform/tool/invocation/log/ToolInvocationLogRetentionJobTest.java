/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.tool.invocation.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.tenant.TenantContext;
import com.bytechef.tenant.service.TenantService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ToolInvocationLogRetentionJobTest {

    @AfterEach
    void afterEach() {
        TenantContext.resetCurrentTenantId();
    }

    @Test
    void testPurgeDeletesRowsOlderThanRetentionWindow() {
        ToolInvocationLogService toolInvocationLogService = mock(ToolInvocationLogService.class);
        TenantService tenantService = mock(TenantService.class);

        when(tenantService.getTenantIds()).thenReturn(List.of(TenantContext.DEFAULT_TENANT_ID));
        when(toolInvocationLogService.deleteToolInvocationLogsCreatedBefore(any())).thenReturn(3);

        ToolInvocationLogRetentionJob retentionJob = new ToolInvocationLogRetentionJob(
            90, toolInvocationLogService, tenantServiceProvider(tenantService));

        Instant before = Instant.now()
            .minus(90, ChronoUnit.DAYS);

        retentionJob.purgeExpiredLogs();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);

        verify(toolInvocationLogService).deleteToolInvocationLogsCreatedBefore(captor.capture());

        Instant cutoff = captor.getValue();

        assertThat(cutoff).isBetween(before.minusSeconds(60), before.plusSeconds(60));
    }

    @Test
    void testPurgeSweepsEveryTenantAndAFailingTenantDoesNotStopTheRest() {
        ToolInvocationLogService toolInvocationLogService = mock(ToolInvocationLogService.class);
        TenantService tenantService = mock(TenantService.class);
        List<String> sweptTenantIds = new ArrayList<>();

        when(tenantService.getTenantIds()).thenReturn(List.of("tenant1", "tenant2"));
        when(toolInvocationLogService.deleteToolInvocationLogsCreatedBefore(any())).thenAnswer(invocation -> {
            String tenantId = TenantContext.getCurrentTenantId();

            sweptTenantIds.add(tenantId);

            if ("tenant1".equals(tenantId)) {
                throw new IllegalStateException("schema unavailable");
            }

            return 4;
        });

        ToolInvocationLogRetentionJob retentionJob = new ToolInvocationLogRetentionJob(
            90, toolInvocationLogService, tenantServiceProvider(tenantService));

        retentionJob.purgeExpiredLogs();

        assertThat(sweptTenantIds).containsExactly("tenant1", "tenant2");
        assertThat(TenantContext.getCurrentTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
    }

    @Test
    void testPurgeRunsInTheCurrentTenantWhenNoTenantServiceIsAvailable() {
        ToolInvocationLogService toolInvocationLogService = mock(ToolInvocationLogService.class);

        ToolInvocationLogRetentionJob retentionJob = new ToolInvocationLogRetentionJob(
            90, toolInvocationLogService, tenantServiceProvider(null));

        retentionJob.purgeExpiredLogs();

        verify(toolInvocationLogService, times(1)).deleteToolInvocationLogsCreatedBefore(any());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TenantService> tenantServiceProvider(@Nullable TenantService tenantService) {
        ObjectProvider<TenantService> tenantServiceProvider = mock(ObjectProvider.class);

        when(tenantServiceProvider.getIfAvailable()).thenReturn(tenantService);

        return tenantServiceProvider;
    }
}
