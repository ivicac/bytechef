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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.audit.KnowledgeBaseAuditPublisher;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.event.KnowledgeBaseOwnerAssignedEvent;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseRepository;
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseOwnershipTest {

    private static final Owner ACCOUNT_A = Owner.connectedUser(1L);
    private static final List<PlatformType> EMBEDDED_POOL = List.of(PlatformType.EMBEDDED);

    @Mock
    private KnowledgeBaseAuditPublisher knowledgeBaseAuditPublisher;

    @Mock
    private KnowledgeBaseDocumentService knowledgeBaseDocumentService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @Test
    void testAnOwnedKnowledgeBaseIsRefusedToAnotherAccount() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(embedded(ownedBy(2L))));

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> knowledgeBaseService.getKnowledgeBase(7L, EMBEDDED_POOL, Optional.of(ACCOUNT_A)));

        // Same message as a genuinely missing knowledge base, so ids cannot be probed for existence.
        assertEquals("KnowledgeBase not found: 7", exception.getMessage());
    }

    @Test
    void testAnOwnedKnowledgeBaseIsReadableByItsOwner() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(embedded(ownedBy(1L))));

        assertNotNull(knowledgeBaseService.getKnowledgeBase(7L, EMBEDDED_POOL, Optional.of(ACCOUNT_A)));
    }

    @Test
    void testAnUnownedKnowledgeBaseIsReadableByAnyAccountWithinItsOwnPool() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(embedded(new KnowledgeBase())));

        assertNotNull(knowledgeBaseService.getKnowledgeBase(7L, EMBEDDED_POOL, Optional.of(ACCOUNT_A)));
    }

    @Test
    void testAnAdminWithNoOwnerReadsAnything() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(ownedBy(2L)));

        assertNotNull(
            knowledgeBaseService.getKnowledgeBase(
                7L, List.of(PlatformType.AUTOMATION, PlatformType.EMBEDDED), Optional.empty()));
    }

    @Test
    void testListingHidesOtherAccountsKnowledgeBases() {
        when(knowledgeBaseRepository.findAllByEnvironmentAndPlatformType(2, PlatformType.AUTOMATION.ordinal()))
            .thenReturn(List.of(ownedBy(1L), ownedBy(2L), new KnowledgeBase()));

        List<KnowledgeBase> knowledgeBases =
            knowledgeBaseService.getKnowledgeBases(2, PlatformType.AUTOMATION, Optional.of(ACCOUNT_A));

        assertEquals(2, knowledgeBases.size());
    }

    @Test
    void testAssigningAnOwnerStampsBothColumns() {
        KnowledgeBase knowledgeBase = embedded(new KnowledgeBase());

        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));

        knowledgeBaseService.assignOwner(7L, ACCOUNT_A);

        assertEquals(1L, knowledgeBase.getOwnerId());
        assertEquals(OwnerType.CONNECTED_USER, knowledgeBase.getOwnerType());
    }

    /**
     * The registry row is not the knowledge base. Its documents carry an owner of their own and their chunks carry one
     * again independently, so an assignment that moved the row alone would leave a document unowned inside an assigned
     * knowledge base: readable by the account that owns it and writable by nobody.
     *
     * <p>
     * Asserted here as the row moving, the documents being re-stamped, and the chunk move being ANNOUNCED -- the chunks
     * live in a datasource no transaction here spans, so the assignment publishes
     * {@link KnowledgeBaseOwnerAssignedEvent} and {@code KnowledgeBaseOwnerAssignedListener} applies it once the commit
     * is durable. What each of the three does with what it is given is pinned against real data by
     * {@code KnowledgeBaseAssignOwnerIntTest} and {@code KnowledgeBaseVectorStoreMetadataServiceIntTest}.
     */
    @Test
    void testAssigningAnOwnerAlsoMovesTheDocumentsAndTheirChunks() {
        KnowledgeBase knowledgeBase = embedded(new KnowledgeBase());

        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));

        knowledgeBaseService.assignOwner(7L, ACCOUNT_A);

        verify(knowledgeBaseDocumentService).restampDocumentOwners(7L, ACCOUNT_A);
        verify(eventPublisher).publishEvent(new KnowledgeBaseOwnerAssignedEvent(7L, ACCOUNT_A));
    }

    /**
     * Unassignment is the same statement inverted and has to be, or a knowledge base handed back to the vendor comes
     * back empty: a run with no owner reaches the unowned documents alone.
     */
    @Test
    void testUnassigningAlsoMovesTheDocumentsAndTheirChunksBackToTheVendor() {
        KnowledgeBase knowledgeBase = embedded(ownedBy(1L));

        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));

        knowledgeBaseService.assignOwner(7L, null);

        verify(knowledgeBaseDocumentService).restampDocumentOwners(7L, null);
        verify(eventPublisher).publishEvent(new KnowledgeBaseOwnerAssignedEvent(7L, null));
    }

    /**
     * A shared knowledge base is exactly where several accounts' documents collect, so assigning one to a single
     * account would hand it every other account's. Refused rather than filtered, on the data table precedent:
     * re-stamping only the unowned documents would leave the rest silently unreachable, which is worse to discover
     * late.
     *
     * <p>
     * The assertions are on nothing having moved, not on a throwable having arrived. A guard that threw AFTER
     * re-stamping would satisfy a throwable-only assertion and still have moved the data.
     */
    @Test
    void testAssigningAKnowledgeBaseHoldingAnotherAccountsDocumentsIsRefused() {
        KnowledgeBase knowledgeBase = embedded(new KnowledgeBase());

        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseDocumentService.countDocumentsOwnedByAnotherAccount(7L, ACCOUNT_A)).thenReturn(2L);

        assertThrows(IllegalArgumentException.class, () -> knowledgeBaseService.assignOwner(7L, ACCOUNT_A));

        assertNull(knowledgeBase.getOwnerId());
        assertNull(knowledgeBase.getOwnerType());

        verify(knowledgeBaseRepository, never()).save(any(KnowledgeBase.class));
        verify(knowledgeBaseDocumentService, never()).restampDocumentOwners(anyLong(), any());
        verify(eventPublisher, never()).publishEvent(any(KnowledgeBaseOwnerAssignedEvent.class));
    }

    /**
     * Unassignment is deliberately unchecked, and is the way out of the refusal above: making a knowledge base shared
     * says its documents are everyone's.
     */
    @Test
    void testUnassigningIsNotRefusedByTheOtherAccountsDocumentsGuard() {
        KnowledgeBase knowledgeBase = embedded(ownedBy(1L));

        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));

        knowledgeBaseService.assignOwner(7L, null);

        verify(knowledgeBaseDocumentService, never()).countDocumentsOwnedByAnotherAccount(anyLong(), any());
        assertNull(knowledgeBase.getOwnerId());
    }

    /**
     * Only the EMBEDDED pool has accounts. An AUTOMATION knowledge base is unowned by definition, and an owner on one
     * resolves for nobody: a run with an owner is narrowed to EMBEDDED, and a run without one only ever reaches unowned
     * rows.
     */
    @Test
    void testAssigningAnOwnerToAnAutomationKnowledgeBaseIsRefused() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));

        assertThrows(IllegalArgumentException.class, () -> knowledgeBaseService.assignOwner(7L, ACCOUNT_A));

        assertNull(knowledgeBase.getOwnerId());
        assertNull(knowledgeBase.getOwnerType());
    }

    @Test
    void testAssigningANullOwnerReturnsTheKnowledgeBaseToTheVendor() {
        KnowledgeBase knowledgeBase = embedded(ownedBy(1L));

        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));

        knowledgeBaseService.assignOwner(7L, null);

        assertNull(knowledgeBase.getOwnerId());
        assertNull(knowledgeBase.getOwnerType());
    }

    /**
     * The repair path for a row that acquired an owner before the pool guard above existed. Refusing this too would
     * leave such a row permanently unreachable, so unassigning stays allowed in every pool.
     */
    @Test
    void testAnAutomationKnowledgeBaseThatSomehowCarriesAnOwnerCanStillBeReturnedToTheVendor() {
        KnowledgeBase knowledgeBase = ownedBy(1L);

        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));

        knowledgeBaseService.assignOwner(7L, null);

        assertNull(knowledgeBase.getOwnerId());
        assertNull(knowledgeBase.getOwnerType());
    }

    @Test
    void testAssigningAnOwnerToAMissingKnowledgeBaseIsRejected() {
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> knowledgeBaseService.assignOwner(7L, ACCOUNT_A));
    }

    private static KnowledgeBase ownedBy(long ownerId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setOwnerId(ownerId);
        knowledgeBase.setOwnerType(OwnerType.CONNECTED_USER);

        return knowledgeBase;
    }

    private static KnowledgeBase embedded(KnowledgeBase knowledgeBase) {
        knowledgeBase.setPlatformType(PlatformType.EMBEDDED);

        return knowledgeBase;
    }
}
