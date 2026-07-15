# Tool-Search Catalog From Build Index — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the full-catalog component load from the AI Hub tool-search path — population and listing load zero components; a component loads only when its specific tool is surfaced to the model or executed.

**Architecture:** Add an index-served `getClusterElementDefinitionStubs(type)` to `ClusterElementDefinitionService` (backed by the registry's existing `getStaticComponentDefinitions()`, which returns index stubs when the build index is present and falls back to full load otherwise). Point the three tool-search *list* callers (feeder population, dispatch-callback builder, GraphQL tool picker) at it. Make `ClusterElementToolCallback` generate its input JSON schema lazily so building the callback map touches only tool names.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5, Mockito, AssertJ, Gradle. EE modules under `server/ee/`.

## Global Constraints

- EE files (under `server/ee/`) use the ByteChef Enterprise license header and carry a `@version ee` Javadoc tag. Platform files under `server/libs/` use the Apache 2.0 header.
- Run `./gradlew spotlessApply` before every commit; server code must pass `checkstyleMain`, `checkstyleTest`, `pmdMain`, `spotbugsMain`.
- Test class names: unit tests end with `Test` (never `IntTest`); test methods are camelCase without underscores.
- Do NOT change the existing full-load `getClusterElementDefinitions(ClusterElementType)` behavior — callers such as embedded `ToolFacadeImpl` and `getRootClusterElementDefinitions` need real property trees.
- Spec: `docs/superpowers/specs/2026-07-15-tool-search-catalog-from-index-design.md`.

---

### Task 1: Index-served cluster-element stub enumeration

**Files:**
- Modify: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/service/ClusterElementDefinitionService.java` (add interface method after line 121)
- Modify: `server/libs/platform/platform-component/platform-component-service/src/main/java/com/bytechef/platform/component/service/ClusterElementDefinitionServiceImpl.java:437-455` (extract shared helper, add stub method)
- Test: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ClusterElementDefinitionServiceTest.java` (create)

**Interfaces:**
- Produces: `List<ClusterElementDefinition> ClusterElementDefinitionService.getClusterElementDefinitionStubs(ClusterElementType clusterElementType)` — returns domain `com.bytechef.platform.component.domain.ClusterElementDefinition`s with componentName/componentVersion/name/title/description/type populated and an **empty property list**, sourced from `ComponentDefinitionRegistry.getStaticComponentDefinitions()` (index stubs when the build index is present; full load otherwise). Consumed by Tasks 2, 3, 4.

- [ ] **Step 1: Write the failing test**

Create `ClusterElementDefinitionServiceTest.java`:

```java
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

package com.bytechef.platform.component.service;

import static com.bytechef.component.definition.ComponentDsl.clusterElement;
import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.string;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ClusterElementDefinition.ClusterElementType;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.platform.component.ComponentDefinitionRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterElementDefinitionServiceTest {

    private static final ClusterElementType TOOLS = new ClusterElementType("TOOLS", "tools", "Tools", true, false);

    private final ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);
    private final ClusterElementDefinitionService clusterElementDefinitionService =
        new ClusterElementDefinitionServiceImpl(componentDefinitionRegistry);

    @Test
    void testGetClusterElementDefinitionStubsReadsFromStaticDefinitionsOnly() {
        ComponentDefinition componentDefinition = component("myComponent")
            .version(1)
            .icon("path:assets/icon.svg")
            .clusterElements(
                clusterElement("sendMessage")
                    .type(TOOLS)
                    .title("Send Message")
                    .description("Sends a message")
                    .properties(string("text")));

        when(componentDefinitionRegistry.getStaticComponentDefinitions()).thenReturn(List.of(componentDefinition));

        List<com.bytechef.platform.component.domain.ClusterElementDefinition> stubs =
            clusterElementDefinitionService.getClusterElementDefinitionStubs(TOOLS);

        assertThat(stubs).singleElement()
            .satisfies(stub -> {
                assertThat(stub.getComponentName()).isEqualTo("myComponent");
                assertThat(stub.getComponentVersion()).isEqualTo(1);
                assertThat(stub.getName()).isEqualTo("sendMessage");
                assertThat(stub.getTitle()).isEqualTo("Send Message");
                assertThat(stub.getDescription()).isEqualTo("Sends a message");
            });

        // Stub path must consult only the static (index-stub) definitions, never the full catalog.
        verify(componentDefinitionRegistry).getStaticComponentDefinitions();
        verifyNoMoreInteractions(componentDefinitionRegistry);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-service:test --tests "com.bytechef.platform.component.service.ClusterElementDefinitionServiceTest"`
Expected: FAIL — compilation error, `getClusterElementDefinitionStubs` is not defined on `ClusterElementDefinitionService`.

- [ ] **Step 3: Add the interface method**

In `ClusterElementDefinitionService.java`, immediately after the existing line 121 (`List<ClusterElementDefinition> getClusterElementDefinitions(ClusterElementType clusterElementType);`), add:

```java
    /**
     * List-view variant of {@link #getClusterElementDefinitions(ClusterElementType)} that returns lightweight stubs
     * (identity, texts, type — no property tree) sourced from the build-time component index without loading any
     * component handler. Use for enumeration/population; use the full method when property trees are required.
     */
    List<ClusterElementDefinition> getClusterElementDefinitionStubs(ClusterElementType clusterElementType);
```

- [ ] **Step 4: Implement the stub method (DRY: extract shared collector)**

In `ClusterElementDefinitionServiceImpl.java`, replace the existing method body at lines 437-455:

```java
    @Override
    public List<ClusterElementDefinition> getClusterElementDefinitions(ClusterElementType clusterElementType) {
        return componentDefinitionRegistry.getComponentDefinitions()
            .stream()
            .filter(componentDefinition -> componentDefinition.getClusterElements()
                .isPresent())
            .flatMap(componentDefinition -> CollectionUtils.stream(
                componentDefinition.getClusterElements()
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Cluster elements not found in component %s".formatted(componentDefinition.getName())))
                    .stream()
                    .filter(clusterElementDefinition -> clusterElementType.equals(clusterElementDefinition.getType()))
                    .map(clusterElementDefinition -> toClusterElementDefinition(
                        clusterElementDefinition, componentDefinition.getName(), componentDefinition.getVersion(),
                        getIcon(componentDefinition)))
                    .toList()))
            .distinct()
            .toList();
    }
```

with:

```java
    @Override
    public List<ClusterElementDefinition> getClusterElementDefinitions(ClusterElementType clusterElementType) {
        return collectClusterElementDefinitions(
            componentDefinitionRegistry.getComponentDefinitions(), clusterElementType);
    }

    @Override
    public List<ClusterElementDefinition> getClusterElementDefinitionStubs(ClusterElementType clusterElementType) {
        return collectClusterElementDefinitions(
            componentDefinitionRegistry.getStaticComponentDefinitions(), clusterElementType);
    }

    private List<ClusterElementDefinition> collectClusterElementDefinitions(
        List<com.bytechef.platform.component.domain.ComponentDefinition> componentDefinitions,
        ClusterElementType clusterElementType) {

        return componentDefinitions.stream()
            .filter(componentDefinition -> componentDefinition.getClusterElements()
                .isPresent())
            .flatMap(componentDefinition -> CollectionUtils.stream(
                componentDefinition.getClusterElements()
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Cluster elements not found in component %s".formatted(componentDefinition.getName())))
                    .stream()
                    .filter(clusterElementDefinition -> clusterElementType.equals(clusterElementDefinition.getType()))
                    .map(clusterElementDefinition -> toClusterElementDefinition(
                        clusterElementDefinition, componentDefinition.getName(), componentDefinition.getVersion(),
                        getIcon(componentDefinition)))
                    .toList()))
            .distinct()
            .toList();
    }
```

Note: `getComponentDefinitions()` and `getStaticComponentDefinitions()` both return
`List<com.bytechef.platform.component.domain.ComponentDefinition>` — confirm the import alias / fully
qualified name matches the file's existing usage (the file already references the domain
`ComponentDefinition`; use the same form it already imports rather than the fully-qualified name shown
here if an import exists).

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-service:test --tests "com.bytechef.platform.component.service.ClusterElementDefinitionServiceTest"`
Expected: PASS.

- [ ] **Step 6: Format, check, commit**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-service:spotlessApply
./gradlew :server:libs:platform:platform-component:platform-component-service:checkstyleMain :server:libs:platform:platform-component:platform-component-service:checkstyleTest :server:libs:platform:platform-component:platform-component-service:pmdMain :server:libs:platform:platform-component:platform-component-service:spotbugsMain
git add server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/service/ClusterElementDefinitionService.java server/libs/platform/platform-component/platform-component-service/src/main/java/com/bytechef/platform/component/service/ClusterElementDefinitionServiceImpl.java server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ClusterElementDefinitionServiceTest.java
git commit -m "732 Add index-served getClusterElementDefinitionStubs enumeration"
```

