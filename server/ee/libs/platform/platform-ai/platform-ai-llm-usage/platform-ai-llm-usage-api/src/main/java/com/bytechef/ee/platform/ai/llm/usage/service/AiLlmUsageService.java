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

package com.bytechef.ee.platform.ai.llm.usage.service;

import com.bytechef.ee.platform.ai.llm.usage.AiLlmUsage;
import com.bytechef.ee.platform.ai.llm.usage.LlmUsageSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read/write service for the unified {@code ai_llm_usage} table. The gateway's facade calls {@link #create(AiLlmUsage)}
 * imperatively to persist a row built from routing-aware factories ({@code AiLlmUsage.forSuccess} / {@code .forError});
 * other agent surfaces (AI Hub, future ad-hoc AI agents) write through the
 * {@link com.bytechef.ee.platform.ai.llm.usage.LlmUsageRecorder} contract that the default implementation also exposes.
 *
 * @author Ivica Cardic
 */
public interface AiLlmUsageService {

    /**
     * Persists the usage row with its owning workspace. The workspace is required: every cost-dashboard query filters
     * {@code ai_llm_usage.workspace_id}, so a row saved without one would silently disappear from analytics.
     */
    void create(AiLlmUsage usage, Long workspaceId);

    /**
     * Persists a usage row for an embedded connected user. Embedded traffic has no workspace -- its connected user is
     * its whole scope -- so the row is written with a null {@code workspace_id} and {@code connectedUserId} in
     * {@code userId}, the column the spend rollup attributes gateway rows by. Kept apart from
     * {@link #create(AiLlmUsage, Long)} so that method's workspace stays required for every other writer.
     */
    void createForConnectedUser(AiLlmUsage usage, long connectedUserId);

    void deleteOlderThan(Instant date);

    void deleteOlderThanByWorkspace(Instant date, Long workspaceId);

    /**
     * Deletes the workspace-less usage rows created before {@code date}. Per-workspace retention never reaches them.
     */
    void deleteOlderThanWithoutWorkspace(Instant date);

    List<Long> findDistinctWorkspaceIds();

    Map<String, Double> getAverageLatencyByModel(Instant since);

    /**
     * Usage rows attributed to a specific owning container ({@code ownerId}) for one source — e.g. all AI_AGENT rows of
     * a workflow job. Feeds the per-job execution cost computation.
     */
    List<AiLlmUsage> getUsagesByOwner(LlmUsageSource source, long ownerId);

    List<AiLlmUsage> getRequestLogs(Instant start, Instant end);

    List<AiLlmUsage> getRequestLogsByWorkspace(Long workspaceId, Instant start, Instant end);

    /**
     * The workspace-less usage rows carrying a {@code userId}, created inside the range: the embedded connected-user
     * rows no per-workspace query can see. Callers still decide whether {@code userId} is a connected user, since that
     * column is shared by every writer.
     */
    List<AiLlmUsage> getConnectedUserRequestLogsWithoutWorkspace(Instant start, Instant end);
}
