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

package com.bytechef.component.ai.vectorstore.knowledgebase.util;

import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_DOCUMENT_ID;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_ID;
import static com.bytechef.component.definition.ComponentDsl.option;

import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.Option;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.owner.OwnerResolution;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocumentChunk;
import com.bytechef.platform.knowledgebase.exception.KnowledgeBaseDocumentChunkNotFoundException;
import com.bytechef.platform.knowledgebase.exception.KnowledgeBaseDocumentNotFoundException;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentChunkFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentChunkService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentTagService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Shared option builders used by Knowledge Base actions and cluster elements.
 *
 * @author Ivica Cardic
 */
public final class KnowledgeBaseOptionsUtils {

    private KnowledgeBaseOptionsUtils() {
    }

    public static ClusterElementDefinition.OptionsFunction<Long> knowledgeBaseOptions(
        KnowledgeBaseService knowledgeBaseService, ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (
            inputParameters, connectionParameters, lookupDependsOnPaths, searchText,
            context) -> buildKnowledgeBaseOptions(
                searchText, knowledgeBaseService, OwnerResolution.resolve(context, ownerResolverProvider));
    }

    public static ActionDefinition.OptionsFunction<Long> knowledgeBaseActionOptions(
        KnowledgeBaseService knowledgeBaseService, ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (
            inputParameters, connectionParameters, dependencyPaths, searchText,
            context) -> buildKnowledgeBaseOptions(
                searchText, knowledgeBaseService,
                OwnerResolution.resolve((ActionContextAware) context, ownerResolverProvider));
    }

    /**
     * The dropdowns BELOW the knowledge base picker take the same owner as the picker itself.
     *
     * <p>
     * Each of them is keyed off an id typed into a sibling parameter -- {@code knowledgeBaseId} for tags and documents,
     * {@code knowledgeBaseDocumentId} for chunks -- and an id typed into a parameter is caller input like any other.
     * Listing straight off it answered "what is in knowledge base N" for every N in the tenant, which is a read of
     * another account's document names and tag names even before the step runs. So each of these builders admits its
     * knowledge base first, through the same gate the step will use, and walks the belongs-to chain down to whatever id
     * it was given.
     */
    public static ClusterElementDefinition.OptionsFunction<String> tagOptions(
        KnowledgeBaseDocumentTagService knowledgeBaseDocumentTagService, KnowledgeBaseService knowledgeBaseService,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (inputParameters, connectionParameters, lookupDependsOnPaths, searchText, context) -> buildTagOptions(
            inputParameters, searchText, knowledgeBaseDocumentTagService, knowledgeBaseService,
            OwnerResolution.resolve(context, ownerResolverProvider));
    }

    public static ActionDefinition.OptionsFunction<String> tagActionOptions(
        KnowledgeBaseDocumentTagService knowledgeBaseDocumentTagService, KnowledgeBaseService knowledgeBaseService,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (inputParameters, connectionParameters, dependencyPaths, searchText, context) -> buildTagOptions(
            inputParameters, searchText, knowledgeBaseDocumentTagService, knowledgeBaseService,
            OwnerResolution.resolve((ActionContextAware) context, ownerResolverProvider));
    }

    public static ClusterElementDefinition.OptionsFunction<Long> documentOptions(
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, KnowledgeBaseService knowledgeBaseService,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (
            inputParameters, connectionParameters, lookupDependsOnPaths, searchText,
            context) -> buildDocumentOptions(
                inputParameters, searchText, knowledgeBaseDocumentService, knowledgeBaseService,
                OwnerResolution.resolve(context, ownerResolverProvider));
    }

    public static ActionDefinition.OptionsFunction<Long> documentActionOptions(
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, KnowledgeBaseService knowledgeBaseService,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (inputParameters, connectionParameters, dependencyPaths, searchText, context) -> buildDocumentOptions(
            inputParameters, searchText, knowledgeBaseDocumentService, knowledgeBaseService,
            OwnerResolution.resolve((ActionContextAware) context, ownerResolverProvider));
    }

    public static ClusterElementDefinition.OptionsFunction<Long> documentChunkOptions(
        KnowledgeBaseDocumentChunkFacade knowledgeBaseDocumentChunkFacade,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, KnowledgeBaseService knowledgeBaseService,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (
            inputParameters, connectionParameters, lookupDependsOnPaths, searchText,
            context) -> buildDocumentChunkOptions(
                inputParameters, knowledgeBaseDocumentChunkFacade, knowledgeBaseDocumentService, knowledgeBaseService,
                OwnerResolution.resolve(context, ownerResolverProvider));
    }

