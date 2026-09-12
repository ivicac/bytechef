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

import com.bytechef.platform.tag.domain.Tag;
import java.util.List;
import java.util.Map;

/**
 * Service for accessing tags associated with KnowledgeBases.
 *
 * <p>
 * Every read here names the knowledge bases it may see, and that is deliberate. There is no tenant-wide reader: this
 * facade sits below every separation the product has -- the {@code workspace_knowledge_base} relation, the
 * {@code platform_type} pool split, and the {@code owner_id}/{@code owner_type} pair an embedded per-account knowledge
 * base carries -- so an unscoped {@code findAll} over {@code knowledge_base} crosses all three at once. Knowledge base
 * ids are what a caller can be authorized against, so they are the only way in.
 *
 * @author Ivica Cardic
 */
public interface KnowledgeBaseTagFacade {

    /**
     * Retrieves the distinct tags assigned to the given knowledge bases.
     *
     * @param knowledgeBaseIds the ids of the knowledge bases whose tags are to be retrieved
     * @return a list of Tag objects assigned to the given knowledge bases
     */
    List<Tag> getTags(List<Long> knowledgeBaseIds);

    /**
     * Retrieves a mapping from knowledge base id to the tags assigned to that knowledge base, for the given knowledge
     * bases only.
     *
     * @param knowledgeBaseIds the ids of the knowledge bases whose tags are to be retrieved
     * @return a map where keys are knowledge base ids and values are lists of Tag objects assigned to each of them
     */
    Map<Long, List<Tag>> getTagsByKnowledgeBaseIds(List<Long> knowledgeBaseIds);

    /**
     * Updates the tags associated with a specific knowledgeBase.
     *
     * @param knowledgeBaseId the unique identifier of the knowledgebase whose tags are to be updated
     * @param tags            a list of Tag objects representing the new set of tags to associate with the knowledgebase
     */
    void updateTags(long knowledgeBaseId, List<Tag> tags);
}
