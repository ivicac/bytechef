/*
* Copyright 2025 - 2025 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.springaicommunity.tool.searcher;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.tool.search.SearchType;
import org.springaicommunity.tool.search.ToolReference;
import org.springaicommunity.tool.search.ToolSearchRequest;
import org.springaicommunity.tool.search.ToolSearchResponse;
import org.springaicommunity.tool.search.ToolSearcher;
import org.springaicommunity.tool.search.ToolSearchResponse.SearchMetadata;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * Vector-based tool searcher for semantic search of tool descriptions.
 * <p>
 * This class provides semantic search capabilities using vector embeddings, allowing for
 * more intelligent matching based on meaning rather than just keywords. Uses Spring AI's
 * VectorStore for vector storage.
 *
 * @author Christian Tzolov
 */
public class VectorToolSearcher implements Closeable, ToolSearcher {

	private static final Logger log = LoggerFactory.getLogger(VectorToolSearcher.class);

	private static final String METADATA_ID = "id";

	private static final String METADATA_SESSION_ID = "sessionId";

	public static final String METADATA_TOOL_NAME = "toolName";

	public static final String METADATA_TOOL_DESCRIPTION = "toolDescription";

	private static final int DEFAULT_MAX_RESULTS = 10;

	private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.2;

	private final VectorStore vectorStore;

	private final java.util.Set<String> additionalSessionIds;

	private final AtomicInteger counter = new AtomicInteger(0);

	private final ConcurrentHashMap<String, List<String>> sessionToolIds = new ConcurrentHashMap<>();

	@Override
	public void clearIndex(String sessionId) {

		List<String> toolIds = sessionToolIds.get(sessionId);

		if (toolIds != null) {
			this.vectorStore.delete(toolIds);
			sessionToolIds.remove(sessionId);
			log.info("Cleared {} tools for sessionId={}", toolIds.size(), sessionId);
		}
		else {
			log.info("No tools found for sessionId={}", sessionId);
		}
	}

	/**
	 * ByteChef fork: deletes every indexed tool for {@code sessionId} by matching the persisted {@code sessionId}
	 * metadata, rather than the in-memory id list used by {@link #clearIndex(String)}. This is restart-safe — a fresh
	 * JVM (empty in-memory map) still removes the previous run's rows for fixed-name sessions (the workspace catalog
	 * and the global tool sessions) before re-indexing, preventing stale rows from accumulating and being matched by
	 * {@link #search}.
	 */
	public void clearSession(String sessionId) {
		this.vectorStore.delete(new FilterExpressionBuilder().eq(METADATA_SESSION_ID, sessionId).build());
		this.sessionToolIds.remove(sessionId);
	}

	/**
	 * Creates a new VectorToolSearcher with the given vector store.
	 * @param vectorStore the vector store to use for storing and searching tool
	 * embeddings
	 */
	public VectorToolSearcher(VectorStore vectorStore) {
		this(vectorStore, java.util.Set.of());
	}

	// ByteChef fork: additionalSessionIds lets a searcher instance also match results from
	// persistent sessions (e.g. the workspace tool catalog) on top of the per-request session,
	// so one search spans the per-conversation self-index plus pre-embedded catalogs.
	public VectorToolSearcher(VectorStore vectorStore, java.util.Set<String> additionalSessionIds) {
		this.vectorStore = vectorStore;
		this.additionalSessionIds = java.util.Set.copyOf(additionalSessionIds);
	}

	@Override
	public SearchType searchType() {
		return SearchType.SEMANTIC;
	}

	@Override
	public void indexTool(String sessionId, ToolReference toolReference) {
		String id = String.valueOf(counter.getAndIncrement());
		this.sessionToolIds.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(id);
		this.add(sessionId, id, toolReference.toolName(), toolReference.summary());
	}

