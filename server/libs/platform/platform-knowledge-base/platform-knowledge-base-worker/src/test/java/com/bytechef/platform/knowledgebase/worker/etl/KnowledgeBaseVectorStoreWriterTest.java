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

package com.bytechef.platform.knowledgebase.worker.etl;

import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_TYPE;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_SHARED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bytechef.platform.configuration.context.EnvironmentContext;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

class KnowledgeBaseVectorStoreWriterTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final KnowledgeBaseVectorStoreWriter writer = new KnowledgeBaseVectorStoreWriter(vectorStore);

    @AfterEach
    void tearDown() {
        EnvironmentContext.clear();
    }

    @Test
    void testSetsEnvironmentDuringAddAndClearsAfter() {
        AtomicReference<Environment> observed = new AtomicReference<>();

        doAnswer(invocation -> {
            observed.set(EnvironmentContext.getCurrentEnvironment());

            return null;
        }).when(vectorStore)
            .add(anyList());

        writer.write(List.of(new Document("hello")), 1L, 2L, Environment.STAGING.ordinal(), List.of(), null);

        assertThat(observed.get()).isEqualTo(Environment.STAGING);
        assertThat(EnvironmentContext.getCurrentEnvironment()).isEqualTo(Environment.PRODUCTION);
    }

    @Test
    void testWriteChunkSetsEnvironmentDuringAddAndClearsAfter() {
        AtomicReference<Environment> observed = new AtomicReference<>();

        doAnswer(invocation -> {
            observed.set(EnvironmentContext.getCurrentEnvironment());

            return null;
        }).when(vectorStore)
            .add(anyList());

        writer.writeChunk(new Document("hello"), 1L, 2L, 3L, Environment.STAGING.ordinal(), List.of(), null);

        assertThat(observed.get()).isEqualTo(Environment.STAGING);
        assertThat(EnvironmentContext.getCurrentEnvironment()).isEqualTo(Environment.PRODUCTION);
    }

    /**
     * A document with no owner chunks as shared. A chunk carrying neither ownership key matches no read filter the
     * knowledge base wrapper builds and would be invisible to every account and to the vendor alike -- the same
     * disappearance the startup backfill exists to prevent for the chunks written before this axis existed.
     */
    @Test
    void testAWrittenChunkIsMarkedShared() {
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.captor();

        writer.writeChunk(new Document("hello"), 1L, 2L, 3L, Environment.STAGING.ordinal(), List.of(), null);

        verify(vectorStore).add(documentsCaptor.capture());

        List<Document> documents = documentsCaptor.getValue();

        Document document = documents.getFirst();

        assertThat(document.getMetadata()).containsEntry(METADATA_SHARED, true);
    }

    @Test
    void testEveryChunkOfAWrittenDocumentIsMarkedShared() {
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.captor();

        writer.write(
            List.of(new Document("first"), new Document("second")), 1L, 2L, Environment.STAGING.ordinal(), List.of(),
            null);

        verify(vectorStore).add(documentsCaptor.capture());

        List<Document> documents = documentsCaptor.getValue();

        assertThat(documents)
            .allSatisfy(document -> assertThat(document.getMetadata()).containsEntry(METADATA_SHARED, true));
    }

    /**
     * The owner arrives as an argument rather than from anything ambient, which is the whole point: this pipeline runs
     * off a message with no security context and no job behind it, so the account is one the caller read off the
     * document row.
     */
    @Test
    void testAChunkOfAnOwnedDocumentCarriesBothOwnerKeys() {
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.captor();

        writer.writeChunk(
            new Document("hello"), 1L, 2L, 3L, Environment.STAGING.ordinal(), List.of(), Owner.connectedUser(42L));

        verify(vectorStore).add(documentsCaptor.capture());

        List<Document> documents = documentsCaptor.getValue();

        Document document = documents.getFirst();

        assertThat(document.getMetadata()).containsEntry(METADATA_OWNER_ID, 42L);
        assertThat(document.getMetadata()).containsEntry(METADATA_OWNER_TYPE, OwnerType.CONNECTED_USER.ordinal());
    }

    /**
     * The two encodings are exclusive. A chunk carrying both an owner and the shared flag would be readable by every
     * account through the shared half of the read filter, which is the leak the owner exists to close.
     */
    @Test
    void testAnOwnedChunkIsNotAlsoMarkedShared() {
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.captor();

        writer.writeChunk(
            new Document("hello"), 1L, 2L, 3L, Environment.STAGING.ordinal(), List.of(), Owner.connectedUser(42L));

        verify(vectorStore).add(documentsCaptor.capture());

        List<Document> documents = documentsCaptor.getValue();

        Document document = documents.getFirst();

        assertThat(document.getMetadata()).doesNotContainKey(METADATA_SHARED);
    }

    /**
     * Both owner keys move together. An {@code owner_id} beside no {@code owner_type} satisfies neither predicate and
     * belongs to nobody, so a shared chunk must carry neither rather than one of them.
     */
    @Test
    void testASharedChunkCarriesNeitherOwnerKey() {
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.captor();

        writer.writeChunk(new Document("hello"), 1L, 2L, 3L, Environment.STAGING.ordinal(), List.of(), null);

        verify(vectorStore).add(documentsCaptor.capture());

        List<Document> documents = documentsCaptor.getValue();

        Document document = documents.getFirst();

        assertThat(document.getMetadata()).doesNotContainKey(METADATA_OWNER_ID);
        assertThat(document.getMetadata()).doesNotContainKey(METADATA_OWNER_TYPE);
    }
}
