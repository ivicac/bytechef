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

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * A wrapper around the system's VectorStore that filters queries by knowledge_base_id and by the account the run acts
 * for.
 *
 * <p>
 * The second axis of ownership lives HERE and nowhere else. This wrapper is the only route the component has to the
 * store, so binding the run's owner into every read, write and insert made in it keeps the enforcement count at one,
 * exactly as {@code DataTableRef} does for data tables. Nothing outside this class may build an owner filter.
 *
 * <p>
 * Ownership is not tags. A tag reaches the same filter but is chosen by the workflow step, so a step that omits one
 * sees every chunk and a step naming another account's tag sees that account's. Tags organize; this isolates. The two
 * are ANDed and neither replaces the other.
 *
 * <p>
 * Reads and writes are deliberately different predicates. A chunk belonging to no account is every account's to read
 * and nobody's to change, so a read admits {@code (owner_id == <run owner> AND owner_type == <run owner type>) OR
 * shared == true} while a write admits the run's own chunks alone. An owner is always the pair and never the id on its
 * own, matching the two columns a data table row carries.
 *
 * @author Ivica Cardic
 */
public class KnowledgeBaseVectorStoreWrapper implements VectorStore {

    private final VectorStore vectorStore;
    private final Long knowledgeBaseId;
    private final List<String> tagNames;
    private final Optional<Owner> owner;

    /**
     * There is one constructor, and the owner is not optional to SUPPLY -- only to hold. An overload defaulting it
     * would let a construction site omit the account it acts for and silently write that account's chunks as shared,
     * which is the failure this whole axis exists to prevent, so every site has to state which of the two it is.
     */
    @SuppressFBWarnings("EI")
    public KnowledgeBaseVectorStoreWrapper(
        VectorStore vectorStore, Long knowledgeBaseId, List<String> tagNames, Optional<Owner> owner) {

        this.vectorStore = vectorStore;
        this.knowledgeBaseId = knowledgeBaseId;
        this.tagNames = tagNames == null ? null : List.copyOf(tagNames);
        this.owner = owner;
    }

    /**
     * Stamps the run's ownership onto every chunk, overwriting whatever the caller supplied.
     *
     * <p>
     * Overwriting rather than defaulting, because the metadata reaching this point has already been merged with the
     * step's own {@code additionalMetadata}, which is ordinary expression-enabled workflow input: a step naming
     * {@code owner_id} there would otherwise stamp another account's chunk and hand itself a permanent read of it.
     */
    @Override
    public void add(List<Document> documents) {
        List<Document> wrappedDocuments = documents.stream()
            .map(document -> {
                Map<String, Object> metadata = new HashMap<>(document.getMetadata());

                metadata.put(METADATA_KNOWLEDGE_BASE_ID, knowledgeBaseId);

                if (owner.isPresent()) {
                    Owner curOwner = owner.get();

                    OwnerType ownerType = curOwner.type();

                    metadata.remove(METADATA_SHARED);
                    metadata.put(METADATA_OWNER_ID, curOwner.id());
                    metadata.put(METADATA_OWNER_TYPE, ownerType.ordinal());
                } else {
                    metadata.remove(METADATA_OWNER_ID);
                    metadata.remove(METADATA_OWNER_TYPE);
                    metadata.put(METADATA_SHARED, true);
                }

                return new Document(document.getId(), document.getText(), metadata);
            })
            .toList();

        vectorStore.add(wrappedDocuments);
    }

    /**
     * Refused, always.
     *
     * <p>
     * A vector store id names a chunk and constrains nothing else -- not the knowledge base, not the account -- and a
     * {@code Filter.Expression} cannot be applied to an id list, so this overload used to hand raw ids straight to the
     * store and delete any chunk in the tenant, including another account's. The store API exposes no read-by-id with
     * which the ids could be resolved and checked instead, so the only honest answer is to refuse: a delete that cannot
     * be scoped must not run. Callers that need to delete scope their deletion with {@link #delete(Filter.Expression)},
     * which carries the write predicate.
     */
    @Override
    public void delete(List<String> idList) {
        throw new UnsupportedOperationException(
            "Deleting knowledge base chunks by vector store id is not supported: an id carries no knowledge base and "
                + "no owner, so such a delete cannot be scoped. Delete by filter expression instead.");
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        Filter.Expression combinedExpression = and(
            new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(METADATA_KNOWLEDGE_BASE_ID),
                new Filter.Value(knowledgeBaseId)),
            filterExpression);

        combinedExpression = and(combinedExpression, buildWriteOwnerFilter());

