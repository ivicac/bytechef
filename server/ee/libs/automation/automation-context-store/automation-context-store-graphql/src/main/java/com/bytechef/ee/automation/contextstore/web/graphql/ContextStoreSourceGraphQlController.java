/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.contextstore.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreSourceFacade;
import com.bytechef.ee.automation.contextstore.service.WorkspaceContextStoreSourceService;
import com.bytechef.ee.automation.contextstore.web.graphql.dto.ContextStoreSourceFilter;
import com.bytechef.ee.automation.contextstore.web.graphql.dto.CreateContextStoreSourceGraphQlInput;
import com.bytechef.ee.automation.contextstore.web.graphql.dto.UpdateContextStoreSourceGraphQlInput;
import com.bytechef.ee.platform.contextstore.domain.ContextStoreSource;
import com.bytechef.ee.platform.contextstore.service.ContextStoreSourceService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.constant.AuthorityConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL controller for the Context Store source surface. All mutations are admin-only and route through
 * {@link WorkspaceContextStoreSourceFacade} — the same code path used by the AI Hub chat-define tools and the UI, so
 * there is no parallel implementation to maintain. Workspace-scoped reads flow through
 * {@link WorkspaceContextStoreSourceService} which joins the relation table with the platform-side source service.
 *
 * <p>
 * Source absorbed the former Entity layer in Phase 2: the per-source record-shape fields ({@code entityName},
 * {@code idField}, {@code indexedFields}, etc.) live directly on the source row, so there are no separate entity
 * mutations or resolvers.
 * </p>
 *
 * @author Ivica Cardic
 * @version ee
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnCoordinator
@ConditionalOnProperty(prefix = "bytechef.context-store", name = "enabled", havingValue = "true")
public class ContextStoreSourceGraphQlController {

    private final ContextStoreSourceService contextStoreSourceService;
    private final WorkspaceContextStoreSourceFacade workspaceContextStoreSourceFacade;
    private final WorkspaceContextStoreSourceService workspaceContextStoreSourceService;

    @SuppressFBWarnings("EI")
    public ContextStoreSourceGraphQlController(
        ContextStoreSourceService contextStoreSourceService,
        WorkspaceContextStoreSourceFacade workspaceContextStoreSourceFacade,
        WorkspaceContextStoreSourceService workspaceContextStoreSourceService) {

        this.contextStoreSourceService = contextStoreSourceService;
        this.workspaceContextStoreSourceFacade = workspaceContextStoreSourceFacade;
        this.workspaceContextStoreSourceService = workspaceContextStoreSourceService;
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ContextStoreSource contextStoreSource(@Argument Long id) {
        return contextStoreSourceService.fetch(id)
            .orElse(null);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public List<ContextStoreSource> contextStoreSources(
        @Argument Long workspaceId, @Argument Long environmentId, @Argument ContextStoreSourceFilter filter) {

        if (filter != null && Boolean.TRUE.equals(filter.enabled())) {
            return workspaceContextStoreSourceService.getAllEnabledSourcesByWorkspaceId(workspaceId, environmentId);
        }

        return workspaceContextStoreSourceService.getAllSourcesByWorkspaceId(workspaceId, environmentId);
    }

    @SchemaMapping(typeName = "ContextStoreSource", field = "workspaceId")
    public Long workspaceId(ContextStoreSource source) {
        return workspaceContextStoreSourceService.fetchWorkspaceIdByContextStoreSourceId(source.getId())
            .orElse(null);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ContextStoreSource createContextStoreSource(@Argument CreateContextStoreSourceGraphQlInput input) {
        return workspaceContextStoreSourceFacade.create(input.workspaceId(), input.toFacadeInput());
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ContextStoreSource updateContextStoreSource(
        @Argument Long id, @Argument UpdateContextStoreSourceGraphQlInput input) {

        Long workspaceId = workspaceContextStoreSourceService.fetchWorkspaceIdByContextStoreSourceId(id)
            .orElseThrow(() -> new IllegalStateException(
                "ContextStoreSource " + id + " has no owning workspace"));

        return workspaceContextStoreSourceFacade.update(workspaceId, id, input.toFacadeInput());
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public boolean deleteContextStoreSource(@Argument Long id) {
        Long workspaceId = workspaceContextStoreSourceService.fetchWorkspaceIdByContextStoreSourceId(id)
            .orElseThrow(() -> new IllegalStateException(
                "ContextStoreSource " + id + " has no owning workspace"));

        workspaceContextStoreSourceFacade.delete(workspaceId, id);

        return true;
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public Long refreshContextStoreSource(@Argument Long id) {
        Long workspaceId = workspaceContextStoreSourceService.fetchWorkspaceIdByContextStoreSourceId(id)
            .orElseThrow(() -> new IllegalStateException(
                "ContextStoreSource " + id + " has no owning workspace"));

        return workspaceContextStoreSourceFacade.refreshNow(workspaceId, id);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ContextStoreSource setContextStoreSourceEnabled(@Argument Long id, @Argument boolean enabled) {
        Long workspaceId = workspaceContextStoreSourceService.fetchWorkspaceIdByContextStoreSourceId(id)
            .orElseThrow(() -> new IllegalStateException(
                "ContextStoreSource " + id + " has no owning workspace"));

        workspaceContextStoreSourceFacade.setEnabled(workspaceId, id, enabled);

        return contextStoreSourceService.get(id);
    }
}
