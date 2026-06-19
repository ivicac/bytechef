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

package com.bytechef.automation.configuration.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} expressions that close the connection IDOR (T18). A bare connection {@code id} is
 * authorized via the {@code Connection:ResourceScope} token; the workspace-listing endpoint uses
 * {@code WorkspaceScope}.
 *
 * @author Ivica Cardic
 */
class ConnectionApiControllerAuthorizationTest {

    @Test
    void testGetConnectionRequiresConnectionViewScope() {
        assertExpression(
            "getConnection", "hasPermission(#id, 'Connection:ResourceScope', 'CONNECTION_VIEW')");
    }

    @Test
    void testDeleteConnectionRequiresConnectionDeleteScope() {
        assertExpression(
            "deleteConnection", "hasPermission(#id, 'Connection:ResourceScope', 'CONNECTION_DELETE')");
    }

    @Test
    void testUpdateConnectionRequiresConnectionEditScope() {
        assertExpression(
            "updateConnection", "hasPermission(#id, 'Connection:ResourceScope', 'CONNECTION_EDIT')");
    }

    @Test
    void testGetWorkspaceConnectionsRequiresConnectionViewScope() {
        assertExpression(
            "getWorkspaceConnections", "hasPermission(#id, 'WorkspaceScope', 'CONNECTION_VIEW')");
    }

    private static void assertExpression(String methodName, String expression) {
        Method method = null;

        for (Method candidate : ConnectionApiController.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals(methodName)) {
                method = candidate;

                break;
            }
        }

        assertThat(method)
            .as("method %s", methodName)
            .isNotNull();

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("@PreAuthorize on %s", methodName)
            .isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(expression);
    }
}
