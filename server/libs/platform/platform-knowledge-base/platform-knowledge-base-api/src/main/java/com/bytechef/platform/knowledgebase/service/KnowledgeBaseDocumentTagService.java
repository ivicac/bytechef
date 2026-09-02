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

package com.bytechef.platform.knowledgebase.service;

import java.util.List;
import java.util.Map;

/**
 * Service for accessing tag names associated with KnowledgeBaseDocuments.
 *
 * <p>
 * Every read here takes a knowledge base id, and that is deliberate. There is no tenant-wide reader: this service sits
 * below every gate the product has -- the workspace relation, the {@code platform_type} pool split, and the
 * {@code owner_id}/{@code owner_type} pair an embedded knowledge base carries -- so an unscoped {@code findAll} over
 * {@code knowledge_base_document} crosses all three at once. The knowledge base id is the thing callers can be
 * authorized against, so it is the only way in.
 *
 * @author Ivica Cardic
 */
public interface KnowledgeBaseDocumentTagService {

    /**
     * Retrieves all distinct tag names used by documents belonging to the given knowledge base.
     *
     * @param knowledgeBaseId the unique identifier of the knowledge base
     * @return a list of tag name strings
     */
    List<String> getTagNamesByKnowledgeBaseId(Long knowledgeBaseId);

    /**
     * Retrieves a mapping from document ID to list of tag names, for the documents of one knowledge base.
     *
     * @param knowledgeBaseId the unique identifier of the knowledge base
     * @return a map where keys are document IDs and values are lists of tag name strings
     */
    Map<Long, List<String>> getTagNamesByKnowledgeBaseDocumentId(Long knowledgeBaseId);

    /**
     * Updates the tag names associated with a specific document.
     *
     * @param knowledgeBaseDocumentId the unique identifier of the document whose tags are to be updated
     * @param tagNames                a list of tag name strings representing the new set of tags
     */
    void updateTagNames(long knowledgeBaseDocumentId, List<String> tagNames);
}
