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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.knowledgebase.config.KnowledgeBaseIntTestConfiguration;
import com.bytechef.platform.knowledgebase.config.KnowledgeBaseIntTestConfigurationSharedMocks;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseSource;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseSourceStatus;
import com.bytechef.platform.knowledgebase.dto.DocumentStatusUpdate;
import com.bytechef.platform.knowledgebase.event.KnowledgeBaseDocumentEvent;
import com.bytechef.platform.knowledgebase.file.storage.KnowledgeBaseFileStorage;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseDocumentRepository;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseRepository;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseSourceRepository;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for {@link KnowledgeBaseDocumentService}.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = KnowledgeBaseIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
@KnowledgeBaseIntTestConfigurationSharedMocks
class KnowledgeBaseDocumentServiceIntTest {

    private static final Owner OWNER = Owner.connectedUser(42L);
    private static final Owner OTHER_OWNER = Owner.connectedUser(43L);

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Autowired
    private KnowledgeBaseDocumentService knowledgeBaseDocumentService;

    @Autowired
    private KnowledgeBaseDocumentRepository knowledgeBaseDocumentRepository;

    @Autowired
    private KnowledgeBaseFileStorage knowledgeBaseFileStorage;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KnowledgeBaseSourceRepository knowledgeBaseSourceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private KnowledgeBase knowledgeBase;

    @BeforeEach
    public void beforeEach() {
        knowledgeBaseDocumentRepository.deleteAll();
        knowledgeBaseSourceRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();

        knowledgeBase = new KnowledgeBase();

        knowledgeBase.setName("Test KnowledgeBase");

        knowledgeBase = knowledgeBaseRepository.save(knowledgeBase);
    }

    @AfterEach
    public void afterEach() {
        knowledgeBaseDocumentRepository.deleteAll();
        knowledgeBaseSourceRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();
    }

    @Test
    void testSaveKnowledgeBaseDocument() {
        KnowledgeBaseDocument document = createDocument("Test Document");

        KnowledgeBaseDocument savedDocument = knowledgeBaseDocumentService.saveKnowledgeBaseDocument(document);

        assertThat(savedDocument.getId()).isNotNull();
        assertThat(savedDocument.getName()).isEqualTo("Test Document");
        assertThat(savedDocument.getKnowledgeBaseId()).isEqualTo(knowledgeBase.getId());
        assertThat(savedDocument.getStatus()).isEqualTo(KnowledgeBaseDocument.STATUS_UPLOADED);
    }

    @Test
    void testGetKnowledgeBaseDocument() {
        KnowledgeBaseDocument document = knowledgeBaseDocumentRepository.save(createDocument("Test Document"));

        KnowledgeBaseDocument retrievedDocument = knowledgeBaseDocumentService.getKnowledgeBaseDocument(
            document.getId());

        assertThat(retrievedDocument).isNotNull();
        assertThat(retrievedDocument.getId()).isEqualTo(document.getId());
        assertThat(retrievedDocument.getName()).isEqualTo("Test Document");
    }

    @Test
    void testGetKnowledgeBaseDocumentNotFound() {
        assertThatThrownBy(() -> knowledgeBaseDocumentService.getKnowledgeBaseDocument(Long.MAX_VALUE))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("KnowledgeBase document not found");
    }

    @Test
    void testGetKnowledgeBaseDocuments() {
        knowledgeBaseDocumentRepository.save(createDocument("Document 1"));
        knowledgeBaseDocumentRepository.save(createDocument("Document 2"));
        knowledgeBaseDocumentRepository.save(createDocument("Document 3"));

        List<KnowledgeBaseDocument> documents =
            knowledgeBaseDocumentService.getKnowledgeBaseDocuments(knowledgeBase.getId());

        assertThat(documents).hasSize(3);
    }

    @Test
    void testGetKnowledgeBaseDocumentStatus() {
        KnowledgeBaseDocument document = createDocument("Test Document");

        document.setStatus(KnowledgeBaseDocument.STATUS_PROCESSING);

        document = knowledgeBaseDocumentRepository.save(document);

        DocumentStatusUpdate statusUpdate =
            knowledgeBaseDocumentService.getKnowledgeBaseDocumentStatus(document.getId());

        assertThat(statusUpdate).isNotNull();
        assertThat(statusUpdate.documentId()).isEqualTo(document.getId());
        assertThat(statusUpdate.status()).isEqualTo(KnowledgeBaseDocument.STATUS_PROCESSING);
    }

