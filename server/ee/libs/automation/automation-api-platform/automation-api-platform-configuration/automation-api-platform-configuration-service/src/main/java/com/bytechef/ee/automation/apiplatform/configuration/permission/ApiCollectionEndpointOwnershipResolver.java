/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.permission;

import com.bytechef.automation.configuration.security.ResourceOwnershipResolver;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionEndpointService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Component;

/**
 * Maps an API-collection-endpoint id to the owner of the collection it belongs to, by delegating to
 * {@link ApiCollectionOwnershipResolver}. Fails closed with {@link ResourceOwner#unknown()} when the endpoint does not
 * exist.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class ApiCollectionEndpointOwnershipResolver implements ResourceOwnershipResolver {

    private final ApiCollectionEndpointService apiCollectionEndpointService;
    private final ApiCollectionOwnershipResolver apiCollectionOwnershipResolver;

    @SuppressFBWarnings("EI")
    public ApiCollectionEndpointOwnershipResolver(
        ApiCollectionEndpointService apiCollectionEndpointService,
        ApiCollectionOwnershipResolver apiCollectionOwnershipResolver) {

        this.apiCollectionEndpointService = apiCollectionEndpointService;
        this.apiCollectionOwnershipResolver = apiCollectionOwnershipResolver;
    }

    @Override
    public String resourceType() {
        return "ApiCollectionEndpoint";
    }

    @Override
    public ResourceOwner resolveOwner(long id) {
        return apiCollectionEndpointService.fetchApiCollectionEndpoint(id)
            .map(ApiCollectionEndpoint::getApiCollectionId)
            .map(apiCollectionId -> apiCollectionOwnershipResolver.resolveOwner(apiCollectionId.longValue()))
            .orElseGet(ResourceOwner::unknown);
    }
}
