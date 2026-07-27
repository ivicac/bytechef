/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.remote.client.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.remote.client.LoadBalancedRestClient;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class RemoteConnectedUserCodeWorkflowReferenceFacadeClientTest {

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenantId("public");
    }

    @AfterEach
    void tearDown() {
        TenantContext.resetCurrentTenantId();
    }

    @Test
    void testGetConnectedUserWorkflowsHitsTheRemoteRestPath() {
        RestClient.Builder builder = RestClient.builder()
            .baseUrl("http://configuration-app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
            .build();

        server.expect(
            requestTo("http://configuration-app/remote/connected-user-code-workflow-reference-facade"
                + "/get-connected-user-workflows/42"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        RemoteConnectedUserCodeWorkflowReferenceFacadeClient client =
            new RemoteConnectedUserCodeWorkflowReferenceFacadeClient(new LoadBalancedRestClient(builder));

        List<ConnectedUserProjectWorkflow> result = client.getConnectedUserWorkflows(42L);

        assertThat(result).isEmpty();

        server.verify();
    }

    @Test
    void testGetOrCreateReferenceTranslates409IntoMissingConnectionException() {
        RestClient.Builder builder = RestClient.builder()
            .baseUrl("http://configuration-app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
            .build();

        server.expect(
            requestTo("http://configuration-app/remote/connected-user-code-workflow-reference-facade"
                + "/get-or-create-reference?externalUserId=ext-1&catalogWorkflowUuid=uuid-1&environment=PRODUCTION"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"missingConnectionComponentName\":\"slack\"}"));

        RemoteConnectedUserCodeWorkflowReferenceFacadeClient client =
            new RemoteConnectedUserCodeWorkflowReferenceFacadeClient(new LoadBalancedRestClient(builder));

        assertThatThrownBy(() -> client.getOrCreateReference("ext-1", "uuid-1", Environment.PRODUCTION))
            .isInstanceOf(MissingConnectionException.class)
            .hasFieldOrPropertyWithValue("componentName", "slack");
    }

    @Test
    void testGetOrCreateReferenceDoesNotTranslateOtherErrorStatuses() {
        RestClient.Builder builder = RestClient.builder()
            .baseUrl("http://configuration-app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
            .build();

        server.expect(
            requestTo("http://configuration-app/remote/connected-user-code-workflow-reference-facade"
                + "/get-or-create-reference?externalUserId=ext-1&catalogWorkflowUuid=uuid-1&environment=PRODUCTION"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        RemoteConnectedUserCodeWorkflowReferenceFacadeClient client =
            new RemoteConnectedUserCodeWorkflowReferenceFacadeClient(new LoadBalancedRestClient(builder));

        assertThatThrownBy(() -> client.getOrCreateReference("ext-1", "uuid-1", Environment.PRODUCTION))
            .isInstanceOf(HttpServerErrorException.class)
            .isNotInstanceOf(MissingConnectionException.class);
    }
}
