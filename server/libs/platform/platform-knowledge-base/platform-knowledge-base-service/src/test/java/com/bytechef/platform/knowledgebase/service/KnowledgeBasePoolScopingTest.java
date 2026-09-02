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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.audit.KnowledgeBaseAuditPublisher;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The id path of the pool split. The list path was scoped from the start; this one was not, so an owned run resolving
 * an id used to read straight out of the automation pool.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBasePoolScopingTest {

    private static final List<PlatformType> BOTH_POOLS =
        List.of(PlatformType.AUTOMATION, PlatformType.EMBEDDED);
    private static final List<PlatformType> EMBEDDED_POOL = List.of(PlatformType.EMBEDDED);

    @Mock
    private KnowledgeBaseAuditPublisher knowledgeBaseAuditPublisher;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @InjectMocks
    private KnowledgeBaseServiceImpl knowledgeBaseService;

    /**
     * The bridged run: a workflow authored in automation but dispatched under a connected user. Its pool is EMBEDDED
     * alone, and the vendor's automation knowledge base must stay invisible to it.
     */
    @Test
    void testAnOwnedRunCannotReadAnAutomationKnowledgeBaseById() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(inPool(PlatformType.AUTOMATION)));

        RuntimeException exception = assertThrows(
            RuntimeException.class, () -> knowledgeBaseService.getKnowledgeBase(7L, EMBEDDED_POOL));

        assertEquals("KnowledgeBase not found: 7", exception.getMessage());
    }

    @Test
    void testAnOwnedRunReadsAnEmbeddedKnowledgeBaseById() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(inPool(PlatformType.EMBEDDED)));

        assertNotNull(knowledgeBaseService.getKnowledgeBase(7L, EMBEDDED_POOL));
    }

    @Test
    void testAVendorRunReadsBothPoolsById() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(inPool(PlatformType.AUTOMATION)));

        assertNotNull(knowledgeBaseService.getKnowledgeBase(7L, BOTH_POOLS));
    }

    /**
     * Fail closed: a run admitted to no pool at all reads nothing.
     */
    @Test
    void testAnEmptyPoolReadsNothing() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(inPool(PlatformType.EMBEDDED)));

        assertThrows(RuntimeException.class, () -> knowledgeBaseService.getKnowledgeBase(7L, List.of()));
    }

    /**
     * The unscoped overload is the trusted-internal one and deliberately keeps reading whatever it is handed. Pinned so
     * that a later "make it consistent" change to it is a decision rather than a slip.
     */
    @Test
    void testTheUnscopedOverloadStaysUnscoped() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(inPool(PlatformType.AUTOMATION)));

        assertNotNull(knowledgeBaseService.getKnowledgeBase(7L));
    }

    private static KnowledgeBase inPool(PlatformType platformType) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setPlatformType(platformType);

        return knowledgeBase;
    }
}
