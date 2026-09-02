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

import static com.bytechef.component.ai.vectorstore.constant.VectorStoreConstants.ADDITIONAL_METADATA;
import static com.bytechef.component.ai.vectorstore.constant.VectorStoreConstants.METADATA_FILTER;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.IS_MULTIPLE;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_DOCUMENT_ID;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_ID;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.QUERY;
import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.TAG_NAMES;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.platform.component.definition.VectorStoreComponentDefinition.VECTOR_STORE;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_ENVIRONMENT_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_DOCUMENT_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_ID;

import com.bytechef.component.ai.vectorstore.VectorStore;
import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.definition.ai.agent.VectorStoreFunction;
import com.bytechef.platform.component.owner.OwnerResolution;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocumentChunk;
import com.bytechef.platform.knowledgebase.file.storage.KnowledgeBaseFileStorage;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentChunkService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentTagService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Knowledge Base VectorStore cluster element for AI agent integration.
 *
 * @author Ivica Cardic
 */
public final class KnowledgeBaseVectorStore {

    private KnowledgeBaseVectorStore() {
    }

    public static ClusterElementDefinition<VectorStoreFunction> of(
        org.springframework.ai.vectorstore.VectorStore vectorStore,
        KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService,
        KnowledgeBaseFileStorage knowledgeBaseFileStorage,
        KnowledgeBaseService knowledgeBaseService, KnowledgeBaseDocumentTagService knowledgeBaseDocumentTagService,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        VectorStore kbVectorStore = createVectorStore(
            knowledgeBaseDocumentChunkService, knowledgeBaseDocumentService, knowledgeBaseFileStorage,
            knowledgeBaseService, vectorStore, ownerResolverProvider);

        return ComponentDsl.<VectorStoreFunction>clusterElement(VECTOR_STORE)
            .title("Knowledge Base VectorStore")
            .description("Knowledge Base VectorStore.")
            .type(VectorStoreFunction.VECTOR_STORE)
            .properties(
                ComponentDsl.integer(KNOWLEDGE_BASE_ID)
                    .label("Knowledge Base")
                    .description("The knowledge base to retrieve documents from.")
                    .options(
                        KnowledgeBaseOptionsUtils.knowledgeBaseOptions(
                            knowledgeBaseService, ownerResolverProvider))
                    .required(true),
                array(TAG_NAMES)
                    .label("Tags")
                    .description(
                        "Filter results by tags. Documents with ANY of the selected tags will be returned (OR logic).")
                    .items(string())
                    .options(
                        KnowledgeBaseOptionsUtils.tagOptions(
                            knowledgeBaseDocumentTagService, knowledgeBaseService, ownerResolverProvider))
                    .optionsLookupDependsOn(KNOWLEDGE_BASE_ID)
                    .required(false))
            // The FOUR-argument createVectorStore, deliberately: the three-argument form takes the knowledge base id
            // straight from workflow input and wraps it unchecked, so calling it here would discard the context one
            // frame after it arrived and leave the override below unreachable behind a green suite.
            .object(() -> (
                inputParameters, connectionParameters, extensions, componentConnections,
                context) -> kbVectorStore.createVectorStore(
                    inputParameters, ParametersFactory.create(connectionParameters), null, context));
    }

    public static VectorStore createVectorStore(
        KnowledgeBaseService knowledgeBaseService, org.springframework.ai.vectorstore.VectorStore vectorStore,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return new VectorStoreImpl(null, null, null, knowledgeBaseService, vectorStore, ownerResolverProvider);
    }

    public static VectorStore createVectorStore(
        KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, KnowledgeBaseFileStorage knowledgeBaseFileStorage,
        KnowledgeBaseService knowledgeBaseService, org.springframework.ai.vectorstore.VectorStore vectorStore,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return new VectorStoreImpl(
            knowledgeBaseDocumentChunkService, knowledgeBaseDocumentService, knowledgeBaseFileStorage,
            knowledgeBaseService, vectorStore, ownerResolverProvider);
    }

