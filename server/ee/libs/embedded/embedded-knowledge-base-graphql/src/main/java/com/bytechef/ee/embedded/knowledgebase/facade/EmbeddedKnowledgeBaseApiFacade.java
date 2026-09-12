/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.knowledgebase.facade;

import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The embedded console's own knowledge base management surface: create, list, update and re-chunk, scoped to the
 * EMBEDDED pool and to the tenant behind the request. This is the HTTP surface and it owns authorization: every method
 * is gated {@code isTenantAdmin()} on the implementation, never on the controller, because a controller wired to an
 * unguarded facade compiles fine and silently drops the check.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface EmbeddedKnowledgeBaseApiFacade {

    /**
     * @param environment the environment ordinal to list
     */
    List<KnowledgeBase> getKnowledgeBases(int environment);

    /**
     * Creates a new knowledge base in the embedded pool.
     *
     * @param environmentId    the environment to create the knowledge base in
     * @param name             the knowledge base's name
     * @param description      the knowledge base's description
     * @param chunkingSettings the chunking settings to create it with, each absent one left to {@link KnowledgeBase}'s
     *                         own default
     */
    void createKnowledgeBase(
        long environmentId, String name, String description, ChunkingSettings chunkingSettings);

    /**
     * Updates a knowledge base in the embedded pool.
     *
     * <p>
     * The console needs this because chunking decides how a document is split before embedding, and therefore what a
     * search can return: without an update the only way to change it is deleting the knowledge base and re-uploading
     * and re-embedding every document in it.
     *
     * <p>
     * Scoped to the EMBEDDED pool, not merely to the tenant. {@code isTenantAdmin()} is satisfied by the same person on
     * both surfaces, so without the pool check an id typed into this mutation would edit an AUTOMATION knowledge base
     * the embedded console cannot even list.
     *
     * @param knowledgeBaseId  the knowledge base to update
     * @param name             the knowledge base's new name
     * @param description      the knowledge base's new description
     * @param chunkingSettings the chunking settings to apply, each absent one leaving the stored value alone
     */
    void updateKnowledgeBase(
        long knowledgeBaseId, String name, @Nullable String description, ChunkingSettings chunkingSettings);

    /**
     * Re-splits and re-embeds the documents already in a knowledge base under the settings it carries now.
     *
     * <p>
     * {@link #updateKnowledgeBase} changes what FUTURE uploads are split into and nothing else, so a vendor who
     * discovers their chunk size is wrong fixes it for everything except the corpus it was wrong for. This is the way
     * out of that, and it is a separate action rather than part of the update because it is destructive and slow: every
     * chunk in the knowledge base is deleted and re-embedded, at an embedding call each, and there is a window during
     * which a re-chunked document returns nothing from a search.
     *
     * <p>
     * Pool-scoped for the same reason the update is: {@code isTenantAdmin()} is satisfied by the same person on both
     * surfaces, so an AUTOMATION id typed into this mutation would otherwise re-embed a knowledge base the embedded
     * console cannot list.
     *
     * @param knowledgeBaseId the knowledge base to re-chunk
     * @return the number of documents queued for re-chunking
     */
    int rechunkKnowledgeBase(long knowledgeBaseId);

    /**
     * The three chunking settings, which travel together everywhere: on the entity, through the create input and
     * through the update input.
     *
     * <p>
     * Every component is nullable and no default is written here. A default on this record would be a second copy of
     * {@link KnowledgeBase}'s, and the two would drift the first time one of them was tuned -- silently, because a
     * knowledge base created through this surface would still look correctly configured. Absent means "whatever the
     * entity defaults to" on create and "leave the stored value alone" on update.
     */
    record ChunkingSettings(
        @Nullable Integer maxChunkSize, @Nullable Integer minChunkSizeChars, @Nullable Integer overlap) {

        public static ChunkingSettings none() {
            return new ChunkingSettings(null, null, null);
        }
    }
}
