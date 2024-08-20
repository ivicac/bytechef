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

package com.bytechef.ee.platform.api_connector.configuration.dto;

import com.bytechef.ee.platform.api_connector.configuration.domain.ApiConnector;
import com.bytechef.ee.platform.api_connector.configuration.domain.ApiConnectorEndpoint;
import java.time.LocalDateTime;
import java.util.List;

public record ApiConnectorDTO(
    int connectorVersion, String createdBy, LocalDateTime createdDate, String definition, String description,
    boolean enabled, List<ApiConnectorEndpoint> endpoints, String icon, Long id, String lastModifiedBy,
    LocalDateTime lastModifiedDate, String name, String specification, String title, int version) {

    public ApiConnectorDTO(
        ApiConnector apiConnector, String definition, String specification, List<ApiConnectorEndpoint> endpoints) {

        this(
            apiConnector.getConnectorVersion(), apiConnector.getCreatedBy(), apiConnector.getCreatedDate(),
            definition, apiConnector.getDescription(), apiConnector.getEnabled(), endpoints,
            apiConnector.getIcon(), apiConnector.getId(), apiConnector.getLastModifiedBy(),
            apiConnector.getLastModifiedDate(), apiConnector.getName(), specification, apiConnector.getTitle(),
            apiConnector.getVersion());
    }
}
