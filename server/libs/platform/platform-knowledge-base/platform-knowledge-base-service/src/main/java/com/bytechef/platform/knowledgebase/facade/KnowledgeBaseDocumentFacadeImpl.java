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

import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.configuration.context.EnvironmentContext;
import com.bytechef.platform.configuration.domain.Environment;
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
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnProperty(prefix = "bytechef.ai.knowledge-base", name = "enabled", havingValue = "true")
class KnowledgeBaseDocumentFacadeImpl implements KnowledgeBaseDocumentFacade {

    private final ApplicationEventPublisher eventPublisher;
    private final KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService;
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService;
    private final KnowledgeBaseDocumentTagService knowledgeBaseDocumentTagService;
    private final KnowledgeBaseFileStorage knowledgeBaseFileStorage;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseStorageService knowledgeBaseStorageService;
    private final KnowledgeBaseVectorStoreMetadataService knowledgeBaseVectorStoreMetadataService;
    private final ObjectProvider<OwnerResolver> ownerResolverProvider;
    private final VectorStore vectorStore;

    @SuppressFBWarnings("EI")
    KnowledgeBaseDocumentFacadeImpl(
        ApplicationEventPublisher eventPublisher, KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService,
        KnowledgeBaseDocumentTagService knowledgeBaseDocumentTagService,
        KnowledgeBaseFileStorage knowledgeBaseFileStorage, KnowledgeBaseService knowledgeBaseService,
        KnowledgeBaseVectorStoreMetadataService knowledgeBaseVectorStoreMetadataService,
        ObjectProvider<OwnerResolver> ownerResolverProvider,
        @Qualifier("knowledgeBasePgVectorStore") VectorStore vectorStore,
        KnowledgeBaseStorageService knowledgeBaseStorageService) {

        this.ownerResolverProvider = ownerResolverProvider;
        this.eventPublisher = eventPublisher;
        this.knowledgeBaseDocumentChunkService = knowledgeBaseDocumentChunkService;
        this.knowledgeBaseDocumentService = knowledgeBaseDocumentService;
        this.knowledgeBaseDocumentTagService = knowledgeBaseDocumentTagService;
        this.knowledgeBaseFileStorage = knowledgeBaseFileStorage;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseVectorStoreMetadataService = knowledgeBaseVectorStoreMetadataService;
        this.vectorStore = vectorStore;
        this.knowledgeBaseStorageService = knowledgeBaseStorageService;
    }

    /**
     * Stamps the uploader's account onto the row before the chunker ever sees it.
     *
     * <p>
     * The chunker runs off {@link KnowledgeBaseDocumentEvent} on another thread with no security context, so asking who
     * uploaded there returns nothing however the upload arrived. The question is answerable exactly here, while the
     * request is still on the stack, and the answer only survives because it is written to the row.
     *
     * <p>
     * An empty resolution is the vendor uploading -- every Community upload and every console upload by a member of the
     * tenant -- and its chunks are shared, readable by every account in the knowledge base.
     */
    @Override
    public KnowledgeBaseDocument createKnowledgeBaseDocument(
        Long knowledgeBaseId, String filename, String contentType, long size, InputStream inputStream) {

        knowledgeBaseStorageService.checkWithinLimit(size);

        FileEntry fileEntry = knowledgeBaseFileStorage.storeDocument(filename, inputStream);

        KnowledgeBaseDocument knowledgeBaseDocument = new KnowledgeBaseDocument();

        knowledgeBaseDocument.setKnowledgeBaseId(knowledgeBaseId);
        knowledgeBaseDocument.setName(filename);
        knowledgeBaseDocument.setDocument(fileEntry);
        knowledgeBaseDocument.setDocumentSize(size);
        knowledgeBaseDocument.setStatus(KnowledgeBaseDocument.STATUS_UPLOADED);
        knowledgeBaseDocument.setOwner(resolveCurrentOwner().orElse(null));

        knowledgeBaseDocument = knowledgeBaseDocumentService.saveKnowledgeBaseDocument(knowledgeBaseDocument);

        eventPublisher.publishEvent(new KnowledgeBaseDocumentEvent(knowledgeBaseDocument.getId()));

        return knowledgeBaseDocument;
    }

