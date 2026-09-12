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

package com.bytechef.component.ai.vectorstore.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.ai.vectorstore.knowledgebase.util.KnowledgeBaseVectorStoreWrapper;
import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocumentChunk;
import com.bytechef.platform.knowledgebase.event.KnowledgeBaseDocumentEvent;
import com.bytechef.platform.knowledgebase.file.storage.KnowledgeBaseFileStorage;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentChunkService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.knowledgebase.worker.KnowledgeBaseDocumentProcessWorker;
import com.bytechef.platform.knowledgebase.worker.etl.KnowledgeBaseDocumentReaderFactory;
import com.bytechef.platform.knowledgebase.worker.etl.KnowledgeBaseDocumentTransformerChain;
import com.bytechef.platform.knowledgebase.worker.etl.KnowledgeBaseEtlPipeline;
import com.bytechef.platform.knowledgebase.worker.etl.KnowledgeBaseVectorStoreWriter;
import com.bytechef.platform.owner.Owner;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The property the knowledge base row axis exists to deliver, end to end: a document an account uploaded produces
 * chunks that account can find and nobody else can.
 *
 * <p>
 * It is end to end because the interesting part of this path is a gap. The upload happens in a request; the chunking
 * happens later, off a message, on a thread with no security context and no job behind it. Every link in between is
 * real here -- {@link KnowledgeBaseDocumentProcessWorker} reading the owner off the document row, the ETL pipeline
 * carrying it, {@link KnowledgeBaseVectorStoreWriter} encoding it into the chunk's metadata, a real
 * {@link PgVectorStore} storing that metadata as JSON, and {@link KnowledgeBaseVectorStoreWrapper} filtering on it --
 * because a mock at any one of them would agree with whatever the code did.
 *
 * <p>
 * Postgres in particular cannot be mocked out of this. The read predicate is rendered to JSONPath by
 * {@code PgVectorFilterExpressionConverter} and evaluated by the database, and the two bugs this axis has already hit
 * -- an OR reassociating across an AND, and a key that cannot be tested for absence -- were both invisible to an
 * assertion on the {@code Filter.Expression} tree and visible only to a query.
 *
 * <p>
 * The services around the worker are mocked because they stand in for rows, not for behaviour;
 * {@code KnowledgeBaseDocumentServiceIntTest} pins that the two owner columns survive a round trip through the real
 * schema, which is the half this test hands itself.
 *
 * @author Ivica Cardic
 */
