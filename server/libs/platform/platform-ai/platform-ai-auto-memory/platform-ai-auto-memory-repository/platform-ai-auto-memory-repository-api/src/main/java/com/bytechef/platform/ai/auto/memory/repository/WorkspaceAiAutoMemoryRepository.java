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

package com.bytechef.platform.ai.auto.memory.repository;

import com.bytechef.platform.ai.auto.memory.WorkspaceAiAutoMemory;
import java.util.List;
import java.util.Optional;

/**
 * Storage contract for {@link WorkspaceAiAutoMemory} membership rows. JDBC binding in
 * {@code platform-ai-auto-memory-repository-jdbc}.
 *
 * @author Ivica Cardic
 */
public interface WorkspaceAiAutoMemoryRepository {

    WorkspaceAiAutoMemory save(WorkspaceAiAutoMemory membership);

    void deleteAll();

    List<WorkspaceAiAutoMemory> findAllByWorkspaceId(long workspaceId);

    Optional<WorkspaceAiAutoMemory> findByAiAutoMemoryId(long aiAutoMemoryId);

    Optional<WorkspaceAiAutoMemory> findByWorkspaceIdAndAiAutoMemoryId(long workspaceId, long aiAutoMemoryId);
}
