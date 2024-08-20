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

package com.bytechef.platform.custom_component.file.storage.config;

import com.bytechef.config.ApplicationProperties;
import com.bytechef.config.ApplicationProperties.CustomComponent.FileStorage.Provider;
import com.bytechef.ee.file.storage.aws.service.AwsFileStorageService;
import com.bytechef.file.storage.filesystem.service.FilesystemFileStorageService;
import com.bytechef.file.storage.service.FileStorageService;
import com.bytechef.platform.custom_component.file.storage.CustomComponentFileStorage;
import com.bytechef.platform.custom_component.file.storage.CustomComponentFileStorageImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Ivica Cardic
 */
@Configuration
public class CustomComponentFileStorageConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(CustomComponentFileStorageConfiguration.class);

    private final ApplicationProperties applicationProperties;

    @SuppressFBWarnings("EI")
    public CustomComponentFileStorageConfiguration(ApplicationProperties applicationProperties) {
        this.applicationProperties = applicationProperties;
    }

    @Bean
    CustomComponentFileStorage customComponentFileStorage(ApplicationProperties applicationProperties) {
        Provider provider = applicationProperties.getCustomComponent()
            .getFileStorage()
            .getProvider();

        if (provider == null) {
            provider = Provider.FILESYSTEM;
        }

        if (logger.isInfoEnabled()) {
            logger.info("Custom component file storage provider type enabled: %s".formatted(provider));
        }

        return new CustomComponentFileStorageImpl(getFileStorageService(provider));
    }

    private FileStorageService getFileStorageService(Provider provider) {
        return switch (provider) {
            case Provider.AWS -> new AwsFileStorageService();
            default -> new FilesystemFileStorageService(getBasedir());
        };
    }

    private String getBasedir() {
        return applicationProperties.getFileStorage()
            .getFilesystem()
            .getBasedir();
    }
}
