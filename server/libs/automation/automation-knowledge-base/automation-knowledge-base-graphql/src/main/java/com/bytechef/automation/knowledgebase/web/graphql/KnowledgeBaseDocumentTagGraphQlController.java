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

package com.bytechef.automation.knowledgebase.web.graphql;

import com.bytechef.automation.knowledgebase.facade.KnowledgeBaseDocumentApiFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * Document tags for one knowledge base.
 *
 * <p>
 * Every operation here names a knowledge base and goes through {@link KnowledgeBaseDocumentApiFacade}, which resolves
 * it to its workspace and checks the caller's role. Nothing on this controller is tenant-wide, and the reason is worth
 * stating because the two listings used to be: {@code /graphql} is only {@code .authenticated()} in
 * {@code SecurityConfiguration}, and the embedded API-key configurer routes a connected user's JWT to it as well, so an
 * unguarded query mapping here answers a principal holding no authorities at all. Neither {@code @PreAuthorize} nor a
 * workspace was involved; there was nothing between the query and {@code knowledge_base_document.findAll()}, which
 * spans every workspace, both platform pools, and every embedded account's own knowledge base.
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnProperty(prefix = "bytechef.ai.knowledge-base", name = "enabled", havingValue = "true")
@SuppressFBWarnings("EI")
public class KnowledgeBaseDocumentTagGraphQlController {

    private final KnowledgeBaseDocumentApiFacade knowledgeBaseDocumentApiFacade;

    @SuppressFBWarnings("EI")
    public KnowledgeBaseDocumentTagGraphQlController(KnowledgeBaseDocumentApiFacade knowledgeBaseDocumentApiFacade) {
        this.knowledgeBaseDocumentApiFacade = knowledgeBaseDocumentApiFacade;
    }

    @QueryMapping
    public List<String> knowledgeBaseDocumentTags(@Argument Long knowledgeBaseId) {
        return knowledgeBaseDocumentApiFacade.getKnowledgeBaseDocumentTagNames(knowledgeBaseId);
    }

    @QueryMapping
    public List<KnowledgeBaseDocumentTagsEntry> knowledgeBaseDocumentTagsByDocument(@Argument Long knowledgeBaseId) {
        Map<Long, List<String>> tagNamesByDocumentId =
            knowledgeBaseDocumentApiFacade.getTagNamesByKnowledgeBaseDocumentId(knowledgeBaseId);

        return tagNamesByDocumentId.entrySet()
            .stream()
            .map(entry -> new KnowledgeBaseDocumentTagsEntry(entry.getKey(), entry.getValue()))
            .toList();
    }

    @MutationMapping
    public boolean updateKnowledgeBaseDocumentTags(@Argument UpdateKnowledgeBaseDocumentTagsInput input) {
        List<String> tagNames = input.tags() == null ? List.of() : input.tags();

        knowledgeBaseDocumentApiFacade.updateKnowledgeBaseDocumentTags(input.knowledgeBaseDocumentId(), tagNames);

        return true;
    }

    public record KnowledgeBaseDocumentTagsEntry(Long knowledgeBaseDocumentId, List<String> tags) {
    }

    public record UpdateKnowledgeBaseDocumentTagsInput(Long knowledgeBaseDocumentId, List<String> tags) {
    }
}
