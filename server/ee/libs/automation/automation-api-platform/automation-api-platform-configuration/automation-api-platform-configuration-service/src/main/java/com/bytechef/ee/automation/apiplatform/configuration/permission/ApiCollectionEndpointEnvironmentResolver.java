/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.permission;

import com.bytechef.automation.configuration.security.ResourceEnvironmentResolver;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionEndpointService;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serializable;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reports the environment an API collection endpoint lives in, which is the environment of the collection it belongs
 * to, by delegating to {@link ApiCollectionEnvironmentResolver}. Returns empty when the endpoint cannot be found, per
 * the SPI.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class ApiCollectionEndpointEnvironmentResolver implements ResourceEnvironmentResolver {

    private final ApiCollectionEndpointService apiCollectionEndpointService;
    private final ApiCollectionEnvironmentResolver apiCollectionEnvironmentResolver;

    @SuppressFBWarnings("EI")
    public ApiCollectionEndpointEnvironmentResolver(
        ApiCollectionEndpointService apiCollectionEndpointService,
        ApiCollectionEnvironmentResolver apiCollectionEnvironmentResolver) {

        this.apiCollectionEndpointService = apiCollectionEndpointService;
        this.apiCollectionEnvironmentResolver = apiCollectionEnvironmentResolver;
    }

    @Override
    public String resourceType() {
        return "ApiCollectionEndpoint";
    }

    @Override
    public Optional<Environment> fetchEnvironment(Serializable id) {
        if (!(id instanceof Number number)) {
            return Optional.empty();
        }

        return apiCollectionEndpointService.fetchApiCollectionEndpoint(number.longValue())
            .map(ApiCollectionEndpoint::getApiCollectionId)
            .flatMap(apiCollectionEnvironmentResolver::fetchEnvironment);
    }
}