---

### Task 2: Feeder population from the stub enumeration

**Files:**
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/toolsearch/ToolSearchCatalogFeeder.java:168-170` (populate)
- Test: `server/ee/libs/ai/ai-hub/ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/toolsearch/ToolSearchCatalogFeederGlobalToolsTest.java` (add a case)

**Interfaces:**
- Consumes: `ClusterElementDefinitionService.getClusterElementDefinitionStubs(ClusterElementType)` from Task 1.

- [ ] **Step 1: Write the failing test**

In `ToolSearchCatalogFeederGlobalToolsTest.java`, add (the class already mocks
`clusterElementDefinitionService`, `vectorToolIndex`, `pgVectorJdbcTemplate` and constructs the feeder
with schema `"public"`):

```java
    @Test
    void testPopulateSourcesToolsFromStubEnumeration() {
        ToolSearchCatalogFeeder feeder = new ToolSearchCatalogFeeder(
            clusterElementDefinitionService, vectorToolIndex, pgVectorJdbcTemplate, "public");

        when(pgVectorJdbcTemplate.queryForObject(any(), eq(String.class), any()))
            .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        when(clusterElementDefinitionService.getClusterElementDefinitionStubs(BaseToolFunction.TOOLS))
            .thenReturn(List.of());

        feeder.populate();

        // Population must read the index-stub enumeration, never the full-load catalog method.
        verify(clusterElementDefinitionService).getClusterElementDefinitionStubs(BaseToolFunction.TOOLS);
        verify(clusterElementDefinitionService, org.mockito.Mockito.never())
            .getClusterElementDefinitions(BaseToolFunction.TOOLS);
    }
