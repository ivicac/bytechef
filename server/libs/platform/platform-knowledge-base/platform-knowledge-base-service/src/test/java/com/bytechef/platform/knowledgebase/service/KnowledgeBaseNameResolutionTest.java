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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.audit.KnowledgeBaseAuditPublisher;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the resolution rule {@link KnowledgeBaseService#fetchKnowledgeBase(String, int, PlatformType)} applies: a name
 * is unique within an environment and a pool, with no further split by owner. A knowledge base is no longer assigned to
 * one account, so there is no owned-versus-shared choice left to make here -- what separates two accounts sharing that
 * knowledge base is the owner on the chunks inside it, applied by {@code KnowledgeBaseVectorStoreWrapper}, not a second
 * row this lookup could pick between.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseNameResolutionTest {

    private static final PlatformType EMBEDDED = PlatformType.EMBEDDED;
    private static final int ENVIRONMENT = 0;

    @Mock
    private KnowledgeBaseAuditPublisher knowledgeBaseAuditPublisher;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @InjectMocks
    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @Test
    void testResolvingAnExistingNameReturnsIt() {
        KnowledgeBase knowledgeBase = knowledgeBaseNamed(1L, "docs");

        when(knowledgeBaseRepository.findByNameAndEnvironmentAndPlatformType("docs", ENVIRONMENT, EMBEDDED.ordinal()))
            .thenReturn(Optional.of(knowledgeBase));

        assertThat(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED)).contains(knowledgeBase);
    }

    @Test
    void testResolvingAMissingNameFindsNothing() {
        when(knowledgeBaseRepository.findByNameAndEnvironmentAndPlatformType("docs", ENVIRONMENT, EMBEDDED.ordinal()))
            .thenReturn(Optional.empty());

        assertThat(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED)).isEmpty();
    }

    /**
     * The id is not decoration. {@link KnowledgeBase#equals} compares id and nothing else, so a fixture built without
     * one is equal to any other and {@code contains} above would degrade to "some knowledge base was returned".
     */
    private static KnowledgeBase knowledgeBaseNamed(long id, String name) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(id);
        knowledgeBase.setName(name);

        return knowledgeBase;
    }
}
