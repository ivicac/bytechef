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

import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.owner.Owner;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Facade for managing knowledge base documents including file storage operations.
 *
 * @author Ivica Cardic
 */
public interface KnowledgeBaseDocumentFacade {

    /**
     * Creates a new knowledge base document by storing the file and creating the document record.
     *
     * @param knowledgeBaseId the ID of the knowledge base to add the document to
     * @param filename        the name of the file
     * @param contentType     the content type of the file
     * @param size            the size of the file content, in bytes
     * @param inputStream     the input stream of the file content
     * @return the created knowledge base document
     */
    KnowledgeBaseDocument createKnowledgeBaseDocument(
        Long knowledgeBaseId, String filename, String contentType, long size, InputStream inputStream);

    /**
     * Deletes a knowledge base document including its stored file.
     *
     * @param id the ID of the document to delete
     */
    void deleteKnowledgeBaseDocument(Long id);

    /**
     * Re-splits and re-embeds every live document of a knowledge base under the chunking settings it carries now.
     *
     * <p>
     * Chunk size, minimum chunk size and overlap are editable, and until this existed editing them reached new uploads
     * only: the documents the setting was wrong for kept the chunks the wrong setting produced, for life, with no way
     * out but deleting and re-uploading them.
     *
     * <p>
     * Each document's existing chunks are evicted -- vector-store rows, chunk content files, chunk rows -- and the
     * document is put back on the ingestion queue rather than re-chunked here. Queued, not synchronous, for two reasons
     * that point the same way: embedding costs an API call per chunk, so a knowledge base of any size would hold a
     * request open for minutes, and the queued path is the one that already reads the owner off the document row.
     * Re-chunking inline would mean writing a second chunking path, and a second chunking path is where the owner gets
     * taken from the caller instead of from the document -- which would hand every chunk in a shared knowledge base to
     * whoever pressed the button.
     *
     * <p>
     * Tombstoned documents are skipped. They are deleted upstream and awaiting eviction, so re-chunking one would put
     * its content back into search results.
     *
     * <p>
     * Recoverable by repetition rather than by repair. The eviction commits before any document is queued, so a failure
     * part-way leaves documents with no chunks and a status that is not READY; running the action again evicts nothing
     * for those (there is nothing left to evict) and queues them afresh. The same is true of a document whose queued
     * message never ran or whose chunking failed. During the window between the eviction and the worker finishing, a
     * search finds nothing for that document -- unavoidable when re-chunking is the removal and re-creation of every
     * chunk, and the reason this is an action a vendor takes rather than a side effect of saving the settings form.
     *
     * @param knowledgeBaseId the knowledge base whose documents are to be re-chunked
     * @return the number of documents queued for re-chunking
     */
    int rechunkKnowledgeBaseDocuments(long knowledgeBaseId);

    /**
     * Updates the tag names for a knowledge base document in both the relational database and the vector store.
     *
     * @param knowledgeBaseDocumentId the ID of the document whose tags are to be updated
     * @param tagNames                the new list of tag name strings
     */
    void updateKnowledgeBaseDocumentTags(long knowledgeBaseDocumentId, List<String> tagNames);

    /**
     * Evicts the derived resources of every tombstoned document tied to the given source: vector-store rows, chunk
     * content files, and chunk rows. The document rows themselves stay tombstoned — they are the resurrect anchor for
     * records that reappear upstream ({@code replaceSyncedDocument} clears {@code deleted_at} and re-runs the chunker).
     * Without this sweep, semantic search keeps serving chunks of documents that no longer exist upstream. Idempotent:
     * documents whose chunks were already evicted contribute nothing, so sweeping all tombstoned documents of a source
     * on every FULL_REPLACE run also self-heals rows tombstoned before a previously failed sweep.
     *
     * <p>
     * Scoped to the sweeping run's OWN documents, by the same WRITE rule the tombstone itself uses. A source no longer
     * identifies one account's documents -- two accounts may sync the same source into one shared knowledge base -- and
     * this method deletes chunks out of the vector store by raw id, so a listing wider than the caller's own documents
     * destroys another account's chunks with nothing downstream left to refuse it.
     *
     * @param sourceId the knowledge base source whose tombstoned documents should be swept
     * @param owner    the account the sync run acted for, empty for the vendor's own run
     * @return the number of chunks removed
     */
    int sweepTombstonedDocumentChunks(long sourceId, Optional<Owner> owner);
}