    private static String deriveDocumentName(List<Document> documents, Parameters inputParameters) {
        if (!documents.isEmpty()) {
            Document document = documents.getLast();

            Map<String, Object> metadata = document.getMetadata();

            Object filename = metadata.get("filename");

            if (filename != null) {
                return filename.toString();
            }
        }

        Map<String, Object> additionalMetadata = inputParameters.getMap(ADDITIONAL_METADATA, new TypeReference<>() {});

        if (additionalMetadata != null) {
            Object filename = additionalMetadata.get("filename");

            if (filename != null) {
                return filename.toString();
            }
        }

        return "Workflow Import";
    }

    private static class VectorStoreImpl implements VectorStore {

        private final org.springframework.ai.vectorstore.VectorStore vectorStore;
        private final KnowledgeBaseService knowledgeBaseService;
        private final KnowledgeBaseDocumentService knowledgeBaseDocumentService;
        private final KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService;
        private final KnowledgeBaseFileStorage knowledgeBaseFileStorage;
        private final ObjectProvider<OwnerResolver> ownerResolverProvider;

        public VectorStoreImpl(
            KnowledgeBaseDocumentChunkService knowledgeBaseDocumentChunkService,
            KnowledgeBaseDocumentService knowledgeBaseDocumentService,
            KnowledgeBaseFileStorage knowledgeBaseFileStorage, KnowledgeBaseService knowledgeBaseService,
            org.springframework.ai.vectorstore.VectorStore vectorStore,
            ObjectProvider<OwnerResolver> ownerResolverProvider) {

            this.knowledgeBaseDocumentChunkService = knowledgeBaseDocumentChunkService;
            this.knowledgeBaseDocumentService = knowledgeBaseDocumentService;
            this.knowledgeBaseFileStorage = knowledgeBaseFileStorage;
            this.knowledgeBaseService = knowledgeBaseService;
            this.ownerResolverProvider = ownerResolverProvider;
            this.vectorStore = vectorStore;
        }

        @Override
        public org.springframework.ai.vectorstore.VectorStore createVectorStore(
            Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel) {

            return createVectorStore(inputParameters, connectionParameters, embeddingModel, null);
        }

        /**
         * The read gate. {@code knowledgeBaseId} is ordinary workflow input -- an integer property, expression-enabled
         * like any other -- so wrapping it unresolved addressed whatever id the run named, in any pool and for any
         * account. The wrapper is built from the RESOLVED knowledge base rather than from the input, so a refusal
         * cannot be stepped over.
         */
        @Override
        public org.springframework.ai.vectorstore.VectorStore createVectorStore(
            Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
            @Nullable Context context) {

            Resolution resolution = resolve(inputParameters, context);

            KnowledgeBase knowledgeBase = resolution.knowledgeBase();

            List<String> tagNames = inputParameters.getList(TAG_NAMES, String.class);

            return new KnowledgeBaseVectorStoreWrapper(
                vectorStore, knowledgeBase.getId(), tagNames, resolution.owner());
        }