    public static ActionDefinition.OptionsFunction<Long> documentChunkActionOptions(
        KnowledgeBaseDocumentChunkFacade knowledgeBaseDocumentChunkFacade,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, KnowledgeBaseService knowledgeBaseService,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (
            inputParameters, connectionParameters, dependencyPaths, searchText,
            context) -> buildDocumentChunkOptions(
                inputParameters, knowledgeBaseDocumentChunkFacade, knowledgeBaseDocumentService, knowledgeBaseService,
                OwnerResolution.resolve((ActionContextAware) context, ownerResolverProvider));
    }

    /**
     * The one implementation. Two inline copies of this used to live in KnowledgeBaseDeleteAction and
     * KnowledgeBaseLoadAction; gating three copies is how one gets missed.
     */
    private static List<Option<Long>> buildKnowledgeBaseOptions(
        String searchText, KnowledgeBaseService knowledgeBaseService, Optional<Owner> owner) {

        List<Option<Long>> options = new ArrayList<>();

        for (KnowledgeBase knowledgeBase : knowledgeBases(knowledgeBaseService, owner)) {
            String name = knowledgeBase.getName();

            if (matchesSearchText(name, searchText)) {
                options.add(option(name, knowledgeBase.getId()
                    .longValue()));
            }
        }

        return options;
    }

    /**
     * Every knowledge base the calling owner may pick from, across every pool the run may see.
     *
     * <p>
     * Mirrors {@code DataTableUtils.poolFor}: the pool follows from WHO the run is for, not from where it was authored.
     * A connected user (owner present) only ever reads their own pool, EMBEDDED; a vendor or admin run (owner absent)
     * reads both. Without this, the Knowledge Base Load/Delete dropdown listed every tenant's knowledge bases across
     * both pools regardless of who was picking -- and unlike the search-bar leak, picking a cross-pool entry here let
     * the workflow step actually read that knowledge base's contents.
     */
    static List<KnowledgeBase> knowledgeBases(KnowledgeBaseService knowledgeBaseService, Optional<Owner> owner) {
        return poolFor(owner).stream()
            .flatMap(platformType -> knowledgeBaseService.getKnowledgeBases(platformType)
                .stream())
            .toList();
    }

    public static List<PlatformType> poolFor(Optional<Owner> owner) {
        return owner.isPresent()
            ? List.of(PlatformType.EMBEDDED)
            : List.of(PlatformType.AUTOMATION, PlatformType.EMBEDDED);
    }

    /**
     * The gate every action and cluster tool runs before it touches a knowledge base by id: the id must name a
     * knowledge base in a pool this run may read.
     *
     * <p>
     * A single helper rather than six inline calls, for the same reason {@link #knowledgeBases} is one: the pool has to
     * be derived from the same owner value everywhere, and six copies are five chances to pass one and forget the
     * other. The owner is still a parameter here -- {@link #poolFor(Optional)} reads it to pick the pool -- even though
     * it no longer decides anything once the pool is settled: a knowledge base in that pool is visible to every run
     * that may read the pool, and what separates two accounts sharing it is the owner on the chunks inside it, applied
     * by {@code KnowledgeBaseVectorStoreWrapper}.
     *
     * @param knowledgeBaseService the knowledge base service
     * @param knowledgeBaseId      the knowledge base the step names
     * @param owner                the owner this invocation acts for, used only to pick the pool
     * @return the knowledge base
     */
    public static KnowledgeBase resolveKnowledgeBase(
        KnowledgeBaseService knowledgeBaseService, long knowledgeBaseId, Optional<Owner> owner) {

        return knowledgeBaseService.getKnowledgeBase(knowledgeBaseId, poolFor(owner));
    }

    /**
     * NOT a gate. The fallback for a frame that cannot learn the owner its run acts for, so cannot even ask the pool
     * question.
     *
     * <p>
     * {@link #resolveKnowledgeBase(KnowledgeBaseService, long, Optional)} asks "may this owner reach this knowledge
     * base", which needs the owner to pick a pool. Where the owner is unobtainable, passing an empty one is not a
     * lesser answer but the opposite answer -- an empty owner opens both pools and admits every knowledge base in the
     * tenant. Such a frame has no pool to check either, so it falls back to the fully unscoped read: a knowledge base
     * is no longer assigned to one account, so there is no narrower question left for it to ask, and this call refuses
     * nothing at all.
     *
     * <p>
     * Named for what it does rather than kept as a disguised gate: a name like the old
     * {@code requireUnassignedKnowledgeBase} claimed a check this method no longer performs, and
     * {@code KnowledgeBaseComponentScopesByIdReadsTest} would have had to keep treating it as one of its admission
     * gates to avoid a rename, which is exactly the trap of a no-op labelled as a gate. It is caught by that scan's
     * plain {@code .getKnowledgeBase(} entry like any other unscoped read, trusted only because the call is made from
     * inside this file.
     *
     * @param knowledgeBaseService the knowledge base service
     * @param knowledgeBaseId      the knowledge base the step names
     * @return the knowledge base
     */
    public static KnowledgeBase readUnscopedKnowledgeBase(
        KnowledgeBaseService knowledgeBaseService, long knowledgeBaseId) {

        return knowledgeBaseService.getKnowledgeBase(knowledgeBaseId);
    }

