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
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_DOCUMENT_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_TYPE;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_SHARED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.bytechef.platform.knowledgebase.exception.KnowledgeBaseDocumentChunkNotFoundException;
import com.bytechef.platform.knowledgebase.exception.KnowledgeBaseDocumentNotFoundException;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentChunkFacade;
import com.bytechef.platform.knowledgebase.file.storage.KnowledgeBaseFileStorage;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentChunkService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
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
 * The gate {@link KnowledgeBaseDocumentScopingTest} pins stops one level short of where ownership now lives.
 *
 * <p>
 * That test admits a document by asking whether it sits in the knowledge base the run was admitted to, which was the
 * whole answer while a knowledge base had exactly one owner. A SHARED knowledge base -- one carrying no owner of its
 * own -- holds the documents of many accounts, so belonging to it says nothing about whose the document is, and an
 * account naming another account's document id passed that gate. The step then re-chunked that document and the wrapper
 * stamped the CALLER onto the new chunks: a document's content moving between accounts.
 *
 * <p>
 * Every assertion here is on the CHUNK STORE, not on the exception. A guard that threw after the delete-and-reload had
 * already run would satisfy an assertion that a throwable arrived, and {@code updateSingle} deletes before it loads, so
 * the throwable proves nothing about ordering. The store below is live: an add lands in it and a delete removes from it
 * by evaluating the same filter expression the wrapper composes.
 *
 * <p>
 * Each refusal is paired with the same operation performed legitimately, because a guard that refused everything would
 * satisfy every refusal case and break the product.
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class KnowledgeBaseDocumentOwnerScopingTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long OTHER_ACCOUNT_ID = 43L;

    private static final Owner OWNER = Owner.connectedUser(ACCOUNT_ID);
    private static final Owner OTHER_OWNER = Owner.connectedUser(OTHER_ACCOUNT_ID);

    /** No owner of its own: the case row-level ownership exists for. */
    private static final long SHARED_KNOWLEDGE_BASE_ID = 7L;

    private static final long OWN_DOCUMENT_ID = 100L;
    private static final long OTHER_DOCUMENT_ID = 200L;
    private static final long VENDOR_DOCUMENT_ID = 300L;
    private static final long MISSING_DOCUMENT_ID = 400L;

    private static final long OWN_CHUNK_ID = 1000L;
    private static final long OTHER_CHUNK_ID = 2000L;
    private static final long VENDOR_CHUNK_ID = 3000L;

    /** The id the persistence layer would assign to a chunk a load creates from scratch. */
    private static final long NEW_CHUNK_ID = 9000L;

    private final Map<Long, KnowledgeBase> knowledgeBases = new HashMap<>();
    private final Map<Long, KnowledgeBaseDocument> knowledgeBaseDocuments = new HashMap<>();
    private final Map<Long, KnowledgeBaseDocumentChunk> knowledgeBaseDocumentChunks = new HashMap<>();

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService = mock(KnowledgeBaseDocumentService.class);
    private final KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService =
        mock(KnowledgeBaseDocumentChunkService.class);
    private final KnowledgeBaseDocumentChunkFacade knowledgeBaseDocumentChunkFacade =
        mock(KnowledgeBaseDocumentChunkFacade.class);
    private final KnowledgeBaseFileStorage knowledgeBaseFileStorage = mock(KnowledgeBaseFileStorage.class);
    private final OwnerResolver ownerResolver = mock(OwnerResolver.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OwnerResolver> ownerResolverProvider = mock(ObjectProvider.class);

    private final RecordingVectorStore vectorStore = new RecordingVectorStore();

    @BeforeEach
    void setUp() {
        knowledgeBases.put(SHARED_KNOWLEDGE_BASE_ID, sharedKnowledgeBase());

        knowledgeBaseDocuments.put(OWN_DOCUMENT_ID, document(OWN_DOCUMENT_ID, "own document", OWNER));
        knowledgeBaseDocuments.put(OTHER_DOCUMENT_ID, document(OTHER_DOCUMENT_ID, "other document", OTHER_OWNER));
        knowledgeBaseDocuments.put(VENDOR_DOCUMENT_ID, document(VENDOR_DOCUMENT_ID, "vendor document", null));

        knowledgeBaseDocumentChunks.put(OWN_CHUNK_ID, chunk(OWN_CHUNK_ID, OWN_DOCUMENT_ID));
        knowledgeBaseDocumentChunks.put(OTHER_CHUNK_ID, chunk(OTHER_CHUNK_ID, OTHER_DOCUMENT_ID));
        knowledgeBaseDocumentChunks.put(VENDOR_CHUNK_ID, chunk(VENDOR_CHUNK_ID, VENDOR_DOCUMENT_ID));

        vectorStore.seed(OWN_DOCUMENT_ID, OWN_CHUNK_ID, OWNER);
        vectorStore.seed(OTHER_DOCUMENT_ID, OTHER_CHUNK_ID, OTHER_OWNER);
        vectorStore.seed(VENDOR_DOCUMENT_ID, VENDOR_CHUNK_ID, null);

        when(ownerResolverProvider.getIfAvailable()).thenReturn(ownerResolver);
        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.of(OWNER));

        // The shared knowledge base is readable by everyone, which is the whole point: the resource axis admits every
        // account here, so the row axis is the only thing separating them.
        when(knowledgeBaseService.getKnowledgeBase(anyLong(), any(), any()))
            .thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);

                KnowledgeBase knowledgeBase = knowledgeBases.get(id);

                if (knowledgeBase == null) {
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

        when(knowledgeBaseDocumentService.getKnowledgeBaseDocuments(SHARED_KNOWLEDGE_BASE_ID))
            .thenAnswer(invocation -> List.copyOf(knowledgeBaseDocuments.values()));

        when(knowledgeBaseDocumentService.saveKnowledgeBaseDocument(any()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        when(knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunk(anyLong()))
            .thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);

                KnowledgeBaseDocumentChunk knowledgeBaseDocumentChunk = knowledgeBaseDocumentChunks.get(id);

                if (knowledgeBaseDocumentChunk == null) {
                    throw new KnowledgeBaseDocumentChunkNotFoundException(id);
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

        when(knowledgeBaseDocumentChunkFacade.getKnowledgeBaseDocumentChunksByDocumentId(anyLong()))
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

        doRemoveOnDelete();
    }

    /**
     * The finding itself. The run is admitted to the shared knowledge base -- as every account in it is -- and names
     * another account's document. Watched fail first: before the fix the throwable never arrived, the victim's chunk
     * was deleted from the store, and a new chunk for that document was written stamped {@code owner_id = 42}.
     */
    @Test
    void testARunNamingAnotherAccountsDocumentDoesNotMoveItsChunksToThisAccount() {
        Throwable thrown = catchThrowable(() -> update(OTHER_DOCUMENT_ID, null));

        // The ownership assertion comes FIRST, so an unguarded build reports the chunk that changed hands rather than
        // the exception that did not arrive.
        assertThat(ownersOfChunksOf(OTHER_DOCUMENT_ID)).containsExactly(Optional.of(OTHER_OWNER));
        assertThat(knowledgeBaseDocumentChunks).containsKey(OTHER_CHUNK_ID);
        assertThat(thrown).isInstanceOf(KnowledgeBaseDocumentNotFoundException.class);
    }

    /** The acceptance half: the run's own document is re-chunked, and the new chunks are its own. */
    @Test
    void testARunNamingItsOwnDocumentRewritesItsChunksUnderItsOwnOwner() {
        update(OWN_DOCUMENT_ID, null);

        assertThat(ownersOfChunksOf(OWN_DOCUMENT_ID)).containsExactly(Optional.of(OWNER));
        assertThat(vectorStore.chunkIdsOf(OWN_DOCUMENT_ID)).contains(NEW_CHUNK_ID);
    }

    /**
     * {@code load} names a document too, and hangs its chunks off it without deleting anything first -- so the effect
     * to assert is that nothing was added under this account, and that the victim row was never written.
     */
    @Test
    void testLoadingIntoAnotherAccountsDocumentAddsNothingToIt() {
        KnowledgeBaseDocument victim = knowledgeBaseDocuments.get(OTHER_DOCUMENT_ID);

        Throwable thrown = catchThrowable(() -> load(OTHER_DOCUMENT_ID));

        assertThat(ownersOfChunksOf(OTHER_DOCUMENT_ID)).containsExactly(Optional.of(OTHER_OWNER));
        assertThat(vectorStore.chunkIdsOf(OTHER_DOCUMENT_ID)).containsExactly(OTHER_CHUNK_ID);

        verify(knowledgeBaseDocumentService, never()).saveKnowledgeBaseDocument(victim);

        assertThat(thrown).isInstanceOf(KnowledgeBaseDocumentNotFoundException.class);
    }

    /**
     * The chain one level down: a chunk id names a document indirectly, and the document behind it is another
     * account's. Reported as a missing CHUNK, because the caller asked about a chunk.
     */
    @Test
    void testARunNamingAChunkOfAnotherAccountsDocumentDoesNotDeleteIt() {
        Throwable thrown = catchThrowable(() -> update(null, OTHER_CHUNK_ID));

        assertThat(ownersOfChunksOf(OTHER_DOCUMENT_ID)).containsExactly(Optional.of(OTHER_OWNER));
        assertThat(knowledgeBaseDocumentChunks).containsKey(OTHER_CHUNK_ID);
        assertThat(thrown).isInstanceOf(KnowledgeBaseDocumentChunkNotFoundException.class);
    }

    /**
     * The read half of the asymmetry: the vendor's unowned document is every account's to read. The picker offers it
     * beside the account's own, and its chunks are enumerable -- which is what makes the write refusal below a rule
     * about writing rather than a blanket refusal.
     */
    @Test
    void testTheVendorsUnownedDocumentIsReadableByAnAccount() throws Exception {
        assertThat(documentOptions()).containsExactlyInAnyOrder(OWN_DOCUMENT_ID, VENDOR_DOCUMENT_ID);
        assertThat(documentChunkOptions(VENDOR_DOCUMENT_ID)).containsExactly(VENDOR_CHUNK_ID);
    }

    /**
     * The write half: nobody's document is nobody's to rewrite. An account admitted to read the vendor's document may
     * not re-chunk it, which would stamp the vendor's content with that account and take it away from everyone else.
     */
    @Test
    void testTheVendorsUnownedDocumentIsNotWritableByAnAccount() {
        Throwable thrown = catchThrowable(() -> update(VENDOR_DOCUMENT_ID, null));

        assertThat(ownersOfChunksOf(VENDOR_DOCUMENT_ID)).containsExactly(Optional.empty());
        assertThat(knowledgeBaseDocumentChunks).containsKey(VENDOR_CHUNK_ID);
        assertThat(thrown).isInstanceOf(KnowledgeBaseDocumentNotFoundException.class);
    }

    /**
     * The other direction, and the one an empty owner makes easy to get wrong: a vendor run reaches the unowned
     * documents and never falls through to an account's, exactly as resolution one level up does.
     */
    @Test
    void testAVendorRunReachesTheUnownedDocumentsAlone() throws Exception {
        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> update(OWN_DOCUMENT_ID, null));

        assertThat(ownersOfChunksOf(OWN_DOCUMENT_ID)).containsExactly(Optional.of(OWNER));
        assertThat(thrown).isInstanceOf(KnowledgeBaseDocumentNotFoundException.class);

        assertThat(documentOptions()).containsExactly(VENDOR_DOCUMENT_ID);
    }

    /**
     * The refusal must be indistinguishable from a missing row, or the id space becomes an oracle: name an id, and the
     * difference between "no such document" and "not yours" tells you both that it exists and that it is somebody
     * else's. Same exception type, same message, same id.
     */
    @Test
    void testTheOwnerRefusalIsIndistinguishableFromAMissingDocument() {
        Throwable refused = catchThrowable(() -> update(OTHER_DOCUMENT_ID, null));
        Throwable missing = catchThrowable(() -> update(MISSING_DOCUMENT_ID, null));

        assertThat(refused).isExactlyInstanceOf(KnowledgeBaseDocumentNotFoundException.class);
        assertThat(missing).isExactlyInstanceOf(KnowledgeBaseDocumentNotFoundException.class);

        String refusedMessage = refused.getMessage();

        assertThat(refusedMessage.replace(String.valueOf(OTHER_DOCUMENT_ID), "<id>"))
            .isEqualTo(missing.getMessage()
                .replace(String.valueOf(MISSING_DOCUMENT_ID), "<id>"));
    }

    private void update(Long knowledgeBaseDocumentId, Long knowledgeBaseDocumentChunkId) {
        Map<String, Object> parametersMap = new HashMap<>();

        parametersMap.put(KNOWLEDGE_BASE_ID, SHARED_KNOWLEDGE_BASE_ID);
        parametersMap.put(IS_MULTIPLE, false);

        if (knowledgeBaseDocumentId != null) {
            parametersMap.put(KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId);
        }

        if (knowledgeBaseDocumentChunkId != null) {
            parametersMap.put(KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID, knowledgeBaseDocumentChunkId);
        }

        knowledgeBaseVectorStore().update(
            ParametersFactory.create(parametersMap), ParametersFactory.create(Map.of()), null, documentReader(),
            List.of(), clusterElementContext());
    }

    private void load(long knowledgeBaseDocumentId) {
        Map<String, Object> parametersMap = new HashMap<>();

        parametersMap.put(KNOWLEDGE_BASE_ID, SHARED_KNOWLEDGE_BASE_ID);
        parametersMap.put(KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId);

        knowledgeBaseVectorStore().load(
            ParametersFactory.create(parametersMap), ParametersFactory.create(Map.of()), null, documentReader(),
            List.of(), clusterElementContext());
    }

    private List<Long> documentOptions() throws Exception {
        ClusterElementDefinition.OptionsFunction<Long> optionsFunction = KnowledgeBaseOptionsUtils.documentOptions(
            knowledgeBaseDocumentService, knowledgeBaseService, ownerResolverProvider);

        return values(
            optionsFunction.apply(
                ParametersFactory.create(Map.of(KNOWLEDGE_BASE_ID, SHARED_KNOWLEDGE_BASE_ID)),
                ParametersFactory.create(Map.of()), Map.of(), null, clusterElementContext()));
    }

    private List<Long> documentChunkOptions(long knowledgeBaseDocumentId) throws Exception {
        ClusterElementDefinition.OptionsFunction<Long> optionsFunction = KnowledgeBaseOptionsUtils.documentChunkOptions(
            knowledgeBaseDocumentChunkFacade, knowledgeBaseDocumentService, knowledgeBaseService,
            ownerResolverProvider);

        return values(
            optionsFunction.apply(
                ParametersFactory.create(
                    Map.of(
                        KNOWLEDGE_BASE_ID, SHARED_KNOWLEDGE_BASE_ID, KNOWLEDGE_BASE_DOCUMENT_ID,
                        knowledgeBaseDocumentId)),
                ParametersFactory.create(Map.of()), Map.of(), null, clusterElementContext()));
    }

    /**
     * Who the chunks of a document belong to, read back off the metadata the wrapper wrote. A set rather than a list,
     * because the claim is that every chunk of the document agrees, not that any one of them does.
     */
    private List<Optional<Owner>> ownersOfChunksOf(long knowledgeBaseDocumentId) {
        return vectorStore.documentsOf(knowledgeBaseDocumentId)
            .stream()
            .map(KnowledgeBaseDocumentOwnerScopingTest::ownerOf)
            .distinct()
            .toList();
    }

    private static Optional<Owner> ownerOf(Document document) {
        Map<String, Object> metadata = document.getMetadata();

        Object ownerId = metadata.get(METADATA_OWNER_ID);
        Object ownerType = metadata.get(METADATA_OWNER_TYPE);

        if (ownerId == null || ownerType == null) {
            return Optional.empty();
        }

        OwnerType[] ownerTypes = OwnerType.values();

        return Optional.of(
            new Owner(ownerTypes[((Number) ownerType).intValue()], ((Number) ownerId).longValue()));
    }

    private static <T> List<T> values(List<? extends Option<T>> options) {
        return options.stream()
            .map(Option::getValue)
            .toList();
    }

    private VectorStore knowledgeBaseVectorStore() {
        return KnowledgeBaseVectorStore.createVectorStore(
            knowledgeBaseDocumentChunkService, knowledgeBaseDocumentService, knowledgeBaseFileStorage,
            knowledgeBaseService, vectorStore, ownerResolverProvider);
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

    private ClusterElementContext clusterElementContext() {
        return mock(ClusterElementContext.class);
    }

    private static KnowledgeBase sharedKnowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(SHARED_KNOWLEDGE_BASE_ID);
        knowledgeBase.setPlatformType(PlatformType.EMBEDDED);

        return knowledgeBase;
    }

    private static KnowledgeBaseDocument document(long id, String name, Owner owner) {
        KnowledgeBaseDocument knowledgeBaseDocument = new KnowledgeBaseDocument();

        knowledgeBaseDocument.setId(id);
        knowledgeBaseDocument.setKnowledgeBaseId(SHARED_KNOWLEDGE_BASE_ID);
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

    private static DocumentReader documentReader() {
        return () -> List.of(new Document("some text"));
    }

    /**
     * A live store rather than a mock, because the claim under test is about what the store CONTAINS afterwards. A mock
     * would record the calls a guarded and an unguarded run both make and agree with either.
     *
     * <p>
     * The delete side evaluates the expression the wrapper composed instead of matching on shape, so a chunk survives a
     * delete only when the composed predicate genuinely fails to match it.
     */
    private static final class RecordingVectorStore implements org.springframework.ai.vectorstore.VectorStore {

        private final List<Document> documents = new ArrayList<>();

        private void seed(long knowledgeBaseDocumentId, long chunkId, Owner owner) {
            Map<String, Object> metadata = new HashMap<>();

            metadata.put(METADATA_KNOWLEDGE_BASE_ID, SHARED_KNOWLEDGE_BASE_ID);
            metadata.put(METADATA_KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId);
            metadata.put(METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID, chunkId);

            if (owner == null) {
                metadata.put(METADATA_SHARED, true);
            } else {
                OwnerType ownerType = owner.type();

                metadata.put(METADATA_OWNER_ID, owner.id());
                metadata.put(METADATA_OWNER_TYPE, ownerType.ordinal());
            }

            documents.add(new Document("seeded-" + chunkId, "seeded text", metadata));
        }

        private List<Document> documentsOf(long knowledgeBaseDocumentId) {
            return documents.stream()
                .filter(document -> matchesLong(document, METADATA_KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId))
                .toList();
        }

        private List<Long> chunkIdsOf(long knowledgeBaseDocumentId) {
            return documentsOf(knowledgeBaseDocumentId).stream()
                .map(document -> document.getMetadata()
                    .get(METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID))
                .filter(Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .toList();
        }

        private static boolean matchesLong(Document document, String key, long expected) {
            Object value = document.getMetadata()
                .get(key);

            return value instanceof Number number && number.longValue() == expected;
        }

        @Override
        public void add(List<Document> addedDocuments) {
            documents.addAll(addedDocuments);
        }

        @Override
        public void delete(List<String> idList) {
            throw new UnsupportedOperationException("Deleting by vector store id is not supported");
        }

        @Override
        public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
            documents.removeIf(document -> FilterEvaluator.matches(filterExpression, document.getMetadata()));
        }

        @Override
        public List<Document> similaritySearch(org.springframework.ai.vectorstore.SearchRequest request) {
            org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression =
                request.getFilterExpression();

            return documents.stream()
                .filter(document -> filterExpression == null
                    || FilterEvaluator.matches(filterExpression, document.getMetadata()))
                .toList();
        }

        @Override
        public String getName() {
            return "recording";
        }
    }

    /**
     * The subset of {@code Filter.Expression} the wrapper and the component actually build: EQ leaves combined with AND
     * and OR, each operand parenthesised in a Group. Anything else fails loudly rather than silently matching, so a
     * future filter shape cannot quietly make these assertions vacuous.
     */
    private static final class FilterEvaluator {

        private FilterEvaluator() {
        }

        private static boolean matches(
            org.springframework.ai.vectorstore.filter.Filter.Operand operand, Map<String, Object> metadata) {

            if (operand instanceof org.springframework.ai.vectorstore.filter.Filter.Group group) {
                return matches(group.content(), metadata);
            }

            if (operand instanceof org.springframework.ai.vectorstore.filter.Filter.Expression expression) {
                return matchesExpression(expression, metadata);
            }

            throw new IllegalArgumentException("Unsupported operand: " + operand);
        }

        private static boolean matchesExpression(
            org.springframework.ai.vectorstore.filter.Filter.Expression expression, Map<String, Object> metadata) {

            return switch (expression.type()) {
                case AND -> matches(expression.left(), metadata) && matches(expression.right(), metadata);
                case OR -> matches(expression.left(), metadata) || matches(expression.right(), metadata);
                case EQ -> equalsValue(expression, metadata);
                default -> throw new IllegalArgumentException("Unsupported expression type: " + expression.type());
            };
        }

        private static boolean equalsValue(
            org.springframework.ai.vectorstore.filter.Filter.Expression expression, Map<String, Object> metadata) {

            if (!(expression.left() instanceof org.springframework.ai.vectorstore.filter.Filter.Key key)
                || !(expression.right() instanceof org.springframework.ai.vectorstore.filter.Filter.Value value)) {

                throw new IllegalArgumentException("Unsupported EQ operands: " + expression);
            }

            Object actual = metadata.get(key.key());
            Object expected = value.value();

            if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
                return actualNumber.longValue() == expectedNumber.longValue();
            }

            return Objects.equals(actual, expected);
        }
    }
}
