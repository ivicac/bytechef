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

package com.bytechef.automation.knowledgebase.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the pool scoping fixed alongside the platform-type split: this provider feeds the automation-workspace search
 * bar and must only ever read the AUTOMATION pool. A regression back to the unscoped listing leaked every tenant's
 * EMBEDDED knowledge bases into every user's automation search results, because
 * {@code AutomationSearchFacadeImpl.isAccessible} treats a null {@code workspaceId} as accessible and no EMBEDDED
 * knowledge base resolves one.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseSearchAssetProviderTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private KnowledgeBaseWorkspaceResolver knowledgeBaseWorkspaceResolver;

    @InjectMocks
    private KnowledgeBaseSearchAssetProvider knowledgeBaseSearchAssetProvider;

    @Test
    void testSearchReadsOnlyTheAutomationPool() {
        when(knowledgeBaseService.getKnowledgeBases(PlatformType.AUTOMATION)).thenReturn(List.of());

        knowledgeBaseSearchAssetProvider.search("invoices", 10);

        verify(knowledgeBaseService).getKnowledgeBases(PlatformType.AUTOMATION);
        verify(knowledgeBaseService, never()).getKnowledgeBases(PlatformType.EMBEDDED);
    }

    @Test
    void testAnAutomationKnowledgeBaseIsReturned() {
        KnowledgeBase knowledgeBase = knowledgeBase(1L, "invoices archive");

        when(knowledgeBaseService.getKnowledgeBases(PlatformType.AUTOMATION)).thenReturn(List.of(knowledgeBase));
        when(knowledgeBaseWorkspaceResolver.getWorkspaceId(1L)).thenReturn(7L);

        List<KnowledgeBaseSearchResult> results = knowledgeBaseSearchAssetProvider.search("invoices", 10);

        assertThat(results).extracting(KnowledgeBaseSearchResult::id, KnowledgeBaseSearchResult::workspaceId)
            .containsExactly(Tuple.tuple(1L, 7L));
    }

    private static KnowledgeBase knowledgeBase(long id, String name) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(id);
        knowledgeBase.setName(name);

        return knowledgeBase;
    }
}
