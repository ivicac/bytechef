/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.knowledgebase.facade;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.knowledge-base", name = "enabled", havingValue = "true")
@SuppressFBWarnings("EI")
public class EmbeddedKnowledgeBaseApiFacadeImpl implements EmbeddedKnowledgeBaseApiFacade {

    private final KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade;
    private final KnowledgeBaseService knowledgeBaseService;

    public EmbeddedKnowledgeBaseApiFacadeImpl(
        KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade, KnowledgeBaseService knowledgeBaseService) {

        this.knowledgeBaseDocumentFacade = knowledgeBaseDocumentFacade;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * Filtering by an account answers "what would this account see", not "what does this account own" -- the vendor's
     * unowned knowledge bases are shared with every account and so appear under each of them. The same choice the data
     * table facade makes, for the same reason.
     */
    @Override
    @PreAuthorize("isTenantAdmin()")
    public List<KnowledgeBase> getKnowledgeBases(int environment, @Nullable Long ownerId) {
        return knowledgeBaseService.getKnowledgeBases(environment, PlatformType.EMBEDDED, toOwner(ownerId));
    }

    @Override
    @PreAuthorize("isTenantAdmin()")
    public void assignKnowledgeBaseOwner(long knowledgeBaseId, @Nullable Long ownerId) {
        knowledgeBaseService.assignOwner(knowledgeBaseId, ownerId == null ? null : Owner.connectedUser(ownerId));
    }

    /**
     * The owner is two columns, {@code owner_id} and {@code owner_type}, and both lookups
     * {@code KnowledgeBaseService#fetchKnowledgeBase} makes match on a whole owner: the account's own lookup on the
     * (id, type) pair, the shared one on {@code owner_id IS NULL}. A row carrying an id with no type therefore matches
     * neither -- it would look created and resolve for nobody -- so the two are written from one {@link Owner}.
     */
    @Override
    @PreAuthorize("isTenantAdmin()")
    public void createKnowledgeBase(
        long environmentId, String name, String description, @Nullable Long ownerId,
        ChunkingSettings chunkingSettings) {

        Environment[] environments = Environment.values();

        if (environmentId < 0 || environmentId >= environments.length) {
            throw new IllegalArgumentException("Invalid environmentId: " + environmentId);
        }

        Optional<Owner> owner = toOwner(ownerId);

        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setName(name);
        knowledgeBase.setDescription(description);
        knowledgeBase.setEnvironment(environments[(int) environmentId]);
        knowledgeBase.setPlatformType(PlatformType.EMBEDDED);
        knowledgeBase.setOwnerId(owner.map(Owner::id)
            .orElse(null));
        knowledgeBase.setOwnerType(owner.map(Owner::type)
            .orElse(null));

        applyChunkingSettings(knowledgeBase, chunkingSettings);

        knowledgeBaseService.createKnowledgeBase(knowledgeBase);
    }

    /**
     * The stored settings are read back and re-applied rather than left out, because
     * {@code KnowledgeBaseService#updateKnowledgeBase} writes all three from the object it is handed: an omitted one
     * left unset here would reset a tuned knowledge base to the entity's defaults on a rename.
     *
     * <p>
     * The read is the pool-scoped overload. {@code isTenantAdmin()} is satisfied by the same person on both surfaces,
     * so an id typed into this mutation would otherwise reach an AUTOMATION knowledge base the embedded console cannot
     * even list.
     */
    @Override
    @PreAuthorize("isTenantAdmin()")
    public void updateKnowledgeBase(
        long knowledgeBaseId, String name, @Nullable String description, ChunkingSettings chunkingSettings) {

        KnowledgeBase existingKnowledgeBase = knowledgeBaseService.getKnowledgeBase(
            knowledgeBaseId, List.of(PlatformType.EMBEDDED), Optional.empty());

        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setName(name);
        knowledgeBase.setDescription(description);
        knowledgeBase.setMaxChunkSize(existingKnowledgeBase.getMaxChunkSize());
        knowledgeBase.setMinChunkSizeChars(existingKnowledgeBase.getMinChunkSizeChars());
        knowledgeBase.setOverlap(existingKnowledgeBase.getOverlap());

        applyChunkingSettings(knowledgeBase, chunkingSettings);

        knowledgeBaseService.updateKnowledgeBase(knowledgeBaseId, knowledgeBase);
    }

    /**
     * The pool read comes first and its result is deliberately discarded: it is the authorization step, not a data
     * fetch. {@code getKnowledgeBase} with the EMBEDDED pool refuses an id from the automation pool with the same "not
     * found" a missing id gets, so a re-chunk cannot be aimed at a knowledge base this console cannot list.
     */
    @Override
    @PreAuthorize("isTenantAdmin()")
    public int rechunkKnowledgeBase(long knowledgeBaseId) {
        knowledgeBaseService.getKnowledgeBase(knowledgeBaseId, List.of(PlatformType.EMBEDDED), Optional.empty());

        return knowledgeBaseDocumentFacade.rechunkKnowledgeBaseDocuments(knowledgeBaseId);
    }

    /**
     * Only the settings that were named are written. Nothing here supplies a fallback: on create the untouched field
     * keeps {@link KnowledgeBase}'s own default, on update it keeps the stored value the caller seeded, and either way
     * this class never holds a second copy of a default that could drift from the entity's.
     */
    private static void applyChunkingSettings(KnowledgeBase knowledgeBase, ChunkingSettings chunkingSettings) {
        Integer maxChunkSize = chunkingSettings.maxChunkSize();

        if (maxChunkSize != null) {
            knowledgeBase.setMaxChunkSize(maxChunkSize);
        }

        Integer minChunkSizeChars = chunkingSettings.minChunkSizeChars();

        if (minChunkSizeChars != null) {
            knowledgeBase.setMinChunkSizeChars(minChunkSizeChars);
        }

        Integer overlap = chunkingSettings.overlap();

        if (overlap != null) {
            knowledgeBase.setOverlap(overlap);
        }
    }

    private static Optional<Owner> toOwner(@Nullable Long ownerId) {
        return ownerId == null ? Optional.empty() : Optional.of(Owner.connectedUser(ownerId));
    }
}
