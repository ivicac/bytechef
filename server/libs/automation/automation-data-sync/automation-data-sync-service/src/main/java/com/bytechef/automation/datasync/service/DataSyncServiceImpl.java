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

package com.bytechef.automation.datasync.service;

import com.bytechef.automation.datasync.domain.DataSync;
import com.bytechef.automation.datasync.repository.DataSyncRepository;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the {@link DataSyncService} interface.
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
public class DataSyncServiceImpl implements DataSyncService {

    private final DataSyncRepository dataSyncRepository;

    public DataSyncServiceImpl(DataSyncRepository dataSyncRepository) {
        this.dataSyncRepository = dataSyncRepository;
    }

    @Override
    public DataSync create(DataSync dataSync) {
        return dataSyncRepository.save(dataSync);
    }

    @Override
    public void delete(long id) {
        dataSyncRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataSync> fetchDataSync(long id) {
        return dataSyncRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public DataSync getDataSync(long id) {
        return dataSyncRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("DataSync with id " + id + " not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataSync> getDataSyncs(@Nullable Long workspaceId) {
        if (workspaceId == null) {
            return dataSyncRepository.findAll();
        }

        return dataSyncRepository.findByWorkspaceId(workspaceId);
    }

    @Override
    public DataSync update(DataSync dataSync) {
        DataSync currentDataSync = getDataSync(dataSync.getId());

        currentDataSync.setName(dataSync.getName());
        currentDataSync.setTitle(dataSync.getTitle());
        currentDataSync.setDescription(dataSync.getDescription());
        currentDataSync.setWorkspaceId(dataSync.getWorkspaceId());
        currentDataSync.setProjectId(dataSync.getProjectId());
        currentDataSync.setTriggerType(dataSync.getTriggerType());
        currentDataSync.setTriggerParameters(dataSync.getTriggerParameters());
        currentDataSync.setVersion(dataSync.getVersion());

        return dataSyncRepository.save(currentDataSync);
    }

    @Override
    public DataSync update(long id, List<Long> tagIds) {
        DataSync dataSync = getDataSync(id);

        dataSync.setTagIds(tagIds);

        return dataSyncRepository.save(dataSync);
    }
}
