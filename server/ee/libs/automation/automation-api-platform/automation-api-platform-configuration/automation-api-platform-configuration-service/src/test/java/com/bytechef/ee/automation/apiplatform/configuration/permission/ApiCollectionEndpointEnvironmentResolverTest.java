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

import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionEndpointService;
import com.bytechef.platform.configuration.domain.Environment;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ApiCollectionEndpointEnvironmentResolverTest {

    private final ApiCollectionEndpointService apiCollectionEndpointService = mock(ApiCollectionEndpointService.class);
    private final ApiCollectionEnvironmentResolver apiCollectionEnvironmentResolver = mock(
        ApiCollectionEnvironmentResolver.class);
    private final ApiCollectionEndpointEnvironmentResolver resolver = new ApiCollectionEndpointEnvironmentResolver(
        apiCollectionEndpointService, apiCollectionEnvironmentResolver);

    @Test
    void testResourceType() {
        assertThat(resolver.resourceType()).isEqualTo("ApiCollectionEndpoint");
    }

    @Test
    void testResolvesTheEnvironmentOfTheParentCollection() {
        ApiCollectionEndpoint apiCollectionEndpoint = new ApiCollectionEndpoint();

        apiCollectionEndpoint.setApiCollectionId(100L);

        when(apiCollectionEndpointService.fetchApiCollectionEndpoint(7L))
            .thenReturn(Optional.of(apiCollectionEndpoint));
        when(apiCollectionEnvironmentResolver.fetchEnvironment(100L)).thenReturn(Optional.of(Environment.PRODUCTION));

        assertThat(resolver.fetchEnvironment(7L)).hasValue(Environment.PRODUCTION);
    }

    @Test
    void testMissingEndpointHasNoEnvironment() {
        when(apiCollectionEndpointService.fetchApiCollectionEndpoint(99L)).thenReturn(Optional.empty());

        assertThat(resolver.fetchEnvironment(99L)).isEmpty();
    }

    @Test
    void testNonNumericIdHasNoEnvironment() {
        assertThat(resolver.fetchEnvironment("abc")).isEmpty();
    }
}
