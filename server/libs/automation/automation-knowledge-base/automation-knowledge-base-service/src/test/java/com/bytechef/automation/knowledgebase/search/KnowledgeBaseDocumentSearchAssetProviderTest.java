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
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the pool scoping fixed alongside the platform-type split: this provider feeds the automation-workspace search
 * bar, iterates every matched knowledge base's documents, and must only ever read the AUTOMATION pool. A regression
 * back to the unscoped listing leaked every tenant's EMBEDDED knowledge bases -- and their document names -- into every
 * user's automation search results, because {@code AutomationSearchFacadeImpl.isAccessible} treats a null
 * {@code workspaceId} as accessible and no EMBEDDED knowledge base resolves one.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseDocumentSearchAssetProviderTest {

    @Mock
    private KnowledgeBaseDocumentService knowledgeBaseDocumentService;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private KnowledgeBaseWorkspaceResolver knowledgeBaseWorkspaceResolver;

    @InjectMocks
    private KnowledgeBaseDocumentSearchAssetProvider knowledgeBaseDocumentSearchAssetProvider;

    @Test
    void testSearchReadsOnlyTheAutomationPool() {
        when(knowledgeBaseService.getKnowledgeBases(PlatformType.AUTOMATION)).thenReturn(List.of());

        knowledgeBaseDocumentSearchAssetProvider.search("invoice", 10);

        verify(knowledgeBaseService).getKnowledgeBases(PlatformType.AUTOMATION);
        verify(knowledgeBaseService, never()).getKnowledgeBases(PlatformType.EMBEDDED);
    }

    @Test
    void testADocumentOfAnAutomationKnowledgeBaseIsReturned() {
        KnowledgeBase knowledgeBase = knowledgeBase(1L, "invoices archive");
        KnowledgeBaseDocument document = document(11L, "invoice-2026.pdf");

        when(knowledgeBaseService.getKnowledgeBases(PlatformType.AUTOMATION)).thenReturn(List.of(knowledgeBase));
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocuments(1L)).thenReturn(List.of(document));
        when(knowledgeBaseWorkspaceResolver.getWorkspaceId(1L)).thenReturn(7L);

        List<KnowledgeBaseDocumentSearchResult> results =
            knowledgeBaseDocumentSearchAssetProvider.search("invoice", 10);

        assertThat(results).hasSize(1);

        KnowledgeBaseDocumentSearchResult result = results.getFirst();

        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.knowledgeBaseId()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("invoice-2026.pdf");
        assertThat(result.workspaceId()).isEqualTo(7L);
    }

    private static KnowledgeBase knowledgeBase(long id, String name) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(id);
        knowledgeBase.setName(name);

        return knowledgeBase;
    }

    private static KnowledgeBaseDocument document(long id, String name) {
        KnowledgeBaseDocument document = new KnowledgeBaseDocument();

        document.setId(id);
        document.setName(name);

        return document;
    }
}