@Testcontainers
class KnowledgeBaseChunkOwnershipE2EIntTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long OTHER_ACCOUNT_ID = 43L;

    private static final long KNOWLEDGE_BASE_ID = 7L;

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres"));

    private final AtomicLong chunkIds = new AtomicLong(1);

    private final KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService =
        mock(KnowledgeBaseDocumentChunkService.class);
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService = mock(KnowledgeBaseDocumentService.class);
    private final KnowledgeBaseFileStorage knowledgeBaseFileStorage = mock(KnowledgeBaseFileStorage.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);

    private HikariDataSource hikariDataSource;
    private PgVectorStore pgVectorStore;
    private KnowledgeBaseDocumentProcessWorker worker;

    @BeforeEach
    void setUp() {
        hikariDataSource = new HikariDataSource();

        hikariDataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        hikariDataSource.setUsername(postgreSQLContainer.getUsername());
        hikariDataSource.setPassword(postgreSQLContainer.getPassword());

        JdbcTemplate jdbcTemplate = new JdbcTemplate(hikariDataSource);

        jdbcTemplate.execute("DROP TABLE IF EXISTS kb_vector_store");

        pgVectorStore = PgVectorStore.builder(jdbcTemplate, new ConstantEmbeddingModel())
            .vectorTableName("kb_vector_store")
            .dimensions(3)
            .initializeSchema(true)
            .build();

        pgVectorStore.afterPropertiesSet();

        worker = new KnowledgeBaseDocumentProcessWorker(
            knowledgeBaseDocumentChunkService, knowledgeBaseDocumentService, newEtlPipeline(),
            knowledgeBaseFileStorage, knowledgeBaseService);

        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(KNOWLEDGE_BASE_ID);

        when(knowledgeBaseService.getKnowledgeBase(KNOWLEDGE_BASE_ID)).thenReturn(knowledgeBase);
        when(knowledgeBaseFileStorage.readDocumentToBytes(any()))
            .thenReturn("the quarterly figures".getBytes(StandardCharsets.UTF_8));
        when(knowledgeBaseFileStorage.storeChunkContent(anyLong(), anyString()))
            .thenReturn(new FileEntry("chunk.txt", "file://chunks/chunk.txt"));
        when(knowledgeBaseDocumentChunkService.saveKnowledgeBaseDocumentChunk(any()))
            .thenAnswer(invocation -> {
                KnowledgeBaseDocumentChunk chunk = invocation.getArgument(0);

                if (chunk.getId() == null) {
                    chunk.setId(chunkIds.getAndIncrement());
                }

                return chunk;
            });
    }

    @AfterEach
    void tearDown() {
        if (hikariDataSource != null) {
            hikariDataSource.close();
        }
    }

    /**
     * The headline. Nothing in the chunking path knows who uploaded except the row, so this failing is the whole row
     * axis being inert.
     */
    @Test
    void testAnAccountFindsTheChunksOfItsOwnUpload() {
        chunk(newDocument(1L, Owner.connectedUser(ACCOUNT_ID)));

        assertThat(searchAs(Optional.of(Owner.connectedUser(ACCOUNT_ID))))
            .containsExactly("the quarterly figures");
    }

    /**
     * The negative half, and the reason the axis exists at all.
     */
    @Test
    void testAnotherAccountDoesNotFindThem() {
        chunk(newDocument(1L, Owner.connectedUser(ACCOUNT_ID)));

        assertThat(searchAs(Optional.of(Owner.connectedUser(OTHER_ACCOUNT_ID)))).isEmpty();
    }

    /**
     * A run with no owner is the vendor, and the vendor's own chunks are the shared ones. It must not fall through to
     * an account's, which is what an unfiltered read or a reassociated OR would do.
     */
    @Test
    void testARunWithNoOwnerDoesNotFindThem() {
        chunk(newDocument(1L, Owner.connectedUser(ACCOUNT_ID)));

        assertThat(searchAs(Optional.empty())).isEmpty();
    }

    /**
     * The default, and the behaviour every document that predates the owner column has to keep: an upload nobody drove
     * chunks as shared and stays readable by everyone in the knowledge base.
     */
    @Test
    void testAnUploadWithNoOwnerProducesSharedChunksEveryoneCanFind() {
        chunk(newDocument(1L, null));

        assertThat(searchAs(Optional.of(Owner.connectedUser(ACCOUNT_ID))))
            .containsExactly("the quarterly figures");
        assertThat(searchAs(Optional.of(Owner.connectedUser(OTHER_ACCOUNT_ID))))
            .containsExactly("the quarterly figures");
        assertThat(searchAs(Optional.empty()))
            .containsExactly("the quarterly figures");
    }

    /**
     * Both encodings side by side in one knowledge base, which is the shape a shared knowledge base actually has: an
     * account sees its own chunks and the unowned ones, and not the other account's.
     */
    @Test
    void testAnAccountSeesItsOwnChunksAndTheSharedOnesAndNoOthers() {
        chunk(newDocument(1L, Owner.connectedUser(ACCOUNT_ID)), "mine");
        chunk(newDocument(2L, Owner.connectedUser(OTHER_ACCOUNT_ID)), "theirs");
        chunk(newDocument(3L, null), "everyones");

        assertThat(searchAs(Optional.of(Owner.connectedUser(ACCOUNT_ID))))
            .containsExactlyInAnyOrder("mine", "everyones");
    }

    /**
     * The chunk of an owned document carries the owner type beside the id. {@code OwnerType} has one constant today, so
     * nothing distinguishes two kinds yet -- which is why the key has to be written now, while every chunk in existence
     * can still be assumed to mean the one kind.
     */
    @Test
    void testAnOwnedChunkCarriesBothOwnerKeysInTheStore() {
        chunk(newDocument(1L, Owner.connectedUser(ACCOUNT_ID)));

        List<String> metadata = new JdbcTemplate(hikariDataSource).queryForList(
            "SELECT metadata::text FROM kb_vector_store", String.class);

        assertThat(metadata).hasSize(1);
        assertThat(metadata.getFirst()).contains("\"owner_id\"");
        assertThat(metadata.getFirst()).contains("\"owner_type\"");
        assertThat(metadata.getFirst()).doesNotContain("\"shared\"");
    }

    /**
     * And the mirror: a shared chunk carries neither owner key rather than one of them. An {@code owner_id} beside no
     * {@code owner_type} satisfies neither half of the read predicate and would make the chunk unreachable.
     */
    @Test
    void testASharedChunkCarriesNeitherOwnerKeyInTheStore() {
        chunk(newDocument(1L, null));

        List<String> metadata = new JdbcTemplate(hikariDataSource).queryForList(
            "SELECT metadata::text FROM kb_vector_store", String.class);

        assertThat(metadata).hasSize(1);
        assertThat(metadata.getFirst()).contains("\"shared\"");
        assertThat(metadata.getFirst()).doesNotContain("\"owner_id\"");
        assertThat(metadata.getFirst()).doesNotContain("\"owner_type\"");
    }

    private void chunk(KnowledgeBaseDocument document) {
        chunk(document, "the quarterly figures");
    }

    private void chunk(KnowledgeBaseDocument document, String text) {
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(document.getId())).thenReturn(document);
        when(knowledgeBaseFileStorage.readDocumentToBytes(document.getDocument()))
            .thenReturn(text.getBytes(StandardCharsets.UTF_8));

        worker.onKnowledgeBaseDocumentEvent(new KnowledgeBaseDocumentEvent(document.getId()));

        assertThat(document.getStatus()).isEqualTo(KnowledgeBaseDocument.STATUS_READY);
    }

    private KnowledgeBaseDocument newDocument(long id, Owner owner) {
        KnowledgeBaseDocument document = new KnowledgeBaseDocument();

        document.setId(id);
        document.setKnowledgeBaseId(KNOWLEDGE_BASE_ID);
        document.setName("document-" + id);
        document.setDocument(new FileEntry("document-" + id + ".txt", "file://documents/" + id + ".txt"));
        document.setOwner(owner);

        return document;
    }

    private KnowledgeBaseEtlPipeline newEtlPipeline() {
        KnowledgeBaseDocumentReaderFactory documentReaderFactory = mock(KnowledgeBaseDocumentReaderFactory.class);
        KnowledgeBaseDocumentTransformerChain transformerChain = mock(KnowledgeBaseDocumentTransformerChain.class);

        when(documentReaderFactory.createDocumentReader(any(), any()))
            .thenAnswer(invocation -> {
                org.springframework.core.io.Resource resource = invocation.getArgument(0);

                String text = new String(
                    resource.getContentAsByteArray(), StandardCharsets.UTF_8);

                return (org.springframework.ai.document.DocumentReader) () -> List.of(new Document(text));
            });
        when(transformerChain.transform(anyList(), anyInt(), anyInt(), anyInt()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        return new KnowledgeBaseEtlPipeline(
            documentReaderFactory, transformerChain, new KnowledgeBaseVectorStoreWriter(pgVectorStore));
    }

    private List<String> searchAs(Optional<Owner> owner) {
        KnowledgeBaseVectorStoreWrapper wrapper = new KnowledgeBaseVectorStoreWrapper(
            pgVectorStore, KNOWLEDGE_BASE_ID, null, owner);

        List<Document> documents = wrapper.similaritySearch(
            SearchRequest.builder()
                .query("figures")
                .topK(10)
                .similarityThreshold(0.0)
                .build());

        List<String> texts = new ArrayList<>();

        for (Document document : documents) {
            texts.add(document.getText());
        }

        return texts;
    }

    /**
     * Every chunk embeds to the same vector, so similarity ranks nothing and the only thing that can remove a row from
     * a result is the filter -- which is what these tests are about.
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
