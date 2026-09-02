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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocumentChunk;
import com.bytechef.platform.knowledgebase.exception.KnowledgeBaseStorageLimitExceededException;
import com.bytechef.platform.knowledgebase.file.storage.KnowledgeBaseFileStorage;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentChunkService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentTagService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseStorageService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseVectorStoreMetadataService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * @author Ivica Cardic
 */
class KnowledgeBaseDocumentFacadeTest {

    private static final Owner OWNER = Owner.connectedUser(42L);
    private static final Owner OTHER_OWNER = Owner.connectedUser(43L);

    private final KnowledgeBaseDocumentChunkService chunkService = mock(KnowledgeBaseDocumentChunkService.class);
    private final KnowledgeBaseFileStorage fileStorage = mock(KnowledgeBaseFileStorage.class);
    private final KnowledgeBaseDocumentService documentService = mock(KnowledgeBaseDocumentService.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeBaseStorageService storageService = mock(KnowledgeBaseStorageService.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final OwnerResolver ownerResolver = mock(OwnerResolver.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OwnerResolver> ownerResolverProvider = mock(ObjectProvider.class);

    private KnowledgeBaseDocumentFacadeImpl createFacade() {
        return new KnowledgeBaseDocumentFacadeImpl(
            mock(ApplicationEventPublisher.class), chunkService, documentService,
            mock(KnowledgeBaseDocumentTagService.class), fileStorage, knowledgeBaseService,
            mock(KnowledgeBaseVectorStoreMetadataService.class), ownerResolverProvider, vectorStore, storageService);
    }

    @Test
    void testCreateBlockedWhenOverLimit() {
        doThrow(new KnowledgeBaseStorageLimitExceededException(2_000L, 1_000L))
            .when(storageService)
            .checkWithinLimit(500);

        assertThatThrownBy(() -> createFacade().createKnowledgeBaseDocument(
            1L, "a.txt", "text/plain", 500, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(KnowledgeBaseStorageLimitExceededException.class);

        verifyNoInteractions(fileStorage);
    }

    @Test
    void testCreatePersistsDocumentSize() {
        when(fileStorage.storeDocument(eq("a.txt"), any())).thenReturn(mock(FileEntry.class));
        when(documentService.saveKnowledgeBaseDocument(any())).thenAnswer(invocation -> invocation.getArgument(0));

        createFacade().createKnowledgeBaseDocument(
            1L, "a.txt", "text/plain", 500, new ByteArrayInputStream(new byte[0]));

        ArgumentCaptor<KnowledgeBaseDocument> captor = ArgumentCaptor.forClass(KnowledgeBaseDocument.class);

        verify(documentService).saveKnowledgeBaseDocument(captor.capture());

        assertThat(captor.getValue()
            .getDocumentSize())
                .isEqualTo(500L);
    }

    /**
     * The console upload is the other half of A2, beside the workflow sync. The chunker runs off an event on another
     * thread with no security context, so the account has to be written to the row while the request is still on the
     * stack -- and both columns have to be written, since an id with no type belongs to nobody.
     */
    @Test
    void testCreateStampsTheUploadingAccountOnTheDocument() {
        when(fileStorage.storeDocument(eq("a.txt"), any())).thenReturn(mock(FileEntry.class));
        when(documentService.saveKnowledgeBaseDocument(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ownerResolverProvider.getIfAvailable()).thenReturn(ownerResolver);
        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.of(Owner.connectedUser(42L)));

        createFacade().createKnowledgeBaseDocument(
            1L, "a.txt", "text/plain", 500, new ByteArrayInputStream(new byte[0]));

        ArgumentCaptor<KnowledgeBaseDocument> captor = ArgumentCaptor.forClass(KnowledgeBaseDocument.class);

        verify(documentService).saveKnowledgeBaseDocument(captor.capture());

        assertThat(captor.getValue()
            .getOwner())
                .contains(Owner.connectedUser(42L));
    }

    /**
     * Community ships no resolver at all, and a tenant member uploading through the console resolves to nobody. Both
     * are the vendor uploading, whose chunks are shared.
     */
    @Test
    void testCreateLeavesTheDocumentUnownedWhenNoAccountDroveIt() {
        when(fileStorage.storeDocument(eq("a.txt"), any())).thenReturn(mock(FileEntry.class));
        when(documentService.saveKnowledgeBaseDocument(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ownerResolverProvider.getIfAvailable()).thenReturn(null);

        createFacade().createKnowledgeBaseDocument(
            1L, "a.txt", "text/plain", 500, new ByteArrayInputStream(new byte[0]));

        ArgumentCaptor<KnowledgeBaseDocument> captor = ArgumentCaptor.forClass(KnowledgeBaseDocument.class);

        verify(documentService).saveKnowledgeBaseDocument(captor.capture());

        assertThat(captor.getValue()
            .getOwner())
                .isEmpty();
    }

    @Test
    void testSweepTombstonedDocumentChunksEvictsVectorsFilesAndRows() {
        KnowledgeBaseDocument documentOne = newTombstonedDocument(10L, 1L);
        KnowledgeBaseDocument documentTwo = newTombstonedDocument(11L, 1L);

        when(documentService.getTombstonedDocuments(5L, Optional.empty()))
            .thenReturn(List.of(documentOne, documentTwo));

        FileEntry chunkContent = new FileEntry("chunk-1.txt", "file://chunks/chunk-1.txt");

        KnowledgeBaseDocumentChunk chunkWithVector = newChunk(10L, "vector-1", chunkContent);
        KnowledgeBaseDocumentChunk chunkWithoutVector = newChunk(11L, null, null);

        when(chunkService.getKnowledgeBaseDocumentChunksByDocumentIds(List.of(10L, 11L)))
            .thenReturn(List.of(chunkWithVector, chunkWithoutVector));

        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setEnvironment(Environment.DEVELOPMENT);

        when(knowledgeBaseService.getKnowledgeBase(1L)).thenReturn(knowledgeBase);

        int sweptChunks = createFacade().sweepTombstonedDocumentChunks(5L, Optional.empty());

        assertThat(sweptChunks).isEqualTo(2);

        verify(vectorStore).delete(List.of("vector-1"));
        verify(fileStorage).deleteChunkContent(chunkContent);
        verify(chunkService).deleteKnowledgeBaseDocumentChunks(List.of(chunkWithVector, chunkWithoutVector));
    }

    @Test
    void testSweepTombstonedDocumentChunksNoTombstonedDocumentsIsNoOp() {
        when(documentService.getTombstonedDocuments(5L, Optional.empty())).thenReturn(List.of());

        int sweptChunks = createFacade().sweepTombstonedDocumentChunks(5L, Optional.empty());

        assertThat(sweptChunks).isEqualTo(0);

        verifyNoInteractions(chunkService, vectorStore, fileStorage);
    }

    @Test
    void testSweepTombstonedDocumentChunksSkipsVectorStoreWhenNoVectorStoreIds() {
        KnowledgeBaseDocument document = newTombstonedDocument(10L, 1L);

        when(documentService.getTombstonedDocuments(5L, Optional.empty())).thenReturn(List.of(document));

        // Chunk rows exist but were never embedded — no vector-store ids to evict.
        KnowledgeBaseDocumentChunk chunk = newChunk(10L, null, null);

        when(chunkService.getKnowledgeBaseDocumentChunksByDocumentIds(List.of(10L))).thenReturn(List.of(chunk));

        int sweptChunks = createFacade().sweepTombstonedDocumentChunks(5L, Optional.empty());

        assertThat(sweptChunks).isEqualTo(1);

        verifyNoInteractions(vectorStore, knowledgeBaseService);
        verify(chunkService).deleteKnowledgeBaseDocumentChunks(List.of(chunk));
    }

    /**
     * The third site of A5. The sweep deletes chunks out of the vector store by raw id -- there is no owner filter
     * below it and, since {@code delete(List)} is refused on the wrapper, no filter that could be applied to an id list
     * -- so whatever the document listing hands it is destroyed. Keying that listing on the source alone destroyed
     * another account's chunks whenever two accounts synced the same source into one shared knowledge base.
     *
     * <p>
     * The listing here is a real predicate over documents of both accounts rather than a canned answer, so the
     * assertion is on the ids that reach {@code delete}: an unscoped sweep hands it {@code vector-theirs} too.
     */
    @Test
    void testSweepTombstonedDocumentChunksDoesNotDeleteAnotherAccountsChunks() {
        KnowledgeBaseDocument ours = newTombstonedDocument(10L, 1L);
        KnowledgeBaseDocument theirs = newTombstonedDocument(11L, 1L);

        ours.setOwner(OWNER);
        theirs.setOwner(OTHER_OWNER);

        when(documentService.getTombstonedDocuments(eq(5L), any()))
            .thenAnswer(invocation -> {
                Optional<Owner> owner = invocation.getArgument(1);

                return List.of(ours, theirs)
                    .stream()
                    .filter(document -> document.isWritableBy(owner))
                    .toList();
            });

        KnowledgeBaseDocumentChunk ourChunk = newChunk(10L, "vector-ours", null);
        KnowledgeBaseDocumentChunk theirChunk = newChunk(11L, "vector-theirs", null);

        // Answered from the ids actually asked for, so an unscoped sweep really does collect both accounts' chunks and
        // the assertion below names the one that should not have been there.
        when(chunkService.getKnowledgeBaseDocumentChunksByDocumentIds(any()))
            .thenAnswer(invocation -> {
                List<Long> documentIds = invocation.getArgument(0);

                return List.of(ourChunk, theirChunk)
                    .stream()
                    .filter(chunk -> documentIds.contains(chunk.getKnowledgeBaseDocumentId()))
                    .toList();
            });

        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setEnvironment(Environment.DEVELOPMENT);

        when(knowledgeBaseService.getKnowledgeBase(1L)).thenReturn(knowledgeBase);

        int sweptChunks = createFacade().sweepTombstonedDocumentChunks(5L, Optional.of(OWNER));

        // What reached the store comes first, so an unscoped build reports the chunk it destroyed rather than a count
        // that happened to be wrong. Captured rather than matched, because a chunk row compares by id and both of
        // these are unsaved: a never-wanted assertion on the row list would be satisfied by the wrong list.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> vectorStoreIdCaptor = ArgumentCaptor.forClass(List.class);

        verify(vectorStore).delete(vectorStoreIdCaptor.capture());

        assertThat(vectorStoreIdCaptor.getValue()).containsExactly("vector-ours");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeBaseDocumentChunk>> chunkCaptor = ArgumentCaptor.forClass(List.class);

        verify(chunkService).deleteKnowledgeBaseDocumentChunks(chunkCaptor.capture());

        assertThat(chunkCaptor.getValue()).extracting(KnowledgeBaseDocumentChunk::getVectorStoreId)
            .containsExactly("vector-ours");

        assertThat(sweptChunks).isEqualTo(1);
    }

    private static KnowledgeBaseDocument newTombstonedDocument(long id, long knowledgeBaseId) {
        KnowledgeBaseDocument document = new KnowledgeBaseDocument();

        document.setId(id);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setDeletedAt(Instant.parse("2026-07-31T10:00:00Z"));

        return document;
    }

    private static KnowledgeBaseDocumentChunk newChunk(long documentId, String vectorStoreId, FileEntry content) {
        KnowledgeBaseDocumentChunk chunk = new KnowledgeBaseDocumentChunk();

        chunk.setKnowledgeBaseDocumentId(documentId);
        chunk.setVectorStoreId(vectorStoreId);
        chunk.setContent(content);

        return chunk;
    }
}
