/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.knowledgebase.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.bytechef.ee.embedded.knowledgebase.facade.EmbeddedKnowledgeBaseApiFacade.ChunkingSettings;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The {@code isTenantAdmin()} gate on {@code createKnowledgeBase} is asserted once, in the canonical enumeration in
 * {@link EmbeddedKnowledgeBaseApiFacadeTest#testEveryFacadeMethodIsGatedOnTenantAdmin()}, not here -- a second
 * reflection assertion in this file would just be a second place for that list to go stale.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class EmbeddedKnowledgeBaseApiFacadeCreateTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @InjectMocks
    private EmbeddedKnowledgeBaseApiFacadeImpl embeddedKnowledgeBaseApiFacade;

    @Captor
    private ArgumentCaptor<KnowledgeBase> knowledgeBaseArgumentCaptor;

    @Test
    void testCreateGoesIntoTheEmbeddedPool() {
        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(0L, "faq", "d", null, ChunkingSettings.none());

        verify(knowledgeBaseService).createKnowledgeBase(knowledgeBaseArgumentCaptor.capture());

        KnowledgeBase knowledgeBase = knowledgeBaseArgumentCaptor.getValue();

        assertThat(knowledgeBase.getName()).isEqualTo("faq");
        assertThat(knowledgeBase.getDescription()).isEqualTo("d");
        assertThat(knowledgeBase.getEnvironment()).isEqualTo(Environment.DEVELOPMENT);
        assertThat(knowledgeBase.getPlatformType()).isEqualTo(PlatformType.EMBEDDED);
    }

    @Test
    void testCreateCarriesTheChunkingSettingsItWasGiven() {
        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(0L, "faq", "d", null, new ChunkingSettings(512, 40, 64));

        verify(knowledgeBaseService).createKnowledgeBase(knowledgeBaseArgumentCaptor.capture());

        KnowledgeBase knowledgeBase = knowledgeBaseArgumentCaptor.getValue();

        assertThat(knowledgeBase.getMaxChunkSize()).isEqualTo(512);
        assertThat(knowledgeBase.getMinChunkSizeChars()).isEqualTo(40);
        assertThat(knowledgeBase.getOverlap()).isEqualTo(64);
    }

    /**
     * Compared against a fresh {@link KnowledgeBase} rather than against 1024/100/200 written out here. Literals would
     * pass just as happily against a second copy of the defaults hardcoded in the facade, and would keep passing after
     * the entity's own defaults were tuned and the two had drifted -- which is the failure this exists to catch.
     */
    @Test
    void testCreateWithoutChunkingSettingsLeavesTheEntityDefaults() {
        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(0L, "faq", "d", null, ChunkingSettings.none());

        verify(knowledgeBaseService).createKnowledgeBase(knowledgeBaseArgumentCaptor.capture());

        KnowledgeBase knowledgeBase = knowledgeBaseArgumentCaptor.getValue();
        KnowledgeBase entityDefaults = new KnowledgeBase();

        assertThat(knowledgeBase.getMaxChunkSize()).isEqualTo(entityDefaults.getMaxChunkSize());
        assertThat(knowledgeBase.getMinChunkSizeChars()).isEqualTo(entityDefaults.getMinChunkSizeChars());
        assertThat(knowledgeBase.getOverlap()).isEqualTo(entityDefaults.getOverlap());
    }

    /**
     * A partly-filled input is the shape the console sends when one field is cleared, and both rules have to hold side
     * by side within the one call: the named setting is carried, and the omitted one still falls back to the entity's
     * default rather than to zero.
     */
    @Test
    void testCreateAppliesOnlyTheChunkingSettingsThatWereNamed() {
        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(0L, "faq", "d", null, new ChunkingSettings(512, null, null));

        verify(knowledgeBaseService).createKnowledgeBase(knowledgeBaseArgumentCaptor.capture());

        KnowledgeBase knowledgeBase = knowledgeBaseArgumentCaptor.getValue();
        KnowledgeBase entityDefaults = new KnowledgeBase();

        assertThat(knowledgeBase.getMaxChunkSize()).isEqualTo(512);
        assertThat(knowledgeBase.getMinChunkSizeChars()).isEqualTo(entityDefaults.getMinChunkSizeChars());
        assertThat(knowledgeBase.getOverlap()).isEqualTo(entityDefaults.getOverlap());
    }
}
