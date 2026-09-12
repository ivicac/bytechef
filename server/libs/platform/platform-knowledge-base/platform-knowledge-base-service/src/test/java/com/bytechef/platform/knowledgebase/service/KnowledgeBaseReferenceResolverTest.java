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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.definition.BaseProperty.ResourceType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class KnowledgeBaseReferenceResolverTest {

    private static final List<PlatformType> EDITOR_POOLS = List.of(PlatformType.AUTOMATION, PlatformType.EMBEDDED);

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeBaseReferenceResolver resolver = new KnowledgeBaseReferenceResolver(knowledgeBaseService);

    @Test
    void resourceTypeIsKnowledgeBase() {
        assertEquals(ResourceType.KNOWLEDGE_BASE, resolver.getResourceType());
    }

    @Test
    void existingKnowledgeBaseInEnvironmentResolves() {
        KnowledgeBase knowledgeBase = knowledgeBase(1L);

        when(knowledgeBaseService.getKnowledgeBase(eq(7L), anyList())).thenReturn(knowledgeBase);

        assertNull(resolver.findProblem("7", 1L));
    }

    @Test
    void knowledgeBaseInOtherEnvironmentIsMissing() {
        KnowledgeBase knowledgeBase = knowledgeBase(0L);

        when(knowledgeBaseService.getKnowledgeBase(eq(7L), anyList())).thenReturn(knowledgeBase);

        assertEquals("Knowledge base with id 7 does not exist in this environment", resolver.findProblem("7", 1L));
    }

    /**
     * The id is a global one, so the pool is not needed to find the row -- but the lookup is still made pool-aware, and
     * the editor's vendor case admits both pools.
     */
    @Test
    void bothPoolsAreOfferedToTheLookup() {
        KnowledgeBase knowledgeBase = knowledgeBase(1L);

        when(knowledgeBaseService.getKnowledgeBase(7L, EDITOR_POOLS)).thenReturn(knowledgeBase);

        assertNull(resolver.findProblem("7", 1L));
    }

    /**
     * The service answers "in another pool" and "does not exist" with the same exception on purpose, so that an id
     * cannot be used as an enumeration oracle. The resolver keeps the two indistinguishable too.
     */
    @Test
    void unknownKnowledgeBaseIsMissing() {
        when(knowledgeBaseService.getKnowledgeBase(eq(7L), anyList()))
            .thenThrow(new RuntimeException("KnowledgeBase not found: 7"));

        assertEquals("Knowledge base with id 7 does not exist in this environment", resolver.findProblem("7", 1L));
    }

    @Test
    void nonNumericReferenceIsReported() {
        assertEquals("Knowledge base reference 'abc' is not a valid id", resolver.findProblem("abc", 0L));
    }

    private static KnowledgeBase knowledgeBase(long environmentId) {
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);

        when(knowledgeBase.getEnvironmentId()).thenReturn(environmentId);

        return knowledgeBase;
    }
}
