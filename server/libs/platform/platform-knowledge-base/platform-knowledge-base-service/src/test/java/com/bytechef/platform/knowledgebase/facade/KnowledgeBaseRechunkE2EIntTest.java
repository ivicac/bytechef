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

package com.bytechef.platform.knowledgebase.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocumentChunk;
import com.bytechef.platform.knowledgebase.event.KnowledgeBaseDocumentEvent;
import com.bytechef.platform.knowledgebase.file.storage.KnowledgeBaseFileStorage;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentChunkService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentTagService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseStorageService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseVectorStoreMetadataService;
import com.bytechef.platform.knowledgebase.worker.KnowledgeBaseDocumentProcessWorker;
import com.bytechef.platform.knowledgebase.worker.etl.KnowledgeBaseDocumentReaderFactory;
import com.bytechef.platform.knowledgebase.worker.etl.KnowledgeBaseDocumentTransformerChain;
import com.bytechef.platform.knowledgebase.worker.etl.KnowledgeBaseEtlPipeline;
import com.bytechef.platform.knowledgebase.worker.etl.KnowledgeBaseVectorStoreWriter;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Re-chunking, from the action down to the rows a search would read.
 *
 * <p>
 * The two properties worth having are one about ownership and one about the chunks themselves, and neither can be seen
 * from the facade alone. A re-chunk that merely called the chunker again would satisfy any assertion counting calls,
 * while producing chunks stamped with whoever triggered it or chunks identical to the ones it replaced -- the first
 * being the defect the row axis exists to prevent and the second being the entire point of the action.
 *
 * <p>
 * So the real pieces are here: the facade evicting and re-queueing, the real {@link KnowledgeBaseDocumentProcessWorker}
 * reading the owner back off each document row, the real splitter re-deciding the boundaries from the knowledge base's
 * current settings, a real {@link PgVectorStore} storing the metadata as JSON, and the metadata read back with SQL. The
 * services around them are mocked because they stand in for rows rather than for behaviour, and the columns they stand
 * in for are pinned against the real schema by {@code KnowledgeBaseDocumentServiceIntTest}.
 *
 * <p>
 * The event publisher collects rather than dispatches, and the collected documents are drained afterwards. That is the
 * production timing, not a convenience: {@code MessageEventListener} sends a {@code MessageEvent} only
 * {@code AFTER_COMMIT}, so no document is ever chunked before the eviction that precedes it is durable.
 *
 * @author Ivica Cardic
 */
@Testcontainers
class KnowledgeBaseRechunkE2EIntTest {

    private static final Owner ACCOUNT_A = Owner.connectedUser(42L);
    private static final Owner ACCOUNT_B = Owner.connectedUser(43L);

