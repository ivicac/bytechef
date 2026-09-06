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

package com.bytechef.automation.ai.tool;

import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for "which workspaces may the caller of an MCP tool reach?", used by the workspace-scoped tool
 * callbacks to resolve the {@code workspaceId} tool argument.
 *
 * <p>
 * Those callbacks previously resolved the argument against {@code WorkspaceService#getWorkspaces}, a bare
 * {@code findAll()} over the tenant. Since the management MCP endpoint authenticates an API key that binds the caller
 * to no workspace, a caller could name any workspace in the tenant and have the wrapped tool operate against it -
 * including the asset-file tools, whose facade carries no authorization of its own. The same call with no
 * {@code workspaceId} returned every workspace's id and name in its error payload, which handed the caller the list of
 * targets.
 * </p>
 *
 * <p>
 * Scoping to the caller's own workspaces fixes both at once: the auto-selection, the error listing and the explicit-id
 * check all read the same membership-filtered list. In CE {@link WorkspaceFacade#getUserWorkspaces} returns every
 * workspace, so this is permissive there by design, exactly as {@code WorkspaceAccessGuard} is on the AI Hub; in EE it
 * returns only accessible ones.
 * </p>
 *
 * <p>
 * Fails closed: with no authenticated user the list is empty, so every explicit id is refused and auto-selection
 * resolves nothing rather than falling back to the tenant.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
public class AccessibleWorkspaceResolver {

    private final UserService userService;
    private final WorkspaceFacade workspaceFacade;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AccessibleWorkspaceResolver(UserService userService, WorkspaceFacade workspaceFacade) {
        this.userService = userService;
        this.workspaceFacade = workspaceFacade;
    }

    public List<Workspace> getAccessibleWorkspaces() {
        return userService.fetchCurrentUser()
            .map(User::getId)
            .map(workspaceFacade::getUserWorkspaces)
            .orElseGet(List::of);
    }

    public boolean isAccessible(long workspaceId) {
        List<Workspace> workspaces = getAccessibleWorkspaces();

        return workspaces.stream()
            .map(Workspace::getId)
            .anyMatch(id -> Objects.equals(id, workspaceId));
    }
}