    @Test
    void testDeleteKnowledgeBaseDocument() {
        KnowledgeBaseDocument document = knowledgeBaseDocumentRepository.save(createDocument("Test Document"));

        assertThat(knowledgeBaseDocumentRepository.findById(document.getId())).isPresent();

        knowledgeBaseDocumentService.delete(document.getId());

        assertThat(knowledgeBaseDocumentRepository.findById(document.getId())).isNotPresent();
    }

    @Test
    void testUpdateDocumentStatus() {
        KnowledgeBaseDocument document = knowledgeBaseDocumentRepository.save(createDocument("Test Document"));

        assertThat(document.getStatus()).isEqualTo(KnowledgeBaseDocument.STATUS_UPLOADED);

        document.setStatus(KnowledgeBaseDocument.STATUS_READY);

        KnowledgeBaseDocument updatedDocument = knowledgeBaseDocumentService.saveKnowledgeBaseDocument(document);

        assertThat(updatedDocument.getStatus()).isEqualTo(KnowledgeBaseDocument.STATUS_READY);
    }

    @Test
    void testCreateSyncedDocumentPersistsAllSyncFieldsAndPublishesEvent() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        when(knowledgeBaseFileStorage.storeDocument(anyString(), any(InputStream.class)))
            .thenAnswer(
                invocation -> new FileEntry(invocation.getArgument(0), "file://stored/" + invocation.getArgument(0)));

        AtomicInteger eventCount = new AtomicInteger();

