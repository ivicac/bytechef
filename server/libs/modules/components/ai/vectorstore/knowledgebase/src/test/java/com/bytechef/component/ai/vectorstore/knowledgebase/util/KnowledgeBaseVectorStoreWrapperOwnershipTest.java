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

import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_TYPE;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_SHARED;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_TAG_NAMES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.owner.Owner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * The second ownership axis, asserted where it is enforced.
 *
 * <p>
 * Behavioural rather than structural: the fake store below actually evaluates the filter the wrapper produces against a
 * fixed set of chunks, so each test names the chunks an account may and may not see rather than the shape of an
 * expression. An assertion on the expression's shape would go green against a filter that reads correctly and means
 * something else.
 *
 * <p>
 * The fixture holds one chunk per case that matters -- account 42's, account 43's, a shared one, and a shared one in a
 * different knowledge base -- because every claim here is a pair: what is returned AND what is not. A gate that
 * returned nothing would satisfy half of them.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseVectorStoreWrapperOwnershipTest {

    private static final long KNOWLEDGE_BASE_ID = 7L;
    private static final long OTHER_KNOWLEDGE_BASE_ID = 8L;

    private static final long ACCOUNT_ID = 42L;
    private static final long OTHER_ACCOUNT_ID = 43L;

    private static final Optional<Owner> ACCOUNT = Optional.of(Owner.connectedUser(ACCOUNT_ID));
    private static final Optional<Owner> OTHER_ACCOUNT = Optional.of(Owner.connectedUser(OTHER_ACCOUNT_ID));
    private static final Optional<Owner> VENDOR = Optional.empty();

    private static final String OWN_CHUNK = "own";
    private static final String OTHER_ACCOUNT_CHUNK = "other-account";
    private static final String SHARED_CHUNK = "shared";
    private static final String SHARED_CHUNK_IN_ANOTHER_KNOWLEDGE_BASE = "shared-elsewhere";

    private final FakeVectorStore vectorStore = new FakeVectorStore(
        List.of(
            ownedChunk(OWN_CHUNK, KNOWLEDGE_BASE_ID, ACCOUNT_ID),
            ownedChunk(OTHER_ACCOUNT_CHUNK, KNOWLEDGE_BASE_ID, OTHER_ACCOUNT_ID),
            sharedChunk(SHARED_CHUNK, KNOWLEDGE_BASE_ID),
            sharedChunk(SHARED_CHUNK_IN_ANOTHER_KNOWLEDGE_BASE, OTHER_KNOWLEDGE_BASE_ID)));

    @Test
    void testAnAccountReadsItsOwnChunksAndTheSharedOnesButNotAnotherAccounts() {
        assertThat(search(wrapper(null, ACCOUNT)))
            .containsExactlyInAnyOrder(OWN_CHUNK, SHARED_CHUNK);
    }

    @Test
    void testTheOtherAccountSeesItsOwnChunkAndNotTheFirstAccounts() {
        assertThat(search(wrapper(null, OTHER_ACCOUNT)))
            .containsExactlyInAnyOrder(OTHER_ACCOUNT_CHUNK, SHARED_CHUNK);
    }

    @Test
    void testAVendorRunReadsTheSharedChunksOnlyAndNeverFallsThroughToAnAccounts() {
        assertThat(search(wrapper(null, VENDOR)))
            .containsExactly(SHARED_CHUNK);
    }

    /**
     * The chunks written before this axis existed carry {@code shared: true} and nothing else, which is what the
     * startup backfill gives them. Every reader has to keep seeing them or the whole existing corpus disappears.
     */
    @Test
    void testABackfilledChunkStaysVisibleToEveryAccountAndToTheVendor() {
        assertThat(search(wrapper(null, ACCOUNT))).contains(SHARED_CHUNK);
        assertThat(search(wrapper(null, OTHER_ACCOUNT))).contains(SHARED_CHUNK);
        assertThat(search(wrapper(null, VENDOR))).contains(SHARED_CHUNK);
    }

    /**
     * Tags and ownership AND together. The tag is the step's choice and reaches whatever it names; ownership is not
     * nameable by the step, so naming the other account's tag yields nothing of theirs.
     */
    @Test
    void testAStepNamingAnotherAccountsTagStillSeesNothingOfTheirs() {
        FakeVectorStore taggedStore = new FakeVectorStore(
            List.of(
                tagged(ownedChunk(OWN_CHUNK, KNOWLEDGE_BASE_ID, ACCOUNT_ID), "mine"),
                tagged(ownedChunk(OTHER_ACCOUNT_CHUNK, KNOWLEDGE_BASE_ID, OTHER_ACCOUNT_ID), "theirs")));

        KnowledgeBaseVectorStoreWrapper wrapper = new KnowledgeBaseVectorStoreWrapper(
            taggedStore, KNOWLEDGE_BASE_ID, List.of("theirs"), ACCOUNT);

        assertThat(search(wrapper)).isEmpty();
    }

    /**
     * The control for the case above: the same wrapper with the account's OWN tag returns its own chunk, so the empty
     * result there is ownership refusing and not the tag filter matching nothing.
     */
    @Test
    void testAStepNamingItsOwnTagStillSeesItsOwnChunk() {
        FakeVectorStore taggedStore = new FakeVectorStore(
            List.of(
                tagged(ownedChunk(OWN_CHUNK, KNOWLEDGE_BASE_ID, ACCOUNT_ID), "mine"),
                tagged(ownedChunk(OTHER_ACCOUNT_CHUNK, KNOWLEDGE_BASE_ID, OTHER_ACCOUNT_ID), "theirs")));

        KnowledgeBaseVectorStoreWrapper wrapper = new KnowledgeBaseVectorStoreWrapper(
            taggedStore, KNOWLEDGE_BASE_ID, List.of("mine"), ACCOUNT);

        assertThat(search(wrapper)).containsExactly(OWN_CHUNK);
    }

    /**
     * Two tags, which is where an ungrouped OR reassociates. Naming both the account's own tag and the other's must
     * still return only its own chunk; if the tag OR escaped its parentheses the second tag would be ORed against the
     * whole predicate and the other account's chunk would come back.
     */
    @Test
    void testNamingTwoTagsDoesNotLetTheSecondEscapeTheOwnerPredicate() {
        FakeVectorStore taggedStore = new FakeVectorStore(
            List.of(
                tagged(ownedChunk(OWN_CHUNK, KNOWLEDGE_BASE_ID, ACCOUNT_ID), "mine"),
                tagged(ownedChunk(OTHER_ACCOUNT_CHUNK, KNOWLEDGE_BASE_ID, OTHER_ACCOUNT_ID), "theirs")));

        KnowledgeBaseVectorStoreWrapper wrapper = new KnowledgeBaseVectorStoreWrapper(
            taggedStore, KNOWLEDGE_BASE_ID, List.of("mine", "theirs"), ACCOUNT);

        assertThat(search(wrapper)).containsExactly(OWN_CHUNK);
    }

    /**
     * The reassociation above is invisible to a tree-walking evaluator, so it is pinned on the tree as well:
     * {@code PgVectorFilterExpressionConverter} renders the tree flat and adds no parentheses, and JSONPath binds
     * {@code &&} tighter than {@code ||} -- so any OR reachable from an AND without a {@link Filter.Group} between them
     * is a predicate that means something other than what it reads.
     */
    @Test
    void testEveryOrInTheProducedFilterIsParenthesised() {
        KnowledgeBaseVectorStoreWrapper wrapper = new KnowledgeBaseVectorStoreWrapper(
            vectorStore, KNOWLEDGE_BASE_ID, List.of("mine", "theirs"), ACCOUNT);

        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();

        wrapper.similaritySearch(
            SearchRequest.builder()
                .query(" ")
                .topK(10)
                .similarityThreshold(0.0)
                .filterExpression(
                    filterExpressionBuilder
                        .or(filterExpressionBuilder.eq("a", 1), filterExpressionBuilder.eq("b", 2))
                        .build())
                .build());

        assertNoUngroupedOr(vectorStore.lastSearchExpression, false);
    }

    @Test
    void testAnAccountCannotDeleteAnotherAccountsChunkByExpression() {
        KnowledgeBaseVectorStoreWrapper wrapper = wrapper(null, ACCOUNT);

        wrapper.delete(matchAll());

        assertThat(vectorStore.remainingIds()).contains(OTHER_ACCOUNT_CHUNK);
    }

    @Test
    void testAnAccountCannotDeleteASharedChunkByExpression() {
        KnowledgeBaseVectorStoreWrapper wrapper = wrapper(null, ACCOUNT);

        wrapper.delete(matchAll());

        assertThat(vectorStore.remainingIds()).contains(SHARED_CHUNK);
    }

    /**
     * The control for the two cases above. A delete that removed nothing would satisfy both and break the product.
     */
    @Test
    void testAnAccountDeletesItsOwnChunkByExpression() {
        KnowledgeBaseVectorStoreWrapper wrapper = wrapper(null, ACCOUNT);

        wrapper.delete(matchAll());

        assertThat(vectorStore.remainingIds()).doesNotContain(OWN_CHUNK);
    }

    /**
     * The hole the id-list overload used to leave open, pinned specifically.
     *
     * <p>
     * A vector store id names a chunk and constrains nothing else, and no filter can be applied to an id list, so the
     * ids used to reach the store untouched and delete any chunk in the tenant. Both halves are asserted: the call is
     * refused, AND the underlying store is never reached -- an exception thrown after the delegation would read the
     * same in the first half alone.
     */
    @Test
    void testAnAccountCannotDeleteAnotherAccountsChunkById() {
        KnowledgeBaseVectorStoreWrapper wrapper = wrapper(null, ACCOUNT);

        assertThatThrownBy(() -> wrapper.delete(List.of(OTHER_ACCOUNT_CHUNK)))
            .isInstanceOf(UnsupportedOperationException.class);

        assertThat(vectorStore.deletedIdLists).isEmpty();
        assertThat(vectorStore.remainingIds()).contains(OTHER_ACCOUNT_CHUNK);
    }

    @Test
    void testAnAccountCannotDeleteASharedChunkById() {
        KnowledgeBaseVectorStoreWrapper wrapper = wrapper(null, ACCOUNT);

        assertThatThrownBy(() -> wrapper.delete(List.of(SHARED_CHUNK)))
            .isInstanceOf(UnsupportedOperationException.class);

        assertThat(vectorStore.deletedIdLists).isEmpty();
        assertThat(vectorStore.remainingIds()).contains(SHARED_CHUNK);
    }

    @Test
    void testAChunkWrittenByARunWithNoOwnerIsShared() {
        KnowledgeBaseVectorStoreWrapper wrapper = wrapper(null, VENDOR);

        wrapper.add(List.of(new Document("new", "text", new LinkedHashMap<>())));

        Map<String, Object> metadata = addedMetadata();

        assertThat(metadata).containsEntry(METADATA_SHARED, true);
        assertThat(metadata).doesNotContainKey(METADATA_OWNER_ID);
        assertThat(metadata).doesNotContainKey(METADATA_OWNER_TYPE);
    }

    /**
     * The owner is the pair, so a chunk carrying half of it belongs to nobody and is reachable by nobody. Asserted from
     * both sides: the account whose id it names cannot read it, and neither can anyone else.
     *
     * <p>
     * Nothing writes this shape today, which is the point -- the type goes into the encoding while every chunk in
     * existence can still be assumed to mean the one {@code OwnerType}. Once a second constant exists an id alone is
     * ambiguous forever.
     */
    @Test
    void testAChunkCarryingAnOwnerIdWithNoOwnerTypeBelongsToNobody() {
        FakeVectorStore halfOwnedStore = new FakeVectorStore(
            List.of(ownerIdOnlyChunk(OWN_CHUNK, KNOWLEDGE_BASE_ID, ACCOUNT_ID)));

        assertThat(search(new KnowledgeBaseVectorStoreWrapper(halfOwnedStore, KNOWLEDGE_BASE_ID, null, ACCOUNT)))
            .isEmpty();
        assertThat(search(new KnowledgeBaseVectorStoreWrapper(halfOwnedStore, KNOWLEDGE_BASE_ID, null, VENDOR)))
            .isEmpty();
    }

    /**
     * The type is part of the predicate rather than decoration. Two principals of different kinds sharing an id are
     * indistinguishable without it, and this is what fails the day one does.
     */
    @Test
    void testAChunkWhoseOwnerTypeDisagreesIsNotReadByTheAccountSharingItsId() {
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put(METADATA_KNOWLEDGE_BASE_ID, KNOWLEDGE_BASE_ID);
        metadata.put(METADATA_OWNER_ID, ACCOUNT_ID);
        metadata.put(METADATA_OWNER_TYPE, OwnerType.CONNECTED_USER.ordinal() + 1);

        FakeVectorStore otherTypeStore = new FakeVectorStore(
            List.of(new Document(OWN_CHUNK, "text", metadata)));

        assertThat(search(new KnowledgeBaseVectorStoreWrapper(otherTypeStore, KNOWLEDGE_BASE_ID, null, ACCOUNT)))
            .isEmpty();
    }

    @Test
    void testAChunkWrittenByARunWithAnOwnerIsStampedWithItAndIsNotShared() {
        KnowledgeBaseVectorStoreWrapper wrapper = wrapper(null, ACCOUNT);

        wrapper.add(List.of(new Document("new", "text", new LinkedHashMap<>())));

        Map<String, Object> metadata = addedMetadata();

        assertThat(metadata).containsEntry(METADATA_OWNER_ID, ACCOUNT_ID);
        assertThat(metadata).containsEntry(METADATA_OWNER_TYPE, OwnerType.CONNECTED_USER.ordinal());
        assertThat(metadata).doesNotContainKey(METADATA_SHARED);
    }

    /**
     * {@code additionalMetadata} is ordinary expression-enabled workflow input and is merged into a chunk's metadata
     * before it reaches the wrapper, so a step naming the ownership keys there would otherwise stamp another account's
     * chunk and hand itself a permanent read of it.
     */
    @Test
    void testAStepCannotForgeOwnershipThroughSuppliedMetadata() {
        KnowledgeBaseVectorStoreWrapper wrapper = wrapper(null, ACCOUNT);

        Map<String, Object> suppliedMetadata = new LinkedHashMap<>();

        suppliedMetadata.put(METADATA_OWNER_ID, OTHER_ACCOUNT_ID);
        suppliedMetadata.put(METADATA_OWNER_TYPE, OwnerType.CONNECTED_USER.ordinal() + 1);
        suppliedMetadata.put(METADATA_SHARED, true);

        wrapper.add(List.of(new Document("new", "text", suppliedMetadata)));

        Map<String, Object> metadata = addedMetadata();

        assertThat(metadata).containsEntry(METADATA_OWNER_ID, ACCOUNT_ID);
        assertThat(metadata).containsEntry(METADATA_OWNER_TYPE, OwnerType.CONNECTED_USER.ordinal());
        assertThat(metadata).doesNotContainKey(METADATA_SHARED);
    }

    /**
     * A vendor run's insert is unowned in both directions: the shared flag goes on and any owner a step supplied comes
     * off, so a step cannot make the vendor write on an account's behalf either.
     */
    @Test
    void testAVendorRunCannotBeMadeToStampAnOwnerThroughSuppliedMetadata() {
        KnowledgeBaseVectorStoreWrapper wrapper = wrapper(null, VENDOR);

        Map<String, Object> suppliedMetadata = new LinkedHashMap<>();

        suppliedMetadata.put(METADATA_OWNER_ID, OTHER_ACCOUNT_ID);
        suppliedMetadata.put(METADATA_OWNER_TYPE, OwnerType.CONNECTED_USER.ordinal());

        wrapper.add(List.of(new Document("new", "text", suppliedMetadata)));

        Map<String, Object> metadata = addedMetadata();

        assertThat(metadata).containsEntry(METADATA_SHARED, true);
        assertThat(metadata).doesNotContainKey(METADATA_OWNER_ID);
        assertThat(metadata).doesNotContainKey(METADATA_OWNER_TYPE);
    }

    private Map<String, Object> addedMetadata() {
        List<Document> addedDocuments = vectorStore.addedDocuments;

        assertThat(addedDocuments).hasSize(1);

        Document document = addedDocuments.getFirst();

        return document.getMetadata();
    }

    private KnowledgeBaseVectorStoreWrapper wrapper(List<String> tagNames, Optional<Owner> owner) {
        return new KnowledgeBaseVectorStoreWrapper(vectorStore, KNOWLEDGE_BASE_ID, tagNames, owner);
    }

    private static List<String> search(KnowledgeBaseVectorStoreWrapper wrapper) {
        List<Document> documents = wrapper.similaritySearch(
            SearchRequest.builder()
                .query(" ")
                .topK(100)
                .similarityThreshold(0.0)
                .build());

        return documents.stream()
            .map(Document::getId)
            .toList();
    }

    /** A predicate satisfied by every chunk, so that what survives a delete is decided by the wrapper alone. */
    private static Filter.Expression matchAll() {
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();

        return filterExpressionBuilder
            .gte(METADATA_KNOWLEDGE_BASE_ID, 0)
            .build();
    }

    private static Document ownedChunk(String id, long knowledgeBaseId, long ownerId) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put(METADATA_KNOWLEDGE_BASE_ID, knowledgeBaseId);
        metadata.put(METADATA_OWNER_ID, ownerId);
        metadata.put(METADATA_OWNER_TYPE, OwnerType.CONNECTED_USER.ordinal());

        return new Document(id, "text", metadata);
    }

    /**
     * Half an owner: the id with no type. Nothing writes this shape -- the wrapper and the chunker both write the pair
     * -- so it stands for a chunk hand-edited or left behind by an interrupted migration.
     */
    private static Document ownerIdOnlyChunk(String id, long knowledgeBaseId, long ownerId) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put(METADATA_KNOWLEDGE_BASE_ID, knowledgeBaseId);
        metadata.put(METADATA_OWNER_ID, ownerId);

        return new Document(id, "text", metadata);
    }

    private static Document sharedChunk(String id, long knowledgeBaseId) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put(METADATA_KNOWLEDGE_BASE_ID, knowledgeBaseId);
        metadata.put(METADATA_SHARED, true);

        return new Document(id, "text", metadata);
    }

    private static Document tagged(Document document, String tagName) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());

        metadata.put(METADATA_TAG_NAMES + "_" + tagName, true);

        return new Document(document.getId(), document.getText(), metadata);
    }

    private static void assertNoUngroupedOr(Filter.Operand operand, boolean underBooleanOperator) {
        if (operand instanceof Filter.Group group) {
            assertNoUngroupedOr(group.content(), false);

            return;
        }

        if (!(operand instanceof Filter.Expression expression)) {
            return;
        }

        Filter.ExpressionType type = expression.type();

        assertThat(type == Filter.ExpressionType.OR && underBooleanOperator)
            .withFailMessage(
                "An OR reachable from an AND without a Filter.Group renders as flat JSONPath and reassociates: " +
                    operand)
            .isFalse();

        boolean booleanOperator = type == Filter.ExpressionType.AND || type == Filter.ExpressionType.OR;

        assertNoUngroupedOr(expression.left(), booleanOperator);

        if (expression.right() != null) {
            assertNoUngroupedOr(expression.right(), booleanOperator);
        }
    }

    /**
     * A store that actually applies the filter it is handed, so a test names chunks rather than expression shapes.
     */
    private static final class FakeVectorStore implements VectorStore {

        private final List<Document> documents = new ArrayList<>();
        private final List<Document> addedDocuments = new ArrayList<>();
        private final List<List<String>> deletedIdLists = new ArrayList<>();

        private Filter.Expression lastSearchExpression;

        private FakeVectorStore(List<Document> documents) {
            this.documents.addAll(documents);
        }

        @Override
        public void add(List<Document> documents) {
            addedDocuments.addAll(documents);
            this.documents.addAll(documents);
        }

        @Override
        public void delete(List<String> idList) {
            deletedIdLists.add(idList);

            documents.removeIf(document -> idList.contains(document.getId()));
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            documents.removeIf(document -> matches(filterExpression, document.getMetadata()));
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            lastSearchExpression = request.getFilterExpression();

            return documents.stream()
                .filter(document -> matches(request.getFilterExpression(), document.getMetadata()))
                .toList();
        }

        private List<String> remainingIds() {
            return documents.stream()
                .map(Document::getId)
                .toList();
        }
    }

    /**
     * A generic {@link Filter.Expression} interpreter, written against the expression model rather than against the
     * wrapper, so it cannot be wrong in the same direction as the code it checks.
     */
    private static boolean matches(Filter.Operand operand, Map<String, Object> metadata) {
        if (operand == null) {
            return true;
        }

        if (operand instanceof Filter.Group group) {
            return matches(group.content(), metadata);
        }

        Filter.Expression expression = (Filter.Expression) operand;

        Filter.ExpressionType type = expression.type();

        if (type == Filter.ExpressionType.AND) {
            return matches(expression.left(), metadata) && matches(expression.right(), metadata);
        }

        if (type == Filter.ExpressionType.OR) {
            return matches(expression.left(), metadata) || matches(expression.right(), metadata);
        }

        Filter.Key key = (Filter.Key) expression.left();
        Filter.Value value = (Filter.Value) expression.right();

        Object actual = metadata.get(key.key());

        if (type == Filter.ExpressionType.EQ) {
            return equalsNumberAware(actual, value.value());
        }

        if (type == Filter.ExpressionType.GTE) {
            if (!(actual instanceof Number actualNumber) || !(value.value() instanceof Number expectedNumber)) {
                return false;
            }

            return actualNumber.doubleValue() >= expectedNumber.doubleValue();
        }

        throw new UnsupportedOperationException("Unsupported expression type in the test evaluator: " + type);
    }

    private static boolean equalsNumberAware(Object actual, Object expected) {
        if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
            return actualNumber.doubleValue() == expectedNumber.doubleValue();
        }

        return actual != null && actual.equals(expected);
    }
}
