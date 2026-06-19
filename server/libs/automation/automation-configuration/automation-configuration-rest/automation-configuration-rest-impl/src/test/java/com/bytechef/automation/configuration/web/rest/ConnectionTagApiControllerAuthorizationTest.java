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
 * Pins the {@code @PreAuthorize} expression that gates connection-tag writes by connection ownership (T18).
 *
 * @author Ivica Cardic
 */
class ConnectionTagApiControllerAuthorizationTest {

    @Test
    void testUpdateConnectionTagsRequiresConnectionEditScope() {
        Method method = null;

        for (Method candidate : ConnectionTagApiController.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals("updateConnectionTags")) {
                method = candidate;

                break;
            }
        }

        assertThat(method)
            .as("method updateConnectionTags")
            .isNotNull();

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("@PreAuthorize on updateConnectionTags")
            .isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasPermission(#id, 'Connection:ResourceScope', 'CONNECTION_EDIT')");
    }
}
