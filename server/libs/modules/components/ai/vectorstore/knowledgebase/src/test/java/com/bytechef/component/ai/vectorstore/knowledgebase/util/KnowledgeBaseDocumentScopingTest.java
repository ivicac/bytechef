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

import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.IS_MULTIPLE;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_DOCUMENT_ID;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.ai.vectorstore.VectorStore;
import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.Option;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocumentChunk;
import com.bytechef.platform.knowledgebase.exception.KnowledgeBaseDocumentNotFoundException;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentChunkFacade;
import com.bytechef.platform.knowledgebase.file.storage.KnowledgeBaseFileStorage;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentChunkService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentTagService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The hole this closes sits one level BELOW the knowledge base admission gate that
 * {@link KnowledgeBaseVectorStoreScopingTest} pins.
 *
 * <p>
 * {@code knowledgeBaseId}, {@code knowledgeBaseDocumentId} and {@code knowledgeBaseDocumentChunkId} are three
 * independent, expression-enabled parameters of the same step. Passing the gate on the first says nothing whatsoever
 * about the other two, so a run admitted to its own knowledge base could still name another account's document -- and
 * flip it to PROCESSING, hang its own chunks off it, or delete its chunks outright.
 *
 * <p>
 * Every assertion here is on the SERVICE rather than on the exception, because a refusal that throws after deleting is
 * not a fix: the chunk store below is live, so a delete that got through leaves a hole in it whatever the method then
 * throws. {@code updateSingle} deletes before it loads, which is exactly why an exception alone proves nothing about
 * ordering.
 *
 * <p>
 * Each refusal is paired with the same operation on the run's OWN document or chunk, because a guard that refused
 * everything would satisfy every refusal case and break the product.
 *
 * <p>
 * This file pins the KNOWLEDGE BASE half of the gate only -- every knowledge base here belongs to exactly one account,
 * so admitting the knowledge base separates the two. It stopped being the whole answer once a knowledge base could be
 * shared between accounts; that half is pinned by {@link KnowledgeBaseDocumentOwnerScopingTest}, and the owners stamped
 * on the documents below are what let these cases keep testing the knowledge base gate rather than that one.
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class KnowledgeBaseDocumentScopingTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long OTHER_ACCOUNT_ID = 43L;
    private static final Owner OWNER = Owner.connectedUser(ACCOUNT_ID);
    private static final Owner OTHER_OWNER = Owner.connectedUser(OTHER_ACCOUNT_ID);

    private static final long OWN_KNOWLEDGE_BASE_ID = 7L;
    private static final long OTHER_KNOWLEDGE_BASE_ID = 8L;

    private static final long OWN_DOCUMENT_ID = 100L;
    private static final long OTHER_DOCUMENT_ID = 200L;

    private static final long OWN_CHUNK_ID = 1000L;
    private static final long OTHER_CHUNK_ID = 2000L;

    /** The id the persistence layer would assign to a document {@code load} creates from scratch. */
    private static final long NEW_DOCUMENT_ID = 300L;

    /** The id the persistence layer would assign to a chunk {@code load} creates from scratch. */
    private static final long NEW_CHUNK_ID = 3000L;

    private final Map<Long, KnowledgeBase> knowledgeBases = new HashMap<>();
    private final Map<Long, KnowledgeBaseDocument> knowledgeBaseDocuments = new HashMap<>();
    private final Map<Long, KnowledgeBaseDocumentChunk> knowledgeBaseDocumentChunks = new HashMap<>();

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService = mock(KnowledgeBaseDocumentService.class);
    private final KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService =
        mock(KnowledgeBaseDocumentChunkService.class);
    private final KnowledgeBaseDocumentTagService knowledgeBaseDocumentTagService =
        mock(KnowledgeBaseDocumentTagService.class);
    private final KnowledgeBaseDocumentChunkFacade knowledgeBaseDocumentChunkFacade =
        mock(KnowledgeBaseDocumentChunkFacade.class);
    private final KnowledgeBaseFileStorage knowledgeBaseFileStorage = mock(KnowledgeBaseFileStorage.class);
    private final OwnerResolver ownerResolver = mock(OwnerResolver.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OwnerResolver> ownerResolverProvider = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        knowledgeBases.put(OWN_KNOWLEDGE_BASE_ID, knowledgeBaseOwnedBy(OWN_KNOWLEDGE_BASE_ID, ACCOUNT_ID));
        knowledgeBases.put(OTHER_KNOWLEDGE_BASE_ID, knowledgeBaseOwnedBy(OTHER_KNOWLEDGE_BASE_ID, OTHER_ACCOUNT_ID));

        // Each document carries the account whose knowledge base it sits in, which is what a document created in an
        // account's knowledge base is stamped with. Leaving them unowned would model a state the write gate now
        // refuses -- an account may not rewrite a document belonging to nobody -- and would make every acceptance
        // case below fail for a reason that has nothing to do with the knowledge base gate this file pins.
        knowledgeBaseDocuments.put(
            OWN_DOCUMENT_ID, document(OWN_DOCUMENT_ID, OWN_KNOWLEDGE_BASE_ID, "own document", OWNER));
        knowledgeBaseDocuments.put(
            OTHER_DOCUMENT_ID,
            document(OTHER_DOCUMENT_ID, OTHER_KNOWLEDGE_BASE_ID, "other document", OTHER_OWNER));

        knowledgeBaseDocumentChunks.put(OWN_CHUNK_ID, chunk(OWN_CHUNK_ID, OWN_DOCUMENT_ID));
        knowledgeBaseDocumentChunks.put(OTHER_CHUNK_ID, chunk(OTHER_CHUNK_ID, OTHER_DOCUMENT_ID));

        when(ownerResolverProvider.getIfAvailable()).thenReturn(ownerResolver);
        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.of(OWNER));

        when(knowledgeBaseService.getKnowledgeBase(anyLong(), any(), any()))
            .thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                List<PlatformType> platformTypes = invocation.getArgument(1);
                Optional<Owner> owner = invocation.getArgument(2);

                KnowledgeBase knowledgeBase = knowledgeBases.get(id);

                if (knowledgeBase == null || !platformTypes.contains(knowledgeBase.getPlatformType()) ||
                    !isReadableBy(knowledgeBase, owner)) {

                    throw new RuntimeException("KnowledgeBase not found: " + id);
                }

                return knowledgeBase;
            });

        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(anyLong()))
            .thenAnswer(invocation -> {
                long id = invocation.getArgument(0);

                KnowledgeBaseDocument knowledgeBaseDocument = knowledgeBaseDocuments.get(id);

                if (knowledgeBaseDocument == null) {
                    throw new KnowledgeBaseDocumentNotFoundException(id);
                }

                return knowledgeBaseDocument;
            });

        when(knowledgeBaseDocumentService.saveKnowledgeBaseDocument(any()))
            .thenAnswer(invocation -> {
                KnowledgeBaseDocument saved = invocation.getArgument(0);

                if (saved.getId() == null) {
                    saved.setId(NEW_DOCUMENT_ID);
                }

                return saved;
            });

        when(knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunk(anyLong()))
            .thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);

                KnowledgeBaseDocumentChunk knowledgeBaseDocumentChunk = knowledgeBaseDocumentChunks.get(id);

                if (knowledgeBaseDocumentChunk == null) {
                    throw new RuntimeException("KnowledgeBase document chunk not found: " + id);
                }

                return knowledgeBaseDocumentChunk;
            });

        when(knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunksByDocumentId(anyLong()))
            .thenAnswer(invocation -> {
                Long documentId = invocation.getArgument(0);

                return knowledgeBaseDocumentChunks.values()
                    .stream()
                    .filter(candidate -> documentId.equals(candidate.getKnowledgeBaseDocumentId()))
                    .toList();
            });

        when(knowledgeBaseDocumentChunkService.saveKnowledgeBaseDocumentChunk(any()))
            .thenAnswer(invocation -> {
                KnowledgeBaseDocumentChunk saved = invocation.getArgument(0);

                if (saved.getId() == null) {
                    saved.setId(NEW_CHUNK_ID);
                }

                knowledgeBaseDocumentChunks.put(saved.getId(), saved);

                return saved;
            });

        // A LIVE store: a delete that gets through actually removes the row, so an assertion on what survives cannot
        // be satisfied by a refusal thrown after the damage.
        doRemoveOnDelete();
    }

    /**
     * The finding's worst case: the run is admitted to its own knowledge base, and names a chunk belonging to another
     * account's document. Watched fail first -- before the guard, the chunk was gone and the method returned normally.
     */
    @Test
    void testARunNamingAnotherAccountsChunkDoesNotDeleteIt() {
        Throwable thrown = catchThrowable(() -> update(OWN_KNOWLEDGE_BASE_ID, null, OTHER_CHUNK_ID));

        // Survival is asserted BEFORE the refusal, so an unguarded build reports the row that went missing rather
        // than the exception that did not arrive. The refusal matters too -- a delete that quietly did nothing would
        // be its own bug -- but it is the weaker of the two claims.
        assertThat(knowledgeBaseDocumentChunks).containsKey(OTHER_CHUNK_ID);
        assertThat(thrown).isInstanceOf(RuntimeException.class);
    }

    /** The acceptance half: the delete this refusal blocks does happen for the run's own chunk. */
    @Test
    void testARunNamingItsOwnChunkDeletesIt() {
        update(OWN_KNOWLEDGE_BASE_ID, null, OWN_CHUNK_ID);

        assertThat(knowledgeBaseDocumentChunks).doesNotContainKey(OWN_CHUNK_ID);
    }

    /**
     * The same shape one level up: no chunk is named, so {@code updateSingle} deletes every chunk of the named
     * document. Watched fail first -- the other account's chunk was deleted wholesale.
     */
    @Test
    void testARunNamingAnotherAccountsDocumentDoesNotDeleteItsChunks() {
        Throwable thrown = catchThrowable(() -> update(OWN_KNOWLEDGE_BASE_ID, OTHER_DOCUMENT_ID, null));

        assertThat(knowledgeBaseDocumentChunks).containsKey(OTHER_CHUNK_ID);
        assertThat(thrown).isInstanceOf(RuntimeException.class);
    }

    @Test
    void testARunNamingItsOwnDocumentDeletesItsChunks() {
        update(OWN_KNOWLEDGE_BASE_ID, OWN_DOCUMENT_ID, null);

        assertThat(knowledgeBaseDocumentChunks).doesNotContainKey(OWN_CHUNK_ID);
    }

    /**
     * The write half of {@code load}. The status flip to PROCESSING is transient -- a successful run sets it back to
     * READY -- so asserting on the status alone would pass against a completely unguarded build. What actually persists
     * is that the victim ROW is written and this run's chunks are hung off it, and that is what is asserted. Watched
     * fail first on both counts.
     */
    @Test
    void testARunNamingAnotherAccountsDocumentIsNeitherWrittenNorGivenThisRunsChunks() {
        Throwable thrown = catchThrowable(() -> load(OWN_KNOWLEDGE_BASE_ID, OTHER_DOCUMENT_ID));

        KnowledgeBaseDocument victim = knowledgeBaseDocuments.get(OTHER_DOCUMENT_ID);

        assertThat(chunkIdsOf(OTHER_DOCUMENT_ID)).containsExactly(OTHER_CHUNK_ID);

        verify(knowledgeBaseDocumentService, never()).saveKnowledgeBaseDocument(victim);

        assertThat(thrown).isInstanceOf(RuntimeException.class);
    }

    @Test
    void testARunNamingItsOwnDocumentLoadsIntoIt() {
        load(OWN_KNOWLEDGE_BASE_ID, OWN_DOCUMENT_ID);

        assertThat(chunkIdsOf(OWN_DOCUMENT_ID)).contains(NEW_CHUNK_ID);

        verify(knowledgeBaseDocumentService, atLeastOnce()).saveKnowledgeBaseDocument(
            knowledgeBaseDocuments.get(OWN_DOCUMENT_ID));
    }

    /**
     * The read-only twins. A dropdown that lists another account's document names leaks both their existence and their
     * content before any step runs, so the options path is guarded by the same gate rather than left as "only a
     * listing". Watched fail first -- the other account's document name was returned.
     */
    @Test
    void testTheDocumentDropdownRefusesAnotherAccountsKnowledgeBase() {
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocuments(OTHER_KNOWLEDGE_BASE_ID))
            .thenReturn(List.of(knowledgeBaseDocuments.get(OTHER_DOCUMENT_ID)));

        List<Long> listed = new ArrayList<>();

        Throwable thrown = catchThrowable(() -> listed.addAll(documentOptions(OTHER_KNOWLEDGE_BASE_ID)));

        assertThat(listed).isEmpty();
        assertThat(thrown).isInstanceOf(RuntimeException.class);
    }

    @Test
    void testTheDocumentDropdownListsTheRunsOwnKnowledgeBase() throws Exception {
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocuments(OWN_KNOWLEDGE_BASE_ID))
            .thenReturn(List.of(knowledgeBaseDocuments.get(OWN_DOCUMENT_ID)));

        assertThat(documentOptions(OWN_KNOWLEDGE_BASE_ID)).containsExactly(OWN_DOCUMENT_ID);
    }

    @Test
    void testTheTagDropdownRefusesAnotherAccountsKnowledgeBase() {
        when(knowledgeBaseDocumentTagService.getTagNamesByKnowledgeBaseId(OTHER_KNOWLEDGE_BASE_ID))
            .thenReturn(List.of("payroll"));

        List<String> listed = new ArrayList<>();

        Throwable thrown = catchThrowable(() -> listed.addAll(tagOptions(OTHER_KNOWLEDGE_BASE_ID)));

        assertThat(listed).isEmpty();
        assertThat(thrown).isInstanceOf(RuntimeException.class);
    }

    @Test
    void testTheTagDropdownListsTheRunsOwnKnowledgeBase() throws Exception {
        when(knowledgeBaseDocumentTagService.getTagNamesByKnowledgeBaseId(OWN_KNOWLEDGE_BASE_ID))
            .thenReturn(List.of("invoices"));

        assertThat(tagOptions(OWN_KNOWLEDGE_BASE_ID)).containsExactly("invoices");
    }

    /**
     * The chunk dropdown depends on a DOCUMENT id, so its own belongs-to chain is the one that matters: an id from
     * another account's knowledge base must not enumerate that document's chunk contents, which the option labels carry
     * verbatim.
     */
    @Test
    void testTheChunkDropdownRefusesADocumentOutsideTheAdmittedKnowledgeBase() {
        when(knowledgeBaseDocumentChunkFacade.getKnowledgeBaseDocumentChunksByDocumentId(OTHER_DOCUMENT_ID))
            .thenReturn(List.of(knowledgeBaseDocumentChunks.get(OTHER_CHUNK_ID)));

        List<Long> listed = new ArrayList<>();

        Throwable thrown =
            catchThrowable(() -> listed.addAll(documentChunkOptions(OWN_KNOWLEDGE_BASE_ID, OTHER_DOCUMENT_ID)));

        assertThat(listed).isEmpty();
        assertThat(thrown).isInstanceOf(RuntimeException.class);
    }

    @Test
    void testTheChunkDropdownListsTheAdmittedKnowledgeBasesOwnDocument() throws Exception {
        when(knowledgeBaseDocumentChunkFacade.getKnowledgeBaseDocumentChunksByDocumentId(OWN_DOCUMENT_ID))
            .thenReturn(List.of(knowledgeBaseDocumentChunks.get(OWN_CHUNK_ID)));

        assertThat(documentChunkOptions(OWN_KNOWLEDGE_BASE_ID, OWN_DOCUMENT_ID)).containsExactly(OWN_CHUNK_ID);
    }

    private void update(long knowledgeBaseId, Long knowledgeBaseDocumentId, Long knowledgeBaseDocumentChunkId) {
        Map<String, Object> parametersMap = new HashMap<>();

        parametersMap.put(KNOWLEDGE_BASE_ID, knowledgeBaseId);
        parametersMap.put(IS_MULTIPLE, false);

        if (knowledgeBaseDocumentId != null) {
            parametersMap.put(KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId);
        }

        if (knowledgeBaseDocumentChunkId != null) {
            parametersMap.put(KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID, knowledgeBaseDocumentChunkId);
        }

        vectorStore().update(
            ParametersFactory.create(parametersMap), ParametersFactory.create(Map.of()), null, documentReader(),
            List.of(), clusterElementContext());
    }

    private void load(long knowledgeBaseId, long knowledgeBaseDocumentId) {
        Map<String, Object> parametersMap = new HashMap<>();

        parametersMap.put(KNOWLEDGE_BASE_ID, knowledgeBaseId);
        parametersMap.put(KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId);

        vectorStore().load(
            ParametersFactory.create(parametersMap), ParametersFactory.create(Map.of()), null, documentReader(),
            List.of(), clusterElementContext());
    }

    private List<Long> documentOptions(long knowledgeBaseId) throws Exception {
        ClusterElementDefinition.OptionsFunction<Long> optionsFunction = KnowledgeBaseOptionsUtils.documentOptions(
            knowledgeBaseDocumentService, knowledgeBaseService, ownerResolverProvider);

        return values(
            optionsFunction.apply(
                ParametersFactory.create(Map.of(KNOWLEDGE_BASE_ID, knowledgeBaseId)),
                ParametersFactory.create(Map.of()), Map.of(), null, clusterElementContext()));
    }

    private List<String> tagOptions(long knowledgeBaseId) throws Exception {
        ClusterElementDefinition.OptionsFunction<String> optionsFunction = KnowledgeBaseOptionsUtils.tagOptions(
            knowledgeBaseDocumentTagService, knowledgeBaseService, ownerResolverProvider);

        return values(
            optionsFunction.apply(
                ParametersFactory.create(Map.of(KNOWLEDGE_BASE_ID, knowledgeBaseId)),
                ParametersFactory.create(Map.of()), Map.of(), null, clusterElementContext()));
    }

    private List<Long> documentChunkOptions(long knowledgeBaseId, long knowledgeBaseDocumentId) throws Exception {
        ClusterElementDefinition.OptionsFunction<Long> optionsFunction = KnowledgeBaseOptionsUtils.documentChunkOptions(
            knowledgeBaseDocumentChunkFacade, knowledgeBaseDocumentService, knowledgeBaseService,
            ownerResolverProvider);

        return values(
            optionsFunction.apply(
                ParametersFactory.create(
                    Map.of(KNOWLEDGE_BASE_ID, knowledgeBaseId, KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId)),
                ParametersFactory.create(Map.of()), Map.of(), null, clusterElementContext()));
    }

    private List<Long> chunkIdsOf(long knowledgeBaseDocumentId) {
        return knowledgeBaseDocumentChunks.values()
            .stream()
            .filter(chunk -> Objects.equals(chunk.getKnowledgeBaseDocumentId(), knowledgeBaseDocumentId))
            .map(KnowledgeBaseDocumentChunk::getId)
            .toList();
    }

    private static <T> List<T> values(List<? extends Option<T>> options) {
        return options.stream()
            .map(Option::getValue)
            .toList();
    }

    private VectorStore vectorStore() {
        return KnowledgeBaseVectorStore.createVectorStore(
            knowledgeBaseDocumentChunkService, knowledgeBaseDocumentService, knowledgeBaseFileStorage,
            knowledgeBaseService, mock(org.springframework.ai.vectorstore.VectorStore.class), ownerResolverProvider);
    }

    private void doRemoveOnDelete() {
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);

            knowledgeBaseDocumentChunks.remove(id);

            return null;
        })
            .when(knowledgeBaseDocumentChunkService)
            .deleteKnowledgeBaseDocumentChunk(anyLong());

        doAnswer(invocation -> {
            List<KnowledgeBaseDocumentChunk> deleted = invocation.getArgument(0);

            for (KnowledgeBaseDocumentChunk knowledgeBaseDocumentChunk : deleted) {
                knowledgeBaseDocumentChunks.remove(knowledgeBaseDocumentChunk.getId());
            }

            return null;
        })
            .when(knowledgeBaseDocumentChunkService)
            .deleteKnowledgeBaseDocumentChunks(any());
    }

    private static DocumentReader documentReader() {
        return () -> List.of(new Document("some text"));
    }

    /**
     * A plain {@link ClusterElementContext}, which is the shape a cluster tool invoked outside an agent presents:
     * {@code OwnerResolution} then falls back to the security context, where the connected user sits.
     */
    private ClusterElementContext clusterElementContext() {
        return mock(ClusterElementContext.class);
    }

    /** The rule {@code KnowledgeBaseServiceImpl} applies, so the refusal is derived rather than arranged. */
    private static boolean isReadableBy(KnowledgeBase knowledgeBase, Optional<Owner> owner) {
        Long ownerId = knowledgeBase.getOwnerId();

        if (ownerId == null || owner.isEmpty()) {
            return true;
        }

        Owner curOwner = owner.get();

        return curOwner.id() == ownerId && curOwner.type() == knowledgeBase.getOwnerType();
    }

    private static KnowledgeBase knowledgeBaseOwnedBy(long id, long ownerId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(id);
        knowledgeBase.setOwnerId(ownerId);
        knowledgeBase.setOwnerType(OwnerType.CONNECTED_USER);
        knowledgeBase.setPlatformType(PlatformType.EMBEDDED);

        return knowledgeBase;
    }

    private static KnowledgeBaseDocument document(long id, long knowledgeBaseId, String name, Owner owner) {
        KnowledgeBaseDocument knowledgeBaseDocument = new KnowledgeBaseDocument();

        knowledgeBaseDocument.setId(id);
        knowledgeBaseDocument.setKnowledgeBaseId(knowledgeBaseId);
        knowledgeBaseDocument.setName(name);
        knowledgeBaseDocument.setStatus(KnowledgeBaseDocument.STATUS_READY);
        knowledgeBaseDocument.setOwner(owner);

        return knowledgeBaseDocument;
    }

    private static KnowledgeBaseDocumentChunk chunk(long id, long knowledgeBaseDocumentId) {
        KnowledgeBaseDocumentChunk knowledgeBaseDocumentChunk = new KnowledgeBaseDocumentChunk();

        knowledgeBaseDocumentChunk.setId(id);
        knowledgeBaseDocumentChunk.setKnowledgeBaseDocumentId(knowledgeBaseDocumentId);

        return knowledgeBaseDocumentChunk;
    }
}