    /**
     * The gate BESIDE the knowledge base gate: a document id, which is caller input in its own right, confirmed to
     * belong to the knowledge base the run was already admitted to AND to be one this run may read.
     *
     * <p>
     * Admitting the knowledge base says nothing about this id. {@code knowledgeBaseId} and
     * {@code knowledgeBaseDocumentId} are two independent parameters of the same step, so a run may pass the first gate
     * with its own knowledge base and then name any document row in the tenant -- which is how a step admitted to its
     * own pool could flip another account's document to PROCESSING and hang its chunks off it.
     *
     * <p>
     * Belonging to the knowledge base is no longer the whole answer. It was while a knowledge base had exactly one
     * owner, so that admitting the knowledge base admitted everything in it; a SHARED knowledge base holds the
     * documents of many accounts, and the row is the only thing left that knows which. So the document's own owner is
     * consulted here, by the same read rule the chunk filter uses: the caller's documents plus the unowned ones.
     *
     * <p>
     * A mismatch is reported as {@link KnowledgeBaseDocumentNotFoundException}, the very exception a missing row
     * produces, so the two are indistinguishable -- and an owner refusal is reported as exactly the same thing, since a
     * refusal that could be told apart from a missing row would turn the document id space into an oracle for which ids
     * exist and who they belong to.
     *
     * @param knowledgeBaseDocumentService the knowledge base document service
     * @param knowledgeBaseId              the id of the ALREADY ADMITTED knowledge base, never one straight from input
     * @param knowledgeBaseDocumentId      the document the step names
     * @param owner                        the owner this invocation acts for
     * @return the document, guaranteed to belong to that knowledge base and to be readable by that owner
     */
    public static KnowledgeBaseDocument requireReadableKnowledgeBaseDocument(
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, long knowledgeBaseId, long knowledgeBaseDocumentId,
        Optional<Owner> owner) {

        KnowledgeBaseDocument knowledgeBaseDocument = requireKnowledgeBaseDocumentIn(
            knowledgeBaseDocumentService, knowledgeBaseId, knowledgeBaseDocumentId);

        if (!knowledgeBaseDocument.isReadableBy(owner)) {
            throw new KnowledgeBaseDocumentNotFoundException(knowledgeBaseDocumentId);
        }

        return knowledgeBaseDocument;
    }

    /**
     * The same gate for the steps that rewrite a document rather than read it: the caller's own documents alone.
     *
     * <p>
     * Deliberately narrower than {@link #requireReadableKnowledgeBaseDocument}, and separate from it rather than a flag
     * on it, so that a call site has to say which of the two it is. The vendor's unowned document is every account's to
     * read and nobody's to rewrite: {@code load} and {@code update} re-chunk what they are given and the wrapper stamps
     * the CALLER onto the new chunks, so admitting a document the caller does not own would move that document's
     * content into the caller's account.
     *
     * @param knowledgeBaseDocumentService the knowledge base document service
     * @param knowledgeBaseId              the id of the ALREADY ADMITTED knowledge base, never one straight from input
     * @param knowledgeBaseDocumentId      the document the step names
     * @param owner                        the owner this invocation acts for
     * @return the document, guaranteed to belong to that knowledge base and to that owner
     */
    public static KnowledgeBaseDocument requireWritableKnowledgeBaseDocument(
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, long knowledgeBaseId, long knowledgeBaseDocumentId,
        Optional<Owner> owner) {

        KnowledgeBaseDocument knowledgeBaseDocument = requireKnowledgeBaseDocumentIn(
            knowledgeBaseDocumentService, knowledgeBaseId, knowledgeBaseDocumentId);

        if (!knowledgeBaseDocument.isWritableBy(owner)) {
            throw new KnowledgeBaseDocumentNotFoundException(knowledgeBaseDocumentId);
        }

        return knowledgeBaseDocument;
    }

