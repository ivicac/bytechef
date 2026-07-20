/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.workflow.execution.cost.repository;

import com.bytechef.ee.automation.workflow.execution.cost.domain.WorkspaceWorkflowExecutionCost;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Repository
public interface WorkspaceWorkflowExecutionCostRepository
    extends ListCrudRepository<WorkspaceWorkflowExecutionCost, Long> {

    Optional<WorkspaceWorkflowExecutionCost> findByWorkflowExecutionCostId(long workflowExecutionCostId);

    @Query("""
        SELECT COALESCE(SUM(workflow_execution_cost.total_cost), 0)
        FROM workflow_execution_cost
        JOIN workspace_workflow_execution_cost
            ON workspace_workflow_execution_cost.workflow_execution_cost_id = workflow_execution_cost.id
        WHERE workspace_workflow_execution_cost.workspace_id = :workspaceId
            AND workflow_execution_cost.created_date >= :since
        """)
    BigDecimal sumTotalCostByWorkspaceIdSince(@Param("workspaceId") long workspaceId, @Param("since") Instant since);
}
