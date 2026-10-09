/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.tool.invocation.log.repository;

import com.bytechef.ee.platform.tool.invocation.log.ToolInvocationLog;
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
public interface ToolInvocationLogRepository extends ListCrudRepository<ToolInvocationLog, Long> {

    // The two date bounds are cast explicitly: a null Instant is bound with an unspecified JDBC type, and a bare
    // parameter in an "IS NULL" test gives PostgreSQL nothing to infer from, so it fails the statement with
    // "could not determine data type of parameter". The other bounds are typed by their equality comparison.
    @Query("""
        SELECT * FROM tool_invocation_log
        WHERE (:workspaceId IS NULL OR workspace_id = :workspaceId)
            AND (:environment IS NULL OR environment = :environment)
            AND (:surface IS NULL OR surface = :surface)
            AND (:outcome IS NULL OR outcome = :outcome)
            AND (:mcpServerId IS NULL OR mcp_server_id = :mcpServerId)
            AND (:connectedUserId IS NULL OR connected_user_id = :connectedUserId)
            AND (:integrationInstanceId IS NULL OR integration_instance_id = :integrationInstanceId)
            AND (:appId IS NULL OR app_id = :appId)
            AND (CAST(:startDate AS TIMESTAMP) IS NULL OR created_date >= CAST(:startDate AS TIMESTAMP))
            AND (CAST(:endDate AS TIMESTAMP) IS NULL OR created_date <= CAST(:endDate AS TIMESTAMP))
        ORDER BY created_date DESC
        LIMIT :limit OFFSET :offset
        """)
    List<ToolInvocationLog> findFiltered(
        @Param("workspaceId") @Nullable Long workspaceId, @Param("environment") @Nullable Integer environment,
        @Param("surface") @Nullable Integer surface, @Param("outcome") @Nullable Integer outcome,
        @Param("mcpServerId") @Nullable Long mcpServerId, @Param("connectedUserId") @Nullable Long connectedUserId,
        @Param("integrationInstanceId") @Nullable Long integrationInstanceId, @Param("appId") @Nullable Long appId,
        @Param("startDate") @Nullable Instant startDate, @Param("endDate") @Nullable Instant endDate,
        @Param("limit") int limit, @Param("offset") long offset);

    @Query("""
        SELECT COUNT(*) FROM tool_invocation_log
        WHERE (:workspaceId IS NULL OR workspace_id = :workspaceId)
            AND (:environment IS NULL OR environment = :environment)
            AND (:surface IS NULL OR surface = :surface)
            AND (:outcome IS NULL OR outcome = :outcome)
            AND (:mcpServerId IS NULL OR mcp_server_id = :mcpServerId)
            AND (:connectedUserId IS NULL OR connected_user_id = :connectedUserId)
            AND (:integrationInstanceId IS NULL OR integration_instance_id = :integrationInstanceId)
            AND (:appId IS NULL OR app_id = :appId)
            AND (CAST(:startDate AS TIMESTAMP) IS NULL OR created_date >= CAST(:startDate AS TIMESTAMP))
            AND (CAST(:endDate AS TIMESTAMP) IS NULL OR created_date <= CAST(:endDate AS TIMESTAMP))
        """)
    long countFiltered(
        @Param("workspaceId") @Nullable Long workspaceId, @Param("environment") @Nullable Integer environment,
        @Param("surface") @Nullable Integer surface, @Param("outcome") @Nullable Integer outcome,
        @Param("mcpServerId") @Nullable Long mcpServerId, @Param("connectedUserId") @Nullable Long connectedUserId,
        @Param("integrationInstanceId") @Nullable Long integrationInstanceId, @Param("appId") @Nullable Long appId,
        @Param("startDate") @Nullable Instant startDate, @Param("endDate") @Nullable Instant endDate);

    @Modifying
    @Query("DELETE FROM tool_invocation_log WHERE created_date < :instant")
    int deleteByCreatedDateBefore(@Param("instant") Instant instant);
}
