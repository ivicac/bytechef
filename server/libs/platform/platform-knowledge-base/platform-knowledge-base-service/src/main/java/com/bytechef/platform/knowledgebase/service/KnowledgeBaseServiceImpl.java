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

package com.bytechef.platform.knowledgebase.service;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.audit.KnowledgeBaseAuditEvent;
import com.bytechef.platform.knowledgebase.audit.KnowledgeBaseAuditPublisher;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.event.KnowledgeBaseOwnerAssignedEvent;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseRepository;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@ConditionalOnProperty(prefix = "bytechef.ai.knowledge-base", name = "enabled", havingValue = "true")
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final ApplicationEventPublisher eventPublisher;
    private final KnowledgeBaseAuditPublisher knowledgeBaseAuditPublisher;
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    @SuppressFBWarnings("EI2")
    public KnowledgeBaseServiceImpl(
        ApplicationEventPublisher eventPublisher, KnowledgeBaseAuditPublisher knowledgeBaseAuditPublisher,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, KnowledgeBaseRepository knowledgeBaseRepository) {

        this.eventPublisher = eventPublisher;
        this.knowledgeBaseAuditPublisher = knowledgeBaseAuditPublisher;
        this.knowledgeBaseDocumentService = knowledgeBaseDocumentService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    @Override
    public KnowledgeBase createKnowledgeBase(KnowledgeBase knowledgeBase) {
        KnowledgeBase savedKnowledgeBase = knowledgeBaseRepository.save(knowledgeBase);

        Map<String, Object> data = new HashMap<>();

        data.put("name", savedKnowledgeBase.getName());

        knowledgeBaseAuditPublisher.publish(KnowledgeBaseAuditEvent.KB_CREATED, savedKnowledgeBase.getId(), data);

        return savedKnowledgeBase;
    }

    @Override
    public void deleteKnowledgeBase(Long id) {
        knowledgeBaseRepository.deleteById(id);

        knowledgeBaseAuditPublisher.publish(KnowledgeBaseAuditEvent.KB_DELETED, id);
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeBase getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("KnowledgeBase not found: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeBase getKnowledgeBase(Long id, List<PlatformType> platformTypes, Optional<Owner> owner) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("KnowledgeBase not found: " + id));

        // The pool gate has to come first and cannot be folded into isReadableBy: "unowned is readable" is the right
        // ROW rule WITHIN a pool, and every AUTOMATION knowledge base is unowned, so without this an owned run reads
        // the automation pool by id.
        if (!platformTypes.contains(knowledgeBase.getPlatformType()) || !isReadableBy(knowledgeBase, owner)) {
            // Deliberately the same message: a caller must not be able to tell "someone else's" from "does not
            // exist", or the id space becomes an enumeration oracle.
            throw new RuntimeException("KnowledgeBase not found: " + id);
        }

        return knowledgeBase;
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBase> getKnowledgeBases(PlatformType platformType) {
        return knowledgeBaseRepository.findAllByPlatformType(platformType.ordinal());
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBase> getKnowledgeBases(PlatformType platformType, Optional<Owner> owner) {
        return knowledgeBaseRepository.findAllByPlatformType(platformType.ordinal())
            .stream()
            .filter(knowledgeBase -> isReadableBy(knowledgeBase, owner))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBase> getKnowledgeBases(int environment, PlatformType platformType, Optional<Owner> owner) {
        return knowledgeBaseRepository.findAllByEnvironmentAndPlatformType(environment, platformType.ordinal())
            .stream()
            .filter(knowledgeBase -> isReadableBy(knowledgeBase, owner))
            .toList();
    }

    /**
     * Two lookups, the second only if the first misses, both keyed off
     * {@code uk_knowledge_base_name_platform_type_environment_owner}. This used to list the whole environment-and-pool
     * and pick through it in Java; the rule it applies is unchanged, and both halves of it are load-bearing.
     *
     * <p>
     * The owned lookup carries the owner TYPE as well as the id, because that is what the {@code isReadableBy} filter
     * it replaces compared -- an owner is the pair, and a row whose type does not match was never the caller's.
     *
     * <p>
     * The shared lookup asks for {@code owner_id IS NULL} rather than for "the first row of this name", which is the
     * whole reason a run with no owner cannot fall through to an account's knowledge base. The listing form returns
     * every account's rows once the owner is empty, so a name match alone would have leaked whichever one the database
     * returned first.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeBase> fetchKnowledgeBase(
        String name, int environment, PlatformType platformType, Optional<Owner> owner) {

        if (owner.isPresent()) {
            Owner curOwner = owner.get();

            OwnerType ownerType = curOwner.type();

            Optional<KnowledgeBase> ownedKnowledgeBase =
                knowledgeBaseRepository.findByNameAndEnvironmentAndPlatformTypeAndOwnerIdAndOwnerType(
                    name, environment, platformType.ordinal(), curOwner.id(), ownerType.ordinal());

            if (ownedKnowledgeBase.isPresent()) {
                return ownedKnowledgeBase;
            }
        }

        return knowledgeBaseRepository.findByNameAndEnvironmentAndPlatformTypeAndOwnerIdIsNull(
            name, environment, platformType.ordinal());
    }

    /**
     * Three rules, matching the data table ones: an unowned knowledge base belongs to the vendor and is readable by
     * everyone, an empty owner is an admin or automation caller and reads everything, and an owned one is readable only
     * by that exact owner.
     */
    private static boolean isReadableBy(KnowledgeBase knowledgeBase, Optional<Owner> owner) {
        Long ownerId = knowledgeBase.getOwnerId();

        if (ownerId == null || owner.isEmpty()) {
            return true;
        }

        Owner curOwner = owner.get();

        return curOwner.id() == ownerId && curOwner.type() == knowledgeBase.getOwnerType();
    }

    @Override
    public KnowledgeBase updateKnowledgeBase(Long id, KnowledgeBase knowledgeBase) {
        KnowledgeBase existingKnowledgeBase = getKnowledgeBase(id);

        existingKnowledgeBase.setName(knowledgeBase.getName());
        existingKnowledgeBase.setDescription(knowledgeBase.getDescription());
        existingKnowledgeBase.setMaxChunkSize(knowledgeBase.getMaxChunkSize());
        existingKnowledgeBase.setMinChunkSizeChars(knowledgeBase.getMinChunkSizeChars());
        existingKnowledgeBase.setOverlap(knowledgeBase.getOverlap());

        KnowledgeBase savedKnowledgeBase = knowledgeBaseRepository.save(existingKnowledgeBase);

        knowledgeBaseAuditPublisher.publish(KnowledgeBaseAuditEvent.KB_UPDATED, id);

        return savedKnowledgeBase;
    }

    /**
     * Assignment moves the knowledge base, its documents and its documents' chunks together, in one transaction.
     *
     * <p>
     * The registry row alone is not the knowledge base. Documents carry an owner of their own since the row axis
     * landed, and their chunks carry one independently of that again, so moving the registry row by itself leaves a
     * document written before {@code owner_id} existed unowned inside an assigned knowledge base: readable by the
     * account that owns it, because the read rule admits unowned documents, and writable by that account in no
     * circumstance, because the write rule matches on the owner alone. The account owns the whole knowledge base and
     * cannot edit what is in it. Data tables answered this by re-stamping the table's rows on assignment; this is the
     * same answer for the same reason, and it is why the alternative -- treating an unowned document inside an owned
     * knowledge base as that account's -- is a special case the design does not need.
     *
     * <p>
     * The chunks go with the documents rather than being left to the next re-chunk. A document whose owner disagrees
     * with its own chunks is the half-owner shape wearing different clothes: the account could edit the document and
     * find not a word of it in a search.
     *
     * <p>
     * They go AFTER the commit, though, and not inside it. The chunks live in the vector store, a separate datasource
     * that no transaction here spans, so the two writes cannot be made one and the only choice left is which of them
     * may survive the other. Written inline, a rollback leaves chunks stamped with an owner whose documents never moved
     * -- an account reading chunks in a knowledge base it owns nothing in. Published as
     * {@link KnowledgeBaseOwnerAssignedEvent} and applied after the commit, the surviving state is the reverse: chunks
     * lagging behind their documents, which is what the assignment was already looking at when it started and which
     * re-running it repairs.
     */
    @Override
    @Transactional
    public void assignOwner(long id, @Nullable Owner owner) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("KnowledgeBase not found: " + id));

        // Only the EMBEDDED pool has accounts, so an owner is refused anywhere else -- phrased as "not EMBEDDED"
        // rather than "is AUTOMATION" so a pool added later has to opt in rather than inherit permission. An owned
        // AUTOMATION row resolves for nobody: a run carrying an owner is narrowed to EMBEDDED by poolFor and never
        // looks at this pool, and a run carrying none reaches only unowned rows.
        //
        // Unassigning is allowed in every pool, deliberately: it is the repair path for a row that acquired an owner
        // before this guard existed, and refusing it would leave that row permanently unreachable.
        if (owner != null && knowledgeBase.getPlatformType() != PlatformType.EMBEDDED) {
            throw new IllegalArgumentException(
                "KnowledgeBase " + id + " is not in the EMBEDDED pool and cannot be assigned an owner");
        }

        checkNoOtherAccountsDocuments(id, owner);

        knowledgeBase.setOwnerId(owner == null ? null : owner.id());
        knowledgeBase.setOwnerType(owner == null ? null : owner.type());

        knowledgeBaseRepository.save(knowledgeBase);

        knowledgeBaseDocumentService.restampDocumentOwners(id, owner);

        eventPublisher.publishEvent(new KnowledgeBaseOwnerAssignedEvent(id, owner));

        knowledgeBaseAuditPublisher.publish(KnowledgeBaseAuditEvent.KB_UPDATED, id);
    }

    /**
     * Refuses an assignment that would hand one account's documents to another.
     *
     * <p>
     * {@link KnowledgeBaseDocumentService#restampDocumentOwners} moves every document in the knowledge base onto the
     * new owner, which is right when the documents are unowned or already that account's and wrong when they are not. A
     * shared knowledge base is exactly where several accounts' documents collect -- that is what the row axis is for --
     * so assigning one to a single account would silently make every other account's documents readable and writable by
     * it, and their chunks searchable by it, under a console action labelled only "assign owner".
     *
     * <p>
     * Refused rather than filtered, on the reasoning the data table version settled: re-stamping only the unowned
     * documents would leave the others sitting in a knowledge base their account can no longer resolve -- not
     * disclosed, but silently unreachable, which is a worse thing to discover late. The vendor can unassign first,
     * which says "share this with everyone" in as many words, and then assign.
     *
     * <p>
     * Unassignment ({@code owner} null) is deliberately not checked. Making a knowledge base shared is a statement that
     * its documents are everyone's, and it is also the repair path for a document that acquired an owner before these
     * rules existed.
     */
    private void checkNoOtherAccountsDocuments(long id, @Nullable Owner owner) {
        if (owner == null) {
            return;
        }

        long otherAccountDocumentCount = knowledgeBaseDocumentService.countDocumentsOwnedByAnotherAccount(id, owner);

        if (otherAccountDocumentCount > 0) {
            throw new IllegalArgumentException(
                "KnowledgeBase " + id + " cannot be assigned to owner " + owner.id() + ": it holds " +
                    otherAccountDocumentCount + " document(s) belonging to another account. Unassign the knowledge " +
                    "base first to share those documents, or remove them.");
        }
    }
}
