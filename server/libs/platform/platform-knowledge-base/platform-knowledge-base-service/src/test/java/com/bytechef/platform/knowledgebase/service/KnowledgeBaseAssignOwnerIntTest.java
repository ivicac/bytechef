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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.config.KnowledgeBaseIntTestConfiguration;
import com.bytechef.platform.knowledgebase.config.KnowledgeBaseIntTestConfigurationSharedMocks;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.event.KnowledgeBaseOwnerAssignedListener;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseDocumentRepository;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseRepository;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What an assignment moves, against a real database.
 *
 * <p>
 * The registry row used to be the whole of it, on the premise that documents inherit through {@code knowledge_base_id}
 * and carry no owner of their own. The row axis made that false, and the shape it left behind is the one asserted here:
 * a document written before {@code owner_id} existed sits unowned inside an assigned knowledge base, readable by the
 * account that owns the knowledge base and writable by nobody.
 *
 * <p>
 * The assertions are on what the account may DO with the documents afterwards rather than on the columns, because the
 * columns are two and a build that moved one of them would satisfy a column assertion and leave the documents belonging
 * to nobody.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = KnowledgeBaseIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
@KnowledgeBaseIntTestConfigurationSharedMocks
class KnowledgeBaseAssignOwnerIntTest {

    private static final Owner OWNER = Owner.connectedUser(42L);
    private static final Owner OTHER_OWNER = Owner.connectedUser(43L);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KnowledgeBaseDocumentRepository knowledgeBaseDocumentRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeBaseVectorStoreMetadataService knowledgeBaseVectorStoreMetadataService;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    private KnowledgeBase knowledgeBase;

    @BeforeEach
    public void beforeEach() {
        knowledgeBaseDocumentRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();

        KnowledgeBase newKnowledgeBase = new KnowledgeBase();

        newKnowledgeBase.setName("Shared KnowledgeBase");
        newKnowledgeBase.setPlatformType(PlatformType.EMBEDDED);

        knowledgeBase = knowledgeBaseRepository.save(newKnowledgeBase);
    }

    @AfterEach
    public void afterEach() {
        knowledgeBaseDocumentRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();
    }

    /**
     * The defect itself. Before the assignment the account owns nothing in the knowledge base and the write rule
     * refuses it every document in there; after it, the documents are its own and it can rewrite them.
     */
    @Test
    void testAssigningAKnowledgeBaseMakesItsUnownedDocumentsWritableByTheNewOwner() {
        KnowledgeBaseDocument first = persistDocument("Legacy One", null);
        KnowledgeBaseDocument second = persistDocument("Legacy Two", null);

        assertThat(reload(first).isWritableBy(Optional.of(OWNER)))
            .as("an unowned document is nobody's to write, which is the state the assignment has to leave behind")
            .isFalse();

        knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

        assertThat(reload(first).isWritableBy(Optional.of(OWNER))).isTrue();
        assertThat(reload(second).isWritableBy(Optional.of(OWNER))).isTrue();
        assertThat(reload(first).getOwner()).contains(OWNER);
        assertThat(reload(second).getOwner()).contains(OWNER);
    }

    /**
     * The two columns, read straight out of the database rather than through {@code getOwner}. A re-stamp that wrote
     * one of them would leave the document belonging to nobody and satisfying neither predicate, and asserting in SQL
     * is what makes that visible as the wrong ROW rather than as a wrong answer from the reader above it.
     */
    @Test
    void testTheReStampWritesBothOwnerColumns() {
        KnowledgeBaseDocument document = persistDocument("Legacy", null);

        knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT owner_id, owner_type FROM knowledge_base_document WHERE id = ?", document.getId());

        assertThat(row.get("owner_id")).isEqualTo(OWNER.id());
        assertThat(row.get("owner_type")).isEqualTo(
            OWNER.type()
                .ordinal());
    }

    /**
     * The chunks go with the documents. The vector store is mocked in this context, so the effect on the metadata
     * itself is pinned by {@code KnowledgeBaseVectorStoreMetadataServiceIntTest} against a real vector table; what is
     * asserted here is that the assignment reaches it at all, with the knowledge base and owner it just moved.
     */
    @Test
    void testAssigningAKnowledgeBaseAlsoMovesItsChunks() {
        persistDocument("Legacy", null);

        knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

        verify(knowledgeBaseVectorStoreMetadataService).updateOwner(knowledgeBase.getId(), OWNER);
    }

