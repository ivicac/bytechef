/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.web.graphql;

import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.ee.automation.aihub.security.WorkspaceAccessGuard;
import com.bytechef.ee.platform.aihub.task.AiHubTaskArtifact;
import com.bytechef.ee.platform.aihub.task.AiHubTaskArtifactKind;
import com.bytechef.ee.platform.aihub.task.AiHubTaskArtifactService;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.user.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * GraphQL surface for AI Hub task artifacts (audit log). Read-only listing for admins plus a
 * {@code recordReferencedAiHubTaskArtifact} mutation used by the composer to attach a referenced resource (file,
 * workflow, data table, knowledge base) to a task.
 *
 * <p>
 * Authorization: every operation requires workspace membership; the audit listing additionally requires {@code ADMIN}
 * authority. "Artifact does not exist" and "artifact exists in another workspace" produce the same opaque
 * {@link com.bytechef.ee.platform.aihub.exception.NotFoundException} so an authenticated workspace member cannot
 * enumerate artifact ids across the rest of the system.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubTaskArtifactGraphQlController {

    /**
     * Default page size for the audit listing endpoint.
     */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Hard upper bound on the {@code size} query parameter. Without this cap a caller could pass a huge {@code size}
     * and force the database to materialise an enormous result set.
     */
    private static final int MAX_PAGE_SIZE = 500;

    /**
     * Hard upper bound on the {@code page} query parameter. Bounded at 10_000 so a pathological
     * {@code page=999_999, size=500} request does not force PostgreSQL to walk hundreds of millions of index entries
     * via OFFSET-based pagination.
     */
    private static final int MAX_PAGE_INDEX = 10_000;

    private static final Logger log = LoggerFactory.getLogger(AiHubTaskArtifactGraphQlController.class);

    private final AiHubTaskArtifactService taskArtifactService;
    private final UserService userService;
    private final WorkspaceFacade workspaceFacade;
    private final JsonMapper jsonMapper;

    @SuppressFBWarnings("EI")
    public AiHubTaskArtifactGraphQlController(
        AiHubTaskArtifactService taskArtifactService,
        UserService userService, WorkspaceFacade workspaceFacade,
        JsonMapper jsonMapper) {

        this.taskArtifactService = taskArtifactService;
        this.userService = userService;
        this.workspaceFacade = workspaceFacade;
        this.jsonMapper = jsonMapper;
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public AiHubTaskArtifactPage aiHubTaskArtifacts(
        @Argument long workspaceId,
        @Argument @Nullable Integer environment,
        @Argument @Nullable Long userId,
        @Argument @Nullable AiHubTaskArtifactKind kind,
        @Argument @Nullable Long from,
        @Argument @Nullable Long to,
        @Argument @Nullable Integer page,
        @Argument @Nullable Integer size) {

        long callerUserId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, callerUserId, workspaceId);

        int requestedPage = page == null ? 0 : page;
        int requestedSize = size == null ? DEFAULT_PAGE_SIZE : size;

        if (requestedPage < 0 || requestedSize < 1) {
            // Negative page or zero/negative size are malformed — not "too large" — so they remain hard errors.
            // The clamp policy below covers the "out of range" cases (page > MAX_PAGE_INDEX, size > MAX_PAGE_SIZE).
            throw new IllegalArgumentException(
                "page must be >= 0 and size must be >= 1");
        }

        boolean pageClamped = requestedPage > MAX_PAGE_INDEX;
        boolean sizeClamped = requestedSize > MAX_PAGE_SIZE;
        int boundedPage = pageClamped ? MAX_PAGE_INDEX : requestedPage;
        int boundedSize = sizeClamped ? MAX_PAGE_SIZE : requestedSize;

        LocalDateTime fromLdt = from == null ? null : LocalDateTime.ofEpochSecond(from / 1000, 0, ZoneOffset.UTC);
        LocalDateTime toLdt = to == null ? null : LocalDateTime.ofEpochSecond(to / 1000, 0, ZoneOffset.UTC);

        List<AiHubTaskArtifact> items = taskArtifactService.listByWorkspace(
            workspaceId, environment, userId, kind, fromLdt, toLdt, boundedPage, boundedSize);

        long totalCount = taskArtifactService.countByWorkspace(
            workspaceId, environment, userId, kind, fromLdt, toLdt);

        boolean hasMore = ((long) boundedPage + 1) * boundedSize < totalCount;

        return new AiHubTaskArtifactPage(items, totalCount, hasMore, pageClamped, sizeClamped);
    }

    @MutationMapping
    public AiHubTaskArtifact recordReferencedAiHubTaskArtifact(
        @Argument RecordReferencedAiHubTaskArtifactInput input) {

        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, input.workspaceId());

        AiHubTaskArtifactKind kind;

        try {
            kind = AiHubTaskArtifactKind.valueOf(input.kind());
        } catch (IllegalArgumentException exception) {
            // The GraphQL layer should have validated the enum already; defensive fallback in case the
            // schema and the Java enum drift between deploys.
            throw new IllegalArgumentException("Unknown AiHubTaskArtifactKind: " + input.kind(),
                exception);
        }

        // Optional metadata blob — currently used by WORKFLOW_REFERENCED to stash projectId /
        // projectWorkflowId so the sidebar quick-open can route the workflow tab (which keys on the
        // parent project). Other kinds may use it later for the same shape of side-channel context.
        // Deserialize defensively: a malformed payload from a misbehaving client must not abort the
        // recording — fall back to no metadata and log so the issue is visible.
        Map<String, Object> metadata = parseMetadataJson(input.metadataJson());

        // Service layer enforces the task-belongs-to-(user, workspace) gate AND the idempotency
        // check by (taskId, kind, artifactId). Re-attaching the same file/workflow via the
        // composer is therefore safe to call repeatedly without proliferating sidebar rows.
        return taskArtifactService.recordReference(
            input.taskId(), input.workspaceId(), userId, kind, input.artifactId(), input.artifactName(),
            metadata);
    }

    @MutationMapping
    public boolean deleteAiHubTaskArtifact(@Argument DeleteAiHubTaskArtifactInput input) {
        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, input.workspaceId());

        // Service enforces (artifact -> task -> workspaceTask) ownership AND the
        // reference-kinds-only guard. The mutation returns true on success or on a benign no-op
        // (already deleted); throws on ownership / cross-workspace / non-reference-kind input.
        taskArtifactService.deleteReference(input.artifactId(), input.workspaceId(), userId);

        return true;
    }

    private Map<String, Object> parseMetadataJson(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }

        try {
            return jsonMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException exception) {
            log.warn("Failed to parse metadataJson on recordReferencedAiHubTaskArtifact; ignoring", exception);

            return Map.of();
        }
    }

    @SchemaMapping(typeName = "AiHubTaskArtifact", field = "kind")
    public String kind(AiHubTaskArtifact artifact) {
        return artifact.getKind()
            .name();
    }

    @SchemaMapping(typeName = "AiHubTaskArtifact", field = "status")
    public String status(AiHubTaskArtifact artifact) {
        return artifact.getStatus()
            .name();
    }

    @SchemaMapping(typeName = "AiHubTaskArtifact", field = "createdAt")
    @Nullable
    public Long createdAt(AiHubTaskArtifact artifact) {
        return artifact.getCreatedAt() == null ? null
            : artifact.getCreatedAt()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
    }

    @SchemaMapping(typeName = "AiHubTaskArtifact", field = "statusChangedAt")
    @Nullable
    public Long statusChangedAt(AiHubTaskArtifact artifact) {
        return artifact.getStatusChangedAt() == null ? null
            : artifact.getStatusChangedAt()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
    }

    /**
     * Page envelope for the audit listing query. {@code pageClamped}/{@code sizeClamped} surface the silent truncation
     * applied when a caller supplies a value above {@link #MAX_PAGE_INDEX} / {@link #MAX_PAGE_SIZE} so the UI can show
     * a "you asked for size=N, served size=M" hint rather than silently rendering a smaller page.
     */
    public record AiHubTaskArtifactPage(
        List<AiHubTaskArtifact> items, long totalCount, boolean hasMore,
        boolean pageClamped, boolean sizeClamped) {

        public AiHubTaskArtifactPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * Input for {@code recordReferencedAiHubTaskArtifact}. Carries the task + workspace ids alongside the resource
     * being attached. {@code kind} is the GraphQL enum value as a string — converted to {@link AiHubTaskArtifactKind}
     * in the controller method (Spring's automatic enum coercion can't be relied on for nested input records the same
     * way it works for top-level @Argument enums).
     */
    public record RecordReferencedAiHubTaskArtifactInput(
        long workspaceId, long taskId, String kind, String artifactId, String artifactName,
        @Nullable String metadataJson) {
    }

    /**
     * Input for {@code deleteAiHubTaskArtifact}. Carries the workspace id (for the access-guard check) and the
     * artifact-row primary-key id. Workspace and task ownership are re-verified server-side via the artifact -> task ->
     * workspaceTask join inside the service.
     */
    public record DeleteAiHubTaskArtifactInput(long workspaceId, long artifactId) {
    }
}
