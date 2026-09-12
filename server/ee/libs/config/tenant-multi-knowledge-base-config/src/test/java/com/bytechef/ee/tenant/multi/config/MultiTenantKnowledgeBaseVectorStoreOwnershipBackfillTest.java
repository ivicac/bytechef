/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.tenant.multi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.tenant.TenantContext;
import com.bytechef.tenant.service.TenantService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Each tenant keeps its chunks in its own schema, so the sweep has to run once per tenant inside that tenant's context.
 * A single-tenant sweep would reach whichever schema the boot thread happened to carry and leave every other tenant's
 * documents matching no read filter -- invisible, which is the failure the backfill exists to prevent.
 *
 * <p>
 * The table name supplier is what records the answer, because that is where the schema is actually resolved in
 * production: asserting that {@code backfill()} was called N times would pass even if all N calls ran against one
 * tenant's schema.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class MultiTenantKnowledgeBaseVectorStoreOwnershipBackfillTest {

    private static final String FIRST_TENANT_ID = "000001";
    private static final String SECOND_TENANT_ID = "000002";

    @AfterEach
    void tearDown() {
        TenantContext.resetCurrentTenantId();
    }

    @Test
    void testTheSweepRunsOnceInsideEachTenantsOwnContext() {
        List<String> sweptTenantIds = new ArrayList<>();

        TenantService tenantService = mock(TenantService.class);

        when(tenantService.getTenantIds()).thenReturn(List.of(FIRST_TENANT_ID, SECOND_TENANT_ID));

        // Left unstubbed on purpose: the mock answers the table-existence probe with null, so the sweep takes its
        // "table is not there yet" branch and this test stays about which tenants it visits.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        MultiTenantKnowledgeBaseVectorStoreOwnershipBackfill backfill =
            new MultiTenantKnowledgeBaseVectorStoreOwnershipBackfill(
                jdbcTemplate,
                () -> {
                    sweptTenantIds.add(TenantContext.getCurrentTenantId());

                    return "kb_vector_store";
                },
                tenantService);

        backfill.afterPropertiesSet();

        assertThat(sweptTenantIds).containsExactly(FIRST_TENANT_ID, SECOND_TENANT_ID);
    }
}