    /**
     * The refusal, asserted on the documents rather than on the throwable. A guard that threw AFTER re-stamping, or
     * after saving the registry row, would satisfy an assertion that merely caught an exception and would still have
     * handed one account's documents to another.
     */
    @Test
    void testAssigningAKnowledgeBaseHoldingAnotherAccountsDocumentsIsRefused() {
        KnowledgeBaseDocument theirs = persistDocument("Theirs", OTHER_OWNER);
        KnowledgeBaseDocument unowned = persistDocument("Unowned", null);

        assertThatThrownBy(() -> knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("belonging to another account");

        assertThat(reload(theirs).getOwner())
            .as("the other account's document must still be theirs after the refusal")
            .contains(OTHER_OWNER);
        assertThat(reload(unowned).getOwner())
            .as("nothing at all moves on a refusal, not even the documents the assignment was entitled to move")
            .isEmpty();

        KnowledgeBase reloadedKnowledgeBase = knowledgeBaseRepository.findById(knowledgeBase.getId())
            .orElseThrow();

        assertThat(reloadedKnowledgeBase.getOwnerId()).isNull();
        assertThat(reloadedKnowledgeBase.getOwnerType()).isNull();

        verify(knowledgeBaseVectorStoreMetadataService, never()).updateOwner(
            knowledgeBase.getId(), OWNER);
    }

    /**
     * The guard must refuse the assignments that would move another account's documents and no others, or it could be
     * satisfied by refusing everything.
     */
    @Test
    void testAKnowledgeBaseWhoseDocumentsAreAlreadyTheTargetsStaysAssignable() {
        KnowledgeBaseDocument alreadyOurs = persistDocument("Ours", OWNER);
        KnowledgeBaseDocument unowned = persistDocument("Unowned", null);

        knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

        assertThat(reload(alreadyOurs).getOwner()).contains(OWNER);
        assertThat(reload(unowned).getOwner()).contains(OWNER);
    }

    /**
     * A document carrying an {@code owner_id} beside a null {@code owner_type} belongs to nobody, so it does not count
     * as another account's and the assignment is entitled to move it. Written through raw SQL because {@code setOwner}
     * cannot produce this shape, which is the point of that method.
     */
    @Test
    void testAHalfWrittenOwnerDoesNotBlockAnAssignmentAndIsMovedByIt() {
        KnowledgeBaseDocument halfOwned = persistDocument("Half", null);

        jdbcTemplate.update(
            "UPDATE knowledge_base_document SET owner_id = ?, owner_type = NULL WHERE id = ?", OTHER_OWNER.id(),
            halfOwned.getId());

        knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

        assertThat(reload(halfOwned).getOwner()).contains(OWNER);
    }

    /**
     * Unassignment is the same statement inverted, and unchecked: handing a knowledge base back to the vendor says its
     * documents are everyone's. Without it the knowledge base would come back empty, since a run with no owner reaches
     * the unowned documents alone.
     */
    @Test
    void testUnassigningAKnowledgeBaseReturnsItsDocumentsToTheVendor() {
        knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

        KnowledgeBaseDocument document = persistDocument("Theirs", OWNER);

        knowledgeBaseService.assignOwner(knowledgeBase.getId(), null);

        assertThat(reload(document).getOwner()).isEmpty();
        assertThat(reload(document).isReadableBy(Optional.empty()))
            .as("the vendor must be able to read what it was handed back")
            .isTrue();

        verify(knowledgeBaseVectorStoreMetadataService).updateOwner(knowledgeBase.getId(), null);
    }

    /**
     * Only this knowledge base's documents move. The re-stamp is keyed on {@code knowledge_base_id}, and a build that
     * dropped that key would move every document in the tenant.
     */
    @Test
    void testDocumentsOfAnotherKnowledgeBaseAreNotMoved() {
        KnowledgeBase otherKnowledgeBase = new KnowledgeBase();

        otherKnowledgeBase.setName("Other KnowledgeBase");
        otherKnowledgeBase.setPlatformType(PlatformType.EMBEDDED);

        otherKnowledgeBase = knowledgeBaseRepository.save(otherKnowledgeBase);

        KnowledgeBaseDocument elsewhere = persistDocument(otherKnowledgeBase.getId(), "Elsewhere", null);

        knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

        assertThat(reload(elsewhere).getOwner()).isEmpty();
    }

    /**
     * The narrow window the two-store assignment used to leave open, closed from the side that matters.
     *
     * <p>
     * The chunks live in the vector store, a separate datasource that no transaction here spans, so a crash between the
     * two writes leaves them disagreeing. Which disagreement it leaves is the whole question. Writing the vector store
     * inline puts the chunks on the new owner while the documents roll back to the old one -- an account reading chunks
     * in a knowledge base whose documents are not its own, which is exactly the cross-account read the row axis exists
     * to prevent.
     *
     * <p>
     * Driven by rolling the whole thing back rather than by crashing the process, which is the same thing from the
     * vector store's point of view and repeatable. The assertion is on the documents as well as the mock: a build that
     * wrote the chunks inline would satisfy neither, and one that wrote them from a hook attached to the wrong phase
     * would satisfy the documents and fail on the chunks.
     */
    @Test
    void testTheChunkMoveDoesNotHappenWhenTheAssignmentRollsBack() {
        KnowledgeBaseDocument document = persistDocument("Legacy", null);

        TransactionTemplate transactionTemplate = new TransactionTemplate(platformTransactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

            throw new IllegalStateException("the assignment does not survive this");
        }))
            .isInstanceOf(IllegalStateException.class);

