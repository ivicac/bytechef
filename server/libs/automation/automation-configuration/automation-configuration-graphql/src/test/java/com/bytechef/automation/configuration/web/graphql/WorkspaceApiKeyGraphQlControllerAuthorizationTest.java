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

package com.bytechef.automation.configuration.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} on {@code createWorkspaceApiKey} (T20). This mutation carries the environment-aware
 * gate for {@code WorkspaceApiKeyFacade#create} because the environment a minted key belongs to is only visible here,
 * as the plain {@code environmentId} argument, before it is folded into the {@code ApiKey} the facade receives.
 *
 * @author Ivica Cardic
 */
class WorkspaceApiKeyGraphQlControllerAuthorizationTest {

    @Test
    void testCreateWorkspaceApiKeyRequiresApiKeyCreateScopeInTheNamedEnvironment() throws NoSuchMethodException {
        Method method = WorkspaceApiKeyGraphQlController.class.getDeclaredMethod(
            "createWorkspaceApiKey", long.class, String.class, Long.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("@PreAuthorize on createWorkspaceApiKey")
            .isNotNull();
        assertThat(preAuthorize.value())
            .isEqualTo("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'API_KEY_CREATE', #environmentId)");
    }
}