    @Override
    public void deleteKnowledgeBaseDocument(Long id) {
        KnowledgeBaseDocument knowledgeBaseDocument = knowledgeBaseDocumentService.getKnowledgeBaseDocument(id);

        evictChunks(knowledgeBaseDocument);

        knowledgeBaseFileStorage.deleteDocument(knowledgeBaseDocument.getDocument());

        knowledgeBaseDocumentService.delete(id);
    }

    /**
     * Re-splits and re-embeds every live document of the knowledge base under the settings it carries now, by evicting
     * each document's chunks and putting the document back on the ingestion queue.
     *
     * <p>
     * The re-queue is the load-bearing choice, and it is a choice about ownership before it is one about latency.
     * {@code KnowledgeBaseDocumentProcessWorker} reads the account off the document row -- the whole reason the column
     * exists is that the chunker runs with no principal on the thread -- so a document re-chunked through the queue
     * gets its OWN owner onto its new chunks, whoever asked for the re-chunk. Re-chunking inline instead would mean a
     * second chunking path, and the natural mistake in a second path is to stamp the chunks with the caller: in a
     * shared knowledge base holding several accounts' documents, that hands every chunk in it to whoever pressed the
     * button.
     *
     * <p>
     * Tombstoned documents are skipped -- they are gone upstream and waiting to be swept, and re-chunking one would put
     * its content back into search.
     *
     * <p>
     * The status goes back to {@code STATUS_UPLOADED} rather than staying READY, so a document that has lost its chunks
     * and not yet regained them does not read as ready in the console while it is empty.
     */
    @Override
    public int rechunkKnowledgeBaseDocuments(long knowledgeBaseId) {
        List<KnowledgeBaseDocument> knowledgeBaseDocuments =
            knowledgeBaseDocumentService.getKnowledgeBaseDocuments(knowledgeBaseId);

        int rechunkedCount = 0;

        for (KnowledgeBaseDocument knowledgeBaseDocument : knowledgeBaseDocuments) {
            if (knowledgeBaseDocument.getDeletedAt() != null) {
                continue;
            }

            evictChunks(knowledgeBaseDocument);

            knowledgeBaseDocument.setStatus(KnowledgeBaseDocument.STATUS_UPLOADED);

            knowledgeBaseDocumentService.saveKnowledgeBaseDocument(knowledgeBaseDocument);

            eventPublisher.publishEvent(new KnowledgeBaseDocumentEvent(knowledgeBaseDocument.getId()));

            rechunkedCount++;
        }

        return rechunkedCount;
    }

    /**
     * Removes everything one document's chunks consist of: the vector-store rows, the chunk content files, and the
     * chunk rows themselves. Shared by deletion and re-chunking, which differ only in what they do afterwards.
     *
     * <p>
     * Idempotent by construction rather than by a guard: a document whose chunks are already gone lists none and the
     * method does nothing, and a vector-store id whose row is already deleted is a no-op delete. That is what makes a
     * re-chunk that failed part-way safe to simply run again.
     */
    private void evictChunks(KnowledgeBaseDocument knowledgeBaseDocument) {
        List<KnowledgeBaseDocumentChunk> knowledgeBaseDocumentChunks =
            knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunksByDocumentId(
                knowledgeBaseDocument.getId());

        List<String> vectorStoreIds = knowledgeBaseDocumentChunks.stream()
            .map(KnowledgeBaseDocumentChunk::getVectorStoreId)
            .filter(vectorStoreId -> vectorStoreId != null)
            .toList();

        if (!vectorStoreIds.isEmpty()) {
            KnowledgeBase knowledgeBase =
                knowledgeBaseService.getKnowledgeBase(knowledgeBaseDocument.getKnowledgeBaseId());
            Environment environment = knowledgeBase.getEnvironment();

            EnvironmentContext.set(environment);

            try {
                vectorStore.delete(vectorStoreIds);
            } finally {
                EnvironmentContext.clear();
            }
        }

        for (KnowledgeBaseDocumentChunk chunk : knowledgeBaseDocumentChunks) {
            FileEntry contentFileEntry = chunk.getContent();

            if (contentFileEntry != null) {
                knowledgeBaseFileStorage.deleteChunkContent(contentFileEntry);
            }
        }

        knowledgeBaseDocumentChunkService.deleteKnowledgeBaseDocumentChunks(knowledgeBaseDocumentChunks);
    }

