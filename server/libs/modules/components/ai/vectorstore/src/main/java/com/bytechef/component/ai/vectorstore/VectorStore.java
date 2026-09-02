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

package com.bytechef.component.ai.vectorstore;

import static com.bytechef.component.ai.vectorstore.constant.VectorStoreConstants.ADDITIONAL_METADATA;
import static com.bytechef.component.ai.vectorstore.constant.VectorStoreConstants.METADATA_FILTER;
import static com.bytechef.component.ai.vectorstore.constant.VectorStoreConstants.QUERY;
import static com.bytechef.component.ai.vectorstore.constant.VectorStoreConstants.SIMILARITY_THRESHOLD;
import static com.bytechef.component.ai.vectorstore.constant.VectorStoreConstants.TOP_K;

import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * @author Monika Kušter
 */
@FunctionalInterface
public interface VectorStore {

    org.springframework.ai.vectorstore.VectorStore createVectorStore(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel);

    /**
     * Context-carrying form. Defaults to the context-free one, so the thirteen vector store components that address a
     * store the vendor configured need no change and this interface stays functional; only a store whose identity
     * belongs to an account overrides it, to resolve the owner the run acts for before addressing anything.
     *
     * @param inputParameters      the input parameters of the vector store
     * @param connectionParameters the connection parameters
     * @param embeddingModel       the embedding model, or {@code null} where the component configures one globally
     * @param context              the component invocation context, or {@code null} when the caller holds none
     * @return the vector store
     */
    default org.springframework.ai.vectorstore.VectorStore createVectorStore(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel, Context context) {

        return createVectorStore(inputParameters, connectionParameters, embeddingModel);
    }

    default void load(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
        DocumentReader documentReader, List<DocumentTransformer> documentTransformers) {

        load(inputParameters, connectionParameters, embeddingModel, documentReader, documentTransformers, null);
    }

    default void load(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
        DocumentReader documentReader, List<DocumentTransformer> documentTransformers, Context context) {

        org.springframework.ai.vectorstore.VectorStore vectorStore = createVectorStore(
            inputParameters, connectionParameters, embeddingModel, context);

        List<Document> documents = documentReader.read();

        for (DocumentTransformer documentTransformer : documentTransformers) {
            documents = documentTransformer.transform(documents);
        }

        List<Map<String, Object>> metadataList = inputParameters.getList(
            ADDITIONAL_METADATA, new TypeReference<>() {});

        if (!metadataList.isEmpty()) {
            Map<String, Object> additionalMetadata = new HashMap<>();

            for (Map<String, Object> metadataEntry : metadataList) {
                additionalMetadata.putAll(metadataEntry);
            }

            documents = documents.stream()
                .map(document -> {
                    Map<String, Object> mergedMetadata = new HashMap<>(document.getMetadata());

                    mergedMetadata.putAll(additionalMetadata);

                    return new Document(document.getId(), document.getText(), mergedMetadata);
                })
                .toList();
        }

        vectorStore.add(documents);
    }

    default void delete(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel) {

        delete(inputParameters, connectionParameters, embeddingModel, null);
    }

    default void delete(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel, Context context) {

        org.springframework.ai.vectorstore.VectorStore vectorStore = createVectorStore(
            inputParameters, connectionParameters, embeddingModel, context);

        List<Map<String, Object>> metadataFilters = inputParameters.getList(
            METADATA_FILTER, new TypeReference<>() {});

        Optional<Filter.Expression> filterExpression = Optional.empty();
        if (metadataFilters != null && !metadataFilters.isEmpty()) {
            filterExpression = getFilterExpression(metadataFilters);

        }

        filterExpression.ifPresent(vectorStore::delete);
    }

    default List<Document> search(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel) {

        return search(inputParameters, connectionParameters, embeddingModel, null);
    }

    default List<Document> search(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel, Context context) {

        org.springframework.ai.vectorstore.VectorStore vectorStore = createVectorStore(
            inputParameters, connectionParameters, embeddingModel, context);

        List<Map<String, Object>> metadata = inputParameters.getList(
            METADATA_FILTER, new TypeReference<>() {});

        Optional<Filter.Expression> filterExpression = Optional.empty();
        if (metadata != null && !metadata.isEmpty()) {
            filterExpression = getFilterExpression(metadata);
        }

        SearchRequest searchRequest = SearchRequest.builder()
            .query(inputParameters.getRequiredString(QUERY))
            .filterExpression(filterExpression.orElse(null))
            .topK(inputParameters.getInteger(TOP_K, SearchRequest.DEFAULT_TOP_K))
            .similarityThreshold(
                inputParameters.getDouble(SIMILARITY_THRESHOLD, SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL))
            .build();

        return vectorStore.similaritySearch(searchRequest);
    }

    default void update(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
        DocumentReader documentReader, List<DocumentTransformer> documentTransformers) {

        update(inputParameters, connectionParameters, embeddingModel, documentReader, documentTransformers, null);
    }

    default void update(
        Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
        DocumentReader documentReader, List<DocumentTransformer> documentTransformers, Context context) {

        delete(inputParameters, connectionParameters, embeddingModel, context);
        load(inputParameters, connectionParameters, embeddingModel, documentReader, documentTransformers, context);
    }

    private static Optional<Filter.Expression> getFilterExpression(List<Map<String, Object>> metadataFilters) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filterExpression = null;

        for (Map<String, Object> metadataFilter : metadataFilters) {
            FilterExpressionBuilder.Op groupExpression = null;

            for (Map.Entry<String, Object> entry : metadataFilter.entrySet()) {
                FilterExpressionBuilder.Op condition = builder.eq(entry.getKey(), entry.getValue());

                groupExpression = groupExpression == null ? condition : builder.and(groupExpression, condition);
            }

            if (groupExpression != null) {
                filterExpression = filterExpression == null
                    ? groupExpression
                    : builder.or(filterExpression, groupExpression);
            }
        }

        if (filterExpression != null) {
            return Optional.of(filterExpression.build());
        }

        return Optional.empty();
    }
}
