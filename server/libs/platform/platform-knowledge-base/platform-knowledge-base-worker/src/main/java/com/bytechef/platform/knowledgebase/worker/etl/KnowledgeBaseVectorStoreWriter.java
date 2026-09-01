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

import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_ENVIRONMENT_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_DOCUMENT_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_TYPE;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_SHARED;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_TAG_NAMES;

import com.bytechef.platform.configuration.context.EnvironmentContext;
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writer for storing knowledge base documents in PgVector store.
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.knowledge-base", name = "enabled", havingValue = "true")
public class KnowledgeBaseVectorStoreWriter {

    private final VectorStore vectorStore;

    @SuppressFBWarnings("EI")
    public KnowledgeBaseVectorStoreWriter(@Qualifier("knowledgeBasePgVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Writes documents to the vector store with knowledge base metadata.
     *
     * @param documents               the documents to write
     * @param knowledgeBaseId         the knowledge base ID
     * @param knowledgeBaseDocumentId the knowledge base document ID
     * @param environmentId           the environment ordinal of the knowledge base
     * @param tagNames                the tag names associated with the document
     * @param owner                   the account the document row was created for, or null for the vendor's own
     */
    public void write(
        List<Document> documents, long knowledgeBaseId, long knowledgeBaseDocumentId, long environmentId,
        List<String> tagNames, @Nullable Owner owner) {

        List<Document> sanitizedDocuments = documents.stream()
            .map(
                document -> sanitizeDocument(
                    document, knowledgeBaseId, knowledgeBaseDocumentId, -1, environmentId, tagNames, owner))
            .toList();

        EnvironmentContext.set((int) environmentId);

        try {
            vectorStore.add(sanitizedDocuments);
        } finally {
            EnvironmentContext.clear();
        }
    }

    /**
     * Writes a single document (chunk) to the vector store with knowledge base metadata.
     *
     * @param document             the document to write
     * @param knowledgeBaseId      the knowledge base ID
     * @param documentId           the knowledge base document ID
     * @param knowledgeBaseChunkId the knowledge base document chunk ID
     * @param environmentId        the environment ordinal of the knowledge base
     * @param tagNames             the tag names associated with the document
     * @param owner                the account the document row was created for, or null for the vendor's own
     */
    public void writeChunk(
        Document document, Long knowledgeBaseId, Long documentId, Long knowledgeBaseChunkId, long environmentId,
        List<String> tagNames, @Nullable Owner owner) {

        Document sanitizedDocument = sanitizeDocument(
            document, knowledgeBaseId, documentId, knowledgeBaseChunkId, environmentId, tagNames, owner);

        EnvironmentContext.set((int) environmentId);

        try {
            vectorStore.add(List.of(sanitizedDocument));
        } finally {
            EnvironmentContext.clear();
        }
    }

    /**
     * Deletes documents by their vector store IDs.
     *
     * @param vectorStoreIds the vector store document IDs to delete
     */
    public void delete(List<String> vectorStoreIds) {
        vectorStore.delete(vectorStoreIds);
    }

    /**
     * Returns the underlying vector store for advanced operations.
     *
     * @return the vector store
     */
    @SuppressFBWarnings("EI")
    public VectorStore getVectorStore() {
        return vectorStore;
    }

    /**
     * Sanitizes a document by removing null bytes from content and adding knowledge base metadata. PostgreSQL text
     * columns don't support null bytes (0x00).
     */
    private Document sanitizeDocument(
        Document document, long knowledgeBaseId, long knowledgeBaseDocumentId, long knowledgeBaseDocumentChunkId,
        long environmentId, List<String> tagNames, @Nullable Owner owner) {

        String content = document.getText();

        if (content != null) {
            content = content.replace("\0", "");
        }

        Map<String, Object> metadata = new java.util.LinkedHashMap<>(document.getMetadata());

        metadata.put(METADATA_ENVIRONMENT_ID, environmentId);
        metadata.put(METADATA_KNOWLEDGE_BASE_ID, knowledgeBaseId);
        metadata.put(METADATA_KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId);

        // This pipeline runs off a message, detached from the request that created the document row, so there is no
        // principal left to ask -- which is why the owner is persisted on the document and handed in here instead. A
        // document with no owner is the vendor's, and its chunks are marked shared: readable by every account in the
        // knowledge base and writable by none of them, the same answer the backfill gives every chunk written before
        // this axis existed. Either both owner keys are written or the shared flag is, never one key of the pair.
        if (owner == null) {
            metadata.remove(METADATA_OWNER_ID);
            metadata.remove(METADATA_OWNER_TYPE);
            metadata.put(METADATA_SHARED, true);
        } else {
            OwnerType ownerType = owner.type();

            metadata.remove(METADATA_SHARED);
            metadata.put(METADATA_OWNER_ID, owner.id());
            metadata.put(METADATA_OWNER_TYPE, ownerType.ordinal());
        }

        if (knowledgeBaseDocumentChunkId != -1) {
            metadata.put(METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID, knowledgeBaseDocumentChunkId);
        }

        if (tagNames != null && !tagNames.isEmpty()) {
            metadata.put(METADATA_TAG_NAMES, tagNames);

            for (String tagName : tagNames) {
                metadata.put(METADATA_TAG_NAMES + "_" + tagName, true);
            }
        }

        return new Document(document.getId(), content, metadata);
    }
}
