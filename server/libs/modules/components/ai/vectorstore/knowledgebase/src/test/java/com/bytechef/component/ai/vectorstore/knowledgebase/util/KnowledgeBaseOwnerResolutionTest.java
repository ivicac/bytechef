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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.owner.Owner;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The step-facing half of name resolution: the caller's environment, pool and owner reach
 * {@link KnowledgeBaseService#fetchKnowledgeBase(String, int, PlatformType, Optional)} unaltered, and a name that
 * resolves to nothing throws rather than returning null into a step.
 *
 * <p>
 * The rule those four arguments are subject to -- an account's own wins over the shared one, and a run with no owner
 * never falls through to an account's -- is asserted in {@code KnowledgeBaseNameResolutionTest}, beside the code that
 * now applies it.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseOwnerResolutionTest {

    private static final long ACCOUNT_ID = 42L;
    private static final Owner OWNER = Owner.connectedUser(ACCOUNT_ID);
    private static final PlatformType EMBEDDED = PlatformType.EMBEDDED;
    private static final int ENVIRONMENT = 0;

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);

    @Test
    void testResolutionIsAskedForTheCallersOwnEnvironmentPoolAndOwner() {
        KnowledgeBase resolved = new KnowledgeBase();

        resolved.setId(1L);

        when(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED, Optional.of(OWNER)))
            .thenReturn(Optional.of(resolved));

        assertThat(KnowledgeBaseOptionsUtils.resolveKnowledgeBase(
            knowledgeBaseService, "docs", ENVIRONMENT, EMBEDDED, Optional.of(OWNER)))
                .isEqualTo(resolved);

        verify(knowledgeBaseService).fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED, Optional.of(OWNER));
    }

    @Test
    void testResolvingAMissingNameThrows() {
        when(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED, Optional.of(OWNER)))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> KnowledgeBaseOptionsUtils.resolveKnowledgeBase(
            knowledgeBaseService, "docs", ENVIRONMENT, EMBEDDED, Optional.of(OWNER)))
                .isInstanceOf(RuntimeException.class);
    }
}
