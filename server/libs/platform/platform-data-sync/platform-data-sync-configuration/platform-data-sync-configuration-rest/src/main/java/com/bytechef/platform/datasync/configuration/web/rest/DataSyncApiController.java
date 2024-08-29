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

package com.bytechef.platform.datasync.configuration.web.rest;

import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.platform.datasync.configuration.domain.DataSync;
import com.bytechef.platform.datasync.configuration.facade.DataSyncFacade;
import com.bytechef.platform.datasync.configuration.service.DataSyncService;
import com.bytechef.platform.datasync.configuration.web.rest.model.DataSyncModel;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Ivica Cardic
 */
@RestController
@RequestMapping("${openapi.openAPIDefinition.base-path.platform:}/internal")
public class DataSyncApiController implements DataSyncApi {

    private final DataSyncFacade dataSyncFacade;
    private final DataSyncService dataSyncService;
    private final ConversionService conversionService;

    @SuppressFBWarnings("EI")
    public DataSyncApiController(
        DataSyncFacade dataSyncFacade, DataSyncService dataSyncService,
        ConversionService conversionService) {

        this.dataSyncFacade = dataSyncFacade;
        this.dataSyncService = dataSyncService;
        this.conversionService = conversionService;
    }

    @Override
    public ResponseEntity<DataSyncModel> createDataSync(DataSyncModel dataSyncModel) {
        return ResponseEntity.ok(
            conversionService.convert(
                dataSyncFacade.create(conversionService.convert(null, DataSync.class)),
                DataSyncModel.class));
    }

    @Override
    public ResponseEntity<Void> deleteDataSync(Long id) {
        dataSyncService.delete(id);

        return ResponseEntity.ok()
            .build();
    }

    @Override
    public ResponseEntity<DataSyncModel> getDataSync(Long id) {
        return ResponseEntity.ok(
            conversionService.convert(dataSyncService.getDataSync(id), DataSyncModel.class));
    }

    @Override
    public ResponseEntity<List<DataSyncModel>> getDataSyncs() {
        return ResponseEntity.ok(
            CollectionUtils.map(
                dataSyncFacade.getDataSyncs(),
                openDataSync -> conversionService.convert(openDataSync, DataSyncModel.class)));
    }

    @Override
    public ResponseEntity<DataSyncModel> updateDataSync(Long id, DataSyncModel dataSyncModel) {
        return ResponseEntity.ok(
            conversionService.convert(
                dataSyncService.update(conversionService.convert(null, DataSync.class)), DataSyncModel.class));
    }
}
