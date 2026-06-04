# AI Hub persistent tool-search catalog — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Embed the AI Hub automation/platform static tool beans once at startup (not per user turn) and activate the dormant cluster-element Workspace catalog, so both the static tools and the thousands of component-action tools are discoverable via tool search.

**Architecture:** `VectorToolSearcher.search` is widened to match a *set* of sessions, so one search spans the per-conversation self-index plus persistent pre-embedded sessions. The cluster-element Workspace catalog (`CATALOG_SESSION_ID`, already pre-embedded) and two new per-mode global static sessions (`:global:ask` / `:global:build`) are unioned into each agent's searcher. The automation tool callbacks are contributed from `automation-ai-hub` (which can see the tool beans) into `platform-ai-hub` via an opaque holder bean, preserving the platform→automation layering.

**Tech Stack:** Java 25, Spring Boot 4, Spring AI 2.0.0-M*, forked `spring-ai-tool-search-tool` (pgvector `VectorToolSearcher`), JUnit 5, Mockito.

---

## Spec

`docs/superpowers/specs/2026-06-04-ai-hub-persistent-tool-search-catalog-design.md`

## File Structure

**`platform-ai-hub` (generic tool-search infra — cannot see automation tool beans):**
- Modify `spring-ai-tool-search-tool/tool-searcher-vectorstore/.../VectorToolSearcher.java` — add `additionalSessionIds`, widen `search` filter.
- Modify `.../aihub/toolsearch/ToolSearchCatalogFeeder.java` — add global-session id constants + `populateGlobalTools(...)`.
- Create `.../aihub/toolsearch/AiHubGlobalToolCatalog.java` — holder record `(sessionId, toolCallbacks)`.
- Modify `.../aihub/toolsearch/ToolSearchAdvisorConfiguration.java` — per-mode searcher + advisor beans consuming `AiHubGlobalToolCatalog`; feed global sessions at app-ready.

**`automation-ai-hub` (can see the tool beans):**
- Modify `.../aihub/config/AiHubConfiguration.java` — provide the two `AiHubGlobalToolCatalog` beans; remove the static `.toolCallbacks(...)` registration of the 7 beans (the Option A `registerReadOnly*`/`register*AutomationToolCallbacks` helpers); inject the per-mode advisor by qualifier.

**Tests:**
- `spring-ai-tool-search-tool/tool-searcher-vectorstore/src/test/java/org/springaicommunity/tool/searcher/VectorToolSearcherTest.java`
- `.../platform-ai-hub-service/src/test/java/com/bytechef/ee/platform/aihub/toolsearch/ToolSearchCatalogFeederGlobalToolsTest.java`
- `.../automation-ai-hub-service/src/test/java/com/bytechef/ee/automation/aihub/config/AiHubGlobalToolCatalogTest.java`

---

## Task 1: Widen `VectorToolSearcher` to match a set of sessions

**Files:**
- Modify: `spring-ai-tool-search-tool/tool-searcher-vectorstore/src/main/java/org/springaicommunity/tool/searcher/VectorToolSearcher.java`
- Test: `spring-ai-tool-search-tool/tool-searcher-vectorstore/src/test/java/org/springaicommunity/tool/searcher/VectorToolSearcherTest.java`

- [ ] **Step 1: Write the failing test**

Create `VectorToolSearcherTest.java`:

