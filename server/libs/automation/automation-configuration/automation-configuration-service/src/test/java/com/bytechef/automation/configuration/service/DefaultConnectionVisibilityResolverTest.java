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

package com.bytechef.automation.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.bytechef.platform.connection.domain.ConnectionVisibility;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.util.SecurityUtils;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DefaultConnectionVisibilityResolverTest {

    private final DefaultConnectionVisibilityResolver resolver = new DefaultConnectionVisibilityResolver();

    private static ConnectionDTO connection(long id, String createdBy, ConnectionVisibility visibility) {
        return ConnectionDTO.builder()
            .id(id)
            .createdBy(createdBy)
            .name("c" + id)
            .componentName("x")
            .visibility(visibility)
            .build();
    }

    @Test
    void testNonAdminSeesOnlyOwnPrivateConnections() {
        ConnectionDTO mine = connection(1L, "user", ConnectionVisibility.PRIVATE);
        ConnectionDTO others = connection(2L, "other", ConnectionVisibility.PRIVATE);
        ConnectionDTO workspace = connection(3L, "other", ConnectionVisibility.WORKSPACE);

        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserLogin)
                .thenReturn("user");
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN))
                .thenReturn(false);

            List<ConnectionDTO> result = resolver.filterVisible(List.of(mine, others, workspace), 10L);

            assertThat(result).containsExactly(mine);
        }
    }

    @Test
    void testAdminSeesAllPrivateConnections() {
        ConnectionDTO mine = connection(1L, "user", ConnectionVisibility.PRIVATE);
        ConnectionDTO others = connection(2L, "other", ConnectionVisibility.PRIVATE);

        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserLogin)
                .thenReturn("admin");
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN))
                .thenReturn(true);

            List<ConnectionDTO> result = resolver.filterVisible(List.of(mine, others), 10L);

            assertThat(result).containsExactly(mine, others);
        }
    }
}
