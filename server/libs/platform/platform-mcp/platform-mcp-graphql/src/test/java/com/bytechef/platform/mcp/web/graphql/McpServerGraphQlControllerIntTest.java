/*
 * Copyright 2025 ByteChef
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

package com.bytechef.platform.mcp.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.mcp.domain.McpServer;
import com.bytechef.platform.mcp.facade.McpServerFacade;
import com.bytechef.platform.mcp.service.McpServerService;
import com.bytechef.platform.mcp.web.graphql.config.McpGraphQlConfigurationSharedMocks;
import com.bytechef.platform.mcp.web.graphql.config.McpGraphQlTestConfiguration;
import com.bytechef.platform.tag.domain.Tag;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ContextConfiguration;

/**
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = {
    McpGraphQlTestConfiguration.class,
    McpServerGraphQlController.class
})
@GraphQlTest(
    controllers = McpServerGraphQlController.class,
    properties = {
        "bytechef.coordinator.enabled=true",
        "spring.graphql.schema.locations=classpath*:/graphql/"
    })
@McpGraphQlConfigurationSharedMocks
public class McpServerGraphQlControllerIntTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @Autowired
    private McpServerFacade mcpServerFacade;

    @Autowired
    private McpServerService mcpServerService;

    @Test
    void testGetMcpServerById() {
        // Given
        McpServer mockServer = createMockMcpServer(
            1L, "Test Server", PlatformType.AUTOMATION, Environment.DEVELOPMENT, true);

        when(mcpServerService.getMcpServer(1L)).thenReturn(mockServer);

        // When & Then
        this.graphQlTester
            .document("""
                query {
                    mcpServer(id: "1") {
                        id
                        name
                        type
                        enabled
                    }
                }
                """)
            .execute()
            .path("mcpServer.id")
            .entity(String.class)
            .isEqualTo("1")
            .path("mcpServer.name")
            .entity(String.class)
            .isEqualTo("Test Server")
            .path("mcpServer.type")
            .entity(String.class)
            .isEqualTo("AUTOMATION")
            .path("mcpServer.enabled")
            .entity(Boolean.class)
            .isEqualTo(true);
    }

    @Test
    void testCreateMcpServer() {
        // Given
        McpServer mockServer = createMockMcpServer(
            1L, "New Server", PlatformType.AUTOMATION, Environment.DEVELOPMENT, true);

        when(mcpServerService.create(eq("New Server"), eq(PlatformType.AUTOMATION),
            eq(Environment.DEVELOPMENT), eq(true))).thenReturn(mockServer);

        // When & Then
        this.graphQlTester
            .document("""
                mutation {
                    createMcpServer(input: {
                        name: "New Server",
                        type: AUTOMATION,
                        environmentId: "0",
                        enabled: true
                    }) {
                        id
                        name
                        type
                        enabled
                    }
                }
                """)
            .execute()
            .path("createMcpServer.id")
            .entity(String.class)
            .isEqualTo("1")
            .path("createMcpServer.name")
            .entity(String.class)
            .isEqualTo("New Server");
    }

    @Test
    void testUpdateMcpServer() {
        // Given
        McpServer mockServer = createMockMcpServer(
            1L, "Updated Server", PlatformType.AUTOMATION, Environment.DEVELOPMENT, false);

        when(mcpServerService.update(eq(1L), eq("Updated Server"), eq(false))).thenReturn(mockServer);

        // When & Then
        this.graphQlTester
            .document("""
                mutation {
                    updateMcpServer(id: "1", input: {
                        name: "Updated Server",
                        enabled: false
                    }) {
                        id
                        name
                        enabled
                    }
                }
                """)
            .execute()
            .path("updateMcpServer.id")
            .entity(String.class)
            .isEqualTo("1")
            .path("updateMcpServer.name")
            .entity(String.class)
            .isEqualTo("Updated Server")
            .path("updateMcpServer.enabled")
            .entity(Boolean.class)
            .isEqualTo(false);
    }

    @Test
    void testUpdateMcpServerAuthenticationRequired() {
        // Given
        McpServer mockServer = createMockMcpServer(
            1L, "Test Server", PlatformType.AUTOMATION, Environment.DEVELOPMENT, true);

        when(mcpServerService.update(eq(1L), eq("Test Server"), eq(true))).thenReturn(mockServer);

        McpServer updatedMockServer = createMockMcpServer(
            1L, "Test Server", PlatformType.AUTOMATION, Environment.DEVELOPMENT, true);

        updatedMockServer.setAuthenticationRequired(false);

        when(mcpServerService.update(any(McpServer.class))).thenReturn(updatedMockServer);

        // When & Then
        this.graphQlTester
            .document("""
                mutation {
                    updateMcpServer(id: "1", input: {
                        name: "Test Server",
                        enabled: true,
                        authenticationRequired: false
                    }) {
                        id
                        authenticationRequired
                    }
                }
                """)
            .execute()
            .path("updateMcpServer.id")
            .entity(String.class)
            .isEqualTo("1")
            .path("updateMcpServer.authenticationRequired")
            .entity(Boolean.class)
            .isEqualTo(false);
    }

    /**
     * Mirrors the invariant enforced by {@code McpServerServiceImpl.update(McpServer)} (Task 2):
     * {@code authenticationRequired == false && enforceToolAuthorization == true} is rejected. The controller applies
     * the two flags via two sequential {@code mcpServerService.update(McpServer)} calls, so the mock is stubbed per
     * argument state to mirror that sequence: the first call (turning {@code enforceToolAuthorization} on, with
     * {@code authenticationRequired} still {@code true}) succeeds, and the second call (turning
     * {@code authenticationRequired} off while {@code enforceToolAuthorization} is now {@code true}) throws, exactly as
     * the real service would.
     */
    @Test
    void testUpdateMcpServerRejectsInvariantViolation() {
        // Given
        McpServer mockServer = createMockMcpServer(
            1L, "Test Server", PlatformType.AUTOMATION, Environment.DEVELOPMENT, true);

        when(mcpServerService.update(eq(1L), eq("Test Server"), eq(true))).thenReturn(mockServer);

        when(mcpServerService.update(argThat(
            server -> server != null && server.isEnforceToolAuthorization() && server.isAuthenticationRequired())))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(mcpServerService.update(argThat(
            server -> server != null && server.isEnforceToolAuthorization() && !server.isAuthenticationRequired())))
                .thenThrow(new IllegalArgumentException(
                    "enforceToolAuthorization requires authenticationRequired to be enabled"));

        // When & Then
        this.graphQlTester
            .document("""
                mutation {
                    updateMcpServer(id: "1", input: {
                        name: "Test Server",
                        enabled: true,
                        enforceToolAuthorization: true,
                        authenticationRequired: false
                    }) {
                        id
                    }
                }
                """)
            .execute()
            .errors()
            .satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.get(0)
                    .getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR);
            })
            .path("updateMcpServer")
            .valueIsNull();
    }

    @Test
    void testUpdateMcpServerTags() {
        // Given
        List<Tag> mockTags = List.of(
            createMockTag(1L, "tag1"),
            createMockTag(2L, "tag2"));

        when(mcpServerFacade.updateMcpServerTags(anyLong(), any())).thenReturn(mockTags);

        // When & Then
        this.graphQlTester
            .document("""
                mutation {
                    updateMcpServerTags(id: "1", tags: [
                        { id: "1", name: "tag1" },
                        { id: "2", name: "tag2" }
                    ]) {
                        id
                        name
                    }
                }
                """)
            .execute()
            .path("updateMcpServerTags")
            .entityList(Object.class)
            .hasSize(2);

        verify(mcpServerFacade).updateMcpServerTags(eq(1L), any());
    }

    @Test
    void testDeleteMcpServer() {
        // When & Then
        this.graphQlTester
            .document("""
                mutation {
                    deleteMcpServer(id: "1")
                }
                """)
            .execute()
            .path("deleteMcpServer")
            .entity(Boolean.class)
            .isEqualTo(true);

        verify(mcpServerFacade).deleteMcpServer(1L);
    }

    private McpServer createMockMcpServer(
        Long id, String name, PlatformType type, Environment environment, Boolean enabled) {

        McpServer server = new McpServer(name, type, environment, enabled);

        server.setId(id);
        server.setVersion(1);

        return server;
    }

    private Tag createMockTag(Long id, String name) {
        Tag tag = new Tag();

        tag.setId(id);
        tag.setName(name);

        return tag;
    }
}
