/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.web.graphql.facade;

import com.bytechef.ee.ai.copilot.workflow.WorkflowDescriptionCopilotRequest;
import com.bytechef.ee.ai.copilot.workflow.WorkflowDescriptionCopilotResult;

/**
 * Facade for the {@code generateWorkflowDescription} GraphQL mutation.
 *
 * @version ee
 * @author Ivica Cardic
 */
public interface WorkflowDescriptionCopilotFacade {

    WorkflowDescriptionCopilotResult generateWorkflowDescription(WorkflowDescriptionCopilotRequest request);
}
