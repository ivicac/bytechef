/*
 * Copyright 2023-present ByteChef Inc.
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

package com.bytechef.platform.datasync.configuration.service;

import com.bytechef.commons.util.OptionalUtils;
import com.bytechef.platform.datasync.configuration.domain.DataSync;
import com.bytechef.platform.datasync.configuration.repository.DataSyncRepository;
import java.util.List;
import org.apache.commons.lang3.Validate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
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
    public DataSync create(@NonNull DataSync dataSync) {
        Validate.notNull(dataSync, "'codeWorkflow' must not be null");
        Validate.isTrue(dataSync.getId() == null, "'id' must be null");
        Validate.notNull(dataSync.getName(), "'name' must not be null");

        return dataSyncRepository.save(dataSync);
    }

    @Override
    public void delete(long id) {
        dataSyncRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public DataSync getDataSync(long id) {
        return OptionalUtils.get(dataSyncRepository.findById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataSync> getDataSyncs() {
        return dataSyncRepository.findAll();
    }

    @Override
    public DataSync update(@NonNull DataSync dataSync) {
        Validate.notNull(dataSync, "'dataSync' must not be null");

        DataSync curDataSync = getDataSync(Validate.notNull(dataSync.getId(), "id"));

        curDataSync.setDescription(dataSync.getDescription());
        curDataSync.setIcon(curDataSync.getIcon());
        curDataSync.setName(Validate.notNull(dataSync.getName(), "name"));

        return dataSyncRepository.save(curDataSync);
    }
}
