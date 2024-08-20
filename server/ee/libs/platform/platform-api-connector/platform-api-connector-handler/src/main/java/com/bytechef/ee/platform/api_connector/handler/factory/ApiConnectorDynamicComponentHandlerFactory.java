/*
 * Copyright 2023-present ByteChef Inc.
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.api_connector.handler.factory;

import com.bytechef.component.ComponentHandler;
import com.bytechef.ee.platform.api_connector.configuration.domain.ApiConnector;
import com.bytechef.ee.platform.api_connector.configuration.service.ApiConnectorService;
import com.bytechef.ee.platform.api_connector.handler.helper.ComponentDefinitionHelper;
import com.bytechef.platform.component.registry.factory.DynamicComponentHandlerFactory;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class ApiConnectorDynamicComponentHandlerFactory implements DynamicComponentHandlerFactory {

    private final ApiConnectorService apiConnectorService;
    private final ComponentDefinitionHelper componentDefinitionHelper;

    public ApiConnectorDynamicComponentHandlerFactory(
        ApiConnectorService apiConnectorService, ComponentDefinitionHelper componentDefinitionHelper) {

        this.apiConnectorService = apiConnectorService;
        this.componentDefinitionHelper = componentDefinitionHelper;
    }

    @Override
    public List<? extends ComponentHandler> getComponentHandlers() {
        return apiConnectorService.getApiConnectors()
            .stream()
            .filter(ApiConnector::getEnabled)
            .map(componentDefinitionHelper::readComponentDefinition)
            .map(componentDefinition -> (ComponentHandler) () -> componentDefinition)
            .toList();
    }

    @Override
    public Optional<ComponentHandler> fetchComponentHandler(String name, int connectorVersion) {
        return apiConnectorService.fetchApiConnector(name, connectorVersion)
            .map(apiConnector -> () -> componentDefinitionHelper.readComponentDefinition(apiConnector));
    }
}
