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

package com.bytechef.component.ai.vectorstore.knowledgebase.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the pool scoping fixed alongside the platform-type split: the Knowledge Base Load/Delete dropdown must resolve
 * its options through {@link KnowledgeBaseOptionsUtils#poolFor(Optional)}, the same rule {@code DataTableUtils.poolFor}
 * applies -- a connected user (owner present) only ever sees the EMBEDDED pool, and a vendor or admin run (owner
 * absent) sees both. A regression back to a pool-unscoped listing let a connected user pick, and then actually read the
 * contents of, another pool's knowledge base.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseOptionsUtilsTest {

    @Test
    void testAnOwnedRunSeesOnlyTheEmbeddedPool() {
        assertThat(KnowledgeBaseOptionsUtils.poolFor(Optional.of(Owner.connectedUser(1L))))
            .containsExactly(PlatformType.EMBEDDED);
    }

    @Test
    void testAnUnownedRunSeesBothPools() {
        assertThat(KnowledgeBaseOptionsUtils.poolFor(Optional.empty()))
            .containsExactlyInAnyOrder(PlatformType.AUTOMATION, PlatformType.EMBEDDED);
    }

    @Test
    void testAnOwnedRunNeverReadsTheAutomationPool() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        Optional<Owner> owner = Optional.of(Owner.connectedUser(1L));

        when(knowledgeBaseService.getKnowledgeBases(PlatformType.EMBEDDED, owner)).thenReturn(List.of());

        KnowledgeBaseOptionsUtils.knowledgeBases(knowledgeBaseService, owner);

        verify(knowledgeBaseService, never()).getKnowledgeBases(PlatformType.AUTOMATION, owner);
    }

    @Test
    void testAnUnownedRunMergesBothPools() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        Optional<Owner> owner = Optional.empty();
        KnowledgeBase automationKnowledgeBase = knowledgeBase(1L, "vendor kb");
        KnowledgeBase embeddedKnowledgeBase = knowledgeBase(2L, "account kb");

        when(knowledgeBaseService.getKnowledgeBases(PlatformType.AUTOMATION, owner))
            .thenReturn(List.of(automationKnowledgeBase));
        when(knowledgeBaseService.getKnowledgeBases(PlatformType.EMBEDDED, owner))
            .thenReturn(List.of(embeddedKnowledgeBase));

        List<KnowledgeBase> knowledgeBases = KnowledgeBaseOptionsUtils.knowledgeBases(knowledgeBaseService, owner);

        assertThat(knowledgeBases).extracting(KnowledgeBase::getId)
            .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void testResolvingByIdNarrowsAnOwnedRunToTheEmbeddedPool() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        Optional<Owner> owner = Optional.of(Owner.connectedUser(1L));

        KnowledgeBaseOptionsUtils.resolveKnowledgeBase(knowledgeBaseService, 7L, owner);

        verify(knowledgeBaseService).getKnowledgeBase(7L, List.of(PlatformType.EMBEDDED), owner);
    }

    @Test
    void testResolvingByIdAdmitsAVendorRunToBothPools() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        Optional<Owner> owner = Optional.empty();

        KnowledgeBaseOptionsUtils.resolveKnowledgeBase(knowledgeBaseService, 7L, owner);

        verify(knowledgeBaseService)
            .getKnowledgeBase(7L, List.of(PlatformType.AUTOMATION, PlatformType.EMBEDDED), owner);
    }

    private static KnowledgeBase knowledgeBase(long id, String name) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(id);
        knowledgeBase.setName(name);

        return knowledgeBase;
    }
}
