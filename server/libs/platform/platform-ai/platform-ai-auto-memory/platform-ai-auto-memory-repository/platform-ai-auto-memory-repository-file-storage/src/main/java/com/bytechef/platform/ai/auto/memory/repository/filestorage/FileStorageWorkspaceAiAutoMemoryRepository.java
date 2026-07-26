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

package com.bytechef.platform.ai.auto.memory.repository.filestorage;

import com.bytechef.platform.ai.auto.memory.WorkspaceAiAutoMemory;
import com.bytechef.platform.ai.auto.memory.repository.WorkspaceAiAutoMemoryRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;

/**
 * File-storage backed {@link WorkspaceAiAutoMemoryRepository}.
 *
 * <p>
 * Membership is not a collection of its own here: it is a field on each stored memory document, so this repository
 * derives membership from {@link FileStorageAiAutoMemoryRepository} rather than maintaining a parallel set of rows that
 * could drift out of step with the memories themselves. Consequently {@link #save(WorkspaceAiAutoMemory)} is not
 * supported — membership is established by saving the memory into its workspace.
 * </p>
 *
 * @author Ivica Cardic
 */
public class FileStorageWorkspaceAiAutoMemoryRepository implements WorkspaceAiAutoMemoryRepository {

    private final FileStorageAiAutoMemoryRepository fileStorageAiAutoMemoryRepository;

    @SuppressFBWarnings("EI")
    public FileStorageWorkspaceAiAutoMemoryRepository(
        FileStorageAiAutoMemoryRepository fileStorageAiAutoMemoryRepository) {

        this.fileStorageAiAutoMemoryRepository = fileStorageAiAutoMemoryRepository;
    }

    @Override
    public WorkspaceAiAutoMemory save(WorkspaceAiAutoMemory membership) {
        throw new UnsupportedOperationException(
            "Membership is stored on the memory itself; save the memory into its workspace instead");
    }

    @Override
    public void deleteAll() {
        fileStorageAiAutoMemoryRepository.deleteAll();
    }

    @Override
    public List<WorkspaceAiAutoMemory> findAllByWorkspaceId(long workspaceId) {
        return fileStorageAiAutoMemoryRepository.findDocumentsByWorkspaceId(workspaceId)
            .stream()
            .map(document -> new WorkspaceAiAutoMemory(document.workspaceId(), document.id()))
            .toList();
    }

    @Override
    public Optional<WorkspaceAiAutoMemory> findByAiAutoMemoryId(long aiAutoMemoryId) {
        return fileStorageAiAutoMemoryRepository.findDocumentByAiAutoMemoryId(aiAutoMemoryId)
            .map(document -> new WorkspaceAiAutoMemory(document.workspaceId(), document.id()));
    }

    @Override
    public Optional<WorkspaceAiAutoMemory> findByWorkspaceIdAndAiAutoMemoryId(long workspaceId, long aiAutoMemoryId) {
        return findByAiAutoMemoryId(aiAutoMemoryId)
            .filter(membership -> {
                Long membershipWorkspaceId = membership.getWorkspaceId();

                return membershipWorkspaceId != null && membershipWorkspaceId == workspaceId;
            });
    }
}
