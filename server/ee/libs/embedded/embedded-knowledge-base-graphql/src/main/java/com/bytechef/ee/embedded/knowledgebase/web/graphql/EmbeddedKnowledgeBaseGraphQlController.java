/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.knowledgebase.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.embedded.knowledgebase.facade.EmbeddedKnowledgeBaseApiFacade;
import com.bytechef.ee.embedded.knowledgebase.facade.EmbeddedKnowledgeBaseApiFacade.ChunkingSettings;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * The embedded console's GraphQL entry point for knowledge bases, scoped to the {@code EMBEDDED} pool and to the tenant
 * behind the request. Authorization lives on {@link EmbeddedKnowledgeBaseApiFacade}, not here — every method there is
 * gated {@code isTenantAdmin()}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnCoordinator
@ConditionalOnProperty(prefix = "bytechef.ai.knowledge-base", name = "enabled", havingValue = "true")
@SuppressFBWarnings("EI")
public class EmbeddedKnowledgeBaseGraphQlController {

    private final EmbeddedKnowledgeBaseApiFacade embeddedKnowledgeBaseApiFacade;
    private final EnvironmentService environmentService;

    public EmbeddedKnowledgeBaseGraphQlController(
        EmbeddedKnowledgeBaseApiFacade embeddedKnowledgeBaseApiFacade, EnvironmentService environmentService) {

        this.embeddedKnowledgeBaseApiFacade = embeddedKnowledgeBaseApiFacade;
        this.environmentService = environmentService;
    }

    @QueryMapping
    public List<EmbeddedKnowledgeBase> embeddedKnowledgeBases(@Argument Long environmentId) {
        Environment environment = environmentService.getEnvironment(environmentId);

        List<KnowledgeBase> knowledgeBases = embeddedKnowledgeBaseApiFacade.getKnowledgeBases(environment.ordinal());

        return knowledgeBases.stream()
            .map(
                knowledgeBase -> new EmbeddedKnowledgeBase(
                    knowledgeBase.getId(), knowledgeBase.getName(), knowledgeBase.getDescription(),
                    toEpochMilli(knowledgeBase.getCreatedDate()), toEpochMilli(knowledgeBase.getLastModifiedDate()),
                    knowledgeBase.getMaxChunkSize(), knowledgeBase.getMinChunkSizeChars(),
                    knowledgeBase.getOverlap()))
            .toList();
    }

    @MutationMapping
    public boolean createEmbeddedKnowledgeBase(@Argument CreateEmbeddedKnowledgeBaseInput input) {
        Environment environment = environmentService.getEnvironment(input.environmentId());

        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(
            environment.ordinal(), input.name(), input.description(), input.chunkingSettings());

        return true;
    }

    @MutationMapping
    public boolean updateEmbeddedKnowledgeBase(@Argument UpdateEmbeddedKnowledgeBaseInput input) {
        embeddedKnowledgeBaseApiFacade.updateKnowledgeBase(
            input.knowledgeBaseId(), input.name(), input.description(), input.chunkingSettings());

        return true;
    }

    @MutationMapping
    public int rechunkEmbeddedKnowledgeBase(@Argument Long knowledgeBaseId) {
        return embeddedKnowledgeBaseApiFacade.rechunkKnowledgeBase(knowledgeBaseId);
    }

    public record CreateEmbeddedKnowledgeBaseInput(Long environmentId, String name, @Nullable String description,
        @Nullable Integer maxChunkSize, @Nullable Integer minChunkSizeChars, @Nullable Integer overlap) {

        public ChunkingSettings chunkingSettings() {
            return new ChunkingSettings(maxChunkSize, minChunkSizeChars, overlap);
        }
    }

    public record UpdateEmbeddedKnowledgeBaseInput(Long knowledgeBaseId, String name, @Nullable String description,
        @Nullable Integer maxChunkSize, @Nullable Integer minChunkSizeChars, @Nullable Integer overlap) {

        public ChunkingSettings chunkingSettings() {
            return new ChunkingSettings(maxChunkSize, minChunkSizeChars, overlap);
        }
    }

    private static @Nullable Long toEpochMilli(@Nullable Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    /**
     * Deliberately the same shape as the automation {@code KnowledgeBase} minus its documents, so one set of client
     * components can render both surfaces.
     */
    public record EmbeddedKnowledgeBase(Long id, String name, String description, @Nullable Long createdDate,
        @Nullable Long lastModifiedDate, int maxChunkSize, int minChunkSizeChars, int overlap) {
    }
}
