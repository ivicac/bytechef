/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Surface-neutral seam for recording a mutating tool callback's side effect as an audit artifact. Implemented by AI
 * Hub (backed by {@code AiHubTaskArtifactService}) to log every knowledge-base mutation performed by the BUILD agent
 * as a task artifact for the audit trail; absent (the tool callback receives {@code null}) on the Copilot panel
 * surface, which has no equivalent task/artifact concept — recording there simply no-ops.
 *
 * <p>
 * The artifact kind is carried as the {@link Enum#name()} of the implementer's own kind enum (e.g. AI Hub's
 * {@code AiHubTaskArtifactKind}) rather than the enum type itself, so this shared lib does not need to depend on
 * ai-hub. Implementers map the string back with {@code valueOf(kind)}.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface ToolMutationArtifactRecorder {

    /**
     * Records a mutation performed by a tool callback against the conversation identified by {@code conversationId}.
     * Mirrors {@code AiHubTaskArtifactService#record} — the only call shape the moved knowledge-base tool callbacks
     * use.
     *
     * @param conversationId the conversation/thread id the mutation occurred in
     * @param userId         the id of the user who owns the conversation
     * @param kind           the artifact kind, as the {@link Enum#name()} of the implementer's kind enum
     * @param artifactId     the string id of the affected entity (document id, etc.)
     * @param artifactName   display name snapshot taken at creation time
     * @param metadata       optional extra context; may be {@code null} or empty
     */
    void record(
        String conversationId, long userId, String kind,
        String artifactId, String artifactName, @Nullable Map<String, Object> metadata);
}
