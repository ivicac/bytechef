/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.knowledgebase.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.knowledgebase.facade.EmbeddedKnowledgeBaseApiFacade.ChunkingSettings;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Chunking decides how a document is split before embedding and therefore what a search can return, so a knowledge base
 * that cannot be updated is one whose chunking is fixed for its whole life -- the escape being to delete it and
 * re-embed every document. These pin the update that removes that.
 *
 * <p>
 * The {@code isTenantAdmin()} gate on {@code updateKnowledgeBase} is asserted once, in the canonical enumeration in
 * {@link EmbeddedKnowledgeBaseApiFacadeTest#testEveryFacadeMethodIsGatedOnTenantAdmin()}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class EmbeddedKnowledgeBaseApiFacadeUpdateTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @InjectMocks
    private EmbeddedKnowledgeBaseApiFacadeImpl embeddedKnowledgeBaseApiFacade;

    @Captor
    private ArgumentCaptor<KnowledgeBase> knowledgeBaseArgumentCaptor;

    @Test
    void testUpdateChangesTheChunkingSettings() {
        stubExistingKnowledgeBase(1024, 100, 200);

        embeddedKnowledgeBaseApiFacade.updateKnowledgeBase(7L, "faq", "d", new ChunkingSettings(512, 40, 64));

        verify(knowledgeBaseService).updateKnowledgeBase(eq(7L), knowledgeBaseArgumentCaptor.capture());

        KnowledgeBase knowledgeBase = knowledgeBaseArgumentCaptor.getValue();

        assertThat(knowledgeBase.getName()).isEqualTo("faq");
        assertThat(knowledgeBase.getDescription()).isEqualTo("d");
        assertThat(knowledgeBase.getMaxChunkSize()).isEqualTo(512);
        assertThat(knowledgeBase.getMinChunkSizeChars()).isEqualTo(40);
        assertThat(knowledgeBase.getOverlap()).isEqualTo(64);
    }

    /**
     * {@code KnowledgeBaseService#updateKnowledgeBase} writes all three settings from the object it is handed, so an
     * omitted one has to be filled from the stored row here. Filling it from a fresh {@code KnowledgeBase} instead
     * would silently reset a tuned knowledge base to 1024 on a rename.
     */
    @Test
    void testUpdateLeavesAnOmittedChunkingSettingAlone() {
        stubExistingKnowledgeBase(512, 40, 64);

        embeddedKnowledgeBaseApiFacade.updateKnowledgeBase(7L, "faq", "d", ChunkingSettings.none());

        verify(knowledgeBaseService).updateKnowledgeBase(eq(7L), knowledgeBaseArgumentCaptor.capture());

        KnowledgeBase knowledgeBase = knowledgeBaseArgumentCaptor.getValue();

        assertThat(knowledgeBase.getMaxChunkSize()).isEqualTo(512);
        assertThat(knowledgeBase.getMinChunkSizeChars()).isEqualTo(40);
        assertThat(knowledgeBase.getOverlap()).isEqualTo(64);
    }

    /**
     * The pool check, not merely the tenant check. {@code isTenantAdmin()} is satisfied by the same person on both
     * surfaces, so an id typed into this mutation would otherwise edit an AUTOMATION knowledge base the embedded
     * console cannot list -- and re-chunking one is destructive of every embedding in it.
     */
    @Test
    void testUpdateRefusesAKnowledgeBaseOutsideTheEmbeddedPool() {
        when(knowledgeBaseService.getKnowledgeBase(7L, List.of(PlatformType.EMBEDDED)))
            .thenThrow(new RuntimeException("KnowledgeBase not found: 7"));

        assertThatThrownBy(
            () -> embeddedKnowledgeBaseApiFacade.updateKnowledgeBase(7L, "faq", "d", ChunkingSettings.none()))
                .hasMessage("KnowledgeBase not found: 7");

        verify(knowledgeBaseService, never()).updateKnowledgeBase(eq(7L), knowledgeBaseArgumentCaptor.capture());
    }

    private void stubExistingKnowledgeBase(int maxChunkSize, int minChunkSizeChars, int overlap) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setMaxChunkSize(maxChunkSize);
        knowledgeBase.setMinChunkSizeChars(minChunkSizeChars);
        knowledgeBase.setOverlap(overlap);
        knowledgeBase.setPlatformType(PlatformType.EMBEDDED);

        when(knowledgeBaseService.getKnowledgeBase(7L, List.of(PlatformType.EMBEDDED)))
            .thenReturn(knowledgeBase);
    }
}
