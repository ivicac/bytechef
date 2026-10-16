/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.security.ResourceOwnershipResolver.ResourceOwner;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionEndpointService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ApiCollectionEndpointOwnershipResolverTest {

    private final ApiCollectionEndpointService apiCollectionEndpointService = mock(ApiCollectionEndpointService.class);
    private final ApiCollectionOwnershipResolver apiCollectionOwnershipResolver = mock(
        ApiCollectionOwnershipResolver.class);
    private final ApiCollectionEndpointOwnershipResolver resolver = new ApiCollectionEndpointOwnershipResolver(
        apiCollectionEndpointService, apiCollectionOwnershipResolver);

    @Test
    void testResourceType() {
        assertThat(resolver.resourceType()).isEqualTo("ApiCollectionEndpoint");
    }

    @Test
    void testResolvesTheOwnerOfTheParentCollection() {
        ApiCollectionEndpoint apiCollectionEndpoint = new ApiCollectionEndpoint();

        apiCollectionEndpoint.setApiCollectionId(100L);

        when(apiCollectionEndpointService.fetchApiCollectionEndpoint(7L))
            .thenReturn(Optional.of(apiCollectionEndpoint));
        when(apiCollectionOwnershipResolver.resolveOwner(100L)).thenReturn(ResourceOwner.ofWorkspace(5L));

        assertThat(resolver.resolveOwner(7L)
            .workspaceId()).hasValue(5L);
    }

    @Test
    void testMissingEndpointIsUnknown() {
        when(apiCollectionEndpointService.fetchApiCollectionEndpoint(99L)).thenReturn(Optional.empty());

        assertThat(resolver.resolveOwner(99L)
            .workspaceId()).isEmpty();
    }
}
