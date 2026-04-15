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

package com.bytechef.ee.automation.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.bytechef.platform.connection.domain.ConnectionVisibility;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.util.SecurityUtils;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectionVisibilityResolverImplTest {

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
    void testWorkspaceVisibleToAllMembers() {
        ConnectionVisibilityResolverImpl resolver = new ConnectionVisibilityResolverImpl();

        ConnectionDTO workspace = connection(1L, "owner", ConnectionVisibility.WORKSPACE);

        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserLogin)
                .thenReturn("member");
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN))
                .thenReturn(false);

            List<ConnectionDTO> result = resolver.filterVisible(List.of(workspace), 10L);

            assertThat(result).containsExactly(workspace);
        }
    }

    @Test
    void testPrivateVisibleOnlyToCreator() {
        ConnectionVisibilityResolverImpl resolver = new ConnectionVisibilityResolverImpl();

        ConnectionDTO own = connection(1L, "member", ConnectionVisibility.PRIVATE);
        ConnectionDTO other = connection(2L, "someone-else", ConnectionVisibility.PRIVATE);

        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserLogin)
                .thenReturn("member");
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN))
                .thenReturn(false);

            List<ConnectionDTO> result = resolver.filterVisible(List.of(own, other), 10L);

            assertThat(result).containsExactly(own);
        }
    }

    @Test
    void testAdminSeesAllPrivateConnections() {
        ConnectionVisibilityResolverImpl resolver = new ConnectionVisibilityResolverImpl();

        ConnectionDTO own = connection(1L, "member", ConnectionVisibility.PRIVATE);
        ConnectionDTO other = connection(2L, "someone-else", ConnectionVisibility.PRIVATE);

        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserLogin)
                .thenReturn("admin");
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN))
                .thenReturn(true);

            List<ConnectionDTO> result = resolver.filterVisible(List.of(own, other), 10L);

            assertThat(result).containsExactly(own, other);
        }
    }
}
