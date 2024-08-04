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

package com.bytechef.embedded.configuration.web.rest;

import com.bytechef.embedded.configuration.facade.UnifiedIntegrationFacade;
import com.bytechef.embedded.configuration.service.UnifiedIntegrationService;
import com.bytechef.embedded.configuration.web.rest.model.UnifiedIntegrationModel;
import java.util.List;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Ivica Cardic
 */
@RestController
@RequestMapping("${openapi.openAPIDefinition.base-path.embedded:}/internal")
public class UnifiedIntegrationApiController implements UnifiedIntegrationApi {

    private final ConversionService conversionService;
    private final UnifiedIntegrationFacade unifiedIntegrationFacade;
    private final UnifiedIntegrationService unifiedIntegrationService;

    public UnifiedIntegrationApiController(
        ConversionService conversionService, UnifiedIntegrationFacade unifiedIntegrationFacade,
        UnifiedIntegrationService unifiedIntegrationService) {

        this.conversionService = conversionService;
        this.unifiedIntegrationFacade = unifiedIntegrationFacade;
        this.unifiedIntegrationService = unifiedIntegrationService;
    }

    @Override
    public ResponseEntity<Void> enableUnifiedIntegration(String componentName, Boolean enable) {
        unifiedIntegrationFacade.enableUnifiedIntegration(componentName, enable);

        return ResponseEntity.noContent()
            .build();
    }

    @Override
    public ResponseEntity<List<UnifiedIntegrationModel>> getUnifiedIntegrations() {
        return ResponseEntity.ok(
            unifiedIntegrationService.getUnifiedComponents()
                .stream()
                .map(unifiedComponent -> conversionService.convert(unifiedComponent, UnifiedIntegrationModel.class))
                .toList());
    }
}