    /**
     * The same gate one level further down: a chunk id confirmed to sit under a document that sits in the admitted
     * knowledge base and belongs to this run. A chunk carries neither a knowledge base nor an owner of its own, so the
     * chain has to be walked -- chunk to its document, document to its knowledge base and its owner -- and every step
     * of it can fail.
     *
     * <p>
     * Every failure along the chain is reported as a missing CHUNK, including the ones that are really about the
     * document behind it. Saying "that document is not yours" would answer a question about the document to someone who
     * only named a chunk.
     *
     * @param knowledgeBaseDocumentChunkService the knowledge base document chunk service
     * @param knowledgeBaseDocumentService      the knowledge base document service
     * @param knowledgeBaseId                   the id of the ALREADY ADMITTED knowledge base
     * @param knowledgeBaseDocumentChunkId      the chunk the step names
     * @param owner                             the owner this invocation acts for
     * @return the chunk, guaranteed to belong to that knowledge base and to that owner
     */
    public static KnowledgeBaseDocumentChunk requireWritableKnowledgeBaseDocumentChunk(
        KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, long knowledgeBaseId,
        long knowledgeBaseDocumentChunkId, Optional<Owner> owner) {

        KnowledgeBaseDocumentChunk knowledgeBaseDocumentChunk =
            knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunk(knowledgeBaseDocumentChunkId);

        Long knowledgeBaseDocumentId = knowledgeBaseDocumentChunk.getKnowledgeBaseDocumentId();

        if (knowledgeBaseDocumentId == null) {
            throw new KnowledgeBaseDocumentChunkNotFoundException(knowledgeBaseDocumentChunkId);
        }

        try {
            requireWritableKnowledgeBaseDocument(
                knowledgeBaseDocumentService, knowledgeBaseId, knowledgeBaseDocumentId, owner);
        } catch (KnowledgeBaseDocumentNotFoundException exception) {
            throw new KnowledgeBaseDocumentChunkNotFoundException(knowledgeBaseDocumentChunkId);
        }

        return knowledgeBaseDocumentChunk;
    }

    /**
     * The half of the gate that predates the second ownership axis: the document sits in the admitted knowledge base.
     * Private, and never the whole answer -- both public forms add the owner rule on top, and a third caller reaching
     * for this one alone would be reintroducing the defect.
     */
    private static KnowledgeBaseDocument requireKnowledgeBaseDocumentIn(
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, long knowledgeBaseId,
        long knowledgeBaseDocumentId) {

        KnowledgeBaseDocument knowledgeBaseDocument =
            knowledgeBaseDocumentService.getKnowledgeBaseDocument(knowledgeBaseDocumentId);

        if (!Objects.equals(knowledgeBaseDocument.getKnowledgeBaseId(), knowledgeBaseId)) {
            throw new KnowledgeBaseDocumentNotFoundException(knowledgeBaseDocumentId);
        }

        return knowledgeBaseDocument;
    }

    /**
     * The knowledge base a run resolves for a name, as a step needs it: present or thrown.
     *
     * <p>
     * The resolution rule -- unique within an environment and a pool, with no further split by owner -- lives in
     * {@link KnowledgeBaseService#fetchKnowledgeBase(String, int, PlatformType)}, beside its data-table twin. It used
     * to be composed here, over a whole-environment listing filtered in Java. That put a rule in a component util one
     * re-composition away from being got wrong, and made every resolution read the environment.
     *
     * @param knowledgeBaseService the knowledge base service
     * @param name                 the knowledge base name to resolve
     * @param environment          the environment ordinal to resolve within
     * @param platformType         the pool to resolve within
     * @return the knowledge base with that name in that environment and pool
     * @throws RuntimeException if none exists
     */
    public static KnowledgeBase resolveKnowledgeBase(
        KnowledgeBaseService knowledgeBaseService, String name, int environment, PlatformType platformType) {

        return knowledgeBaseService.fetchKnowledgeBase(name, environment, platformType)
            .orElseThrow(() -> new RuntimeException("KnowledgeBase not found: " + name));
    }

