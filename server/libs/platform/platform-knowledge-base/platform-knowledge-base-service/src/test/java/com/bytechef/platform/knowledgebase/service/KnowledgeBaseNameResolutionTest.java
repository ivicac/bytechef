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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.audit.KnowledgeBaseAuditPublisher;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseRepository;
import com.bytechef.platform.owner.Owner;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;

/**
 * Pins the resolution rule {@link KnowledgeBaseService#fetchKnowledgeBase(String, int, PlatformType, Optional)}
 * applies, mirroring {@code DataTableService#fetchDataTable}: within a pool, an account's own knowledge base wins over
 * the shared one of the same name, and a run with no owner resolves only shared knowledge bases -- it must never fall
 * through to an account's.
 *
 * <p>
 * These are the scenarios {@code KnowledgeBaseOwnerResolutionTest} used to assert one layer up, while the rule was
 * composed in Java over a whole-environment listing. The rule did not change when the listing became two targeted
 * lookups; only where it is expressed did.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseNameResolutionTest {

    private static final long ACCOUNT_ID = 42L;
    private static final Owner OWNER = Owner.connectedUser(ACCOUNT_ID);
    private static final PlatformType EMBEDDED = PlatformType.EMBEDDED;
    private static final int ENVIRONMENT = 0;

    // Distinct, and that is the whole point -- see knowledgeBaseNamed.
    private static final long OWNED_ID = 1L;
    private static final long SHARED_ID = 2L;

    @Mock
    private KnowledgeBaseAuditPublisher knowledgeBaseAuditPublisher;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @InjectMocks
    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @Test
    void testTheAccountsOwnKnowledgeBaseWinsOverTheSharedOne() {
        KnowledgeBase owned = knowledgeBaseNamed(OWNED_ID, "docs", ACCOUNT_ID);

        whenOwnedLookup().thenReturn(Optional.of(owned));

        assertThat(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED, Optional.of(OWNER)))
            .contains(owned);

        // The shared row of that name may well exist; the point is that it is never asked for once the account has
        // one of its own.
        verify(knowledgeBaseRepository, never())
            .findByNameAndEnvironmentAndPlatformTypeAndOwnerIdIsNull(anyString(), anyInt(), anyInt());
    }

    @Test
    void testAnAccountWithNoOwnKnowledgeBaseFallsBackToTheSharedOne() {
        KnowledgeBase shared = knowledgeBaseNamed(SHARED_ID, "docs", null);

        whenOwnedLookup().thenReturn(Optional.empty());
        whenSharedLookup().thenReturn(Optional.of(shared));

        assertThat(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED, Optional.of(OWNER)))
            .contains(shared);
    }

    /**
     * The opposite of the cross-pool rule: within a pool two matches are the feature, but a run with no owner is not
     * one of the two matchers. A naive "first name match wins" resolution would leak whichever account's knowledge base
     * the database happened to return first.
     */
    @Test
    void testAnUnownedRunNeverFallsThroughToAnAccountsKnowledgeBase() {
        whenSharedLookup().thenReturn(Optional.empty());

        assertThat(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED, Optional.empty()))
            .isEmpty();

        verify(knowledgeBaseRepository, never())
            .findByNameAndEnvironmentAndPlatformTypeAndOwnerIdAndOwnerType(
                anyString(), anyInt(), anyInt(), anyLong(), anyInt());
    }

    @Test
    void testAnUnownedRunResolvesTheSharedKnowledgeBase() {
        KnowledgeBase shared = knowledgeBaseNamed(SHARED_ID, "docs", null);

        whenSharedLookup().thenReturn(Optional.of(shared));

        assertThat(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED, Optional.empty()))
            .contains(shared);
    }

    @Test
    void testResolvingAMissingNameFindsNothing() {
        whenOwnedLookup().thenReturn(Optional.empty());
        whenSharedLookup().thenReturn(Optional.empty());

        assertThat(knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED, Optional.of(OWNER)))
            .isEmpty();
    }

    /**
     * An owner IS the pair, not the id: the listing this replaced filtered through {@code isReadableBy}, which compares
     * type as well, so the owned lookup has to carry the type too or a second {@code OwnerType} sharing an id would
     * resolve as the first.
     */
    @Test
    void testTheOwnedLookupCarriesTheOwnerTypeAsWellAsTheId() {
        whenOwnedLookup().thenReturn(Optional.empty());
        whenSharedLookup().thenReturn(Optional.empty());

        knowledgeBaseService.fetchKnowledgeBase("docs", ENVIRONMENT, EMBEDDED, Optional.of(OWNER));

        verify(knowledgeBaseRepository).findByNameAndEnvironmentAndPlatformTypeAndOwnerIdAndOwnerType(
            "docs", ENVIRONMENT, EMBEDDED.ordinal(), ACCOUNT_ID, OwnerType.CONNECTED_USER.ordinal());
    }

    private OngoingStubbing<Optional<KnowledgeBase>> whenOwnedLookup() {
        return when(
            knowledgeBaseRepository.findByNameAndEnvironmentAndPlatformTypeAndOwnerIdAndOwnerType(
                "docs", ENVIRONMENT, EMBEDDED.ordinal(), ACCOUNT_ID, OwnerType.CONNECTED_USER.ordinal()));
    }

    private OngoingStubbing<Optional<KnowledgeBase>> whenSharedLookup() {
        return when(
            knowledgeBaseRepository.findByNameAndEnvironmentAndPlatformTypeAndOwnerIdIsNull(
                "docs", ENVIRONMENT, EMBEDDED.ordinal()));
    }

    /**
     * The id is not decoration. {@link KnowledgeBase#equals} compares id and nothing else, so two fixtures built
     * without one are equal to each other and every {@code contains} above would degrade to "some knowledge base was
     * returned" -- passing just as happily for the shared one as for the account's own.
     */
    private static KnowledgeBase knowledgeBaseNamed(long id, String name, @Nullable Long ownerId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(id);
        knowledgeBase.setName(name);
        knowledgeBase.setOwnerId(ownerId);

        return knowledgeBase;
    }
}
