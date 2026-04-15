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

import com.bytechef.platform.annotation.ConditionalOnCEVersion;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.util.SecurityUtils;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * CE default: only PRIVATE connections are reachable in CE, so a connection is visible to its creator or to an admin
 * (admins need full PRIVATE visibility for orphan-recovery from the connections list). WORKSPACE / PROJECT /
 * ORGANIZATION connections do not exist in CE (creation is forced PRIVATE) and are filtered out if present.
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnCEVersion
public class DefaultConnectionVisibilityResolver implements ConnectionVisibilityResolver {

    @Override
    public List<ConnectionDTO> filterVisible(List<ConnectionDTO> connections, long workspaceId) {
        String currentUserLogin = SecurityUtils.getCurrentUserLogin();
        boolean isAdmin = SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN);

        return connections.stream()
            .filter(connection -> switch (connection.visibility()) {
                case PRIVATE -> isAdmin || Objects.equals(currentUserLogin, connection.createdBy());
                default -> false;
            })
            .toList();
    }
}
