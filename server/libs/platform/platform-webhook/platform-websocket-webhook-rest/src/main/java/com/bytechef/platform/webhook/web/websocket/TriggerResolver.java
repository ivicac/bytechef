/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.webhook.web.websocket;

import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.workflow.WorkflowExecutionId;

/**
 * Finds the deployed trigger a voice WebSocket upgrade addresses.
 *
 * @author Ivica Cardic
 */
interface TriggerResolver {

    /**
     * Whether the deployment the webhook id names has this workflow enabled — the same check an HTTP webhook delivery
     * makes before it runs anything.
     */
    boolean isWorkflowEnabled(WorkflowExecutionId workflowExecutionId);

    /**
     * The environment the deployment the webhook id names runs in, as the trigger pre-send processors resolve it.
     */
    long getEnvironmentId(WorkflowExecutionId workflowExecutionId);

    WorkflowTrigger resolve(WorkflowExecutionId workflowExecutionId);
}