        @Override
        public void load(
            Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
            DocumentReader documentReader, List<DocumentTransformer> documentTransformers,
            @Nullable Context context) {

            Resolution resolution = resolve(inputParameters, context);

            KnowledgeBase knowledgeBase = resolution.knowledgeBase();

            Long knowledgeBaseId = knowledgeBase.getId();

            org.springframework.ai.vectorstore.VectorStore wrappedVectorStore =
                new KnowledgeBaseVectorStoreWrapper(vectorStore, knowledgeBaseId, null, resolution.owner());

            List<Document> documents = documentReader.read();

            for (DocumentTransformer documentTransformer : documentTransformers) {
                documents = documentTransformer.transform(documents);
            }

            Long existingDocumentId = inputParameters.getLong(KNOWLEDGE_BASE_DOCUMENT_ID);

            KnowledgeBaseDocument knowledgeBaseDocument;

            if (existingDocumentId != null) {
                // The document id is a parameter of its own, so passing the knowledge base gate above says nothing
                // about it. Without this the step flipped any document row in the tenant to PROCESSING and hung its
                // new chunks off it. The WRITE rule, because those chunks are stamped with this run's owner below:
                // admitting a document this run does not own would move that document's content into this account.
                knowledgeBaseDocument = KnowledgeBaseOptionsUtils.requireWritableKnowledgeBaseDocument(
                    knowledgeBaseDocumentService, knowledgeBaseId, existingDocumentId, resolution.owner());

                knowledgeBaseDocument.setStatus(KnowledgeBaseDocument.STATUS_PROCESSING);
            } else {
                knowledgeBaseDocument = new KnowledgeBaseDocument();
                knowledgeBaseDocument.setKnowledgeBaseId(knowledgeBaseId);
                knowledgeBaseDocument.setName(deriveDocumentName(documents, inputParameters));
                knowledgeBaseDocument.setStatus(KnowledgeBaseDocument.STATUS_PROCESSING);

                // This path writes its chunks inline through the wrapper, which stamps the owner itself, so the column
                // changes nothing today. It is set anyway because the row outlives this step: a later re-chunk runs
                // through the asynchronous worker, which has only the row to read the account off.
                knowledgeBaseDocument.setOwner(
                    resolution.owner()
                        .orElse(null));
            }

            knowledgeBaseDocument = knowledgeBaseDocumentService.saveKnowledgeBaseDocument(knowledgeBaseDocument);

            long knowledgeBaseDocumentId = knowledgeBaseDocument.getId();

            try {
                for (Document document : documents) {
                    KnowledgeBaseDocumentChunk knowledgeBaseDocumentChunk = new KnowledgeBaseDocumentChunk();

                    knowledgeBaseDocumentChunk.setKnowledgeBaseDocumentId(knowledgeBaseDocumentId);

                    knowledgeBaseDocumentChunk = knowledgeBaseDocumentChunkService.saveKnowledgeBaseDocumentChunk(
                        knowledgeBaseDocumentChunk);

                    long knowledgeBaseDocumentChunkId = knowledgeBaseDocumentChunk.getId();

                    Map<String, Object> metadata = new HashMap<>(document.getMetadata());

                    metadata.put(METADATA_ENVIRONMENT_ID, knowledgeBase.getEnvironmentId());
                    metadata.put(METADATA_KNOWLEDGE_BASE_ID, knowledgeBaseId);
                    metadata.put(METADATA_KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId);
                    metadata.put(METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID, knowledgeBaseDocumentChunkId);

                    Map<String, Object> userMetadata = inputParameters.getMap(
                        ADDITIONAL_METADATA, new TypeReference<>() {});

                    if (userMetadata != null) {
                        metadata.putAll(userMetadata);
                    }

                    String text = document.getText();

                    if (text != null) {
                        text = text.replace("\0", "");
                    }

                    Document enrichedDocument = new Document(document.getId(), text, metadata);

                    wrappedVectorStore.add(List.of(enrichedDocument));

                    knowledgeBaseDocumentChunk.setVectorStoreId(enrichedDocument.getId());

                    FileEntry chunkContent =
                        knowledgeBaseFileStorage.storeChunkContent(knowledgeBaseDocumentChunkId, text);

                    knowledgeBaseDocumentChunk.setContent(chunkContent);

                    knowledgeBaseDocumentChunkService.saveKnowledgeBaseDocumentChunk(knowledgeBaseDocumentChunk);
                }

                knowledgeBaseDocument.setStatus(KnowledgeBaseDocument.STATUS_READY);

                knowledgeBaseDocumentService.saveKnowledgeBaseDocument(knowledgeBaseDocument);
            } catch (RuntimeException exception) {
                knowledgeBaseDocument.setStatus(KnowledgeBaseDocument.STATUS_ERROR);

                knowledgeBaseDocumentService.saveKnowledgeBaseDocument(knowledgeBaseDocument);

                throw exception;
            }
        }

        @Override
        public void update(
            Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
            DocumentReader documentReader, List<DocumentTransformer> documentTransformers,
            @Nullable Context context) {

            Resolution resolution = resolve(inputParameters, context);

            KnowledgeBase knowledgeBase = resolution.knowledgeBase();

            Long knowledgeBaseId = knowledgeBase.getId();

            if (Boolean.TRUE.equals(inputParameters.getBoolean(IS_MULTIPLE))) {
                updateMultiple(
                    inputParameters, connectionParameters, embeddingModel, knowledgeBaseId, documentReader,
                    documentTransformers, context);
            } else {
                updateSingle(
                    inputParameters, connectionParameters, embeddingModel, knowledgeBaseId, resolution.owner(),
                    documentReader, documentTransformers, context);
            }
        }

