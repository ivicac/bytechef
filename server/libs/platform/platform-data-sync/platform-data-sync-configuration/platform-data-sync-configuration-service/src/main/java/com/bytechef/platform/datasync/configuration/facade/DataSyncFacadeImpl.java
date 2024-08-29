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

package com.bytechef.platform.datasync.configuration.facade;

import com.bytechef.platform.datasync.configuration.domain.DataSync;
import com.bytechef.platform.datasync.configuration.service.DataSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Ivica Cardic
 */
@Service
@Transactional
public class DataSyncFacadeImpl implements DataSyncFacade {

    private final DataSyncService dataSyncService;

    public DataSyncFacadeImpl(DataSyncService dataSyncService, ObjectMapper objectMapper) {
        this.dataSyncService = dataSyncService;
    }

    @Override
    public DataSync create(@NonNull DataSync customComponent) {
//        sdkComponent.setDefinition("{}");
//
//        String name = sdkComponent.getName();
//
//        try {
//            Path openApiPath = Files.createTempFile("open_api_specification", ".yaml");
//
//            Files.writeString(openApiPath, sdkComponent.getSpecification());
//
//            Path outputPath = Files.createTempDirectory("open_api_output");
//
//            // Classloader issues due to Spring Boot dev mode's RestartClassloader
////            ComponentInitOpenApiGenerator generator = new ComponentInitOpenApiGenerator(
////                "com.bytechef.component", name.toLowerCase(), 1, false, openApiPath.toString(), outputPath.toString());
//
////            generator.generate();
//
//            Process process = new ProcessBuilder()
//                .directory(new File(
//                    "/Volumes/Data/bytechef/bytechef/server/ee/libs/platform/platform-api-connector/platform-api-connector-configuration/platform-api-connector-configuration-service/src/main/resources/cli-app/bin"))
//                .command(
//                    "sh",
//                    "-c",
//                    "./cli-app component init --internal-component true --open-api-path %s -o %s -n %s"
//                        .formatted(openApiPath, outputPath, name.toLowerCase()))
//                .redirectErrorStream(true)
//                .start();
//
//            process.waitFor();
//
//            Path definitionFilePath = outputPath.resolve(name.toLowerCase())
//                .resolve("src/test/resources/definition")
//                .resolve(name.toLowerCase() + "_v1.json");
//
//            sdkComponent.setFileName(Files.readString(definitionFilePath));
//        } catch (Exception e) {
//            throw new ComponentConfigurationException(e);
//        }

        return dataSyncService.create(customComponent);
    }

    @Transactional(readOnly = true)
    @Override
    public List<DataSync> getDataSyncs() {
        return dataSyncService.getDataSyncs();
    }
}