    private static final long KNOWLEDGE_BASE_ID = 7L;

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(
        org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres"));

    private final AtomicLong chunkIds = new AtomicLong(1);

    private final Map<Long, KnowledgeBaseDocumentChunk> chunkRows = new LinkedHashMap<>();
    private final Map<Long, KnowledgeBaseDocument> documentRows = new LinkedHashMap<>();
    private final Map<Long, String> documentTexts = new LinkedHashMap<>();
    private final List<Long> queuedDocumentIds = new ArrayList<>();

    private final KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService =
        mock(KnowledgeBaseDocumentChunkService.class);
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService = mock(KnowledgeBaseDocumentService.class);
    private final KnowledgeBaseFileStorage knowledgeBaseFileStorage = mock(KnowledgeBaseFileStorage.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);

    private HikariDataSource hikariDataSource;
    private JdbcTemplate jdbcTemplate;
    private KnowledgeBase knowledgeBase;
    private KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade;
    private KnowledgeBaseDocumentProcessWorker worker;

    @BeforeEach
    void setUp() {
        hikariDataSource = new HikariDataSource();

        hikariDataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        hikariDataSource.setUsername(postgreSQLContainer.getUsername());
        hikariDataSource.setPassword(postgreSQLContainer.getPassword());

        jdbcTemplate = new JdbcTemplate(hikariDataSource);

        jdbcTemplate.execute("DROP TABLE IF EXISTS kb_vector_store");

        PgVectorStore pgVectorStore = PgVectorStore.builder(jdbcTemplate, new ConstantEmbeddingModel())
            .vectorTableName("kb_vector_store")
            .dimensions(3)
            .initializeSchema(true)
            .build();

        pgVectorStore.afterPropertiesSet();

        knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(KNOWLEDGE_BASE_ID);

        stubDocumentRows();
        stubChunkRows();
        stubFileStorage();

        when(knowledgeBaseService.getKnowledgeBase(KNOWLEDGE_BASE_ID)).thenReturn(knowledgeBase);

        worker = new KnowledgeBaseDocumentProcessWorker(
            knowledgeBaseDocumentChunkService, knowledgeBaseDocumentService, newEtlPipeline(pgVectorStore),
            knowledgeBaseFileStorage, knowledgeBaseService);

        knowledgeBaseDocumentFacade = new KnowledgeBaseDocumentFacadeImpl(
            applicationEvent -> {
                if (applicationEvent instanceof KnowledgeBaseDocumentEvent knowledgeBaseDocumentEvent) {
                    queuedDocumentIds.add(knowledgeBaseDocumentEvent.getDocumentId());
                }
            },
            knowledgeBaseDocumentChunkService, knowledgeBaseDocumentService,
            mock(KnowledgeBaseDocumentTagService.class), knowledgeBaseFileStorage, knowledgeBaseService,
            mock(KnowledgeBaseVectorStoreMetadataService.class), ownerResolverProvider(), pgVectorStore,
            mock(KnowledgeBaseStorageService.class));
    }

    @AfterEach
    void tearDown() {
        if (hikariDataSource != null) {
            hikariDataSource.close();
        }
    }

    /**
     * The headline, and the reason this action is queued rather than chunked inline. Nothing in the re-chunk call names
     * an account -- it cannot, it is one knowledge base id -- so a build that took the owner from anywhere but each
     * document's own row would stamp every chunk in a shared knowledge base with one value, and the two accounts would
     * read each other's documents afterwards.
     *
     * <p>
     * The unowned document is in here because "everything got one owner" and "everything kept its owner" agree on a
     * knowledge base holding one account's documents, and disagree on this one.
     */
    @Test
    void testEachAccountsChunksAreStillItsOwnAfterARechunk() {
        newDocument(1L, ACCOUNT_A, "the first account's quarterly figures");
        newDocument(2L, ACCOUNT_B, "the second account's quarterly figures");
        newDocument(3L, null, "the vendor's own quarterly figures");

        chunkEverything();

        int rechunkedCount = knowledgeBaseDocumentFacade.rechunkKnowledgeBaseDocuments(KNOWLEDGE_BASE_ID);

        assertThat(rechunkedCount).isEqualTo(3);

        drainQueue();

        assertThat(chunkOwnerIdsOf(1L)).containsOnly(ACCOUNT_A.id());
        assertThat(chunkOwnerIdsOf(2L))
            .as("the second account's document must not have acquired the first account's owner")
            .containsOnly(ACCOUNT_B.id());
        assertThat(chunkOwnerIdsOf(3L))
            .as("the vendor's document stays the vendor's, rather than being handed to an account by the re-chunk")
            .containsOnly((Long) null);
    }

    /**
     * A shared chunk has to come back out of a re-chunk carrying {@code shared: true} and no owner keys, not merely a
     * missing {@code owner_id}. A chunk carrying neither key satisfies no read filter the wrapper builds, so a re-chunk
     * that produced them would make the vendor's own documents invisible to everybody.
     */
    @Test
    void testARechunkedUnownedDocumentComesBackShared() {
        newDocument(1L, null, "the vendor's own quarterly figures");

        chunkEverything();

        knowledgeBaseDocumentFacade.rechunkKnowledgeBaseDocuments(KNOWLEDGE_BASE_ID);

        drainQueue();

        List<String> metadata = jdbcTemplate.queryForList(
            "SELECT metadata::text FROM kb_vector_store", String.class);

        assertThat(metadata).isNotEmpty();
        assertThat(metadata).allSatisfy(chunkMetadata -> {
            assertThat(chunkMetadata).contains("\"shared\"");
            assertThat(chunkMetadata).doesNotContain("\"owner_id\"");
            assertThat(chunkMetadata).doesNotContain("\"owner_type\"");
        });
    }

    /**
     * The point of the action. Editing the chunking settings reached new uploads only, so the assertion that matters is
     * that a document already embedded comes back split differently -- not that the chunker ran, which it also does
     * when it produces exactly what it produced before.
     *
     * <p>
     * Asserted on the stored text of the chunks rather than on their number alone, because a build that re-read the
     * settings and re-split correctly and a build that re-embedded the old chunks under new ids both change the ids.
     */
    @Test
    void testRechunkingRewritesTheBoundariesOfAnAlreadyEmbeddedDocument() {
        knowledgeBase.setMaxChunkSize(2000);
        knowledgeBase.setMinChunkSizeChars(10);
        knowledgeBase.setOverlap(0);

        newDocument(1L, null, longDocumentText());

        chunkEverything();

        List<String> originalChunkTexts = storedChunkTexts();

        assertThat(originalChunkTexts)
            .as("the whole document fits one chunk at 2000 tokens, which is the boundary the re-chunk has to move")
            .hasSize(1);

        knowledgeBase.setMaxChunkSize(30);

        knowledgeBaseDocumentFacade.rechunkKnowledgeBaseDocuments(KNOWLEDGE_BASE_ID);

        drainQueue();

        List<String> rechunkedTexts = storedChunkTexts();

        assertThat(rechunkedTexts)
            .as("the smaller chunk size has to produce more chunks out of the same document")
            .hasSizeGreaterThan(originalChunkTexts.size());
        assertThat(rechunkedTexts)
            .as("and different ones, not the same text re-embedded under new ids")
            .doesNotContainAnyElementsOf(originalChunkTexts);
    }

    /**
     * The old chunks go. Left behind they would be found by every search alongside the new ones, which is worse than
     * the wrong chunk size the re-chunk was run to fix.
     */
    @Test
    void testTheChunksTheRechunkReplacesAreGoneFromTheStore() {
        newDocument(1L, ACCOUNT_A, "the quarterly figures");

        chunkEverything();

        List<String> originalVectorStoreIds = storedVectorStoreIds();

        assertThat(originalVectorStoreIds).isNotEmpty();

        knowledgeBaseDocumentFacade.rechunkKnowledgeBaseDocuments(KNOWLEDGE_BASE_ID);

        drainQueue();

        assertThat(storedVectorStoreIds()).doesNotContainAnyElementsOf(originalVectorStoreIds);
    }

    /**
     * A document deleted upstream and awaiting its sweep must not be put back into search by a re-chunk. Its chunks are
     * evicted for it and its row is kept only as the resurrect anchor.
     */
    @Test
    void testATombstonedDocumentIsNotRechunked() {
        KnowledgeBaseDocument tombstoned = newDocument(1L, null, "gone upstream");

        chunkEverything();

        tombstoned.setDeletedAt(Instant.now());

        int rechunkedCount = knowledgeBaseDocumentFacade.rechunkKnowledgeBaseDocuments(KNOWLEDGE_BASE_ID);

        drainQueue();

        assertThat(rechunkedCount).isZero();
        assertThat(storedVectorStoreIds())
            .as("nothing is touched, so the sweep still has the same chunks to evict")
            .hasSize(1);
    }

    /**
     * The recovery story, driven rather than described. A re-chunk that dies between the eviction and the chunking
     * leaves the document with no chunks; running the action again is the whole repair, and it has to work on a
     * document whose chunks are already gone -- the case an eviction written as "delete what is listed" handles for
     * free and one written as "delete what should be there" does not.
     */
    @Test
    void testARechunkThatNeverReachedTheChunkerIsRepairedByRunningItAgain() {
        newDocument(1L, ACCOUNT_A, "the quarterly figures");

        chunkEverything();

        knowledgeBaseDocumentFacade.rechunkKnowledgeBaseDocuments(KNOWLEDGE_BASE_ID);

        queuedDocumentIds.clear();

        assertThat(storedVectorStoreIds())
            .as("the state a crash between the two halves leaves: evicted and not yet re-chunked")
            .isEmpty();

        int rechunkedCount = knowledgeBaseDocumentFacade.rechunkKnowledgeBaseDocuments(KNOWLEDGE_BASE_ID);

        drainQueue();

        assertThat(rechunkedCount).isEqualTo(1);
        assertThat(storedVectorStoreIds()).isNotEmpty();
        assertThat(chunkOwnerIdsOf(1L))
            .as("the repair carries the owner as surely as the first attempt would have")
            .containsOnly(ACCOUNT_A.id());
    }

    private void chunkEverything() {
        for (Long documentId : List.copyOf(documentRows.keySet())) {
            worker.onKnowledgeBaseDocumentEvent(new KnowledgeBaseDocumentEvent(documentId));
        }
    }

    /**
     * Stands in for the message broker hop, and deliberately runs after the facade has returned rather than from inside
     * its loop: production sends the event only once the eviction has committed.
     */
    private void drainQueue() {
        for (Long documentId : List.copyOf(queuedDocumentIds)) {
            worker.onKnowledgeBaseDocumentEvent(new KnowledgeBaseDocumentEvent(documentId));
        }

        queuedDocumentIds.clear();
    }

    private List<Long> chunkOwnerIdsOf(long documentId) {
        return jdbcTemplate.queryForList(
            "SELECT (metadata::jsonb ->> 'owner_id')::bigint FROM kb_vector_store" +
                " WHERE metadata::jsonb ->> 'knowledge_base_document_id' = ?",
            Long.class, String.valueOf(documentId));
    }

    private List<String> storedChunkTexts() {
        return jdbcTemplate.queryForList("SELECT content FROM kb_vector_store ORDER BY content", String.class);
    }

    private List<String> storedVectorStoreIds() {
        return jdbcTemplate.queryForList("SELECT id::text FROM kb_vector_store", String.class);
    }

    private static String longDocumentText() {
        StringBuilder text = new StringBuilder();

        for (int index = 0; index < 200; index++) {
            text.append("quarterly figures for region ")
                .append(index)
                .append(". ");
        }

        return text.toString();
    }

    private KnowledgeBaseDocument newDocument(long id, @Nullable Owner owner, String text) {
        KnowledgeBaseDocument document = new KnowledgeBaseDocument();

        document.setId(id);
        document.setKnowledgeBaseId(KNOWLEDGE_BASE_ID);
        document.setName("document-" + id);
        document.setDocument(new FileEntry("document-" + id + ".txt", "file://documents/" + id + ".txt"));
        document.setStatus(KnowledgeBaseDocument.STATUS_UPLOADED);
        document.setOwner(owner);

        documentRows.put(id, document);
        documentTexts.put(id, text);

        return document;
    }

    private KnowledgeBaseEtlPipeline newEtlPipeline(PgVectorStore pgVectorStore) {
        KnowledgeBaseDocumentReaderFactory documentReaderFactory = mock(KnowledgeBaseDocumentReaderFactory.class);

        when(documentReaderFactory.createDocumentReader(any(), any()))
            .thenAnswer(invocation -> {
                Resource resource = invocation.getArgument(0);

                String text = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);

                return (DocumentReader) () -> List.of(new Document(text));
            });

        return new KnowledgeBaseEtlPipeline(
            documentReaderFactory, new KnowledgeBaseDocumentTransformerChain(),
            new KnowledgeBaseVectorStoreWriter(pgVectorStore));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OwnerResolver> ownerResolverProvider() {
        return mock(ObjectProvider.class);
    }

    private void stubChunkRows() {
        when(knowledgeBaseDocumentChunkService.saveKnowledgeBaseDocumentChunk(any()))
            .thenAnswer(invocation -> {
                KnowledgeBaseDocumentChunk chunk = invocation.getArgument(0);

                if (chunk.getId() == null) {
                    chunk.setId(chunkIds.getAndIncrement());
                }

                chunkRows.put(chunk.getId(), chunk);

                return chunk;
            });
        when(knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunksByDocumentId(anyLong()))
            .thenAnswer(invocation -> {
                Long documentId = invocation.getArgument(0);

                return chunkRows.values()
                    .stream()
                    .filter(chunk -> documentId.equals(chunk.getKnowledgeBaseDocumentId()))
                    .toList();
            });
        doAnswerRemovingChunks();
    }

    private void doAnswerRemovingChunks() {
        org.mockito.Mockito.doAnswer(invocation -> {
            List<KnowledgeBaseDocumentChunk> chunks = invocation.getArgument(0);

            for (KnowledgeBaseDocumentChunk chunk : chunks) {
                chunkRows.remove(chunk.getId());
            }

            return null;
        })
            .when(knowledgeBaseDocumentChunkService)
            .deleteKnowledgeBaseDocumentChunks(any());
    }

    private void stubDocumentRows() {
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(anyLong()))
            .thenAnswer(invocation -> documentRows.get(invocation.<Long>getArgument(0)));
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocuments(anyLong()))
            .thenAnswer(invocation -> List.copyOf(documentRows.values()));
        when(knowledgeBaseDocumentService.saveKnowledgeBaseDocument(any()))
            .thenAnswer(invocation -> {
                KnowledgeBaseDocument document = invocation.getArgument(0);

                documentRows.put(document.getId(), document);

                return document;
            });
    }

    private void stubFileStorage() {
        when(knowledgeBaseFileStorage.readDocumentToBytes(any()))
            .thenAnswer(invocation -> {
                FileEntry fileEntry = invocation.getArgument(0);

                String documentId = fileEntry.getName()
                    .replace("document-", "")
                    .replace(".txt", "");

                return documentTexts.get(Long.parseLong(documentId))
                    .getBytes(StandardCharsets.UTF_8);
            });
        when(knowledgeBaseFileStorage.storeChunkContent(anyLong(), anyString()))
            .thenAnswer(invocation -> new FileEntry(
                "chunk-" + invocation.<Long>getArgument(0) + ".txt",
                "file://chunks/" + invocation.<Long>getArgument(0) + ".txt"));
    }

    /**
     * Every chunk embeds to the same vector, so nothing here depends on similarity -- the assertions are about which
     * rows exist and what metadata they carry.
     */
    private static class ConstantEmbeddingModel implements EmbeddingModel {

        private static final float[] VECTOR = {
            0.1f, 0.2f, 0.3f
        };

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();

            List<String> instructions = request.getInstructions();

            for (int index = 0; index < instructions.size(); index++) {
                embeddings.add(new Embedding(VECTOR.clone(), index));
            }

            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return VECTOR.clone();
        }

        @Override
        public int dimensions() {
            return VECTOR.length;
        }
    }
}