    @Override
    public int sweepTombstonedDocumentChunks(long sourceId, Optional<Owner> owner) {
        List<KnowledgeBaseDocument> tombstonedDocuments = knowledgeBaseDocumentService.getTombstonedDocuments(
            sourceId, owner);

        if (tombstonedDocuments.isEmpty()) {
            return 0;
        }

        List<Long> documentIds = tombstonedDocuments.stream()
            .map(KnowledgeBaseDocument::getId)
            .toList();

        List<KnowledgeBaseDocumentChunk> knowledgeBaseDocumentChunks =
            knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunksByDocumentIds(documentIds);

        if (knowledgeBaseDocumentChunks.isEmpty()) {
            return 0;
        }

        List<String> vectorStoreIds = knowledgeBaseDocumentChunks.stream()
            .map(KnowledgeBaseDocumentChunk::getVectorStoreId)
            .filter(vectorStoreId -> vectorStoreId != null)
            .toList();

        if (!vectorStoreIds.isEmpty()) {
            KnowledgeBaseDocument firstDocument = tombstonedDocuments.get(0);

            KnowledgeBase knowledgeBase = knowledgeBaseService.getKnowledgeBase(firstDocument.getKnowledgeBaseId());
            Environment environment = knowledgeBase.getEnvironment();

            EnvironmentContext.set(environment);

            try {
                vectorStore.delete(vectorStoreIds);
            } finally {
                EnvironmentContext.clear();
            }
        }

        for (KnowledgeBaseDocumentChunk chunk : knowledgeBaseDocumentChunks) {
            FileEntry contentFileEntry = chunk.getContent();

            if (contentFileEntry != null) {
                knowledgeBaseFileStorage.deleteChunkContent(contentFileEntry);
            }
        }

        knowledgeBaseDocumentChunkService.deleteKnowledgeBaseDocumentChunks(knowledgeBaseDocumentChunks);

        return knowledgeBaseDocumentChunks.size();
    }

    private Optional<Owner> resolveCurrentOwner() {
        OwnerResolver ownerResolver = ownerResolverProvider.getIfAvailable();

        if (ownerResolver == null) {
            return Optional.empty();
        }

        return ownerResolver.resolveCurrentPrincipal();
    }

    @Override
    public void updateKnowledgeBaseDocumentTags(long knowledgeBaseDocumentId, List<String> tagNames) {
        knowledgeBaseDocumentTagService.updateTagNames(knowledgeBaseDocumentId, tagNames);

        KnowledgeBaseDocument knowledgeBaseDocument =
            knowledgeBaseDocumentService.getKnowledgeBaseDocument(knowledgeBaseDocumentId);
        List<String> updatedTagNames = knowledgeBaseDocument.getTagNames();

        List<KnowledgeBaseDocumentChunk> chunks =
            knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunksByDocumentId(knowledgeBaseDocumentId);

        for (KnowledgeBaseDocumentChunk chunk : chunks) {
            String vectorStoreId = chunk.getVectorStoreId();

            if (vectorStoreId == null) {
                continue;
            }

            knowledgeBaseVectorStoreMetadataService.updateTagNames(vectorStoreId, updatedTagNames);
        }
    }
}
