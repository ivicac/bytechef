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

import com.bytechef.definition.BaseProperty.ResourceType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.workflow.validator.ResourceReferenceResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.knowledge-base", name = "enabled", havingValue = "true")
public class KnowledgeBaseReferenceResolver implements ResourceReferenceResolver {

    /**
     * The pools a reference may resolve in. Validation runs in the editor, where there is no connected user -- the
     * vendor case, which {@code DataTableUtils.poolFor} admits to BOTH pools -- so a reference counts as missing only
     * when it is missing from both. Narrowing this to one surface would make the validator disagree with the run it is
     * validating, and the validator is not told which surface it was called from anyway.
     */
    private static final List<PlatformType> EDITOR_POOLS = List.of(PlatformType.AUTOMATION, PlatformType.EMBEDDED);

    private final KnowledgeBaseService knowledgeBaseService;

    @SuppressFBWarnings("EI")
    public KnowledgeBaseReferenceResolver(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.KNOWLEDGE_BASE;
    }

    @Override
    @Nullable
    public String findProblem(String reference, long environmentId) {
        long knowledgeBaseId;

        try {
            knowledgeBaseId = Long.parseLong(reference);
        } catch (NumberFormatException numberFormatException) {
            return "Knowledge base reference '" + reference + "' is not a valid id";
        }

        KnowledgeBase knowledgeBase;

        try {
            // The id is a global one and the row carries its own pool, so this is the pool-aware by-id accessor every
            // caller acting on behalf of a run uses -- one indexed read rather than listing every knowledge base of
            // every pool and filtering in memory.
            knowledgeBase = knowledgeBaseService.getKnowledgeBase(knowledgeBaseId, EDITOR_POOLS);
        } catch (RuntimeException runtimeException) {
            return missing(knowledgeBaseId);
        }

        if (knowledgeBase.getEnvironmentId() != environmentId) {
            return missing(knowledgeBaseId);
        }

        return null;
    }

    /**
     * One message for every outcome. The service already answers "in another pool" and "does not exist" identically, so
     * that an id cannot be used as an enumeration oracle; saying which it was here would undo that.
     */
    private static String missing(long knowledgeBaseId) {
        return "Knowledge base with id " + knowledgeBaseId + " does not exist in this environment";
    }
}