        applicationContext.addApplicationListener(new SmartApplicationListener() {

            @Override
            public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
                return PayloadApplicationEvent.class.isAssignableFrom(eventType);
            }

            @Override
            public void onApplicationEvent(ApplicationEvent event) {
                if (event instanceof PayloadApplicationEvent<?> payloadEvent
                    && payloadEvent.getPayload() instanceof KnowledgeBaseDocumentEvent) {

                    eventCount.incrementAndGet();
                }
            }
        });

        Instant now = Instant.parse("2026-05-08T12:00:00Z");

        KnowledgeBaseDocument created = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-1", "Record One", "Hello world",
            Map.of("kind", "contact"), null, "hash-1", now, null);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getKnowledgeBaseId()).isEqualTo(knowledgeBase.getId());
        assertThat(created.getSourceId()).isEqualTo(source.getId());
        assertThat(created.getSourceRecordId()).isEqualTo("rec-1");
        assertThat(created.getSyncedPayloadHash()).isEqualTo("hash-1");
        assertThat(created.getLastSeenAt()).isEqualTo(now);
        assertThat(created.getDeletedAt()).isNull();
        assertThat(created.getStatus()).isEqualTo(KnowledgeBaseDocument.STATUS_UPLOADED);
        assertThat(created.getName()).isEqualTo("Record One");
        assertThat(created.getDocument()
            .getName()).isEqualTo("rec-1.md");
        assertThat(created.getTagNames()).contains("kind=contact");
        assertThat(eventCount.get()).isGreaterThan(0);

        verify(knowledgeBaseFileStorage, atLeastOnce()).storeDocument(anyString(), any(InputStream.class));
    }

    @Test
    void testReplaceSyncedDocumentClearsDeletedAtAndBumpsHash() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        when(knowledgeBaseFileStorage.storeDocument(anyString(), any(InputStream.class)))
            .thenAnswer(
                invocation -> new FileEntry(invocation.getArgument(0), "file://stored/" + invocation.getArgument(0)));

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");
        KnowledgeBaseDocument original = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-1", "Record One", "Hello v1",
            Map.of("kind", "contact"), null, "hash-1", initial, null);

        // Mark the doc as tombstoned to verify replaceSyncedDocument clears deleted_at on re-appearance.
        original.setDeletedAt(Instant.parse("2026-05-08T11:00:00Z"));
        knowledgeBaseDocumentRepository.save(original);

        Instant later = Instant.parse("2026-05-08T12:00:00Z");

        KnowledgeBaseDocument replaced = impl.replaceSyncedDocument(
            original.getId(), "Record One v2", "Hello v2", Map.of("kind", "contact"), null, "hash-2", later);

        assertThat(replaced.getId()).isEqualTo(original.getId());
        assertThat(replaced.getSyncedPayloadHash()).isEqualTo("hash-2");
        assertThat(replaced.getLastSeenAt()).isEqualTo(later);
        assertThat(replaced.getDeletedAt()).isNull();
        assertThat(replaced.getStatus()).isEqualTo(KnowledgeBaseDocument.STATUS_UPLOADED);
        assertThat(replaced.getName()).isEqualTo("Record One v2");
    }

    @Test
    void testReplaceSyncedDocumentTakesIdempotentFastPathWhenHashMatches() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        when(knowledgeBaseFileStorage.storeDocument(anyString(), any(InputStream.class)))
            .thenAnswer(
                invocation -> new FileEntry(invocation.getArgument(0), "file://stored/" + invocation.getArgument(0)));

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");
        KnowledgeBaseDocument original = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-1", "Record One", "Hello v1",
            Map.of("kind", "contact"), null, "hash-1", initial, null);

        FileEntry originalFileEntry = original.getDocument();

        Instant later = Instant.parse("2026-05-08T12:00:00Z");

        KnowledgeBaseDocument replaced = impl.replaceSyncedDocument(
            original.getId(), "Record One", "Hello v1", Map.of("kind", "contact"), null, "hash-1", later);

        // Same hash + not tombstoned ⇒ fast path: only last_seen_at moves; name/document/hash unchanged.
        assertThat(replaced.getSyncedPayloadHash()).isEqualTo("hash-1");
        assertThat(replaced.getLastSeenAt()).isEqualTo(later);
        assertThat(replaced.getDocument()
            .getName())
                .isEqualTo(originalFileEntry.getName());
    }

    @Test
    void testTombstoneUnseenSetsDeletedAtForAbsentRecords() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        when(knowledgeBaseFileStorage.storeDocument(anyString(), any(InputStream.class)))
            .thenAnswer(
                invocation -> new FileEntry(invocation.getArgument(0), "file://stored/" + invocation.getArgument(0)));

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        KnowledgeBaseDocument seenA = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-A", "Record A", "Body A", Map.of(), null, "hash-A", initial,
            null);
        KnowledgeBaseDocument seenB = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-B", "Record B", "Body B", Map.of(), null, "hash-B", initial,
            null);
        KnowledgeBaseDocument missingC = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-C", "Record C", "Body C", Map.of(), null, "hash-C", initial,
            null);

        Instant runEnd = Instant.parse("2026-05-08T12:00:00Z");

        int tombstoned = knowledgeBaseDocumentRepository.tombstoneUnseenUnowned(
            source.getId(), List.of("rec-A", "rec-B"), runEnd);

        assertThat(tombstoned).isEqualTo(1);
        assertThat(knowledgeBaseDocumentRepository.findById(seenA.getId())
            .orElseThrow()
            .getDeletedAt()).isNull();
        assertThat(knowledgeBaseDocumentRepository.findById(seenB.getId())
            .orElseThrow()
            .getDeletedAt()).isNull();
        assertThat(knowledgeBaseDocumentRepository.findById(missingC.getId())
            .orElseThrow()
            .getDeletedAt()).isEqualTo(runEnd);
    }

    @Test
    void testGetTombstonedDocumentsReturnsOnlyTombstonedRowsOfSource() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");
        KnowledgeBaseSource otherSource = persistSource("Airtable");

        when(knowledgeBaseFileStorage.storeDocument(anyString(), any(InputStream.class)))
            .thenAnswer(
                invocation -> new FileEntry(invocation.getArgument(0), "file://stored/" + invocation.getArgument(0)));

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-A", "Record A", "Body A", Map.of(), null, "hash-A", initial,
            null);
        KnowledgeBaseDocument tombstoned = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-B", "Record B", "Body B", Map.of(), null, "hash-B", initial,
            null);
        KnowledgeBaseDocument otherSourceTombstoned = impl.createSyncedDocument(
            knowledgeBase.getId(), otherSource.getId(), "rec-C", "Record C", "Body C", Map.of(), null, "hash-C",
            initial, null);

        Instant runEnd = Instant.parse("2026-05-08T12:00:00Z");

        knowledgeBaseDocumentRepository.tombstoneUnseenUnowned(source.getId(), List.of("rec-A"), runEnd);
        knowledgeBaseDocumentRepository.tombstoneUnseenUnowned(otherSource.getId(), List.of("__never_matches__"),
            runEnd);

        List<KnowledgeBaseDocument> tombstonedDocuments = knowledgeBaseDocumentService.getTombstonedDocuments(
            source.getId(), Optional.empty());

        assertThat(tombstonedDocuments).extracting(KnowledgeBaseDocument::getId)
            .containsExactly(tombstoned.getId());

        List<KnowledgeBaseDocument> otherTombstonedDocuments = knowledgeBaseDocumentService.getTombstonedDocuments(
            otherSource.getId(), Optional.empty());

        assertThat(otherTombstonedDocuments).extracting(KnowledgeBaseDocument::getId)
            .containsExactly(otherSourceTombstoned.getId());
    }

    @Test
    void testTombstoneUnseenIgnoresManualUploads() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        when(knowledgeBaseFileStorage.storeDocument(anyString(), any(InputStream.class)))
            .thenAnswer(
                invocation -> new FileEntry(invocation.getArgument(0), "file://stored/" + invocation.getArgument(0)));

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-A", "Record A", "Body A", Map.of(), null, "hash-A", initial,
            null);

        // Manual upload — source_id is NULL, so it must not be touched by the tombstone sweep.
        KnowledgeBaseDocument manualUpload = knowledgeBaseDocumentRepository.save(createDocument("Manual"));

        Instant runEnd = Instant.parse("2026-05-08T12:00:00Z");

        // Empty seen set ⇒ all source-tagged rows for this source are unseen.
        int tombstoned = knowledgeBaseDocumentRepository.tombstoneUnseenUnowned(
            source.getId(), List.of("__never_matches__"), runEnd);

        assertThat(tombstoned).isEqualTo(1);
        assertThat(knowledgeBaseDocumentRepository.findById(manualUpload.getId())
            .orElseThrow()
            .getDeletedAt()).isNull();
    }

    private KnowledgeBaseSource persistSource(String name) {
        KnowledgeBaseSource source = new KnowledgeBaseSource();

        source.setName(name);
        source.setSourceComponentName("hubspot");
        source.setSourceComponentVersion(1);
        source.setSourceClusterElementName("contactsReader");
        source.setKnowledgeBaseId(knowledgeBase.getId());
        source.setCadence("@hourly");
        source.setStatus(KnowledgeBaseSourceStatus.BUILDING_PREVIEW);

        return knowledgeBaseSourceRepository.save(source);
    }

    /**
     * The owner is on the row because the chunker cannot ask anyone. This pins the storage half: both columns written,
     * both read back, through the real schema the changeset builds.
     */
    @Test
    void testAnOwnedDocumentRoundTripsBothOwnerColumns() {
        KnowledgeBaseDocument document = createDocument("Owned Document");

        document.setOwner(Owner.connectedUser(42L));

        KnowledgeBaseDocument saved = knowledgeBaseDocumentService.saveKnowledgeBaseDocument(document);

        KnowledgeBaseDocument reloaded = knowledgeBaseDocumentService.getKnowledgeBaseDocument(saved.getId());

        assertThat(reloaded.getOwner()).contains(Owner.connectedUser(42L));

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT owner_id, owner_type FROM knowledge_base_document WHERE id = ?", saved.getId());

        assertThat(row.get("owner_id")).isEqualTo(42L);
        assertThat(row.get("owner_type")).isEqualTo(OwnerType.CONNECTED_USER.ordinal());
    }

    /**
     * The columns move as a pair. One written without the other belongs to nobody -- it satisfies neither the owned
     * predicate nor the shared one -- so an unowned document has to leave both null rather than one.
     */
    @Test
    void testAnUnownedDocumentLeavesBothOwnerColumnsNull() {
        KnowledgeBaseDocument saved = knowledgeBaseDocumentService.saveKnowledgeBaseDocument(
            createDocument("Unowned Document"));

        KnowledgeBaseDocument reloaded = knowledgeBaseDocumentService.getKnowledgeBaseDocument(saved.getId());

        assertThat(reloaded.getOwner()).isEmpty();

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT owner_id, owner_type FROM knowledge_base_document WHERE id = ?", saved.getId());

        assertThat(row.get("owner_id")).isNull();
        assertThat(row.get("owner_type")).isNull();
    }

    /**
     * A synced document created for an account carries that account, and one created by the vendor's own sync carries
     * nobody. Re-syncing never moves a document between accounts, which is why {@code replaceSyncedDocument} has no
     * owner argument to disagree with this one.
     */
    @Test
    void testCreateSyncedDocumentPersistsTheOwnerItWasCreatedFor() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        when(knowledgeBaseFileStorage.storeDocument(anyString(), any(InputStream.class)))
            .thenAnswer(
                invocation -> new FileEntry(invocation.getArgument(0), "file://stored/" + invocation.getArgument(0)));

        Instant now = Instant.parse("2026-05-08T12:00:00Z");

        KnowledgeBaseDocument owned = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-owned", "Owned", "Hello", Map.of(), null, "hash-owned", now,
            Owner.connectedUser(42L));
        KnowledgeBaseDocument unowned = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-unowned", "Unowned", "Hello", Map.of(), null, "hash-unowned",
            now, null);

        assertThat(knowledgeBaseDocumentService.getKnowledgeBaseDocument(owned.getId())
            .getOwner()).contains(Owner.connectedUser(42L));
        assertThat(knowledgeBaseDocumentService.getKnowledgeBaseDocument(unowned.getId())
            .getOwner()).isEmpty();
    }

    /**
     * The tombstone half of the finding. Two accounts sync the same source into one shared knowledge base, and account
     * 42's FULL_REPLACE run sees none of its records this time round. Before the owner was part of the predicate the
     * sweep keyed on {@code source_id} alone and reaped account 43's rows and the vendor's along with 42's, and the
     * chunk sweep that follows then deleted their chunks out of the vector store by raw id.
     *
     * <p>
     * The assertion is on {@code deleted_at} of every row, not on the returned count: a count is one number that a
     * wrong predicate can still produce, whereas the survivors name themselves.
     */
    @Test
    void testTombstoneUnseenLeavesAnotherAccountsDocumentsFromTheSameSource() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        stubDocumentStorage();

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        KnowledgeBaseDocument ours = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-ours", "Ours", "Body", Map.of(), null, "hash-ours", initial,
            OWNER);
        KnowledgeBaseDocument theirs = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-theirs", "Theirs", "Body", Map.of(), null, "hash-theirs",
            initial, OTHER_OWNER);
        KnowledgeBaseDocument vendors = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-vendors", "Vendors", "Body", Map.of(), null, "hash-vendors",
            initial, null);

        Instant runEnd = Instant.parse("2026-05-08T12:00:00Z");

        int tombstoned = knowledgeBaseDocumentService.tombstoneUnseen(
            source.getId(), Set.of("__never_matches__"), runEnd, Optional.of(OWNER));

        // The survivors are asserted first, so an unscoped build names the account whose documents it reaped rather
        // than reporting a count that happens to be wrong.
        assertThat(deletedAtOf(theirs)).isNull();
        assertThat(deletedAtOf(vendors)).isNull();
        assertThat(deletedAtOf(ours)).isEqualTo(runEnd);
        assertThat(tombstoned).isEqualTo(1);
    }

    /**
     * The other direction, and the one an empty owner makes easy to get wrong: the vendor's own sync reaps the
     * documents belonging to nobody and never falls through to an account's.
     */
    @Test
    void testTombstoneUnseenForAVendorRunReapsTheUnownedDocumentsAlone() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        stubDocumentStorage();

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        KnowledgeBaseDocument ours = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-ours", "Ours", "Body", Map.of(), null, "hash-ours", initial,
            OWNER);
        KnowledgeBaseDocument vendors = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-vendors", "Vendors", "Body", Map.of(), null, "hash-vendors",
            initial, null);

        Instant runEnd = Instant.parse("2026-05-08T12:00:00Z");

        int tombstoned = knowledgeBaseDocumentService.tombstoneUnseen(
            source.getId(), Set.of("__never_matches__"), runEnd, Optional.empty());

        assertThat(deletedAtOf(ours)).isNull();
        assertThat(deletedAtOf(vendors)).isEqualTo(runEnd);
        assertThat(tombstoned).isEqualTo(1);
    }

    /**
     * A document carrying an {@code owner_id} beside a null {@code owner_type} belongs to nobody, and must satisfy
     * neither predicate: the account whose id it carries may not reap it, and neither may the vendor. Written through
     * raw SQL because {@code setOwner} cannot produce this shape -- which is the point of that method.
     */
    @Test
    void testTombstoneUnseenReapsNeitherHalfOfAHalfWrittenOwner() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        stubDocumentStorage();

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        KnowledgeBaseDocument halfOwned = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-half", "Half", "Body", Map.of(), null, "hash-half", initial,
            null);

        jdbcTemplate.update(
            "UPDATE knowledge_base_document SET owner_id = ?, owner_type = NULL WHERE id = ?", OWNER.id(),
            halfOwned.getId());

        Instant runEnd = Instant.parse("2026-05-08T12:00:00Z");

        knowledgeBaseDocumentService.tombstoneUnseen(
            source.getId(), Set.of("__never_matches__"), runEnd, Optional.of(OWNER));
        knowledgeBaseDocumentService.tombstoneUnseen(
            source.getId(), Set.of("__never_matches__"), runEnd, Optional.empty());

        assertThat(deletedAtOf(halfOwned)).isNull();
    }

    /**
     * What the chunk sweep is handed. It deletes every chunk of every document this returns, out of the vector store by
     * raw id and with no owner filter of its own, so a listing wider than the caller's own documents is a cross-account
     * delete by another name.
     */
    @Test
    void testGetTombstonedDocumentsReturnsOnlyTheGivenOwnersDocuments() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        stubDocumentStorage();

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        KnowledgeBaseDocument ours = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-ours", "Ours", "Body", Map.of(), null, "hash-ours", initial,
            OWNER);
        KnowledgeBaseDocument theirs = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-theirs", "Theirs", "Body", Map.of(), null, "hash-theirs",
            initial, OTHER_OWNER);
        KnowledgeBaseDocument vendors = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-vendors", "Vendors", "Body", Map.of(), null, "hash-vendors",
            initial, null);

        Instant runEnd = Instant.parse("2026-05-08T12:00:00Z");

        // Everything is tombstoned, by each owner's own sweep, so the listing below is separating owners rather than
        // separating tombstoned rows from live ones.
        knowledgeBaseDocumentService.tombstoneUnseen(
            source.getId(), Set.of("__never_matches__"), runEnd, Optional.of(OWNER));
        knowledgeBaseDocumentService.tombstoneUnseen(
            source.getId(), Set.of("__never_matches__"), runEnd, Optional.of(OTHER_OWNER));
        knowledgeBaseDocumentService.tombstoneUnseen(
            source.getId(), Set.of("__never_matches__"), runEnd, Optional.empty());

        assertThat(knowledgeBaseDocumentService.getTombstonedDocuments(source.getId(), Optional.of(OWNER)))
            .extracting(KnowledgeBaseDocument::getId)
            .containsExactly(ours.getId());
        assertThat(knowledgeBaseDocumentService.getTombstonedDocuments(source.getId(), Optional.of(OTHER_OWNER)))
            .extracting(KnowledgeBaseDocument::getId)
            .containsExactly(theirs.getId());
        assertThat(knowledgeBaseDocumentService.getTombstonedDocuments(source.getId(), Optional.empty()))
            .extracting(KnowledgeBaseDocument::getId)
            .containsExactly(vendors.getId());
    }

    /**
     * The fifth instance of the defect class, at the level where it bites. Two accounts sync the same source record
     * into one shared knowledge base. Unscoped, account 43's lookup found account 42's document and the replace path
     * rewrote its content: ownership never moved, the content did.
     *
     * <p>
     * The intended outcome is two documents, one per account, and the assertions say so directly rather than counting
     * rows -- a count of two is also what a build that created two documents for ONE account would produce. Each
     * account's lookup must return its own, and only its own.
     */
    @Test
    void testTwoAccountsSyncingOneSourceRecordEachKeepTheirOwnDocument() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        stubDocumentStorage();

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        assertThat(knowledgeBaseDocumentService.findSyncedDocument(source.getId(), "rec-1", Optional.of(OWNER)))
            .isEmpty();

        KnowledgeBaseDocument ours = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-1", "Ours", "Body ours", Map.of(), null, "hash-ours", initial,
            OWNER);

        // The second account's run for the SAME source record. It must not find the first account's document, or the
        // replace path below rewrites content that stays somebody else's.
        assertThat(knowledgeBaseDocumentService.findSyncedDocument(source.getId(), "rec-1", Optional.of(OTHER_OWNER)))
            .isEmpty();

        KnowledgeBaseDocument theirs = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-1", "Theirs", "Body theirs", Map.of(), null, "hash-theirs",
            initial, OTHER_OWNER);

        assertThat(theirs.getId()).isNotEqualTo(ours.getId());

        assertThat(knowledgeBaseDocumentService.findSyncedDocument(source.getId(), "rec-1", Optional.of(OWNER)))
            .map(KnowledgeBaseDocument::getId)
            .contains(ours.getId());
        assertThat(knowledgeBaseDocumentService.findSyncedDocument(source.getId(), "rec-1", Optional.of(OTHER_OWNER)))
            .map(KnowledgeBaseDocument::getId)
            .contains(theirs.getId());
    }

    /**
     * The content-crossing half, asserted on the other account's document rather than on the lookup: account 42's
     * re-sync rewrites its own document and leaves account 43's exactly as 43's run wrote it.
     */
    @Test
    void testARunReSyncingOneSourceRecordLeavesAnotherAccountsCopyUntouched() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        stubDocumentStorage();

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-1", "Ours v1", "Body ours v1", Map.of(), null, "hash-ours-1",
            initial, OWNER);

        KnowledgeBaseDocument theirs = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-1", "Theirs", "Body theirs", Map.of(), null, "hash-theirs",
            initial, OTHER_OWNER);

        Instant later = Instant.parse("2026-05-08T12:00:00Z");

        KnowledgeBaseDocument found = knowledgeBaseDocumentService
            .findSyncedDocument(source.getId(), "rec-1", Optional.of(OWNER))
            .orElseThrow();

        impl.replaceSyncedDocument(
            found.getId(), "Ours v2", "Body ours v2", Map.of(), null, "hash-ours-2", later);

        KnowledgeBaseDocument reloadedTheirs = knowledgeBaseDocumentRepository.findById(theirs.getId())
            .orElseThrow();

        assertThat(reloadedTheirs.getName()).isEqualTo("Theirs");
        assertThat(reloadedTheirs.getSyncedPayloadHash()).isEqualTo("hash-theirs");
        assertThat(reloadedTheirs.getOwner()).contains(OTHER_OWNER);
    }

    /**
     * The direction an empty owner makes easy to get wrong: the vendor's own sync reaches the documents belonging to
     * nobody and never falls through to an account's. An unscoped lookup returns whichever row the database offers
     * first, which for a shared source is an account's as often as not.
     */
    @Test
    void testFindSyncedDocumentForAVendorRunReachesTheUnownedDocumentAlone() {
        KnowledgeBaseDocumentServiceImpl impl = (KnowledgeBaseDocumentServiceImpl) knowledgeBaseDocumentService;
        KnowledgeBaseSource source = persistSource("HubSpot");

        stubDocumentStorage();

        Instant initial = Instant.parse("2026-05-08T10:00:00Z");

        impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-1", "Ours", "Body ours", Map.of(), null, "hash-ours", initial,
            OWNER);

        assertThat(knowledgeBaseDocumentService.findSyncedDocument(source.getId(), "rec-1", Optional.empty()))
            .as("an account's document is not the vendor's to find, still less to rewrite")
            .isEmpty();

        KnowledgeBaseDocument vendors = impl.createSyncedDocument(
            knowledgeBase.getId(), source.getId(), "rec-1", "Vendors", "Body vendors", Map.of(), null, "hash-vendors",
            initial, null);

        assertThat(knowledgeBaseDocumentService.findSyncedDocument(source.getId(), "rec-1", Optional.empty()))
            .map(KnowledgeBaseDocument::getId)
            .contains(vendors.getId());
    }

    private Instant deletedAtOf(KnowledgeBaseDocument document) {
        KnowledgeBaseDocument reloaded = knowledgeBaseDocumentRepository.findById(document.getId())
            .orElseThrow();

        return reloaded.getDeletedAt();
    }

    private void stubDocumentStorage() {
        when(knowledgeBaseFileStorage.storeDocument(anyString(), any(InputStream.class)))
            .thenAnswer(
                invocation -> new FileEntry(invocation.getArgument(0), "file://stored/" + invocation.getArgument(0)));
    }

    private KnowledgeBaseDocument createDocument(String name) {
        KnowledgeBaseDocument document = new KnowledgeBaseDocument();

        document.setKnowledgeBaseId(knowledgeBase.getId());
        document.setName(name);
        document.setDocument(new FileEntry(name + ".txt", "file://test/" + name + ".txt"));
        document.setStatus(KnowledgeBaseDocument.STATUS_UPLOADED);

        return document;
    }
}
