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
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The step-facing half of name resolution: the caller's environment and pool reach
 * {@link KnowledgeBaseService#fetchKnowledgeBase(String, int, PlatformType)} unaltered, and a name that resolves to
 * nothing throws rather than returning null into a step.
 *
 * <p>
 * There is no owner argument left to pass through: a knowledge base is no longer assigned to one account, so resolution
 * is by name within an environment and a pool alone. What separates two accounts sharing that knowledge base is the
 * owner on the chunks inside it, applied by {@code KnowledgeBaseVectorStoreWrapper}, not a second row this lookup could
 * choose between.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseOwnerResolutionTest {

    private static final PlatformType EMBEDDED = PlatformType.EMBEDDED;
    private static final int ENVIRONMENT = 0;

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);

    @Test
    void testResolutionIsAskedForTheCallersOwnEnvironmentAndPool() {
        KnowledgeBase resolved = new KnowledgeBase();

        resolved.setId(1L);

        when(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED))
            .thenReturn(Optional.of(resolved));

        assertThat(KnowledgeBaseOptionsUtils.resolveKnowledgeBase(knowledgeBaseService, "docs", ENVIRONMENT, EMBEDDED))
            .isEqualTo(resolved);

        verify(knowledgeBaseService).fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED);
    }

    @Test
    void testResolvingAMissingNameThrows() {
        when(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED))
            .thenReturn(Optional.empty());

        assertThatThrownBy(
            () -> KnowledgeBaseOptionsUtils.resolveKnowledgeBase(knowledgeBaseService, "docs", ENVIRONMENT, EMBEDDED))
                .isInstanceOf(RuntimeException.class);
    }
}
