/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.workflow.execution.exception;

import com.bytechef.exception.AbstractErrorType;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public class IntegrationWorkflowExecutionErrorType extends AbstractErrorType {

    public static final IntegrationWorkflowExecutionErrorType GET_WORKFLOW_EXECUTION =
        new IntegrationWorkflowExecutionErrorType(100);
    public static final IntegrationWorkflowExecutionErrorType LIST_WORKFLOW_EXECUTIONS =
        new IntegrationWorkflowExecutionErrorType(101);

    private IntegrationWorkflowExecutionErrorType(int errorKey) {
        super(IntegrationWorkflowExecutionErrorType.class, errorKey);
    }
}