    /**
     * The unpicked case returns nothing rather than {@code getAllTagNames()}. That fallback listed every tag name in
     * the tenant -- every pool, every account -- to anyone who opened the dropdown before choosing a knowledge base,
     * which is the same cross-account read as the picked case, just without needing an id to guess.
     */
    private static List<Option<String>> buildTagOptions(
        Parameters inputParameters, String searchText,
        KnowledgeBaseDocumentTagService knowledgeBaseDocumentTagService, KnowledgeBaseService knowledgeBaseService,
        Optional<Owner> owner) {

        Long knowledgeBaseId = inputParameters.getLong(KNOWLEDGE_BASE_ID);

        if (knowledgeBaseId == null) {
            return List.of();
        }

        KnowledgeBase knowledgeBase = resolveKnowledgeBase(knowledgeBaseService, knowledgeBaseId, owner);

        List<String> tagNames = knowledgeBaseDocumentTagService.getTagNamesByKnowledgeBaseId(knowledgeBase.getId());

        List<Option<String>> options = new ArrayList<>();

        for (String tagName : tagNames) {
            if (matchesSearchText(tagName, searchText)) {
                options.add(option(tagName, tagName));
            }
        }

        return options;
    }

    /**
     * Admitting the knowledge base is not admitting everything in it. A shared knowledge base holds the documents of
     * many accounts, so the listing applies the READ rule per document -- the caller's own plus the vendor's unowned
     * ones -- rather than returning whatever the knowledge base contains. Without it the picker named another account's
     * documents, which is a read of their titles before any step runs and an invitation to type the id into the step
     * that follows.
     */
    private static List<Option<Long>> buildDocumentOptions(
        Parameters inputParameters, String searchText, KnowledgeBaseDocumentService knowledgeBaseDocumentService,
        KnowledgeBaseService knowledgeBaseService, Optional<Owner> owner) {

        Long knowledgeBaseId = inputParameters.getLong(KNOWLEDGE_BASE_ID);

        if (knowledgeBaseId == null) {
            return List.of();
        }

        KnowledgeBase knowledgeBase = resolveKnowledgeBase(knowledgeBaseService, knowledgeBaseId, owner);

        List<Option<Long>> options = new ArrayList<>();

        List<KnowledgeBaseDocument> documents =
            knowledgeBaseDocumentService.getKnowledgeBaseDocuments(knowledgeBase.getId());

        for (KnowledgeBaseDocument document : documents) {
            if (!document.isReadableBy(owner)) {
                continue;
            }

            String name = document.getName();

            if (matchesSearchText(name, searchText)) {
                Long documentId = document.getId();

                options.add(option(name, documentId.longValue()));
            }
        }

        return options;
    }

    /**
     * Two ids to admit, not one. The chunk dropdown depends on {@code knowledgeBaseDocumentId}, but a document id alone
     * constrains nothing -- it is the knowledge base beside it that the run is entitled to, so both are read and the
     * chain between them is walked.
     *
     * <p>
     * A listing, so the READ rule: the picker offers the caller's own documents' chunks and the vendor's shared ones,
     * and not another account's in the same shared knowledge base.
     */
    private static List<Option<Long>> buildDocumentChunkOptions(
        Parameters inputParameters, KnowledgeBaseDocumentChunkFacade knowledgeBaseDocumentChunkFacade,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, KnowledgeBaseService knowledgeBaseService,
        Optional<Owner> owner) {

        Long knowledgeBaseId = inputParameters.getLong(KNOWLEDGE_BASE_ID);
        Long knowledgeBaseDocumentId = inputParameters.getLong(KNOWLEDGE_BASE_DOCUMENT_ID);

        if (knowledgeBaseId == null || knowledgeBaseDocumentId == null) {
            return List.of();
        }

        KnowledgeBase knowledgeBase = resolveKnowledgeBase(knowledgeBaseService, knowledgeBaseId, owner);

        requireReadableKnowledgeBaseDocument(
            knowledgeBaseDocumentService, knowledgeBase.getId(), knowledgeBaseDocumentId, owner);

        List<Option<Long>> options = new ArrayList<>();

        List<KnowledgeBaseDocumentChunk> chunks =
            knowledgeBaseDocumentChunkFacade.getKnowledgeBaseDocumentChunksByDocumentId(knowledgeBaseDocumentId);

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeBaseDocumentChunk chunk = chunks.get(i);

            String textContent = chunk.getTextContent();

            String label = (textContent != null && !textContent.isBlank())
                ? (textContent.length() > 50 ? textContent.substring(0, 50) + "..." : textContent)
                : "Chunk #" + (i + 1);

            Long chunkId = chunk.getId();

            options.add(option(label, chunkId.longValue()));
        }

        return options;
    }

    private static boolean matchesSearchText(String value, String searchText) {
        if (searchText == null) {
            return true;
        }

        return value.toLowerCase(Locale.ROOT)
            .contains(searchText.toLowerCase(Locale.ROOT));
    }
}
