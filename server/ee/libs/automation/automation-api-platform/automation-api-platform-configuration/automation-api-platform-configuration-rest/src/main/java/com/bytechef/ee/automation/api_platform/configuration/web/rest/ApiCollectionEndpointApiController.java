/*
 * Copyright 2023-present ByteChef Inc.
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.api_platform.configuration.web.rest;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.automation.api_platform.configuration.dto.ApiCollectionEndpointDTO;
import com.bytechef.ee.automation.api_platform.configuration.facade.ApiCollectionFacade;
import com.bytechef.ee.automation.api_platform.configuration.service.ApiCollectionEndpointService;
import com.bytechef.ee.automation.api_platform.configuration.web.rest.model.ApiCollectionEndpointModel;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@RestController
@RequestMapping("${openapi.openAPIDefinition.base-path.automation:}/internal")
@ConditionalOnCoordinator
public class ApiCollectionEndpointApiController implements ApiCollectionEndpointApi {

    private final ApiCollectionEndpointService apiCollectionEndpointService;
    private final ApiCollectionFacade apiCollectionFacade;
    private final ConversionService conversionService;

    public ApiCollectionEndpointApiController(
        ApiCollectionEndpointService apiCollectionEndpointService, ApiCollectionFacade apiCollectionFacade,
        ConversionService conversionService) {

        this.apiCollectionEndpointService = apiCollectionEndpointService;
        this.apiCollectionFacade = apiCollectionFacade;
        this.conversionService = conversionService;
    }

    @Override
    public ResponseEntity<ApiCollectionEndpointModel> createApiCollectionEndpoint(
        ApiCollectionEndpointModel apiCollectionEndpointModel) {

        return ResponseEntity.ok(
            conversionService.convert(
                apiCollectionFacade.createApiCollectionEndpoint(
                    conversionService.convert(apiCollectionEndpointModel, ApiCollectionEndpointDTO.class)),
                ApiCollectionEndpointModel.class));
    }

    @Override
    public ResponseEntity<Void> deleteApiCollectionEndpoint(Long id) {
        apiCollectionEndpointService.delete(id);

        return ResponseEntity.noContent()
            .build();
    }

    @Override
    public ResponseEntity<ApiCollectionEndpointModel> updateApiCollectionEndpoint(
        Long id, ApiCollectionEndpointModel apiCollectionEndpointModel) {

        apiCollectionEndpointModel = apiCollectionEndpointModel.id(id);

        return ResponseEntity.ok(
            conversionService.convert(
                apiCollectionFacade.updateApiCollectionEndpoint(
                    conversionService.convert(apiCollectionEndpointModel, ApiCollectionEndpointDTO.class)),
                ApiCollectionEndpointModel.class));
    }
}
