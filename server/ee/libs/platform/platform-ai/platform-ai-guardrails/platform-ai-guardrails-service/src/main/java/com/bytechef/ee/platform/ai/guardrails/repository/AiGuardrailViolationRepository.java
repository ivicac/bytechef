/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.repository;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolation;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiGuardrailViolationRepository extends ListCrudRepository<AiGuardrailViolation, Long> {

    /**
     * Counts a workspace's records since {@code since}, for the daily cap.
     *
     * <p>
     * Uses {@code IS NOT DISTINCT FROM} rather than {@code =} so the tenant-default rows — the ones whose
     * {@code workspace_id} is null — count against their own bucket. A plain {@code =} never matches null, which would
     * leave exactly that bucket uncapped, and it is the bucket every unattributed call lands in.
     * </p>
     */
    @Query("""
        SELECT COUNT(*) FROM ai_guardrail_violation
        WHERE workspace_id IS NOT DISTINCT FROM :workspaceId AND created_date >= :since
        """)
    long countForWorkspaceSince(@Param("workspaceId") @Nullable Long workspaceId, @Param("since") Instant since);

    List<AiGuardrailViolation> findAllByWorkspaceIdOrderByCreatedDateDesc(@Nullable Long workspaceId);

    @Modifying
    @Query("DELETE FROM ai_guardrail_violation WHERE created_date < :cutoff")
    int deleteByCreatedDateBefore(@Param("cutoff") Instant cutoff);
}
