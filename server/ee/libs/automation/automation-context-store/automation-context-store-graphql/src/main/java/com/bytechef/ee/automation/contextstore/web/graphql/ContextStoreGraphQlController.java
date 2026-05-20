/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.contextstore.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreFacade;
import com.bytechef.ee.automation.contextstore.web.graphql.dto.CreateContextStoreGraphQlInput;
import com.bytechef.ee.automation.contextstore.web.graphql.dto.UpdateContextStoreGraphQlInput;
import com.bytechef.ee.platform.contextstore.domain.ContextStore;
import com.bytechef.ee.platform.contextstore.service.ContextStoreService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.tag.domain.Tag;
import com.bytechef.platform.tag.service.TagService;
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
 * GraphQL controller for the parent {@link ContextStore} entity. Sources, entities, and records are surfaced by
 * {@code ContextStoreSourceGraphQlController}; this controller only owns the parent-store CRUD. All mutations are
 * admin-only and route through {@link WorkspaceContextStoreFacade}.
 *
 * @author Ivica Cardic
 * @version ee
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnCoordinator
@ConditionalOnProperty(prefix = "bytechef.context-store", name = "enabled", havingValue = "true")
public class ContextStoreGraphQlController {

    private final ContextStoreService contextStoreService;
    private final EnvironmentService environmentService;
    private final TagService tagService;
    private final WorkspaceContextStoreFacade workspaceContextStoreFacade;

    @SuppressFBWarnings("EI")
    public ContextStoreGraphQlController(
        ContextStoreService contextStoreService, EnvironmentService environmentService, TagService tagService,
        WorkspaceContextStoreFacade workspaceContextStoreFacade) {

        this.contextStoreService = contextStoreService;
        this.environmentService = environmentService;
        this.tagService = tagService;
        this.workspaceContextStoreFacade = workspaceContextStoreFacade;
    }

    @SchemaMapping(typeName = "ContextStore", field = "environment")
    public String environment(ContextStore contextStore) {
        return contextStore.getEnvironment()
            .name();
    }

    @SchemaMapping(typeName = "ContextStore", field = "tags")
    public List<Tag> tags(ContextStore contextStore) {
        List<Long> tagIds = contextStore.getTagIds();

        if (tagIds.isEmpty()) {
            return List.of();
        }

        return tagService.getTags(tagIds);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public List<ContextStore> contextStores(@Argument Long workspaceId, @Argument Long environmentId) {
        environmentService.getEnvironment(environmentId);

        return workspaceContextStoreFacade.getWorkspaceContextStores(workspaceId, environmentId);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ContextStore contextStore(@Argument Long id) {
        return contextStoreService.fetch(id)
            .orElse(null);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public List<Tag> contextStoreTags(@Argument Long workspaceId) {
        return workspaceContextStoreFacade.getWorkspaceContextStoreTags(workspaceId);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public Long contextStoreIdByName(
        @Argument Long workspaceId, @Argument String name, @Argument Long environmentId) {

        environmentService.getEnvironment(environmentId);

        // Returns null (mapped to GraphQL null) when there's no match — caller decides whether the absence is a
        // workflow misconfiguration or an acceptable "store doesn't exist in this env yet" fallback.
        return workspaceContextStoreFacade.findContextStoreIdByName(workspaceId, name, environmentId)
            .orElse(null);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ContextStore createContextStore(
        @Argument Long workspaceId, @Argument Long environmentId, @Argument CreateContextStoreGraphQlInput input) {

        environmentService.getEnvironment(environmentId);

        ContextStore contextStore = new ContextStore();

        contextStore.setName(input.name());
        contextStore.setDescription(input.description());
        contextStore.setTagIds(input.tagIds());

        return workspaceContextStoreFacade.createWorkspaceContextStore(contextStore, workspaceId, environmentId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ContextStore updateContextStore(
        @Argument Long workspaceId, @Argument Long id, @Argument UpdateContextStoreGraphQlInput input) {

        ContextStore contextStore = new ContextStore();

        contextStore.setId(id);
        contextStore.setName(input.name());
        contextStore.setDescription(input.description());
        contextStore.setTagIds(input.tagIds());
        contextStore.setVersion(input.version());

        return workspaceContextStoreFacade.updateWorkspaceContextStore(workspaceId, contextStore);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public List<Tag> updateContextStoreTags(
        @Argument Long workspaceId, @Argument Long id, @Argument List<Tag> tags) {

        return workspaceContextStoreFacade.updateWorkspaceContextStoreTags(workspaceId, id, tags);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public boolean deleteContextStore(@Argument Long workspaceId, @Argument Long id) {
        workspaceContextStoreFacade.deleteWorkspaceContextStore(workspaceId, id);

        return true;
    }
}
