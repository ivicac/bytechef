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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocumentChunk;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentChunkFacade;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentChunkService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentTagService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Verifies that every document/chunk operation authorizes the caller against the owning knowledge base's workspace role
 * before touching the shared platform facades/services, and fails closed when the role is missing or the resource is
 * orphaned. The shared facades stay ungated so the AI-Hub agent tools keep working with no user security context.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseDocumentApiFacadeImplTest {

    private static final String KNOWLEDGE_BASE = "KnowledgeBase";

    @Mock
    private KnowledgeBaseDocumentChunkFacade knowledgeBaseDocumentChunkFacade;

    @Mock
    private KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService;

    @Mock
    private KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade;

    @Mock
    private KnowledgeBaseDocumentService knowledgeBaseDocumentService;

    @Mock
    private KnowledgeBaseDocumentTagService knowledgeBaseDocumentTagService;

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private KnowledgeBaseDocumentApiFacadeImpl knowledgeBaseDocumentApiFacade;

    @Test
    void testGetKnowledgeBaseDocumentTagNamesAllowsViewerOfThatKnowledgeBase() {
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "VIEWER")).thenReturn(true);
        when(knowledgeBaseDocumentTagService.getTagNamesByKnowledgeBaseId(7L)).thenReturn(List.of("alpha"));

        assertThat(knowledgeBaseDocumentApiFacade.getKnowledgeBaseDocumentTagNames(7L)).containsExactly("alpha");
    }

    /**
     * The refusal that matters. This listing was tenant-wide and unguarded, so it answered any authenticated principal
     * -- including a connected user, whose JWT the embedded API-key configurer routes to {@code /graphql} -- with every
     * tag name in every workspace, both platform pools, and every embedded account's own knowledge base.
     */
    @Test
    void testGetKnowledgeBaseDocumentTagNamesDeniesNonViewer() {
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "VIEWER")).thenReturn(false);

        assertThatThrownBy(() -> knowledgeBaseDocumentApiFacade.getKnowledgeBaseDocumentTagNames(7L))
            .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(knowledgeBaseDocumentTagService);
    }

    @Test
    void testGetTagNamesByKnowledgeBaseDocumentIdAllowsViewerOfThatKnowledgeBase() {
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "VIEWER")).thenReturn(true);
        when(knowledgeBaseDocumentTagService.getTagNamesByKnowledgeBaseDocumentId(7L))
            .thenReturn(Map.of(10L, List.of("alpha")));

        assertThat(knowledgeBaseDocumentApiFacade.getTagNamesByKnowledgeBaseDocumentId(7L))
            .containsExactly(Map.entry(10L, List.of("alpha")));
    }

    /**
     * The by-document listing leaks more than names: it hands back the document ids too, so an unguarded call
     * enumerates the tenant's whole document id space for a caller who can reach none of it.
     */
    @Test
    void testGetTagNamesByKnowledgeBaseDocumentIdDeniesNonViewer() {
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "VIEWER")).thenReturn(false);

        assertThatThrownBy(() -> knowledgeBaseDocumentApiFacade.getTagNamesByKnowledgeBaseDocumentId(7L))
            .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(knowledgeBaseDocumentTagService);
    }

    @Test
    void testGetKnowledgeBaseDocumentAllowsViewer() {
        KnowledgeBaseDocument document = mockDocument(10L, 7L);

        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(10L)).thenReturn(document);
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "VIEWER")).thenReturn(true);

        assertThat(knowledgeBaseDocumentApiFacade.getKnowledgeBaseDocument(10L)).isSameAs(document);
    }

    @Test
    void testGetKnowledgeBaseDocumentDeniesNonViewer() {
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(10L)).thenReturn(mockDocument(10L, 7L));
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "VIEWER")).thenReturn(false);

        assertThatThrownBy(() -> knowledgeBaseDocumentApiFacade.getKnowledgeBaseDocument(10L))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void testGetKnowledgeBaseDocumentDeniesOrphanDocument() {
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(10L)).thenReturn(mockDocument(10L, null));

        assertThatThrownBy(() -> knowledgeBaseDocumentApiFacade.getKnowledgeBaseDocument(10L))
            .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(permissionService);
    }

    @Test
    void testGetKnowledgeBaseDocumentChunksByDocumentIdWithoutContentAllowsViewer() {
        KnowledgeBaseDocumentChunk chunk = mockChunk(20L, 10L);

        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(10L)).thenReturn(mockDocument(10L, 7L));
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "VIEWER")).thenReturn(true);
        when(knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunksByDocumentId(10L))
            .thenReturn(List.of(chunk));

        assertThat(knowledgeBaseDocumentApiFacade.getKnowledgeBaseDocumentChunksByDocumentIdWithoutContent(10L))
            .containsExactly(chunk);

        verifyNoInteractions(knowledgeBaseDocumentChunkFacade);
    }

    @Test
    void testGetKnowledgeBaseDocumentChunksByDocumentIdWithoutContentDeniesNonViewer() {
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(10L)).thenReturn(mockDocument(10L, 7L));
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "VIEWER")).thenReturn(false);

        assertThatThrownBy(
            () -> knowledgeBaseDocumentApiFacade.getKnowledgeBaseDocumentChunksByDocumentIdWithoutContent(10L))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(knowledgeBaseDocumentChunkService);
        verifyNoInteractions(knowledgeBaseDocumentChunkFacade);
    }

    @Test
    void testDeleteKnowledgeBaseDocumentRequiresEditor() {
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(10L)).thenReturn(mockDocument(10L, 7L));
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "EDITOR")).thenReturn(true);

        knowledgeBaseDocumentApiFacade.deleteKnowledgeBaseDocument(10L);

        verify(knowledgeBaseDocumentFacade).deleteKnowledgeBaseDocument(10L);
    }

    @Test
    void testDeleteKnowledgeBaseDocumentDeniesNonEditor() {
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(10L)).thenReturn(mockDocument(10L, 7L));
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "EDITOR")).thenReturn(false);

        assertThatThrownBy(() -> knowledgeBaseDocumentApiFacade.deleteKnowledgeBaseDocument(10L))
            .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(knowledgeBaseDocumentFacade);
    }

    @Test
    void testDeleteKnowledgeBaseDocumentChunkResolvesChunkToKnowledgeBase() {
        KnowledgeBaseDocumentChunk chunk = mockChunk(20L, 10L);

        when(knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunk(20L)).thenReturn(chunk);
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(10L)).thenReturn(mockDocument(10L, 7L));
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "EDITOR")).thenReturn(true);

        knowledgeBaseDocumentApiFacade.deleteKnowledgeBaseDocumentChunk(20L);

        verify(knowledgeBaseDocumentChunkFacade).deleteKnowledgeBaseDocumentChunk(20L);
    }

    @Test
    void testDeleteKnowledgeBaseDocumentChunkDeniesNonEditor() {
        when(knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunk(20L)).thenReturn(mockChunk(20L, 10L));
        when(knowledgeBaseDocumentService.getKnowledgeBaseDocument(10L)).thenReturn(mockDocument(10L, 7L));
        when(permissionService.hasResourceRole(7L, KNOWLEDGE_BASE, "EDITOR")).thenReturn(false);

        assertThatThrownBy(() -> knowledgeBaseDocumentApiFacade.deleteKnowledgeBaseDocumentChunk(20L))
            .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(knowledgeBaseDocumentChunkFacade);
    }

    @Test
    void testDeleteKnowledgeBaseDocumentChunkDeniesOrphanChunk() {
        when(knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunk(20L)).thenReturn(mockChunk(20L, null));

        assertThatThrownBy(() -> knowledgeBaseDocumentApiFacade.deleteKnowledgeBaseDocumentChunk(20L))
            .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(permissionService);
        verifyNoInteractions(knowledgeBaseDocumentChunkFacade);
    }

    private static KnowledgeBaseDocument mockDocument(long id, Long knowledgeBaseId) {
        KnowledgeBaseDocument document = new KnowledgeBaseDocument();

        document.setId(id);
        document.setKnowledgeBaseId(knowledgeBaseId);

        return document;
    }

    private static KnowledgeBaseDocumentChunk mockChunk(long id, Long knowledgeBaseDocumentId) {
        KnowledgeBaseDocumentChunk chunk = new KnowledgeBaseDocumentChunk();

        chunk.setId(id);
        chunk.setKnowledgeBaseDocumentId(knowledgeBaseDocumentId);

        return chunk;
    }
}
