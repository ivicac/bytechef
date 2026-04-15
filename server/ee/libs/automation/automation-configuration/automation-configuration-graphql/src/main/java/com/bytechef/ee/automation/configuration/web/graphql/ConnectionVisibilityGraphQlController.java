/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.automation.configuration.dto.BulkPromoteResultDTO;
import com.bytechef.ee.automation.configuration.facade.WorkspaceConnectionFacade;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;

/**
 * GraphQL controller for the EE-only connection visibility transitions (promote / demote). Injects the EE
 * {@code WorkspaceConnectionFacade} so the mutations exist only in EE builds; the CE
 * {@code ConnectionGraphQlController} keeps the edition-agnostic CRUD mutations.
 *
 * <p>
 * Authorization is enforced on {@link WorkspaceConnectionFacade} (ADMIN for promote; admin-OR-creator for demote), not
 * here.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnCoordinator
public class ConnectionVisibilityGraphQlController {

    private final WorkspaceConnectionFacade workspaceConnectionFacade;

    @SuppressFBWarnings("EI")
    public ConnectionVisibilityGraphQlController(WorkspaceConnectionFacade workspaceConnectionFacade) {
        this.workspaceConnectionFacade = workspaceConnectionFacade;
    }

    @MutationMapping(name = "promoteConnectionToWorkspace")
    public boolean promoteConnectionToWorkspace(@Argument long workspaceId, @Argument long connectionId) {
        workspaceConnectionFacade.promoteToWorkspace(workspaceId, connectionId);

        return true;
    }

    @MutationMapping(name = "promoteAllPrivateConnectionsToWorkspace")
    public BulkPromoteResultDTO promoteAllPrivateConnectionsToWorkspace(@Argument long workspaceId) {
        return workspaceConnectionFacade.promoteAllPrivateToWorkspace(workspaceId);
    }

    // Authorization handled in WorkspaceConnectionFacadeImpl.demoteToPrivate() — admin OR creator,
    // so that workspace connections do not become orphaned if every admin loses their role.
    @MutationMapping(name = "demoteConnectionToPrivate")
    public boolean demoteConnectionToPrivate(@Argument long workspaceId, @Argument long connectionId) {
        workspaceConnectionFacade.demoteToPrivate(workspaceId, connectionId);

        return true;
    }
}