        assertThat(reload(document).getOwner())
            .as("the document half rolled back, so the knowledge base is as it was before the attempt")
            .isEmpty();

        verify(knowledgeBaseVectorStoreMetadataService, never()).updateOwner(anyLong(), any());
    }

    /**
     * What a failure of the second half leaves behind, and that running the same assignment again is all it takes.
     *
     * <p>
     * The vector store stands in for itself here: the mock keeps the owner it was last given, so "the chunks" is a
     * value that can be compared against the documents' owner rather than a call that can be counted. The first attempt
     * fails inside it after the database has committed, and the assertion is that the two now DISAGREE -- documents
     * moved, chunks not -- which is the recoverable direction and the one this ordering was chosen to leave. The repair
     * is then asserted by doing the only thing an operator is asked to do, which is to run it again.
     *
     * <p>
     * The first assignment deliberately does NOT throw, and that is not an oversight in the listener. Spring dispatches
     * an {@code AFTER_COMMIT} listener from the synchronization's {@code afterCompletion} callback, and
     * {@code AbstractPlatformTransactionManager} catches whatever that callback throws -- by design, since the
     * transaction has already committed and cannot be failed retroactively. The failure is therefore announced in the
     * log rather than in the return, which {@link #testAFailedChunkMoveIsAnnouncedRatherThanSilent} pins.
     */
    @Test
    void testAFailedChunkMoveLeavesAStateReRunningTheAssignmentRepairs() {
        KnowledgeBaseDocument document = persistDocument("Legacy", null);

        AtomicReference<Owner> chunkOwner = new AtomicReference<>(null);

        stubVectorStoreFailingOnce(chunkOwner);

        knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

        assertThat(reload(document).getOwner())
            .as("the database half committed, which is what makes the failure repairable rather than a lost write")
            .contains(OWNER);
        assertThat(chunkOwner.get())
            .as("the chunks lag their documents, the direction that discloses nothing")
            .isNull();

        knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);

        assertThat(chunkOwner.get())
            .as("re-running the assignment is the whole repair")
            .isEqualTo(OWNER);
        assertThat(reload(document).getOwner()).contains(OWNER);
    }

    /**
     * The other half of "recoverable": somebody has to be told. Nothing can make the assignment call fail once the
     * commit has happened, so the only place the lag can be announced is the log, and a lag announced nowhere is
     * indistinguishable from no lag.
     *
     * <p>
     * Asserted on the message reaching an appender rather than on the catch block existing, and on the message naming
     * the repair, because an ERROR that does not say what to do leaves the operator exactly where silence would.
     */
    @Test
    void testAFailedChunkMoveIsAnnouncedRatherThanSilent() {
        persistDocument("Legacy", null);

        stubVectorStoreFailingOnce(new AtomicReference<>(null));

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(KnowledgeBaseOwnerAssignedListener.class);

        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

        listAppender.start();
        listenerLogger.addAppender(listAppender);

        try {
            knowledgeBaseService.assignOwner(knowledgeBase.getId(), OWNER);
        } finally {
            listenerLogger.detachAppender(listAppender);
        }

        assertThat(listAppender.list)
            .anySatisfy(loggingEvent -> {
                assertThat(loggingEvent.getLevel()).isEqualTo(Level.ERROR);
                assertThat(loggingEvent.getFormattedMessage()).contains("Re-run the assignment");
            });
    }

    /**
     * One failure, then a working vector store, with the owner it was last given kept so the chunks can be compared
     * against the documents rather than merely counted.
     */
    private void stubVectorStoreFailingOnce(AtomicReference<Owner> chunkOwner) {
        AtomicBoolean vectorStoreDown = new AtomicBoolean(true);

        when(knowledgeBaseVectorStoreMetadataService.updateOwner(anyLong(), any()))
            .thenAnswer(invocation -> {
                if (vectorStoreDown.getAndSet(false)) {
                    throw new IllegalStateException("vector store unreachable");
                }

                chunkOwner.set(invocation.getArgument(1));

                return 1;
            });
    }

    private KnowledgeBaseDocument persistDocument(String name, Owner owner) {
        return persistDocument(knowledgeBase.getId(), name, owner);
    }

    private KnowledgeBaseDocument persistDocument(Long knowledgeBaseId, String name, Owner owner) {
        KnowledgeBaseDocument document = new KnowledgeBaseDocument();

        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setName(name);
        document.setDocument(new FileEntry(name + ".txt", "file://test/" + name + ".txt"));
        document.setStatus(KnowledgeBaseDocument.STATUS_UPLOADED);
        document.setOwner(owner);

        return knowledgeBaseDocumentRepository.save(document);
    }

    private KnowledgeBaseDocument reload(KnowledgeBaseDocument document) {
        return knowledgeBaseDocumentRepository.findById(document.getId())
            .orElseThrow();
    }
}
