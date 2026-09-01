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
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The id path of the pool split. The list path was scoped from the start; this one was not, and the row rule cannot
 * cover for it -- "unowned is readable" is correct WITHIN a pool, and every AUTOMATION knowledge base is unowned, so an
 * owned run resolving an id read straight out of the automation pool.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBasePoolScopingTest {

    private static final Owner ACCOUNT_A = Owner.connectedUser(1L);
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
     * The bridged run: a workflow authored in automation but dispatched under a connected user. It carries an owner, so
     * its pool is EMBEDDED alone, and the vendor's unowned automation knowledge base must stay invisible to it.
     */
    @Test
    void testAnOwnedRunCannotReadAnUnownedAutomationKnowledgeBaseById() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(inPool(PlatformType.AUTOMATION)));

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> knowledgeBaseService.getKnowledgeBase(7L, EMBEDDED_POOL, Optional.of(ACCOUNT_A)));

        assertEquals("KnowledgeBase not found: 7", exception.getMessage());
    }

    @Test
    void testAnOwnedRunReadsAnUnownedEmbeddedKnowledgeBaseById() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(inPool(PlatformType.EMBEDDED)));

        assertNotNull(knowledgeBaseService.getKnowledgeBase(7L, EMBEDDED_POOL, Optional.of(ACCOUNT_A)));
    }

    @Test
    void testAVendorRunReadsBothPoolsById() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(inPool(PlatformType.AUTOMATION)));

        assertNotNull(knowledgeBaseService.getKnowledgeBase(7L, BOTH_POOLS, Optional.empty()));
    }

    /**
     * Fail closed: a run admitted to no pool at all reads nothing, rather than falling back to the row rule.
     */
    @Test
    void testAnEmptyPoolReadsNothing() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(inPool(PlatformType.EMBEDDED)));

        assertThrows(
            RuntimeException.class, () -> knowledgeBaseService.getKnowledgeBase(7L, List.of(), Optional.empty()));
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