	@Override
	public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {

		List<Document> docs = this.doSearch(toolSearchRequest.query());

		List<ToolReference> toolReferences = docs.stream().map(doc -> {
			Object docSessionId = doc.getMetadata().get(METADATA_SESSION_ID);
			// ByteChef fork: keep the doc if its session matches the request session OR a configured additional
			// (persistent) session. Null-safe: a doc with no sessionId metadata matches nothing and is dropped.
			boolean matchesRequest = toolSearchRequest.sessionId().equals(docSessionId);
			boolean matchesAdditional = docSessionId != null && this.additionalSessionIds.contains(docSessionId);

			if (!matchesRequest && !matchesAdditional) {
				return null;
			}
			String toolName = (String) doc.getMetadata().get(METADATA_TOOL_NAME);
			Double relevanceScore = doc.getScore();
			String summary = (String) doc.getMetadata().get(METADATA_TOOL_DESCRIPTION);

			return ToolReference.builder().toolName(toolName).relevanceScore(relevanceScore).summary(summary).build();
		}).filter(Objects::nonNull).toList();

		return ToolSearchResponse.builder()
			.toolReferences(toolReferences)
			.totalMatches(toolReferences.size())
			.searchMetadata(
					SearchMetadata.builder().searchType(this.searchType()).query(toolSearchRequest.query()).build())
			.build();
	}

	/**
	 * Adds a single tool to the vector index.
	 * @param sessionId the session ID associated with the tool
	 * @param id unique identifier for the tool
	 * @param toolName name of the tool
	 * @param toolDescription description of the tool (used for embedding)
	 */
	public void add(String sessionId, String id, String toolName, String toolDescription) {
		Document document = new Document(id, toolDescription, Map.of(METADATA_SESSION_ID, sessionId, METADATA_ID, id,
				METADATA_TOOL_NAME, toolName, METADATA_TOOL_DESCRIPTION, toolDescription));
		this.vectorStore.add(List.of(document));
	}

	/**
	 * Searches for tools matching the query string using semantic similarity.
	 * @param queryString the search query
	 * @return list of matching documents sorted by similarity
	 */
	public List<Document> doSearch(String queryString) {
		return doSearch(queryString, DEFAULT_MAX_RESULTS, DEFAULT_SIMILARITY_THRESHOLD);
	}

	/**
	 * Searches for tools matching the query string with custom parameters.
	 * @param queryString the search query
	 * @param maxResults maximum number of results to return
	 * @param similarityThreshold minimum similarity threshold (0.0 to 1.0)
	 * @return list of matching documents sorted by similarity
	 */
	public List<Document> doSearch(String queryString, int maxResults, double similarityThreshold) {
		SearchRequest searchRequest = SearchRequest.builder()
			.query(queryString)
			.topK(maxResults)
			.similarityThreshold(similarityThreshold)
			.build();

		return this.vectorStore.similaritySearch(searchRequest);
	}

	/**
	 * Deletes a tool from the index by its ID.
	 * @param id the tool ID to delete
	 */
	public void delete(String id) {
		this.vectorStore.delete(List.of(id));
	}

	/**
	 * Deletes multiple tools from the index.
	 * @param ids list of tool IDs to delete
	 */
	public void deleteAll(List<String> ids) {
		this.vectorStore.delete(ids);
	}

	@Override
	public void close() throws IOException {
		// SimpleVectorStore doesn't require explicit cleanup,
		// but this method is provided for interface consistency
		// and potential future vector store implementations that may need it
	}

	/**
	 * Gets the tool name from a search result document.
	 * @param document the search result document
	 * @return the tool name
	 */
	public static String getToolName(Document document) {
		return (String) document.getMetadata().get(METADATA_TOOL_NAME);
	}

	/**
	 * Gets the tool ID from a search result document.
	 * @param document the search result document
	 * @return the tool ID
	 */
	public static String getToolId(Document document) {
		return (String) document.getMetadata().get(METADATA_ID);
	}

	/**
	 * Gets the tool description (content) from a search result document.
	 * @param document the search result document
	 * @return the tool description
	 */
	public static String getToolDescription(Document document) {
		return document.getText();
	}

}
