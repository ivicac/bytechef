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

package com.bytechef.component.ai.vectorstore.knowledgebase.action;

import static com.bytechef.component.ai.vectorstore.constant.VectorStoreConstants.METADATA_FILTER;
import static com.bytechef.component.ai.vectorstore.constant.VectorStoreConstants.METADATA_FILTER_PROPERTY;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_ID;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.QUERY;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.SIMILARITY_THRESHOLD;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.TAG_NAMES;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.TOP_K;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.platform.component.definition.VectorStoreComponentDefinition.SEARCH;

import com.bytechef.component.ai.vectorstore.knowledgebase.util.KnowledgeBaseOptionsUtils;
import com.bytechef.component.ai.vectorstore.knowledgebase.util.KnowledgeBaseVectorStoreWrapper;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.MultipleConnectionsPerformFunction;
import com.bytechef.platform.component.owner.OwnerResolution;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentTagService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Search action for querying the internal knowledge base vector store. Supports three search modes:
 * <ul>
 * <li>Tag-Only Search: Direct retrieval by tags (no vector search)</li>
 * <li>Vector Search Only: Semantic search without tags</li>
 * <li>Combined Tag Filtering + Vector Search: Filter by tags, then semantic search</li>
 * </ul>
 *
 * @author Ivica Cardic
 */
public final class KnowledgeBaseSearchAction {

    private KnowledgeBaseSearchAction() {
    }

    public static ActionDefinition of(
        VectorStore vectorStore, KnowledgeBaseService knowledgeBaseService,
        KnowledgeBaseDocumentTagService knowledgeBaseDocumentTagService,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return action(SEARCH)
            .title("Search Data")
            .description(
                "Query data from the knowledge base. Supports three modes: tag-only search, vector search, " +
                    "or combined tag filtering with vector search.")
            .properties(
                integer(KNOWLEDGE_BASE_ID)
                    .label("Knowledge Base")
                    .description("The knowledge base to search.")
                    .options(
                        KnowledgeBaseOptionsUtils.knowledgeBaseActionOptions(
                            knowledgeBaseService, ownerResolverProvider))
                    .required(true),
                string(QUERY)
                    .label("Query")
                    .description(
                        "The search query for semantic similarity search. Leave empty for filter-only search.")
                    .required(false),
                array(TAG_NAMES)
                    .label("Tags")
                    .description(
                        "Filter results by tags. Documents with ANY of the selected tags will be returned (OR logic).")
                    .items(string())
                    .options(
                        KnowledgeBaseOptionsUtils.tagActionOptions(
                            knowledgeBaseDocumentTagService, knowledgeBaseService, ownerResolverProvider))
                    .required(false),
                METADATA_FILTER_PROPERTY,
                integer(TOP_K)
                    .label("Top K")
                    .description("Maximum number of results to return.")
                    .defaultValue(10)
                    .required(false),
                number(SIMILARITY_THRESHOLD)
                    .label("Similarity Threshold")
                    .description(
                        "Minimum similarity score (0.0 to 1.0). Only results with similarity above this " +
                            "threshold will be returned.")
                    .defaultValue(0.0)
                    .required(false))
            .output()
            .perform((MultipleConnectionsPerformFunction) (
                inputParameters, componentConnections, extensions, context) -> perform(
                    inputParameters, vectorStore, knowledgeBaseService, ownerResolverProvider,
                    (ActionContextAware) context));
    }

