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

package com.bytechef.component.ai.agent.utils.cluster;

import static com.bytechef.component.definition.ai.agent.BaseToolFunction.TOOLS;

import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.ai.agent.memory.AutoMemoryDirectoryOps;
import com.bytechef.platform.ai.agent.memory.AutoMemoryResourceResolver;
import com.bytechef.platform.ai.agent.memory.AutoMemoryTools;
import com.bytechef.platform.ai.agent.memory.DbBackedAutoMemoryDirectoryOps;
import com.bytechef.platform.ai.agent.memory.MemoryResourceResolver;
import com.bytechef.platform.ai.auto.memory.AiAutoMemoryService;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ai.agent.ToolCallbackProviderFunction;
import com.bytechef.platform.constant.PlatformType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * Provides persistent long-term memory scoped to the running workflow deployment. The memory is backed by
 * {@link AiAutoMemoryService} and resolved per agent run from the action context: the platform must be
 * {@link PlatformType#AUTOMATION}, the deployment id comes from the job principal, the workspace id is read from the
 * trigger-injected {@code __jobParameters.contextStore.workspaceId} job metadata entry, and the environment is the
 * action's environment ordinal. When the running context has no resolvable deployment-scoped memory (e.g. an editor
 * test run or a non-automation platform), an inert empty tool provider is returned.
 *
 * @author Ivica Cardic
 */
public class AiAgentUtilsAutoMemoryTool {

    /**
     * Reserved job metadata key under which trigger-time {@code JobParameter} overrides are stored.
     */
    private static final String JOB_PARAMETERS_METADATA_KEY = "__jobParameters";

    /**
     * JobParameter key set by the context-store workflow-context contributor carrying the running workflow's workspace
     * id.
     */
    private static final String WORKSPACE_ID_JOB_PARAM_KEY = "contextStore.workspaceId";

    private final AiAutoMemoryService aiAutoMemoryService;

    public final ClusterElementDefinition<ToolCallbackProviderFunction> clusterElementDefinition;

    @SuppressFBWarnings("EI")
    public AiAgentUtilsAutoMemoryTool(AiAutoMemoryService aiAutoMemoryService) {
        this.aiAutoMemoryService = aiAutoMemoryService;

        this.clusterElementDefinition = ComponentDsl.<ToolCallbackProviderFunction>clusterElement("autoMemoryTool")
            .title("Auto Memory Tool")
            .description("Persistent long-term memory scoped to the running workflow deployment.")
            .type(TOOLS)
            .object(() -> this::apply);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private ToolCallbackProvider apply(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        ActionContextAware aware = (ActionContextAware) context;

        PlatformType platformType = aware.getPlatformType();
        Long deploymentId = aware.getJobPrincipalId();
        Long workspaceId = extractWorkspaceId(aware.getJobMetadata());
        Long environmentId = aware.getEnvironmentId();

        int environment = environmentId == null ? 0 : environmentId.intValue();

        if (platformType != PlatformType.AUTOMATION || workspaceId == null || deploymentId == null) {
            return ToolCallbackProvider.from(List.of());
        }

        MemoryResourceResolver resolver = new AutoMemoryResourceResolver(
            aiAutoMemoryService, workspaceId, deploymentId, environment);
        AutoMemoryDirectoryOps directoryOps = new DbBackedAutoMemoryDirectoryOps(
            aiAutoMemoryService, workspaceId, deploymentId, environment);

        AutoMemoryTools autoMemoryTools = new AutoMemoryTools(resolver, directoryOps);

        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(autoMemoryTools)
            .build()
            .getToolCallbacks();

        return ToolCallbackProvider.from(List.of(callbacks));
    }

    @SuppressWarnings("unchecked")
    private static Long extractWorkspaceId(Map<String, Object> jobMetadata) {
        Object jobParameters = jobMetadata.get(JOB_PARAMETERS_METADATA_KEY);

        if (!(jobParameters instanceof Map<?, ?> map)) {
            return null;
        }

        Object value = ((Map<String, ?>) map).get(WORKSPACE_ID_JOB_PARAM_KEY);

        if (value instanceof Number number) {
            return number.longValue();
        }

        return null;
    }
}
