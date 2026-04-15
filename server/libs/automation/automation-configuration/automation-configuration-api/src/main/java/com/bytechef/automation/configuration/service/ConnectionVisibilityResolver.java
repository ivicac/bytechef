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

import com.bytechef.platform.connection.dto.ConnectionDTO;
import java.util.List;

/**
 * Filters the connections of a workspace down to those visible to the current principal. CE resolves PRIVATE-only
 * (creator or admin); EE resolves the full WORKSPACE / PROJECT / ORGANIZATION scope model.
 *
 * @author Ivica Cardic
 */
public interface ConnectionVisibilityResolver {

    /**
     * @param connections all connections belonging to the workspace (already loaded); never {@code null}
     * @param workspaceId the workspace whose connections are being listed
     * @return the subset visible to the current principal. Never {@code null}; may be empty.
     */
    List<ConnectionDTO> filterVisible(List<ConnectionDTO> connections, long workspaceId);
}