    private static Object perform(
        Parameters inputParameters, VectorStore vectorStore, KnowledgeBaseService knowledgeBaseService,
        ObjectProvider<OwnerResolver> ownerResolverProvider, ActionContextAware actionContextAware) {

        Long knowledgeBaseId = inputParameters.getRequiredLong(KNOWLEDGE_BASE_ID);

        // The same owner admits the knowledge base and then scopes the chunks inside it. Resolved once and reused
        // rather than derived twice, so the chunks this search may read cannot drift from the knowledge base the
        // admission gate approved.
        Optional<Owner> owner = OwnerResolution.resolve(actionContextAware, ownerResolverProvider);

        // Throws before the vector store is ever wrapped, so an id outside this run's pool -- or one the caller does
        // not own -- never reaches it.
        KnowledgeBaseOptionsUtils.resolveKnowledgeBase(knowledgeBaseService, knowledgeBaseId, owner);

        String query = inputParameters.getString(QUERY);
        List<String> tagNames = inputParameters.getList(TAG_NAMES, String.class);
        List<Map<String, Object>> metadataFilters = inputParameters.getList(METADATA_FILTER, new TypeReference<>() {});
        int topK = inputParameters.getInteger(TOP_K, 10);
        double similarityThreshold = inputParameters.getDouble(SIMILARITY_THRESHOLD, 0.0);

        KnowledgeBaseVectorStoreWrapper wrappedVectorStore = new KnowledgeBaseVectorStoreWrapper(
            vectorStore, knowledgeBaseId, null, owner);

        boolean hasQuery = query != null && !query.isBlank();

        Expression combinedFilter = buildCombinedFilter(tagNames, metadataFilters);

        if (!hasQuery && combinedFilter == null) {
            return List.of();
        }

        if (!hasQuery) {
            return searchByFilterOnly(wrappedVectorStore, combinedFilter, topK);
        }

        if (combinedFilter == null) {
            return searchByVectorOnly(wrappedVectorStore, query, topK, similarityThreshold);
        }

        return searchWithFilter(wrappedVectorStore, query, combinedFilter, topK, similarityThreshold);
    }

    private static List<Document> searchByFilterOnly(VectorStore vectorStore, Expression filter, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
            .query("")
            .topK(topK)
            .similarityThreshold(0.0)
            .filterExpression(filter)
            .build();

        return vectorStore.similaritySearch(searchRequest);
    }

    private static List<Document> searchByVectorOnly(
        VectorStore vectorStore, String query, int topK, double similarityThreshold) {

        SearchRequest searchRequest = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(similarityThreshold)
            .build();

        return vectorStore.similaritySearch(searchRequest);
    }

    private static List<Document> searchWithFilter(
        VectorStore vectorStore, String query, Expression filter, int topK, double similarityThreshold) {

        SearchRequest searchRequest = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(similarityThreshold)
            .filterExpression(filter)
            .build();

        return vectorStore.similaritySearch(searchRequest);
    }

    private static Expression buildCombinedFilter(
        List<String> tagNames, List<Map<String, Object>> metadataFilters) {

        Expression tagFilter = (tagNames != null && !tagNames.isEmpty())
            ? KnowledgeBaseVectorStoreWrapper.buildTagFilter(tagNames) : null;

        Expression metadataFilter = buildMetadataFilter(metadataFilters);

        if (tagFilter == null) {
            return metadataFilter;
        }

        if (metadataFilter == null) {
            return tagFilter;
        }

        // Both sides parenthesised: the tag filter is an OR chain and the converter emits flat JSONPath, in which
        // "&&" binds tighter than "||" -- so an ungrouped AND would silently become "tag_a || (tag_b && metadata)".
        return new Filter.Expression(
            Filter.ExpressionType.AND, new Filter.Group(tagFilter), new Filter.Group(metadataFilter));
    }

    private static Expression buildMetadataFilter(List<Map<String, Object>> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return null;
        }

        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op result = null;

        for (Map<String, Object> group : metadataFilters) {
            FilterExpressionBuilder.Op groupExpression = null;

            for (Map.Entry<String, Object> entry : group.entrySet()) {
                FilterExpressionBuilder.Op condition = builder.eq(entry.getKey(), entry.getValue());

                groupExpression = groupExpression == null ? condition : builder.and(groupExpression, condition);
            }

            if (groupExpression != null) {
                result = result == null ? groupExpression : builder.or(result, groupExpression);
            }
        }

        return result == null ? null : result.build();
    }

}
