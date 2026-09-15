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

package com.bytechef.platform.configuration.domain;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.evaluator.Evaluator;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Optional;

/**
 * The workflow node that owns a set of cluster elements: either a task (an AI agent, a data stream) or a trigger (a
 * voice session). Editor facades receive only the root's node name alongside a cluster element's type and name, and
 * resolve the root through {@link #of(Workflow, String)} so a trigger root behaves the same as a task root.
 *
 * <p>
 * A trigger is looked up first and the task lookup runs only when no trigger has that name. Node names are unique
 * across triggers and tasks, so a task root resolves exactly as {@link Workflow#getTask(String)} always did.
 *
 * @author Ivica Cardic
 */
@SuppressFBWarnings("EI")
public final class ClusterRootWorkflowNode {

    private final WorkflowTask workflowTask;
    private final WorkflowTrigger workflowTrigger;

    private ClusterRootWorkflowNode(WorkflowTask workflowTask, WorkflowTrigger workflowTrigger) {
        this.workflowTask = workflowTask;
        this.workflowTrigger = workflowTrigger;
    }

    public static ClusterRootWorkflowNode of(Workflow workflow, String workflowNodeName) {
        Optional<WorkflowTrigger> workflowTriggerOptional = WorkflowTrigger.fetch(workflow, workflowNodeName);

        if (workflowTriggerOptional.isPresent()) {
            return new ClusterRootWorkflowNode(null, workflowTriggerOptional.get());
        }

        return new ClusterRootWorkflowNode(workflow.getTask(workflowNodeName), null);
    }

    public static ClusterRootWorkflowNode of(WorkflowTask workflowTask) {
        return new ClusterRootWorkflowNode(workflowTask, null);
    }

    public static ClusterRootWorkflowNode of(WorkflowTrigger workflowTrigger) {
        return new ClusterRootWorkflowNode(null, workflowTrigger);
    }

    public Map<String, ?> evaluateParameters(Map<String, ?> context, Evaluator evaluator) {
        if (workflowTrigger != null) {
            return workflowTrigger.evaluateParameters(context, evaluator);
        }

        return workflowTask.evaluateParameters(context, evaluator);
    }

    /**
     * Parses the root's {@code clusterElements} extension. Both slot shapes are supported: a single element stored as
     * an object and multiple elements stored as a list.
     */
    public ClusterElementMap getClusterElementMap() {
        return ClusterElementMap.of(getExtensions());
    }

    public Map<String, ?> getExtensions() {
        if (workflowTrigger != null) {
            return workflowTrigger.getExtensions();
        }

        return workflowTask.getExtensions();
    }

    public String getName() {
        if (workflowTrigger != null) {
            return workflowTrigger.getName();
        }

        return workflowTask.getName();
    }

    public String getType() {
        if (workflowTrigger != null) {
            return workflowTrigger.getType();
        }

        return workflowTask.getType();
    }

    /**
     * Whether the root is a trigger. A trigger has no upstream workflow nodes, so callers must not ask for previous
     * node outputs on its behalf: {@code Workflow#getTasks(String)} treats a name it cannot find among the tasks as "no
     * boundary" and would report every task as a predecessor.
     */
    public boolean isTrigger() {
        return workflowTrigger != null;
    }
}
