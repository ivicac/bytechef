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

package com.bytechef.platform.knowledgebase.event;

import com.bytechef.platform.knowledgebase.service.KnowledgeBaseVectorStoreMetadataService;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Moves a knowledge base's chunks onto their documents' new owner, once that move is durable.
 *
 * <p>
 * {@code AFTER_COMMIT} is the whole point and not a detail of wiring. The assignment writes two stores and only one of
 * them is transactional with the caller, so the order in which they are written decides which half-applied state a
 * crash can leave. Written inline, the losing state is chunks stamped with an owner whose documents rolled back --
 * chunks readable by an account that owns nothing in the knowledge base, which is precisely the disclosure the row axis
 * exists to prevent. Written after the commit, the losing state is chunks still carrying the previous owner while the
 * documents carry the new one: strictly narrower than before the assignment was attempted, disclosing nothing that was
 * not already disclosed, and repaired by running the same assignment again, which both statements are idempotent under.
 *
 * <p>
 * It is the same posture the ingestion path already has, arrived at the same way: chunks are written by
 * {@code KnowledgeBaseDocumentProcessWorker} off a message that {@code MessageEventListener} only sends
 * {@code AFTER_COMMIT}, so no chunk has ever been written for a document whose row did not commit. This listener is
 * that rule applied to the one vector-store write that was still inline.
 *
 * <p>
 * The failure is announced in the log and nowhere else, and that is forced rather than chosen. Spring dispatches an
 * {@code AFTER_COMMIT} listener from the synchronization's {@code afterCompletion} callback, and
 * {@code AbstractPlatformTransactionManager} catches what that callback throws -- correctly, since the transaction has
 * already committed and cannot be failed after the fact. So the rethrow below reaches no caller, and the ERROR line is
 * the whole of what an operator gets: it therefore has to name the knowledge base and the repair, or it leaves them
 * exactly where silence would. Kept synchronous rather than {@code @Async} anyway, so the write is attempted on the
 * thread that made the assignment and cannot be lost to a full executor queue.
 *
 * <p>
 * {@code fallbackExecution = true} keeps the write happening when there is no transaction to wait for at all, rather
 * than dropping it silently. With no transaction there is nothing that can roll back underneath it, so running inline
 * is the correct behaviour in that case and not a weakening of the rule above.
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.knowledge-base", name = "enabled", havingValue = "true")
public class KnowledgeBaseOwnerAssignedListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseOwnerAssignedListener.class);

    private final KnowledgeBaseVectorStoreMetadataService knowledgeBaseVectorStoreMetadataService;

    @SuppressFBWarnings("EI2")
    public KnowledgeBaseOwnerAssignedListener(
        KnowledgeBaseVectorStoreMetadataService knowledgeBaseVectorStoreMetadataService) {

        this.knowledgeBaseVectorStoreMetadataService = knowledgeBaseVectorStoreMetadataService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onKnowledgeBaseOwnerAssigned(KnowledgeBaseOwnerAssignedEvent event) {
        long knowledgeBaseId = event.knowledgeBaseId();
        Owner owner = event.owner();

        try {
            knowledgeBaseVectorStoreMetadataService.updateOwner(knowledgeBaseId, owner);
        } catch (RuntimeException exception) {
            // Logged before the rethrow because the rethrow is swallowed by the transaction manager: an after-commit
            // synchronization cannot fail a transaction that already committed. This line is the only report anyone
            // gets, so it names the knowledge base, both owners and the repair.
            log.error(
                "Chunk owner move failed for knowledge base {}; its documents committed on owner {} and its chunks " +
                    "still carry the previous one. Re-run the assignment to repair it.",
                knowledgeBaseId, owner, exception);

            throw exception;
        }
    }
}
