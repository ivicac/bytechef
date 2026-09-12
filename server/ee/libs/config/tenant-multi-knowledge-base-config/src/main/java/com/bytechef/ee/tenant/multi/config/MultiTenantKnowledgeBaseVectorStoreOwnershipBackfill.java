/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.tenant.multi.config;

import com.bytechef.platform.knowledgebase.service.KnowledgeBaseVectorStoreOwnershipBackfill;
import com.bytechef.tenant.TenantContext;
import com.bytechef.tenant.service.TenantService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs the chunk ownership backfill once per tenant.
 *
 * <p>
 * Every tenant keeps its chunks in its own {@code vectorstore} schema, so the single-tenant sweep would reach whichever
 * schema the boot thread happened to carry and leave every other tenant's documents invisible under the new read
 * filter. The table name supplier resolves the schema from {@link TenantContext} on each call, which is why the sweep
 * has to be driven inside {@code runWithTenantId} rather than once with a fixed name.
 *
 * <p>
 * Only the tenants that exist at startup are swept. A schema created later has no chunks predating chunk ownership, so
 * there is nothing in it to backfill.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class MultiTenantKnowledgeBaseVectorStoreOwnershipBackfill extends KnowledgeBaseVectorStoreOwnershipBackfill {

    private final TenantService tenantService;

    @SuppressFBWarnings("EI2")
    public MultiTenantKnowledgeBaseVectorStoreOwnershipBackfill(
        JdbcTemplate pgVectorJdbcTemplate, Supplier<String> fullTableNameSupplier, TenantService tenantService) {

        super(pgVectorJdbcTemplate, fullTableNameSupplier);

        this.tenantService = tenantService;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> tenantIds = tenantService.getTenantIds();

        for (String tenantId : tenantIds) {
            TenantContext.runWithTenantId(tenantId, this::backfill);
        }
    }
}
