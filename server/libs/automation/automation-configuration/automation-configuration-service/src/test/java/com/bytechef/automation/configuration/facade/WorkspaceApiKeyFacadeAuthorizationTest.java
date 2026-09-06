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

package com.bytechef.automation.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} expressions that gate workspace API-key operations (T19). List authorizes by workspace
 * scope (the arg is a workspaceId); delete authorizes via the {@code ApiKey:ResourceScope} token (the arg is an
 * apiKeyId). {@code create}'s gate here is environment-unaware by design -- see {@code WorkspaceApiKeyFacade#create}'s
 * javadoc for why, and {@code WorkspaceApiKeyGraphQlControllerAuthorizationTest} for the environment-aware gate that
 * additionally covers it at the GraphQL controller.
 *
 * @author Ivica Cardic
 */
class WorkspaceApiKeyFacadeAuthorizationTest {

    @Test
    void testCreateRequiresApiKeyCreateScope() {
        assertExpression("create", "hasPermission(#workspaceId, 'Workspace', 'API_KEY_CREATE')");
    }

    @Test
    void testDeleteRequiresApiKeyDeleteScope() {
        assertExpression("delete", "hasPermission(#apiKeyId, 'ApiKey', 'API_KEY_DELETE')");
    }

    @Test
    void testGetApiKeysRequiresApiKeyViewScope() {
        assertExpression("getApiKeys",
            "hasWorkspaceScopeInEnvironmentId(#workspaceId, 'API_KEY_VIEW', #environmentId)");
    }

    private static void assertExpression(String methodName, String expression) {
        Method method = findMethod(methodName);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("@PreAuthorize on %s", methodName)
            .isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(expression);
    }

    private static Method findMethod(String methodName) {
        Method method = null;

        for (Method candidate : WorkspaceApiKeyFacadeImpl.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals(methodName)) {
                method = candidate;

                break;
            }
        }

        assertThat(method)
            .as("method %s", methodName)
            .isNotNull();

        return method;
    }
}