        private void updateMultiple(
            Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
            Long knowledgeBaseId, DocumentReader documentReader, List<DocumentTransformer> documentTransformers,
            @Nullable Context context) {

            List<Map<String, Object>> metadata = inputParameters.getList(METADATA_FILTER, new TypeReference<>() {});

            Map<String, Object> deleteParametersMap = new HashMap<>();

            deleteParametersMap.put(KNOWLEDGE_BASE_ID, knowledgeBaseId);
            deleteParametersMap.put(METADATA_FILTER, metadata);

            delete(ParametersFactory.create(deleteParametersMap), connectionParameters, embeddingModel, context);

            load(
                ParametersFactory.create(deleteParametersMap), connectionParameters, embeddingModel, documentReader,
                documentTransformers, context);
        }

        private void updateSingle(
            Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
            Long knowledgeBaseId, Optional<Owner> owner, DocumentReader documentReader,
            List<DocumentTransformer> documentTransformers, @Nullable Context context) {

            Long knowledgeBaseDocumentId = inputParameters.getLong(KNOWLEDGE_BASE_DOCUMENT_ID);
            Long knowledgeBaseDocumentChunkId = inputParameters.getLong(KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID);

            // Both belongs-to checks run HERE, at the top, and not beside the deletes they protect. This method
            // deletes before it loads, so a check placed between the two destructive branches -- or left to load()
            // further down -- would refuse a run whose victim's chunks were already gone. Both take the WRITE rule:
            // this method deletes what it is given and re-loads it under this run's owner.
            if (knowledgeBaseDocumentId != null) {
                KnowledgeBaseOptionsUtils.requireWritableKnowledgeBaseDocument(
                    knowledgeBaseDocumentService, knowledgeBaseId, knowledgeBaseDocumentId, owner);
            }

            if (knowledgeBaseDocumentChunkId != null) {
                KnowledgeBaseOptionsUtils.requireWritableKnowledgeBaseDocumentChunk(
                    knowledgeBaseDocumentChunkService, knowledgeBaseDocumentService, knowledgeBaseId,
                    knowledgeBaseDocumentChunkId, owner);
            }

            Map<String, Object> loadMetadata = new HashMap<>();

            if (knowledgeBaseDocumentChunkId != null) {
                FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();

                Filter.Expression chunkFilter = filterBuilder
                    .eq(METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID, knowledgeBaseDocumentChunkId)
                    .build();

                KnowledgeBaseVectorStoreWrapper wrappedVectorStore = new KnowledgeBaseVectorStoreWrapper(
                    vectorStore, knowledgeBaseId, null, owner);

                List<Document> existingDocuments = wrappedVectorStore.similaritySearch(
                    SearchRequest.builder()
                        .query(" ")
                        .topK(1)
                        .similarityThreshold(0.0)
                        .filterExpression(chunkFilter)
                        .build());

                if (!existingDocuments.isEmpty()) {
                    Map<String, Object> inheritedMetadata = new HashMap<>(existingDocuments.getFirst()
                        .getMetadata());

                    inheritedMetadata.remove(METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID);

                    if (!inheritedMetadata.isEmpty()) {
                        loadMetadata.putAll(inheritedMetadata);
                    }
                }
            }

            Map<String, Object> additionalMetadata = inputParameters.getMap(ADDITIONAL_METADATA, Object.class);

            if (additionalMetadata != null && !additionalMetadata.isEmpty()) {
                loadMetadata.putAll(additionalMetadata);
            }

            if (knowledgeBaseDocumentChunkId != null) {
                knowledgeBaseDocumentChunkService.deleteKnowledgeBaseDocumentChunk(knowledgeBaseDocumentChunkId);
            } else if (knowledgeBaseDocumentId != null) {
                List<KnowledgeBaseDocumentChunk> existingChunks =
                    knowledgeBaseDocumentChunkService.getKnowledgeBaseDocumentChunksByDocumentId(
                        knowledgeBaseDocumentId);

                knowledgeBaseDocumentChunkService.deleteKnowledgeBaseDocumentChunks(existingChunks);
            }

            Map<String, Object> deleteFilter = new HashMap<>();

            if (knowledgeBaseDocumentChunkId != null) {
                deleteFilter.put(METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID, knowledgeBaseDocumentChunkId);
            } else if (knowledgeBaseDocumentId != null) {
                deleteFilter.put(METADATA_KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId);
            }

            List<Map<String, Object>> metadataFilters = deleteFilter.isEmpty() ? List.of() : List.of(deleteFilter);

            Map<String, Object> deleteParametersMap = new HashMap<>();

            deleteParametersMap.put(KNOWLEDGE_BASE_ID, knowledgeBaseId);
            deleteParametersMap.put(METADATA_FILTER, metadataFilters);

            delete(ParametersFactory.create(deleteParametersMap), connectionParameters, embeddingModel, context);

            Map<String, Object> loadParametersMap = new HashMap<>();

            loadParametersMap.put(KNOWLEDGE_BASE_ID, knowledgeBaseId);
            loadParametersMap.put(ADDITIONAL_METADATA, loadMetadata);

            if (knowledgeBaseDocumentId != null) {
                loadParametersMap.put(KNOWLEDGE_BASE_DOCUMENT_ID, knowledgeBaseDocumentId);
            }

            load(
                ParametersFactory.create(loadParametersMap), connectionParameters, embeddingModel, documentReader,
                documentTransformers, context);
        }

