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

package com.bytechef.automation.knowledgebase.facade;

import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocumentChunk;
import com.bytechef.platform.knowledgebase.dto.DocumentStatusUpdate;
import java.util.List;
import java.util.Map;

/**
 * Workspace-scoped read/write surface for knowledge-base documents and chunks, consumed by the admin GraphQL
 * controllers. Each operation resolves the target document/chunk to its owning knowledge base and authorizes the caller
 * against that knowledge base's workspace role before delegating to the shared platform facades/services.
 *
 * <p>
 * The shared {@code KnowledgeBaseDocumentFacade}/{@code KnowledgeBaseDocumentChunkFacade} are also invoked by AI-Hub
 * agent tools (which run with no user security context), so the authorization cannot live on the shared facades; it
 * lives here, on the admin path. The agent tools keep calling the shared facades directly.
 *
 * @author Ivica Cardic
 */
public interface KnowledgeBaseDocumentApiFacade {

    KnowledgeBaseDocument getKnowledgeBaseDocument(long id);

    /**
     * Every distinct document tag name in one knowledge base, for a caller authorized against that knowledge base.
     *
     * <p>
     * Scoped to a knowledge base rather than to the tenant. The console listing that consumes it is always inside one
     * knowledge base, and the unscoped reader it replaced spanned every workspace, both platform pools, and every
     * embedded account's own knowledge base.
     */
    List<String> getKnowledgeBaseDocumentTagNames(long knowledgeBaseId);

    /**
     * Document id to tag names, for the documents of one knowledge base the caller is authorized against.
     */
    Map<Long, List<String>> getTagNamesByKnowledgeBaseDocumentId(long knowledgeBaseId);

    DocumentStatusUpdate getKnowledgeBaseDocumentStatus(long id);

    List<KnowledgeBaseDocumentChunk> getKnowledgeBaseDocumentChunksByDocumentId(long documentId);

    List<KnowledgeBaseDocumentChunk> getKnowledgeBaseDocumentChunksByDocumentIdWithoutContent(long documentId);

    void deleteKnowledgeBaseDocument(long id);

    void updateKnowledgeBaseDocumentTags(long documentId, List<String> tagNames);

    KnowledgeBaseDocumentChunk updateKnowledgeBaseDocumentChunk(long id, String content);

    void deleteKnowledgeBaseDocumentChunk(long id);
}
