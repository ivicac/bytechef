/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.workflow.execution.cost.repository;

import com.bytechef.ee.automation.workflow.execution.cost.domain.WorkflowExecutionCost;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Workspace-scoped reads filter {@code workflow_execution_cost.workspace_id} directly; a workspace-less cost row (an
 * editor run or an embedded execution) is invisible to them, which is the intended behavior.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Repository
public interface WorkflowExecutionCostRepository extends ListCrudRepository<WorkflowExecutionCost, Long> {

    Optional<WorkflowExecutionCost> findByJobId(Long jobId);
}