        @Override
        public List<Document> search(
            Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel,
            @Nullable Context context) {

            org.springframework.ai.vectorstore.VectorStore wrappedVectorStore = createVectorStore(
                inputParameters, connectionParameters, embeddingModel, context);

            return wrappedVectorStore.similaritySearch(inputParameters.getRequiredString(QUERY));
        }

        /**
         * The one place this implementation turns a named id into a knowledge base.
         *
         * <p>
         * A gate and a fallback, because only one of them is answerable at any given frame. With a context in hand the
         * run's owner is knowable, so the pool question is asked: may this owner, in the pool {@code poolFor} allows
         * them, reach this id. Without one the owner is not merely unknown but UNKNOWABLE, and passing an empty owner
         * would be the opposite answer rather than a weaker one -- an empty owner opens both pools and admits every
         * knowledge base in the tenant. Such a frame has no pool to check either, so it falls back to the fully
         * unscoped read: a knowledge base is no longer assigned to one account, so there is no narrower question left
         * for it to ask, and that read refuses nothing. What separates two accounts sharing it is the owner on the
         * chunks inside it, applied further down by {@code KnowledgeBaseVectorStoreWrapper}.
         * </p>
         */
        private Resolution resolve(Parameters inputParameters, @Nullable Context context) {
            long knowledgeBaseId = inputParameters.getRequiredLong(KNOWLEDGE_BASE_ID);

            // An agent's RAG, chat memory and document retriever paths all hand this element the agent action's own
            // ActionContextAware; a cluster tool invoked outside an agent hands it a ClusterElementContext. Both are
            // owner-bearing, and both are asked through the shared OwnerResolution so this element cannot drift from
            // the actions beside it.
            if (context instanceof ActionContextAware actionContextAware) {
                Optional<Owner> owner = OwnerResolution.resolve(actionContextAware, ownerResolverProvider);

                return new Resolution(
                    KnowledgeBaseOptionsUtils.resolveKnowledgeBase(knowledgeBaseService, knowledgeBaseId, owner),
                    owner);
            }

            if (context instanceof ClusterElementContext clusterElementContext) {
                Optional<Owner> owner = OwnerResolution.resolve(clusterElementContext, ownerResolverProvider);

                return new Resolution(
                    KnowledgeBaseOptionsUtils.resolveKnowledgeBase(knowledgeBaseService, knowledgeBaseId, owner),
                    owner);
            }

            return new Resolution(
                KnowledgeBaseOptionsUtils.readUnscopedKnowledgeBase(knowledgeBaseService, knowledgeBaseId),
                Optional.empty());
        }
    }

    /**
     * The knowledge base a frame resolved together with the owner it resolved it AS.
     *
     * <p>
     * The two travel as one because they are answers to the same question and the wrapper needs both: the knowledge
     * base picks the store, the owner picks the chunks within it. Re-deriving the owner beside each wrapper would be a
     * second resolution able to drift from the one the admission gate actually used.
     */
    private record Resolution(KnowledgeBase knowledgeBase, Optional<Owner> owner) {
    }
}