        vectorStore.delete(combinedExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();

        Filter.Expression combinedFilter = filterExpressionBuilder
            .eq(METADATA_KNOWLEDGE_BASE_ID, knowledgeBaseId)
            .build();

        combinedFilter = and(combinedFilter, buildReadOwnerFilter());

        if (tagNames != null && !tagNames.isEmpty()) {
            combinedFilter = and(combinedFilter, buildTagFilter(tagNames));
        }

        if (request.getFilterExpression() != null) {
            combinedFilter = and(combinedFilter, request.getFilterExpression());
        }

        SearchRequest filteredRequest = SearchRequest.builder()
            .query(request.getQuery())
            .topK(request.getTopK())
            .similarityThreshold(request.getSimilarityThreshold())
            .filterExpression(combinedFilter)
            .build();

        return vectorStore.similaritySearch(filteredRequest);
    }

    /**
     * Builds an OR filter expression on per-tag boolean flags ({@code tag_names_NAME: true}). Documents that have ANY
     * of the specified tag names in their metadata will satisfy the expression.
     */
    public static Filter.Expression buildTagFilter(List<String> tagNames) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        if (tagNames == null || tagNames.isEmpty()) {
            return null;
        }

        FilterExpressionBuilder.Op filter = b.eq(METADATA_TAG_NAMES + "_" + tagNames.get(0), true);

        for (int i = 1; i < tagNames.size(); i++) {
            filter = b.or(filter, b.eq(METADATA_TAG_NAMES + "_" + tagNames.get(i), true));
        }

        return filter.build();
    }

    /**
     * Combines two predicates, parenthesising both.
     *
     * <p>
     * The parentheses are load-bearing, not decoration. {@code PgVectorFilterExpressionConverter} renders the
     * expression tree as flat JSONPath and adds no grouping of its own, and JSONPath binds {@code &&} tighter than
     * {@code ||} exactly as SQL does. An OR ANDed in without a {@link Filter.Group} therefore reassociates:
     * {@code knowledge_base_id == 7 && owner_id == 42 || shared == true} admits every shared chunk in every knowledge
     * base, which turns the isolation this class exists for into its opposite. The same reassociation is what would
     * otherwise let a step naming two tags read another account's chunks, since the tag filter is an OR chain too.
     */
    private static Filter.Expression and(Filter.Expression left, Filter.Expression right) {
        return new Filter.Expression(
            Filter.ExpressionType.AND, new Filter.Group(left), new Filter.Group(right));
    }

    /**
     * Combines two predicates disjunctively, parenthesising both for the same reason {@link #and} does: this OR is
     * itself ANDed into the knowledge base predicate one frame up, and an unparenthesised operand would reassociate
     * there.
     */
    private static Filter.Expression or(Filter.Expression left, Filter.Expression right) {
        return new Filter.Expression(
            Filter.ExpressionType.OR, new Filter.Group(left), new Filter.Group(right));
    }

    /**
     * The run's own chunks plus the ones belonging to nobody. A run with no owner sees the shared ones alone and never
     * falls through to an account's.
     */
    private Filter.Expression buildReadOwnerFilter() {
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();

        if (owner.isEmpty()) {
            return filterExpressionBuilder
                .eq(METADATA_SHARED, true)
                .build();
        }

        return or(
            buildOwnedFilter(),
            filterExpressionBuilder
                .eq(METADATA_SHARED, true)
                .build());
    }

    /**
     * The run's own chunks alone -- deliberately narrower than the read predicate, so a shared chunk is every account's
     * to read and nobody's to delete. A run with no owner is the vendor, whose own chunks are the shared ones.
     */
    private Filter.Expression buildWriteOwnerFilter() {
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();

        if (owner.isEmpty()) {
            return filterExpressionBuilder
                .eq(METADATA_SHARED, true)
                .build();
        }

        return buildOwnedFilter();
    }

    /**
     * An owner is the pair, never the id alone.
     *
     * <p>
     * {@code OwnerType} has one constant today, so the type narrows nothing yet and this reads as ceremony. It is not:
     * chunks are written once and read for as long as they exist, so the day a second constant is added every chunk
     * written with an id alone becomes ambiguous between the two kinds with nothing left to disambiguate it. Both keys
     * are written together by {@link #add(List)} and both are asked for here, so the pair can never come apart.
     */
    private Filter.Expression buildOwnedFilter() {
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();

        Owner curOwner = owner.orElseThrow();

        OwnerType ownerType = curOwner.type();

        return and(
            filterExpressionBuilder
                .eq(METADATA_OWNER_ID, curOwner.id())
                .build(),
            filterExpressionBuilder
                .eq(METADATA_OWNER_TYPE, ownerType.ordinal())
                .build());
    }

    @Override
    public String getName() {
        return "KnowledgeBase-" + knowledgeBaseId;
    }
}