```

Add the import `import com.bytechef.component.definition.ai.agent.BaseToolFunction;` if not already present in the test file.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests "com.bytechef.ee.ai.hub.toolsearch.ToolSearchCatalogFeederGlobalToolsTest"`
Expected: FAIL — `getClusterElementDefinitionStubs` stubbed but `populate()` still calls `getClusterElementDefinitions`, so the `never()` verification fails (or the stubbed method is never invoked).

- [ ] **Step 3: Switch populate() to the stub method**

In `ToolSearchCatalogFeeder.java`, in `populate()` (line ~169), change:

```java
        List<ClusterElementDefinition> toolDefinitions =
            clusterElementDefinitionService.getClusterElementDefinitions(BaseToolFunction.TOOLS);
```

to:

```java
        List<ClusterElementDefinition> toolDefinitions =
            clusterElementDefinitionService.getClusterElementDefinitionStubs(BaseToolFunction.TOOLS);
```

(`buildSummary` uses only `getTitle()` / `getDescription()`, and `toolName` uses
`getComponentName()` / `getName()` — all present on the stub.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests "com.bytechef.ee.ai.hub.toolsearch.ToolSearchCatalogFeederGlobalToolsTest"`
Expected: PASS.

- [ ] **Step 5: Format, check, commit**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:spotlessApply
git add server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/toolsearch/ToolSearchCatalogFeeder.java server/ee/libs/ai/ai-hub/ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/toolsearch/ToolSearchCatalogFeederGlobalToolsTest.java
git commit -m "732 Source tool-search population from index-stub enumeration"
```

---

### Task 3: Lazy per-tool input schema in dispatch callbacks

**Files:**
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/toolsearch/ClusterElementToolCallback.java:65-123` (lazy schema)
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/toolsearch/ToolSearchAdvisorConfiguration.java:306-354` (build by name via stub method)
- Test: `server/ee/libs/ai/ai-hub/ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/toolsearch/ClusterElementToolCallbackLazySchemaTest.java` (create)

**Interfaces:**
- Consumes: `ClusterElementDefinitionService.getClusterElementDefinitionStubs(type)` (Task 1); `ClusterElementDefinitionService.getClusterElementDefinition(String componentName, int componentVersion, String clusterElementName)` (existing — loads exactly that one component and returns a domain `ClusterElementDefinition` with a real `getProperties()`); `JsonSchemaGeneratorUtils.generateInputSchema(List<? extends Property>)` (existing).
- Produces: a new `ClusterElementToolCallback` constructor overload without an eager `inputSchema` that generates it lazily and memoized.

- [ ] **Step 1: Write the failing test**

Create `ClusterElementToolCallbackLazySchemaTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.domain.ClusterElementDefinition;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.connection.service.ConnectionService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ClusterElementToolCallbackLazySchemaTest {

    private final ClusterElementDefinitionService clusterElementDefinitionService =
        mock(ClusterElementDefinitionService.class);
    private final ConnectionService connectionService = mock(ConnectionService.class);

    @Test
    void testInputSchemaIsGeneratedLazilyAndCached() {
        ClusterElementDefinition definition = mock(ClusterElementDefinition.class);

        when(definition.getProperties()).thenReturn(List.of());
        when(clusterElementDefinitionService.getClusterElementDefinition("slack", 1, "sendMessage"))
            .thenReturn(definition);

        ClusterElementToolCallback callback = new ClusterElementToolCallback(
            "slack_sendMessage", "Send a Slack message", "slack", 1, "sendMessage",
            clusterElementDefinitionService, connectionService);

        // Construction must not resolve the component (no schema generation yet).
        verifyNoInteractions(clusterElementDefinitionService);

        // Name is available without loading.
        assertThat(callback.getToolDefinition()
            .name()).isEqualTo("slack_sendMessage");

        // Accessing the schema twice resolves the component exactly once (memoized).
        callback.getToolDefinition()
            .inputSchema();
        callback.getToolDefinition()
            .inputSchema();

        verify(clusterElementDefinitionService, times(1)).getClusterElementDefinition("slack", 1, "sendMessage");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests "com.bytechef.ee.ai.hub.toolsearch.ClusterElementToolCallbackLazySchemaTest"`
Expected: FAIL — no `ClusterElementToolCallback(String, String, String, int, String, ClusterElementDefinitionService, ConnectionService)` constructor (the current one requires an `inputSchema` String as the 3rd arg).

- [ ] **Step 3: Make the input schema lazy in `ClusterElementToolCallback`**

In `ClusterElementToolCallback.java`, replace the field declaration at line 67:

```java
    private final String inputSchema;
```

with:

```java
    private final java.util.function.Supplier<String> inputSchemaSupplier;
```

Add a new lazy constructor (place it alongside the existing two constructors, before line 87):

```java
    /**
     * Catalog-discovery constructor: the input schema is generated lazily on first {@link #getToolDefinition()} access
     * by loading only this one component, and memoized. Keeps building the tool-search callback map free of component
     * loads — the schema materializes only when the tool is actually surfaced to the model.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ClusterElementToolCallback(
        String toolName, String description, String componentName, int componentVersion, String clusterElementName,
        ClusterElementDefinitionService clusterElementDefinitionService, ConnectionService connectionService) {

        this.toolName = toolName;
        this.description = description;
        this.componentName = componentName;
        this.componentVersion = componentVersion;
        this.clusterElementName = clusterElementName;
        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.connectionService = connectionService;
        this.pinnedConnectionId = null;
        this.pinnedParameters = Map.of();
        this.inputSchemaSupplier = com.bytechef.commons.util.MemoizationUtils.memoize(
            () -> com.bytechef.platform.component.util.JsonSchemaGeneratorUtils.generateInputSchema(
                clusterElementDefinitionService
                    .getClusterElementDefinition(componentName, componentVersion, clusterElementName)
                    .getProperties()));
    }
```

Update the two existing constructors so the eager `String inputSchema` argument is stored as a
constant supplier. In the delegating constructor at lines 98-114, replace:

```java
        this.inputSchema = inputSchema;
```

with:

```java
        this.inputSchemaSupplier = () -> inputSchema;
```

Update `getToolDefinition()` at lines 116-123:

```java
    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(toolName)
            .description(description)
            .inputSchema(inputSchemaSupplier.get())
            .build();
    }
```

Confirm the module already depends on `commons-util` (it does — `MemoizationUtils` is used by the
sibling `LazyToolCallingManager`) and on `platform-component-api` for `JsonSchemaGeneratorUtils`
(used elsewhere in this package). Prefer top-of-file imports over the fully-qualified names shown
here; Spotless will not add imports for you.

- [ ] **Step 4: Build the dispatch callbacks by name (no eager schema)**

In `ToolSearchAdvisorConfiguration.java`, in `buildClusterElementToolCallbacks` (lines 306-354),
change the enumeration source and the callback construction. Replace:

```java
        List<ClusterElementDefinition> toolDefinitions =
            clusterElementDefinitionService.getClusterElementDefinitions(BaseToolFunction.TOOLS);
```

with:

```java
        List<ClusterElementDefinition> toolDefinitions =
            clusterElementDefinitionService.getClusterElementDefinitionStubs(BaseToolFunction.TOOLS);
```

Then remove the eager `inputSchema` generation block and its try/catch (the lines that call
`JsonSchemaGeneratorUtils.generateInputSchema(toolDefinition.getProperties())`), and replace the
`new ClusterElementToolCallback(...)` construction with the lazy constructor:

```java
            ClusterElementToolCallback callback = new ClusterElementToolCallback(
                toolName, description, toolDefinition.getComponentName(),
                toolDefinition.getComponentVersion(), toolDefinition.getName(),
                clusterElementDefinitionService, connectionService);
```

Remove the now-unused import of `JsonSchemaGeneratorUtils` if this file no longer references it, and
delete the local `inputSchema` variable and the `continue`-on-failure branch that guarded schema
generation (the lazy path surfaces a malformed schema at first use, logged by the generator's own
caller; a broken single tool no longer blocks map construction).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests "com.bytechef.ee.ai.hub.toolsearch.ClusterElementToolCallbackLazySchemaTest" --tests "com.bytechef.ee.ai.hub.toolsearch.PinnedToolSearchToolCallingAdvisorTest"`
Expected: PASS (new lazy-schema test passes; existing advisor tests still green).

- [ ] **Step 6: Full module test + checks + commit**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:spotlessApply
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:checkstyleMain :server:ee:libs:ai:ai-hub:ai-hub-service:checkstyleTest :server:ee:libs:ai:ai-hub:ai-hub-service:pmdMain :server:ee:libs:ai:ai-hub:ai-hub-service:spotbugsMain :server:ee:libs:ai:ai-hub:ai-hub-service:test
git add server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/toolsearch/ClusterElementToolCallback.java server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/toolsearch/ToolSearchAdvisorConfiguration.java server/ee/libs/ai/ai-hub/ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/toolsearch/ClusterElementToolCallbackLazySchemaTest.java
git commit -m "732 Lazy per-tool input schema in tool-search dispatch callbacks"
```

---

### Task 4: GraphQL tool picker from the stub enumeration

**Files:**
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-graphql/src/main/java/com/bytechef/ee/ai/hub/web/graphql/AiHubTaskToolGraphQlController.java:126,408` (two call sites)

**Interfaces:**
- Consumes: `ClusterElementDefinitionService.getClusterElementDefinitionStubs(type)` (Task 1).

- [ ] **Step 1: Switch both list call sites to the stub method**

In `AiHubTaskToolGraphQlController.java`, change the call at line ~126:

```java
        List<ClusterElementDefinition> toolDefinitions =
            clusterElementDefinitionService.getClusterElementDefinitionStubs(BaseToolFunction.TOOLS);
```

and the call at line ~408:

```java
        for (ClusterElementDefinition definition : clusterElementDefinitionService
            .getClusterElementDefinitionStubs(BaseToolFunction.TOOLS)) {
```

Both sites use only `getComponentName()` / `getComponentVersion()` / `getName()` / `getTitle()` /
`getDescription()` — all present on the stub. If either site reads `getProperties()` or output
schema, STOP and leave that site on the full method (re-verify before changing).

- [ ] **Step 2: Compile the module**

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-graphql:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run any existing controller tests**

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-graphql:test`
Expected: PASS (no behavior change — the picker lists the same tool identity/text, now without loading components).

- [ ] **Step 4: Format and commit**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-graphql:spotlessApply
git add server/ee/libs/ai/ai-hub/ai-hub-graphql/src/main/java/com/bytechef/ee/ai/hub/web/graphql/AiHubTaskToolGraphQlController.java
git commit -m "732 client - Serve AI Hub tool picker from index-stub enumeration"
```

Note: this file is server-side (GraphQL controller), so use the server commit convention
`732 <description>` — drop the `client -` shown above if the repo treats `ai-hub-graphql` as server.

---

### Task 5: End-to-end verification

**Files:** none (observation only).

- [ ] **Step 1: Rebuild and run the server with AI Hub enabled**

Ensure `bytechef.ai.hub.enabled=true` (already set in `application-local.yml`). Rebuild so the
component index regenerates:

```bash
./gradlew :server:apps:server-app:classes
```

- [ ] **Step 2: Trigger a devtools restart and watch the startup + first-turn logs**

Confirm the `Loaded component 'X' on demand` flood does NOT appear for the whole catalog at startup or
on the first AI Hub chat turn. Open AI Hub, send one message, and confirm only the components whose
tools are actually surfaced by `searchTool` load on demand. Execution of a tool still loads exactly
that one component.

- [ ] **Step 3: Confirm no regression with an absent index (fallback path)**

Because `getStaticComponentDefinitions()` falls back to the full map when no index is present, an EE
app without a generated index still serves the same tool set (loading on first access). No code
change — just note this is covered by the registry's existing fallback and Task 1's reliance on it.

---

## Self-Review

- **Spec coverage:** §1 (stub enumeration) → Task 1; §2 (population) → Task 2; §3 (lazy dispatch) → Task 3; GraphQL picker → Task 4; validation-timing tradeoff → covered by the lazy path in Task 3 + verification in Task 5; testing → per-task tests + Task 5. All spec sections mapped.
- **Placeholder scan:** No TBD/TODO. Each code step shows complete code. Fully-qualified names are used where an import may be missing, with a note to prefer imports.
- **Type consistency:** `getClusterElementDefinitionStubs(ClusterElementType)` returns `List<com.bytechef.platform.component.domain.ClusterElementDefinition>` consistently across Tasks 1-4. The new `ClusterElementToolCallback` constructor signature `(String toolName, String description, String componentName, int componentVersion, String clusterElementName, ClusterElementDefinitionService, ConnectionService)` matches between Task 3 Step 3 (definition) and Step 4 (call site) and the Task 3 test.
- **Open risk to verify during Task 3/4:** confirm the exact import form for the domain `ComponentDefinition` in `ClusterElementDefinitionServiceImpl` (Step 4 helper param type) and that `JsonSchemaGeneratorUtils`/`MemoizationUtils` imports resolve in `ai-hub-service`; both are used by siblings, so the dependencies exist.