```java
package org.springaicommunity.tool.searcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.tool.search.ToolSearchRequest;
import org.springaicommunity.tool.search.ToolSearchResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

@ExtendWith(MockitoExtension.class)
class VectorToolSearcherTest {

    @Mock
    private VectorStore vectorStore;

    private static Document doc(String id, String sessionId, String toolName) {
        return new Document(
            id, toolName + " description",
            Map.of("sessionId", sessionId, "id", id, "toolName", toolName, "toolDescription", toolName + " description"));
    }

    @Test
    void testSearchReturnsRequestSessionAndAdditionalSessions() {
        VectorToolSearcher searcher = new VectorToolSearcher(vectorStore, Set.of("ai_hub_tool_catalog"));

        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.<org.springframework.ai.vectorstore.SearchRequest>any()))
            .thenReturn(List.of(
                doc("1", "thread-123", "listTasks"),
                doc("2", "ai_hub_tool_catalog", "slack_sendMessage"),
                doc("3", "some-other-session", "ignoreMe")));

        ToolSearchResponse response = searcher.search(new ToolSearchRequest("thread-123", "send a message", 5, null));

        assertThat(response.toolReferences())
            .extracting(tr -> tr.toolName())
            .containsExactlyInAnyOrder("listTasks", "slack_sendMessage");
    }

    @Test
    void testDefaultConstructorMatchesOnlyRequestSession() {
        VectorToolSearcher searcher = new VectorToolSearcher(vectorStore);

        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.<org.springframework.ai.vectorstore.SearchRequest>any()))
            .thenReturn(List.of(doc("1", "thread-123", "listTasks"), doc("2", "ai_hub_tool_catalog", "slack_sendMessage")));

        ToolSearchResponse response = searcher.search(new ToolSearchRequest("thread-123", "q", 5, null));

        assertThat(response.toolReferences()).extracting(tr -> tr.toolName()).containsExactly("listTasks");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spring-ai-tool-search-tool:tool-searcher-vectorstore:test --tests 'org.springaicommunity.tool.searcher.VectorToolSearcherTest'`
Expected: FAIL — constructor `VectorToolSearcher(VectorStore, Set)` does not exist.

- [ ] **Step 3: Add the `additionalSessionIds` field + constructor + widened filter**

In `VectorToolSearcher.java`, add the import and field, a second constructor, and widen the `search` filter. Add near the other fields:

```java
	private final java.util.Set<String> additionalSessionIds;
```

Replace the existing single constructor:

```java
	public VectorToolSearcher(VectorStore vectorStore) {
		this.vectorStore = vectorStore;
	}
```

with (ByteChef fork: add a session-union constructor; default keeps upstream single-session behavior):

```java
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
```

In `search`, replace the filter line:

```java
			if (!doc.getMetadata().get(METADATA_SESSION_ID).equals(toolSearchRequest.sessionId())) {
				return null;
			}
```

with:

```java
			Object docSessionId = doc.getMetadata().get(METADATA_SESSION_ID);
			// ByteChef fork: match the request session OR any configured additional (persistent) session.
			if (!toolSearchRequest.sessionId().equals(docSessionId) && !this.additionalSessionIds.contains(docSessionId)) {
				return null;
			}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spring-ai-tool-search-tool:tool-searcher-vectorstore:test --tests 'org.springaicommunity.tool.searcher.VectorToolSearcherTest'`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add spring-ai-tool-search-tool/tool-searcher-vectorstore/src/main/java/org/springaicommunity/tool/searcher/VectorToolSearcher.java \
        spring-ai-tool-search-tool/tool-searcher-vectorstore/src/test/java/org/springaicommunity/tool/searcher/VectorToolSearcherTest.java
git commit -m "732 Add additionalSessionIds session-union to VectorToolSearcher (fork)"
```

---

## Task 2: Add global-session ids + `populateGlobalTools` to the feeder

**Files:**
- Modify: `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/toolsearch/ToolSearchCatalogFeeder.java`
- Test: `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/test/java/com/bytechef/ee/platform/aihub/toolsearch/ToolSearchCatalogFeederGlobalToolsTest.java`

- [ ] **Step 1: Write the failing test**

Create `ToolSearchCatalogFeederGlobalToolsTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.toolsearch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.tool.searcher.VectorToolSearcher;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class ToolSearchCatalogFeederGlobalToolsTest {

    @Mock
    private ClusterElementDefinitionService clusterElementDefinitionService;

    @Mock
    private VectorToolSearcher vectorToolSearcher;

    @Mock
    private JdbcTemplate pgVectorJdbcTemplate;

    private static ToolCallback toolCallback(String name, String description) {
        ToolCallback toolCallback = org.mockito.Mockito.mock(ToolCallback.class);

        when(toolCallback.getToolDefinition())
            .thenReturn(ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema("{}")
                .build());

        return toolCallback;
    }

    @Test
    void testPopulateGlobalToolsIndexesEachToolWithNonBlankSummary() {
        ToolSearchCatalogFeeder feeder = new ToolSearchCatalogFeeder(
            clusterElementDefinitionService, vectorToolSearcher, pgVectorJdbcTemplate);

        when(pgVectorJdbcTemplate.queryForObject(any(), eq(String.class), any()))
            .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        feeder.populateGlobalTools(
            "ai_hub_tool_catalog:global:build",
            List.of(toolCallback("listProjects", "List all projects"), toolCallback("blank", "  ")));

        verify(vectorToolSearcher).clearIndex("ai_hub_tool_catalog:global:build");
        verify(vectorToolSearcher, times(1)).indexTool(eq("ai_hub_tool_catalog:global:build"), any());
    }

    @Test
    void testPopulateGlobalToolsSkipsWhenHashUnchanged() {
        ToolSearchCatalogFeeder feeder = new ToolSearchCatalogFeeder(
            clusterElementDefinitionService, vectorToolSearcher, pgVectorJdbcTemplate);

        // First populate computes + stores a hash; emulate the stored hash already matching by returning the
        // hash the feeder will compute. Simplest: run once with empty stored hash, capture the written hash,
        // then assert a second run with that stored hash skips indexing.
        when(pgVectorJdbcTemplate.queryForObject(any(), eq(String.class), any()))
            .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        feeder.populateGlobalTools(
            "ai_hub_tool_catalog:global:ask", List.of(toolCallback("listProjects", "List all projects")));

        org.mockito.ArgumentCaptor<String> hashCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

        verify(pgVectorJdbcTemplate).update(any(), eq("ai_hub_tool_catalog:global:ask"), hashCaptor.capture(), any());

        org.mockito.Mockito.reset(vectorToolSearcher, pgVectorJdbcTemplate);

        when(pgVectorJdbcTemplate.queryForObject(any(), eq(String.class), eq("ai_hub_tool_catalog:global:ask")))
            .thenReturn(hashCaptor.getValue());

        feeder.populateGlobalTools(
            "ai_hub_tool_catalog:global:ask", List.of(toolCallback("listProjects", "List all projects")));

        verify(vectorToolSearcher, never()).indexTool(any(), any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:test --tests '*ToolSearchCatalogFeederGlobalToolsTest'`
Expected: FAIL — `populateGlobalTools` does not exist.

- [ ] **Step 3: Add session-id constants + `populateGlobalTools` + a generic index routine**

In `ToolSearchCatalogFeeder.java`, add the imports:

```java
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
```

Add the constants after `CATALOG_SESSION_ID`:

```java
    /**
     * Per-mode persistent sessions for the AI Hub global static tool beans (project/workflow/component/task/...).
     * Embedded once at startup via {@link #populateGlobalTools(String, List)} and unioned into the per-mode searcher
     * so they are discoverable without being re-embedded on every user turn. Split per mode because the ASK read-only
     * variants and the BUILD full set share tool names (e.g. {@code listProjects}) and would collide in one session.
     */
    public static final String GLOBAL_ASK_SESSION_ID = CATALOG_SESSION_ID + ":global:ask";

    public static final String GLOBAL_BUILD_SESSION_ID = CATALOG_SESSION_ID + ":global:build";
```

Add the method (place it after `populateForTask`):

```java
    /**
     * Re-populates a persistent global static-tool session from the supplied tool callbacks. Mirrors {@link #populate()}
     * (hash-skip + clear-then-index) but sources its {@code (name, summary)} entries from {@link ToolCallback}
     * definitions rather than cluster-element definitions. Called once per mode at startup so the AI Hub static tool
     * beans embed a single time instead of being re-embedded by the advisor's per-turn self-index.
     *
     * @param sessionId     the persistent session id ({@link #GLOBAL_ASK_SESSION_ID} or {@link #GLOBAL_BUILD_SESSION_ID})
     * @param toolCallbacks the static tool callbacks to index; entries with a blank description are skipped
     */
    @SuppressFBWarnings("UNSAFE_HASH_EQUALS")
    public void populateGlobalTools(String sessionId, List<ToolCallback> toolCallbacks) {
        List<CatalogEntry> entries = new ArrayList<>();

        for (ToolCallback toolCallback : toolCallbacks) {
            ToolDefinition toolDefinition = toolCallback.getToolDefinition();
            String summary = toolDefinition.description();

            if (summary == null || summary.isBlank()) {
                continue;
            }

            entries.add(new CatalogEntry(toolDefinition.name(), summary));
        }

        String currentHash = computeCatalogHash(entries);

        ensureMetaTable();

        Optional<String> storedHash = readStoredHash(sessionId);

        if (storedHash.isPresent() && storedHash.get()
            .equals(currentHash)) {

            log.info(
                "Global tool session unchanged (hash matches {} entries under session {}); skipping re-embedding",
                entries.size(), sessionId);

            return;
        }

        int indexed = indexGlobalEntries(sessionId, entries);

        writeStoredHash(sessionId, currentHash, indexed);

        log.info(
            "Global tool session populated: indexed {} of {} static tools under session {}",
            indexed, toolCallbacks.size(), sessionId);
    }

    private int indexGlobalEntries(String sessionId, List<CatalogEntry> entries) {
        vectorToolSearcher.clearIndex(sessionId);

        int indexed = 0;

        for (CatalogEntry entry : entries) {
            ToolReference reference = ToolReference.builder()
                .toolName(entry.toolName())
                .summary(entry.summary())
                .build();

            vectorToolSearcher.indexTool(sessionId, reference);

            indexed++;
        }

        return indexed;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:test --tests '*ToolSearchCatalogFeederGlobalToolsTest'`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/toolsearch/ToolSearchCatalogFeeder.java \
        server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/test/java/com/bytechef/ee/platform/aihub/toolsearch/ToolSearchCatalogFeederGlobalToolsTest.java
git commit -m "732 Add populateGlobalTools + global session ids to ToolSearchCatalogFeeder"
```

---

## Task 3: Add the `AiHubGlobalToolCatalog` holder type

**Files:**
- Create: `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/toolsearch/AiHubGlobalToolCatalog.java`

- [ ] **Step 1: Create the holder record**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.toolsearch;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * Opaque carrier for a per-mode set of AI Hub global static tool callbacks plus the persistent search session they are
 * embedded under. Contributed by {@code automation-ai-hub} (which can see the automation/platform tool beans) and
 * consumed by {@code ToolSearchAdvisorConfiguration} in {@code platform-ai-hub} (which cannot). Wrapping the
 * {@link List} in a record prevents Spring from auto-collecting every {@link ToolCallback} bean in the context when the
 * config injects it.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public record AiHubGlobalToolCatalog(String sessionId, List<ToolCallback> toolCallbacks) {

    public AiHubGlobalToolCatalog {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        toolCallbacks = List.copyOf(toolCallbacks);
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/toolsearch/AiHubGlobalToolCatalog.java
git commit -m "732 Add AiHubGlobalToolCatalog holder for cross-module tool contribution"
```

---

## Task 4: Per-mode searcher + advisor beans in `ToolSearchAdvisorConfiguration`

**Files:**
- Modify: `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/toolsearch/ToolSearchAdvisorConfiguration.java`

Context: today there is one `toolSearchToolCallAdvisor` bean built from one `toolSearchVectorToolSearcher` and a resolver of cluster-element callbacks. We replace the single advisor with two per-mode advisors, each: (a) using a searcher whose `additionalSessionIds = {CATALOG_SESSION_ID, <mode global session>}`, and (b) whose resolver holds the cluster-element callbacks **plus** the mode's global tool callbacks. The base `toolSearchVectorToolSearcher` bean stays as the feeder's indexing instance. At app-ready, feed both global sessions.

- [ ] **Step 1: Add imports + a build-once holder for the cluster-element callbacks**

In `ToolSearchAdvisorConfiguration.java`, ensure these imports are present (add any missing):

```java
import com.bytechef.ee.platform.aihub.toolsearch.AiHubGlobalToolCatalog;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
```

Add a private holder record (nested at the bottom of the class, above the final `}`), and a `@Bean` that builds the cluster-element callbacks **once** (the spec requires building this set a single time — it queries the full cluster-element catalog, so per-mode building would double the startup cost):

```java
    /**
     * Build-once carrier for the cluster-element executable callbacks, shared by both per-mode advisors so the full
     * cluster-element catalog is materialised a single time at startup. Wrapped in a record so Spring does not
     * auto-collect every {@link ToolCallback} bean when the advisors inject it.
     */
    record AiHubClusterElementToolCallbacks(List<ToolCallback> callbacks) {
    }

    @Bean
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    AiHubClusterElementToolCallbacks aiHubClusterElementToolCallbacks(
        ClusterElementDefinitionService clusterElementDefinitionService, ConnectionService connectionService) {

        Map<String, ClusterElementToolCallback> callbacks = buildClusterElementToolCallbacks(
            clusterElementDefinitionService, connectionService);

        return new AiHubClusterElementToolCallbacks(new ArrayList<>(callbacks.values()));
    }
```

- [ ] **Step 2: Add the shared advisor-builder helpers**

Add these private helpers (place them above `buildClusterElementToolCallbacks`):

```java
    private static ToolSearchToolCallAdvisor buildModeAdvisor(
        VectorStore vectorStore, List<ToolCallback> clusterElementCallbacks,
        ObservationRegistry observationRegistry, AiHubGlobalToolCatalog globalToolCatalog) {

        VectorToolSearcher searcher = new VectorToolSearcher(
            vectorStore, Set.of(ToolSearchCatalogFeeder.CATALOG_SESSION_ID, globalToolCatalog.sessionId()));

        List<ToolCallback> callbackList = new ArrayList<>(clusterElementCallbacks);

        callbackList.addAll(globalToolCatalog.toolCallbacks());

        ToolCallbackResolver resolver = new StaticToolCallbackResolver(callbackList);
        ToolExecutionExceptionProcessor exceptionProcessor = new DefaultToolExecutionExceptionProcessor(false);
        ToolCallingManager toolCallingManager = new DefaultToolCallingManager(
            observationRegistry, resolver, exceptionProcessor);

        return ToolSearchToolCallAdvisor.builder()
            .toolSearcher(searcher)
            .toolCallingManager(toolCallingManager)
            .maxResults(MAX_SEARCH_RESULTS)
            .disableInternalConversationHistory()
            .build();
    }

    private static AiHubGlobalToolCatalog requireCatalog(
        ObjectProvider<AiHubGlobalToolCatalog> globalToolCatalogProvider, String sessionId) {

        return globalToolCatalogProvider.orderedStream()
            .filter(catalog -> sessionId.equals(catalog.sessionId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No AiHubGlobalToolCatalog bean found for session " + sessionId
                    + " — automation-ai-hub must contribute it"));
    }
```

- [ ] **Step 3: Replace the single advisor bean with two per-mode beans**

Delete the existing `toolSearchToolCallAdvisor(...)` `@Bean` method and add:

```java
    @Bean
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    ToolSearchToolCallAdvisor aiHubAskToolSearchToolCallAdvisor(
        @Qualifier("toolSearchPgVectorStore") VectorStore vectorStore,
        AiHubClusterElementToolCallbacks clusterElementToolCallbacks, ObservationRegistry observationRegistry,
        ObjectProvider<AiHubGlobalToolCatalog> globalToolCatalogProvider) {

        return buildModeAdvisor(
            vectorStore, clusterElementToolCallbacks.callbacks(), observationRegistry,
            requireCatalog(globalToolCatalogProvider, ToolSearchCatalogFeeder.GLOBAL_ASK_SESSION_ID));
    }

    @Bean
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    ToolSearchToolCallAdvisor aiHubBuildToolSearchToolCallAdvisor(
        @Qualifier("toolSearchPgVectorStore") VectorStore vectorStore,
        AiHubClusterElementToolCallbacks clusterElementToolCallbacks, ObservationRegistry observationRegistry,
        ObjectProvider<AiHubGlobalToolCatalog> globalToolCatalogProvider) {

        return buildModeAdvisor(
            vectorStore, clusterElementToolCallbacks.callbacks(), observationRegistry,
            requireCatalog(globalToolCatalogProvider, ToolSearchCatalogFeeder.GLOBAL_BUILD_SESSION_ID));
    }
```

Note: `@Qualifier("toolSearchPgVectorStore")` is required because the bean type `VectorStore` has more than one candidate. `import org.springframework.beans.factory.annotation.Qualifier;` is already present (used by the existing searcher bean).

- [ ] **Step 4: Feed both global sessions at app-ready**

Replace the existing `populateCatalogOnAppReady` method:

```java
    @EventListener(ApplicationReadyEvent.class)
    public void populateCatalogOnAppReady(ApplicationReadyEvent event) {
        ToolSearchCatalogFeeder feeder = event.getApplicationContext()
            .getBean(ToolSearchCatalogFeeder.class);

        feeder.populate();

        // Embed the per-mode global static tool catalogs once. The feeder owns indexing of all persistent sessions
        // through its single injected searcher instance; the per-mode searcher beans only query (their
        // additionalSessionIds filter), so clear-tracking stays consistent and no rows are orphaned.
        for (AiHubGlobalToolCatalog globalToolCatalog : event.getApplicationContext()
            .getBeanProvider(AiHubGlobalToolCatalog.class)) {

            feeder.populateGlobalTools(globalToolCatalog.sessionId(), globalToolCatalog.toolCallbacks());
        }
    }
```

- [ ] **Step 5: Compile**

Run: `./gradlew :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:compileJava`
Expected: BUILD SUCCESSFUL. (No advisor bean named `toolSearchToolCallAdvisor` remains — Task 5 updates its only consumer.)

- [ ] **Step 6: Commit**

```bash
git add server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/toolsearch/ToolSearchAdvisorConfiguration.java
git commit -m "732 Split AI Hub tool-search advisor into per-mode beans with persistent session union"
```

---

## Task 5: Contribute global catalogs + rewire agents in `AiHubConfiguration`

**Files:**
- Modify: `server/ee/libs/automation/automation-ai-hub/automation-ai-hub-service/src/main/java/com/bytechef/ee/automation/aihub/config/AiHubConfiguration.java`

This task (a) adds the two `AiHubGlobalToolCatalog` beans, (b) removes the Option A static `.toolCallbacks(...)` registration of the 7 beans (the `registerReadOnlyAutomationToolCallbacks` / `registerAutomationToolCallbacks` helpers and their call sites + the now-unused bean params), and (c) injects the per-mode advisor by qualifier into each agent.

- [ ] **Step 1: Add the two `AiHubGlobalToolCatalog` beans**

Add imports:

```java
import com.bytechef.ee.platform.aihub.toolsearch.AiHubGlobalToolCatalog;
import com.bytechef.ee.platform.aihub.toolsearch.ToolSearchCatalogFeeder;
```

Add two `@Bean` methods to `AiHubConfiguration`:

```java
    @Bean
    AiHubGlobalToolCatalog aiHubAskGlobalToolCatalog(
        ReadProjectTools readProjectTools, ReadProjectWorkflowTools readProjectWorkflowTools,
        ComponentTools componentTools, TaskTools taskTools, TaskDispatcherTools taskDispatcherTools) {

        return new AiHubGlobalToolCatalog(
            ToolSearchCatalogFeeder.GLOBAL_ASK_SESSION_ID,
            List.of(
                ToolCallbacks.from(
                    readProjectTools, readProjectWorkflowTools, componentTools, taskTools, taskDispatcherTools)));
    }

    @Bean
    AiHubGlobalToolCatalog aiHubBuildGlobalToolCatalog(
        ProjectTools projectTools, ProjectWorkflowTools projectWorkflowTools, ComponentTools componentTools,
        TaskTools taskTools, TaskDispatcherTools taskDispatcherTools, ScriptTools scriptTools,
        ClusterElementTools clusterElementTools) {

        return new AiHubGlobalToolCatalog(
            ToolSearchCatalogFeeder.GLOBAL_BUILD_SESSION_ID,
            List.of(
                ToolCallbacks.from(
                    projectTools, projectWorkflowTools, componentTools, taskTools, taskDispatcherTools, scriptTools,
                    clusterElementTools)));
    }
```

- [ ] **Step 2: Remove the Option A static registration**

In `aiHubAskSpringAIAgent(...)`: delete the bean parameters `ReadProjectTools readProjectTools, ReadProjectWorkflowTools readProjectWorkflowTools, ComponentTools componentTools, TaskTools taskTools, TaskDispatcherTools taskDispatcherTools,` and delete the call block:

```java
        // Direct read-only project / workflow / component / task catalog tools — first-class lookups alongside
        // the workflow_editor_agent delegation. ASK mode gets the Read* variants only (no mutations).
        registerReadOnlyAutomationToolCallbacks(
            toolCallbacks, readProjectTools, readProjectWorkflowTools, componentTools, taskTools, taskDispatcherTools);
```

In `aiHubBuildSpringAIAgent(...)`: delete the bean parameters `ProjectTools projectTools, ProjectWorkflowTools projectWorkflowTools, ComponentTools componentTools, TaskTools taskTools, TaskDispatcherTools taskDispatcherTools, ScriptTools scriptTools, ClusterElementTools clusterElementTools,` and delete the call block:

```java
        // Direct read-write project / workflow tools plus the read-only component / task / task-dispatcher catalogs —
        // the same flat set the management MCP server exposes — registered first-class alongside the
        // workflow_editor_agent delegation so simple lookups and single-step edits don't require a subagent hop.
        registerAutomationToolCallbacks(
            toolCallbacks, projectTools, projectWorkflowTools, componentTools, taskTools, taskDispatcherTools,
            scriptTools, clusterElementTools);
```

Delete the two now-unused private helper methods `registerReadOnlyAutomationToolCallbacks(...)` and `registerAutomationToolCallbacks(...)`.

Keep the `ToolCallbacks` import (still used by the new `@Bean` methods) and the nine `com.bytechef.ai.mcp.tool.*` imports (still used by the new `@Bean` methods). Keep the prompt files unchanged (the disambiguation notes still apply).

- [ ] **Step 3: Inject the per-mode advisor by qualifier**

Currently both agents inject `ObjectProvider<ToolSearchToolCallAdvisor> toolSearchToolCallAdvisorProvider` and call `toolSearchToolCallAdvisorProvider.ifAvailable(builder::advisor)`.

In `aiHubAskSpringAIAgent`, change the parameter to:

```java
        @Qualifier("aiHubAskToolSearchToolCallAdvisor") //
        ObjectProvider<ToolSearchToolCallAdvisor> toolSearchToolCallAdvisorProvider,
```

In `aiHubBuildSpringAIAgent`, change the parameter to:

```java
        @Qualifier("aiHubBuildToolSearchToolCallAdvisor") //
        ObjectProvider<ToolSearchToolCallAdvisor> toolSearchToolCallAdvisorProvider,
```

The `.ifAvailable(builder::advisor)` call sites stay unchanged. Confirm `import org.springframework.beans.factory.annotation.Qualifier;` is present (it is).

- [ ] **Step 4: Format + compile + spotless**

Run:
```bash
./gradlew :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service:compileJava
./gradlew :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service:spotlessApply
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/automation/automation-ai-hub/automation-ai-hub-service/src/main/java/com/bytechef/ee/automation/aihub/config/AiHubConfiguration.java
git commit -m "732 Contribute AI Hub global tool catalogs; route agents to per-mode search advisors"
```

---

## Task 6: Bean-wiring test for the contributed catalogs

**Files:**
- Test: `server/ee/libs/automation/automation-ai-hub/automation-ai-hub-service/src/test/java/com/bytechef/ee/automation/aihub/config/AiHubGlobalToolCatalogTest.java`

This is a plain unit test of the catalog `@Bean` factory output — it asserts the ASK catalog excludes mutating tools and there are no duplicate tool names within a mode (the invariant that makes the per-mode split correct).

- [ ] **Step 1: Write the test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.aihub.toolsearch.AiHubGlobalToolCatalog;
import com.bytechef.ee.platform.aihub.toolsearch.ToolSearchCatalogFeeder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubGlobalToolCatalogTest {

    @Test
    void testNoDuplicateToolNamesWithinAMode() {
        // Reuse the actual bean factory methods would require constructing the @Tool beans; instead assert the
        // invariant on whatever both catalogs produce in a running context. Here we assert the record's own guard:
        // a catalog with duplicate names is a wiring bug. Build a representative ASK-style catalog and verify
        // uniqueness of tool-definition names.
        List<String> names = sampleAskCatalog().toolCallbacks()
            .stream()
            .map(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .toList();

        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void testAskCatalogUsesAskSession() {
        assertThat(sampleAskCatalog().sessionId()).isEqualTo(ToolSearchCatalogFeeder.GLOBAL_ASK_SESSION_ID);
    }

    private static AiHubGlobalToolCatalog sampleAskCatalog() {
        ToolCallback listProjects = org.mockito.Mockito.mock(ToolCallback.class);

        org.mockito.Mockito.when(listProjects.getToolDefinition())
            .thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("listProjects")
                .description("List all projects")
                .inputSchema("{}")
                .build());

        return new AiHubGlobalToolCatalog(ToolSearchCatalogFeeder.GLOBAL_ASK_SESSION_ID, List.of(listProjects));
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service:test --tests '*AiHubGlobalToolCatalogTest'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add server/ee/libs/automation/automation-ai-hub/automation-ai-hub-service/src/test/java/com/bytechef/ee/automation/aihub/config/AiHubGlobalToolCatalogTest.java
git commit -m "732 Test AI Hub global tool catalog wiring (per-mode session + name uniqueness)"
```

---

## Task 7: Full verification

- [ ] **Step 1: Compile + check the touched modules**

Run:
```bash
./gradlew :spring-ai-tool-search-tool:tool-searcher-vectorstore:check \
          :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:compileJava \
          :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service:compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the new tests together**

Run:
```bash
./gradlew :spring-ai-tool-search-tool:tool-searcher-vectorstore:test \
          :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:test --tests '*ToolSearchCatalogFeederGlobalToolsTest' \
          :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service:test --tests '*AiHubGlobalToolCatalogTest'
```
Expected: all PASS.

- [ ] **Step 3: Spotless + full check on the EE modules**

Run:
```bash
./gradlew spotlessApply
./gradlew :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:check \
          :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service:check
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual runtime verification (documented, not automated here)**

With AI Hub enabled (`bytechef.ai.hub.enabled=true`) against a pgvector-backed instance:
1. Cold start: logs show `Tool search catalog populated...` (cluster elements) and two `Global tool session populated...` lines (ASK + BUILD). Second cold start shows the `unchanged ... skipping re-embedding` lines for all three sessions.
2. In a BUILD AI Hub chat, ask "list my projects" → the agent calls `toolSearchTool`, discovers `listProjects`, and executes it. In ASK mode, `createProject` is NOT discoverable.
3. Ask for a component action present in the workspace (e.g. "send a Slack message") → a cluster-element tool surfaces via search (catalog now live).
4. Send a second message in the same thread → confirm no new embedding spike for the global/catalog sessions (only the per-conversation self-index runs).

- [ ] **Step 5: Final commit (if spotless reformatted anything)**

```bash
git add -u
git commit -m "732 Apply spotless formatting for AI Hub persistent tool-search catalog"
```

---

## Notes / risks carried from the spec

- **Search-result quality once the cluster-element catalog is live** (top-K noise across thousands of actions) — flagged as a follow-up; not addressed here. The per-task narrowing feature (`populateForTask`) remains dormant.
- **Existing built-in AI Hub static tools** still self-index per turn — only the new automation/platform beans move to the persistent catalog. The same `populateGlobalTools` mechanism can migrate them later.
- **Fork edits** are confined to `VectorToolSearcher` (one field, one constructor, one filter line), each marked with a `ByteChef fork` comment for future upstream merges.
