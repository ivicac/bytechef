# Context Store Copilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an ASK/BUILD Copilot to the Context Store detail page, backed by a shared tool library and a delegating `context_store_agent` AI Hub subagent — the tracer-bullet first slice of the Domain Copilot pattern (Design: `docs/superpowers/specs/2026-07-17-domain-copilot-context-store-kb-datatable-design.md`).

**Architecture:** Move the 11 existing Context Store `ToolCallback` classes out of `ai-hub-service` into the shared EE tool lib, swapping their AI-Hub-specific invocation context for the surface-neutral `AgentToolInvocationContext` so both the Copilot panel and the AI Hub can use them. Add a new top-level `deleteContextStore` tool. Wire two Copilot source agents (`CONTEXT_STORE_ASK` / `CONTEXT_STORE_BUILD`) plus two stateless subagent chat clients; expose the BUILD subagent to the AI Hub as `context_store_agent` and remove the flat Context Store tool registrations from `AiHubConfiguration`. Add the frontend `Source.CONTEXT_STORE` entry, a Copilot trigger on the Context Store detail page, and post-turn query invalidation.

**Tech Stack:** Java 21, Spring Boot, Spring AI (`ToolCallback`, `ChatClient`), Gradle; React + TypeScript, Zustand, GraphQL (frontend).

## Global Constraints

- Enterprise-licensed files use the ByteChef Enterprise License header (copy the header verbatim from any existing file in `ee/`); Apache-licensed files use the Apache 2.0 header (copy from any `libs/` file). Match the header to the module the file lives in.
- Shared Context Store tools live in the EE shared lib `server/ee/libs/automation/automation-ai/automation-ai-tool`, package `com.bytechef.ee.automation.ai.tool` — the same lib SP-B's `CustomComponentTools` uses. Never introduce a dependency from `ai-copilot-*` onto `ai-hub-*`.
- Tools resolve workspace/environment from `AgentToolInvocationContext.fromToolContext(toolContext)` (keys under `bytechef.agentTool.*`) — never from client-supplied tool input for workspace/user/environment.
- Tool error responses use `ToolErrors.toolError(jsonMapper, message)` and `ToolErrors.runtimeFailure(jsonMapper, <Class>.class, TOOL_NAME, exception)` — the existing convention in every ported class.
- Every backend change compiles and passes via `./gradlew :server:ee:libs:...:<module>:test` for the touched module; frontend via `npm --prefix client test`.
- Frontend `Source` enum (`useCopilotStore.ts`) and backend `Source` enum (`com.bytechef.ai.copilot.util.Source`) must stay in sync — add `CONTEXT_STORE` to both.

---

## File Structure

**Shared tool lib** — `server/ee/libs/automation/automation-ai/automation-ai-tool/src/main/java/com/bytechef/ee/automation/ai/tool/contextstore/`
- Move here (from `ee/libs/ai/ai-hub/ai-hub-service/.../tool/`): `ListContextSourcesToolCallback`, `SearchContextStoreToolCallback`, `GetContextStoreRecordToolCallback`, `SemanticSearchContextStoreToolCallback`, `ListAvailableSourceComponentsToolCallback`, `DescribeSourceComponentEntitiesToolCallback`, `CreateContextStoreSourceToolCallback`, `UpdateContextStoreSourceToolCallback`, `DeleteContextStoreSourceToolCallback`, `RefreshContextStoreSourceToolCallback`, `SetContextStoreSourceEnabledToolCallback`.
- Create: `DeleteContextStoreToolCallback` (new top-level store delete).
- Create: `ContextStoreToolCallbacksFactory` — builds the ASK (read) list and BUILD (read+write) list from injected services/facades, consumed by both surfaces.

**Copilot service** — `server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/`
- Modify `config/CopilotConfiguration.java`: add `contextStoreAskSpringAIAgent`, `contextStoreBuildSpringAIAgent`, `contextStoreAskSubAgentChatClient`, `contextStoreBuildSubAgentChatClient` beans + two prompt resource fields.
- Create `agent/ContextStoreSpringAIAgent.java` (thin subclass mirroring `SkillsSpringAIAgent`).
- Modify `config/ToolCallbackContributorConfiguration.java`: register `ContextStoreAgentToolCallback`.
- Create resources `resources/prompt_context_store_ask.txt`, `resources/prompt_context_store_build.txt`.

**Copilot tool** — `server/libs/ai/ai-copilot/ai-copilot-tool/src/main/java/com/bytechef/ai/copilot/tool/`
- Create `ContextStoreAgentToolCallback.java` (mirrors `SkillsAgentToolCallback`).
- Modify `CopilotAgentType.java`: add `CONTEXT_STORE_ASK`, `CONTEXT_STORE_BUILD`, `CONTEXT_STORE`, `CONTEXT_STORE_AGENT` entries.

**Copilot api** — `server/libs/ai/ai-copilot/ai-copilot-api/src/main/java/com/bytechef/ai/copilot/util/Source.java`: add `CONTEXT_STORE`.

**AI Hub** — `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/config/AiHubConfiguration.java`: remove the flat Context Store registrations; add the `context_store_agent` subagent (via the contributor). Delete the moved `tool/*ContextStore*`/`*Source*` classes' old locations.

**Frontend** — `client/src/`
- Modify `shared/components/copilot/stores/useCopilotStore.ts`: add `Source.CONTEXT_STORE`.
- Modify `pages/automation/context-store/ContextStoreSources.tsx`: add the Copilot trigger + post-turn invalidation.

---

## Task 1: Add `CONTEXT_STORE` to both Source enums

**Files:**
- Modify: `server/libs/ai/ai-copilot/ai-copilot-api/src/main/java/com/bytechef/ai/copilot/util/Source.java`
- Modify: `client/src/shared/components/copilot/stores/useCopilotStore.ts:13-22`

**Interfaces:**
- Produces: backend `Source.CONTEXT_STORE`; frontend `Source.CONTEXT_STORE = 'CONTEXT_STORE'`. Later tasks build agent bean names as `Source.CONTEXT_STORE.name() + "_" + Mode.ASK.name()` → `"CONTEXT_STORE_ASK"`.

- [ ] **Step 1: Add the backend enum constant**

In `Source.java`, add `CONTEXT_STORE` to the enum list:

```java
public enum Source {

    WORKFLOW_EDITOR, CODE_EDITOR, CONVERTER, CLUSTER_ELEMENT, SKILLS, JSON_SCHEMA_BUILDER, SAMPLE_OUTPUT,
    WORKFLOW_EXECUTION, WORKFLOW_CODE_EDITOR, CONTEXT_STORE
}
```

- [ ] **Step 2: Add the frontend enum constant**

In `useCopilotStore.ts`, add to the `Source` enum:

```ts
export enum Source {
    WORKFLOW_EXECUTION = 'WORKFLOW_EXECUTION',
    WORKFLOW_EDITOR = 'WORKFLOW_EDITOR',
    CODE_EDITOR = 'CODE_EDITOR',
    CLUSTER_ELEMENT = 'CLUSTER_ELEMENT',
    SKILLS = 'SKILLS',
    WORKFLOW_CODE_EDITOR = 'WORKFLOW_CODE_EDITOR',
    JSON_SCHEMA_BUILDER = 'JSON_SCHEMA_BUILDER',
    SAMPLE_OUTPUT = 'SAMPLE_OUTPUT',
    CONTEXT_STORE = 'CONTEXT_STORE',
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:libs:ai:ai-copilot:ai-copilot-api:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add server/libs/ai/ai-copilot/ai-copilot-api/src/main/java/com/bytechef/ai/copilot/util/Source.java client/src/shared/components/copilot/stores/useCopilotStore.ts
git commit -m "feat(copilot): add CONTEXT_STORE source enum on both surfaces"
```

---

## Task 2: Move the 11 Context Store tool callbacks to the shared EE tool lib

This is a mechanical relocation. Each class keeps its logic; only the package, license header context, and invocation-context type change. Do all 11 in one task — they move together and share one build.

**Files (move each `.java` from → to):**
- From `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/tool/`
- To `server/ee/libs/automation/automation-ai/automation-ai-tool/src/main/java/com/bytechef/ee/automation/ai/tool/contextstore/`

Classes: `ListContextSourcesToolCallback`, `SearchContextStoreToolCallback`, `GetContextStoreRecordToolCallback`, `SemanticSearchContextStoreToolCallback`, `ListAvailableSourceComponentsToolCallback`, `DescribeSourceComponentEntitiesToolCallback`, `CreateContextStoreSourceToolCallback`, `UpdateContextStoreSourceToolCallback`, `DeleteContextStoreSourceToolCallback`, `RefreshContextStoreSourceToolCallback`, `SetContextStoreSourceEnabledToolCallback`. Move their `*Test` counterparts from `.../ai-hub-service/src/test/.../tool/` to `.../automation-ai-tool/src/test/.../tool/contextstore/` as well.

**Interfaces:**
- Consumes: `AgentToolInvocationContext` (from `com.bytechef.ai.copilot.tool.context`) — replaces `AiHubToolInvocationContext`. Both expose `.workspaceId()`. Add a dependency in the shared lib's `build.gradle` on the module providing `AgentToolInvocationContext` (`ai-copilot-tool`) if not already present.
- Produces: 11 relocated `ToolCallback` classes in package `com.bytechef.ee.automation.ai.tool.contextstore`, each with an unchanged `TOOL_NAME` and public constructor.

- [ ] **Step 1: Confirm the module dependency exists**

Run: `grep -n "ai-copilot-tool\|automation-context-store\|platform-context-store" server/ee/libs/automation/automation-ai/automation-ai-tool/build.gradle`
Expected: the context-store service/api and `ai-copilot-tool` modules appear; if any are missing, add them as `implementation project(':server:...')` lines mirroring `ai-hub-service`'s `build.gradle` entries for the same modules.

- [ ] **Step 2: Move the classes and rewrite package + invocation context**

For each of the 11 files: `git mv` it to the new directory, change the `package` line to `com.bytechef.ee.automation.ai.tool.contextstore;`, and in the classes that read the invocation context (`ListContextSourcesToolCallback`, `SearchContextStoreToolCallback`, `GetContextStoreRecordToolCallback`, `SemanticSearchContextStoreToolCallback`) replace:

```java
AiHubToolInvocationContext invocationContext = AiHubToolInvocationContext.fromToolContext(toolContext);
Long workspaceId = invocationContext == null ? null : invocationContext.workspaceId();
```

with:

```java
AgentToolInvocationContext invocationContext = AgentToolInvocationContext.fromToolContext(toolContext);
Long workspaceId = invocationContext == null ? null : invocationContext.workspaceId();
```

Update the corresponding `import` from `com.bytechef.ee.ai.hub.tool.AiHubToolInvocationContext` to `com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext`. The `DescribeSourceComponentEntitiesToolCallback` and `ListAvailableSourceComponentsToolCallback` take no workspace context — only the package line changes.

- [ ] **Step 3: Update the moved tests' package + imports**

Change each moved `*Test` file's `package` to `...tool.contextstore;` and, where a test builds an AI-Hub tool context, switch it to build an `AgentToolInvocationContext` tool context (use `AgentToolInvocationContext.builder().workspaceId(1L).environmentId(0L).build().toToolContext()`).

- [ ] **Step 4: Run the moved tests**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:ee:libs:automation:automation-ai:automation-ai-tool:test -q`
Expected: PASS (all relocated tests green).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(context-store): move copilot tool callbacks to shared automation-ai-tool lib"
```

---

## Task 3: Add the new `deleteContextStore` tool

**Files:**
- Create: `server/ee/libs/automation/automation-ai/automation-ai-tool/src/main/java/com/bytechef/ee/automation/ai/tool/contextstore/DeleteContextStoreToolCallback.java`
- Test: `server/ee/libs/automation/automation-ai/automation-ai-tool/src/test/java/com/bytechef/ee/automation/ai/tool/contextstore/DeleteContextStoreToolCallbackTest.java`

**Interfaces:**
- Consumes: `WorkspaceContextStoreFacade.deleteWorkspaceContextStore(Long workspaceId, Long contextStoreId)`; workspace from `AgentToolInvocationContext.workspaceId()`.
- Produces: `DeleteContextStoreToolCallback` with `static final String TOOL_NAME = "deleteContextStore"` and constructor `DeleteContextStoreToolCallback(WorkspaceContextStoreFacade)`.

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.ee.automation.ai.tool.contextstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreFacade;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;

class DeleteContextStoreToolCallbackTest {

    private final WorkspaceContextStoreFacade facade = Mockito.mock(WorkspaceContextStoreFacade.class);
    private final DeleteContextStoreToolCallback toolCallback = new DeleteContextStoreToolCallback(facade);

    @Test
    void deletesStoreScopedToContextWorkspace() {
        ToolContext toolContext = new ToolContext(
            AgentToolInvocationContext.builder()
                .workspaceId(7L)
                .build()
                .toToolContext());

        String result = toolCallback.call("{\"id\": 42}", toolContext);

        verify(facade).deleteWorkspaceContextStore(7L, 42L);
        assertThat(result).contains("\"deleted\":true");
    }

    @Test
    void rejectsMissingWorkspaceContext() {
        String result = toolCallback.call("{\"id\": 42}", new ToolContext(Map.of()));

        assertThat(result).contains("Workspace context unavailable");
    }

    @Test
    void rejectsMissingId() {
        ToolContext toolContext = new ToolContext(
            AgentToolInvocationContext.builder()
                .workspaceId(7L)
                .build()
                .toToolContext());

        String result = toolCallback.call("{}", toolContext);

        assertThat(result).contains("id is required");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:ee:libs:automation:automation-ai:automation-ai-tool:test --tests "*DeleteContextStoreToolCallbackTest" -q`
Expected: FAIL — `DeleteContextStoreToolCallback` does not exist / cannot be resolved.

- [ ] **Step 3: Implement the tool**

Use the ByteChef Enterprise License header (copy from `DeleteContextStoreSourceToolCallback`).

```java
package com.bytechef.ee.automation.ai.tool.contextstore;

import com.bytechef.ai.agent.tool.ToolErrors;
import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deletes an entire Context Store (and, by FK cascade, all its sources, entities, and records) scoped to the
 * invocation's workspace. Irreversible — the caller must confirm with the user first.
 *
 * @author Ivica Cardic
 * @version ee
 */
public class DeleteContextStoreToolCallback implements ToolCallback {

    static final String TOOL_NAME = "deleteContextStore";

    private static final String DESCRIPTION = """
        Delete an entire Context Store. Cascade deletes every source, entity, and record it contains. Irreversible.
        Always confirm with the user before calling.""";

    private static final String INPUT_SCHEMA = """
        {
            "type": "object",
            "properties": {
                "id": {"type": "integer", "description": "Context Store id to delete"}
            },
            "required": ["id"]
        }""";

    private final WorkspaceContextStoreFacade workspaceContextStoreFacade;
    private final JsonMapper jsonMapper = new JsonMapper();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public DeleteContextStoreToolCallback(WorkspaceContextStoreFacade workspaceContextStoreFacade) {
        this.workspaceContextStoreFacade = workspaceContextStoreFacade;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(INPUT_SCHEMA)
            .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        try {
            DeleteContextStoreToolInput input = jsonMapper.readValue(toolInput, DeleteContextStoreToolInput.class);

            if (input.id() == null) {
                return toolError("id is required");
            }

            AgentToolInvocationContext invocationContext = AgentToolInvocationContext.fromToolContext(toolContext);
            Long workspaceId = invocationContext == null ? null : invocationContext.workspaceId();

            if (workspaceId == null) {
                return toolError("Workspace context unavailable - open this chat from a workspace.");
            }

            workspaceContextStoreFacade.deleteWorkspaceContextStore(workspaceId, input.id());

            return jsonMapper.writeValueAsString(Map.of("deleted", true, "id", input.id()));
        } catch (JacksonException exception) {
            return toolError("Invalid tool input: " + exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return toolError(exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolErrors.runtimeFailure(jsonMapper, DeleteContextStoreToolCallback.class, TOOL_NAME, exception);
        }
    }

    private String toolError(String message) {
        return ToolErrors.toolError(jsonMapper, message);
    }

    public record DeleteContextStoreToolInput(Long id) {
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:ee:libs:automation:automation-ai:automation-ai-tool:test --tests "*DeleteContextStoreToolCallbackTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/automation/automation-ai/automation-ai-tool/src/main/java/com/bytechef/ee/automation/ai/tool/contextstore/DeleteContextStoreToolCallback.java server/ee/libs/automation/automation-ai/automation-ai-tool/src/test/java/com/bytechef/ee/automation/ai/tool/contextstore/DeleteContextStoreToolCallbackTest.java
git commit -m "feat(context-store): add deleteContextStore tool"
```

---

## Task 4: Add the tool-list factory (ASK read list + BUILD read/write list)

**Files:**
- Create: `server/ee/libs/automation/automation-ai/automation-ai-tool/src/main/java/com/bytechef/ee/automation/ai/tool/contextstore/ContextStoreToolCallbacksFactory.java`
- Test: `.../test/.../contextstore/ContextStoreToolCallbacksFactoryTest.java`

**Interfaces:**
- Consumes: `WorkspaceContextStoreSourceService`, `ContextStoreQueryService`, `WorkspaceContextStoreSourceFacade`, `WorkspaceContextStoreFacade`, `ContextStoreSemanticSearchService` (nullable), `ClusterElementDefinitionService`. (Constructor signatures for the existing tool callbacks are unchanged from their old ai-hub registration — see `AiHubConfiguration.registerContextStoreReadOnlyToolCallbacks` / `registerContextStoreToolCallbacks`.)
- Produces: `List<ToolCallback> readToolCallbacks()` (list/search/get-record/semantic-search/list-available/describe) and `List<ToolCallback> writeToolCallbacks()` (read list + create/update/delete-source/refresh/set-enabled/**deleteContextStore**).

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.ee.automation.ai.tool.contextstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreFacade;
import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreSourceFacade;
import com.bytechef.ee.automation.contextstore.service.WorkspaceContextStoreSourceService;
import com.bytechef.ee.platform.contextstore.service.ContextStoreQueryService;
import com.bytechef.ee.platform.contextstore.service.ContextStoreSemanticSearchService;
import com.bytechef.platform.component.definition.service.ClusterElementDefinitionService;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;

class ContextStoreToolCallbacksFactoryTest {

    private final ContextStoreToolCallbacksFactory factory = new ContextStoreToolCallbacksFactory(
        Mockito.mock(WorkspaceContextStoreSourceService.class),
        Mockito.mock(ContextStoreQueryService.class),
        Mockito.mock(WorkspaceContextStoreSourceFacade.class),
        Mockito.mock(WorkspaceContextStoreFacade.class),
        Mockito.mock(ContextStoreSemanticSearchService.class),
        Mockito.mock(ClusterElementDefinitionService.class));

    @Test
    void readListExcludesMutations() {
        List<String> names = toolNames(factory.readToolCallbacks());

        assertThat(names).contains("listContextSources", "searchContextStore");
        assertThat(names).doesNotContain("deleteContextStore", "createContextStoreSource");
    }

    @Test
    void writeListIncludesReadsAndMutationsAndTopLevelDelete() {
        List<String> names = toolNames(factory.writeToolCallbacks());

        assertThat(names).contains(
            "listContextSources", "createContextStoreSource", "deleteContextStoreSource", "deleteContextStore");
    }

    private static List<String> toolNames(List<ToolCallback> toolCallbacks) {
        return toolCallbacks.stream()
            .map(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .collect(Collectors.toList());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:ee:libs:automation:automation-ai:automation-ai-tool:test --tests "*ContextStoreToolCallbacksFactoryTest" -q`
Expected: FAIL — factory class missing.

- [ ] **Step 3: Implement the factory**

Enterprise header. Reconstruct the two lists using the exact constructor arguments from the old `AiHubConfiguration` registration methods (read the current `registerContextStoreReadOnlyToolCallbacks`, `registerContextStoreSemanticSearchToolCallback`, and `registerContextStoreToolCallbacks` bodies for the precise constructor argument order). Structure:

```java
package com.bytechef.ee.automation.ai.tool.contextstore;

import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreFacade;
import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreSourceFacade;
import com.bytechef.ee.automation.contextstore.service.WorkspaceContextStoreSourceService;
import com.bytechef.ee.platform.contextstore.service.ContextStoreQueryService;
import com.bytechef.ee.platform.contextstore.service.ContextStoreSemanticSearchService;
import com.bytechef.platform.component.definition.service.ClusterElementDefinitionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

/**
 * Builds the Context Store tool-callback lists shared by the Copilot source agents and the AI Hub
 * {@code context_store_agent} subagent. Read list feeds ASK; write list feeds BUILD.
 *
 * @author Ivica Cardic
 * @version ee
 */
public class ContextStoreToolCallbacksFactory {

    private final WorkspaceContextStoreSourceService workspaceContextStoreSourceService;
    private final ContextStoreQueryService contextStoreQueryService;
    private final WorkspaceContextStoreSourceFacade workspaceContextStoreSourceFacade;
    private final WorkspaceContextStoreFacade workspaceContextStoreFacade;
    private final @Nullable ContextStoreSemanticSearchService contextStoreSemanticSearchService;
    private final ClusterElementDefinitionService clusterElementDefinitionService;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ContextStoreToolCallbacksFactory(
        WorkspaceContextStoreSourceService workspaceContextStoreSourceService,
        ContextStoreQueryService contextStoreQueryService,
        WorkspaceContextStoreSourceFacade workspaceContextStoreSourceFacade,
        WorkspaceContextStoreFacade workspaceContextStoreFacade,
        @Nullable ContextStoreSemanticSearchService contextStoreSemanticSearchService,
        ClusterElementDefinitionService clusterElementDefinitionService) {

        this.workspaceContextStoreSourceService = workspaceContextStoreSourceService;
        this.contextStoreQueryService = contextStoreQueryService;
        this.workspaceContextStoreSourceFacade = workspaceContextStoreSourceFacade;
        this.workspaceContextStoreFacade = workspaceContextStoreFacade;
        this.contextStoreSemanticSearchService = contextStoreSemanticSearchService;
        this.clusterElementDefinitionService = clusterElementDefinitionService;
    }

    public List<ToolCallback> readToolCallbacks() {
        List<ToolCallback> toolCallbacks = new ArrayList<>();

        toolCallbacks.add(new ListContextSourcesToolCallback(workspaceContextStoreSourceService));
        toolCallbacks.add(
            new SearchContextStoreToolCallback(contextStoreQueryService, workspaceContextStoreSourceService));
        toolCallbacks.add(
            new GetContextStoreRecordToolCallback(contextStoreQueryService, workspaceContextStoreSourceService));
        toolCallbacks.add(new ListAvailableSourceComponentsToolCallback());
        toolCallbacks.add(new DescribeSourceComponentEntitiesToolCallback(clusterElementDefinitionService));

        if (contextStoreSemanticSearchService != null) {
            toolCallbacks.add(
                new SemanticSearchContextStoreToolCallback(
                    contextStoreSemanticSearchService, workspaceContextStoreSourceService));
        }

        return toolCallbacks;
    }

    public List<ToolCallback> writeToolCallbacks() {
        List<ToolCallback> toolCallbacks = new ArrayList<>(readToolCallbacks());

        toolCallbacks.add(new CreateContextStoreSourceToolCallback(workspaceContextStoreSourceFacade));
        toolCallbacks.add(new UpdateContextStoreSourceToolCallback(workspaceContextStoreSourceFacade));
        toolCallbacks.add(
            new DeleteContextStoreSourceToolCallback(
                workspaceContextStoreSourceFacade, workspaceContextStoreSourceService));
        toolCallbacks.add(new RefreshContextStoreSourceToolCallback(workspaceContextStoreSourceFacade));
        toolCallbacks.add(new SetContextStoreSourceEnabledToolCallback(workspaceContextStoreSourceFacade));
        toolCallbacks.add(new DeleteContextStoreToolCallback(workspaceContextStoreFacade));

        return toolCallbacks;
    }
}
```

> Note: verify the exact constructor argument lists for `UpdateContextStoreSourceToolCallback`, `RefreshContextStoreSourceToolCallback`, and `SetContextStoreSourceEnabledToolCallback` against the old `registerContextStoreToolCallbacks` body (some may take an extra service). Match them exactly.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:ee:libs:automation:automation-ai:automation-ai-tool:test --tests "*ContextStoreToolCallbacksFactoryTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/automation/automation-ai/automation-ai-tool/src/main/java/com/bytechef/ee/automation/ai/tool/contextstore/ContextStoreToolCallbacksFactory.java server/ee/libs/automation/automation-ai/automation-ai-tool/src/test/java/com/bytechef/ee/automation/ai/tool/contextstore/ContextStoreToolCallbacksFactoryTest.java
git commit -m "feat(context-store): add tool-list factory for ASK/BUILD"
```

---

## Task 5: Add Copilot prompt resources + `ContextStoreSpringAIAgent`

**Files:**
- Create: `server/libs/ai/ai-copilot/ai-copilot-service/src/main/resources/prompt_context_store_ask.txt`
- Create: `server/libs/ai/ai-copilot/ai-copilot-service/src/main/resources/prompt_context_store_build.txt`
- Create: `server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/agent/ContextStoreSpringAIAgent.java`

**Interfaces:**
- Produces: `ContextStoreSpringAIAgent` with a `builder()` mirroring `SkillsSpringAIAgent` (fields: `agentId`, `chatMemory`, `chatModel`, `systemMessage`, `state`, `toolCallbacks`, `overrideChatClientResolver`). Task 6 uses this builder.

- [ ] **Step 1: Create the ASK prompt** (`prompt_context_store_ask.txt`)

Draft content (product to refine later — this is a working default, not a placeholder):

```
You are the Context Store assistant in ByteChef, embedded in the Context Store detail page.

You help the user understand and inspect a Context Store and its sources: what sources exist, their
sync status (BUILDING_PREVIEW, PREVIEW, READY, FAILED, DISABLED), the record shape (entity name,
id field, indexed fields) each source exposes, and the data inside them.

Use `listContextSources` first to discover sources and their indexed-field schemas. Use
`searchContextStore` / `getContextStoreRecord` (and `semanticSearchContextStore` when available) to
answer questions about the stored data. Use `listAvailableSourceComponents` /
`describeSourceComponentEntities` to explain what source types can be added.

You are READ-ONLY. Never create, update, delete, refresh, enable, or disable anything. If the user
asks to change something, explain what you would change and tell them to switch to Build mode.

Be concise. Cite source names and ids. If workspace context is unavailable, say so.
```

- [ ] **Step 2: Create the BUILD prompt** (`prompt_context_store_build.txt`)

```
You are the Context Store builder in ByteChef, embedded in the Context Store detail page.

You can inspect AND modify Context Stores and their sources. Available actions: add a source
(`createContextStoreSource`), update a source (`updateContextStoreSource`), refresh a source's sync
(`refreshContextStoreSource`), enable/disable a source (`setContextStoreSourceEnabled`), delete a
source (`deleteContextStoreSource`), and delete an entire Context Store (`deleteContextStore`).

Always call `listContextSources` first to ground yourself in the current state. Before any
irreversible action (delete source, delete store), state exactly what will be removed and get the
user's explicit confirmation in the conversation before calling the tool.

Use `listAvailableSourceComponents` / `describeSourceComponentEntities` to pick the right source
component and entity when adding a source. Be concise; report the ids you created or changed.
```

- [ ] **Step 3: Create `ContextStoreSpringAIAgent`**

Read `server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/agent/SkillsSpringAIAgent.java` and create `ContextStoreSpringAIAgent` as an identical structure with the class name changed (it overrides `toToolContext` to return `CopilotToolContextUtils.toToolContext(input.state())`, exactly like `SkillsSpringAIAgent:62`). No behavioural difference — the agent identity comes from its `agentId`, system prompt, and tool list supplied at construction.

- [ ] **Step 4: Verify compilation**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:libs:ai:ai-copilot:ai-copilot-service:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add server/libs/ai/ai-copilot/ai-copilot-service/src/main/resources/prompt_context_store_ask.txt server/libs/ai/ai-copilot/ai-copilot-service/src/main/resources/prompt_context_store_build.txt server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/agent/ContextStoreSpringAIAgent.java
git commit -m "feat(copilot): add context store agent class + ask/build prompts"
```

---

## Task 6: Wire the Copilot source agents + subagent chat clients in `CopilotConfiguration`

**Files:**
- Modify: `server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/config/CopilotConfiguration.java`

**Interfaces:**
- Consumes: `ContextStoreToolCallbacksFactory` (Task 4), `ContextStoreSpringAIAgent` (Task 5), the existing private fields `state`, `securityContextRehydrator` usage via `wrapTools(...)`, `getSystemPrompt(Resource)`, and the two new prompt `Resource` fields.
- Produces beans: `contextStoreAskSpringAIAgent`, `contextStoreBuildSpringAIAgent` (keyed `CONTEXT_STORE_ASK` / `CONTEXT_STORE_BUILD`), `contextStoreAskSubAgentChatClient`, `contextStoreBuildSubAgentChatClient`.

- [ ] **Step 1: Add prompt resource fields**

Near the other `@Value("classpath:prompt_*.txt") private Resource promptSkills*Resource;` declarations, add:

```java
@Value("classpath:prompt_context_store_ask.txt")
private Resource promptContextStoreAskResource;

@Value("classpath:prompt_context_store_build.txt")
private Resource promptContextStoreBuildResource;
```

- [ ] **Step 2: Add a factory bean**

```java
@Bean
ContextStoreToolCallbacksFactory contextStoreToolCallbacksFactory(
    WorkspaceContextStoreSourceService workspaceContextStoreSourceService,
    ContextStoreQueryService contextStoreQueryService,
    WorkspaceContextStoreSourceFacade workspaceContextStoreSourceFacade,
    WorkspaceContextStoreFacade workspaceContextStoreFacade,
    ObjectProvider<ContextStoreSemanticSearchService> contextStoreSemanticSearchServiceProvider,
    ClusterElementDefinitionService clusterElementDefinitionService) {

    return new ContextStoreToolCallbacksFactory(
        workspaceContextStoreSourceService, contextStoreQueryService, workspaceContextStoreSourceFacade,
        workspaceContextStoreFacade, contextStoreSemanticSearchServiceProvider.getIfAvailable(),
        clusterElementDefinitionService);
}
```

- [ ] **Step 3: Add the two source-agent beans** (model exactly on `skillsAskSpringAIAgent` / `skillsBuildSpringAIAgent`, `CopilotConfiguration.java:505-556`)

```java
@Bean
ContextStoreSpringAIAgent contextStoreAskSpringAIAgent(
    ChatMemory chatMemory, ChatModel chatModel, ContextStoreToolCallbacksFactory contextStoreToolCallbacksFactory,
    SecurityContextRehydrator securityContextRehydrator,
    ObjectProvider<OverrideChatClientResolver> overrideChatClientResolverProvider)
    throws AGUIException {

    String name = Source.CONTEXT_STORE.name() + "_" + Mode.ASK.name();

    return ContextStoreSpringAIAgent.builder()
        .agentId(name.toLowerCase())
        .chatMemory(chatMemory)
        .chatModel(chatModel)
        .systemMessage(getSystemPrompt(promptContextStoreAskResource))
        .state(state)
        .toolCallbacks(
            wrapToolCallbacks(securityContextRehydrator, contextStoreToolCallbacksFactory.readToolCallbacks()))
        .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
        .build();
}

@Bean
ContextStoreSpringAIAgent contextStoreBuildSpringAIAgent(
    ChatMemory chatMemory, ChatModel chatModel, ContextStoreToolCallbacksFactory contextStoreToolCallbacksFactory,
    SecurityContextRehydrator securityContextRehydrator,
    ObjectProvider<OverrideChatClientResolver> overrideChatClientResolverProvider)
    throws AGUIException {

    String name = Source.CONTEXT_STORE.name() + "_" + Mode.BUILD.name();

    return ContextStoreSpringAIAgent.builder()
        .agentId(name.toLowerCase())
        .chatMemory(chatMemory)
        .chatModel(chatModel)
        .systemMessage(getSystemPrompt(promptContextStoreBuildResource))
        .state(state)
        .toolCallbacks(
            wrapToolCallbacks(securityContextRehydrator, contextStoreToolCallbacksFactory.writeToolCallbacks()))
        .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
        .build();
}
```

> The Skills agents pass `@Tool` component objects through `wrapTools(...)`. Our tools are already `ToolCallback` instances, so they need the callback-wrapping variant. Read the existing `wrapTools` helper (`CopilotConfiguration.java:353-365`): it wraps each callback via `RehydrateContextToolCallback.wrap(toolCallback, securityContextRehydrator)`. If a `List<ToolCallback>`-accepting helper does not already exist, add a small private `wrapToolCallbacks(SecurityContextRehydrator, List<ToolCallback>)` that maps each element through `RehydrateContextToolCallback.wrap(...)` and returns the wrapped list — mirroring the loop at lines 353-365.

- [ ] **Step 4: Add the two stateless subagent chat clients** (model on `skillsAskSubAgentChatClient` / `skillsBuildSubAgentChatClient`, `CopilotConfiguration.java:847-880`)

```java
@Bean
ChatClient contextStoreAskSubAgentChatClient(
    ChatModel chatModel, ContextStoreToolCallbacksFactory contextStoreToolCallbacksFactory) {

    return ChatClient.builder(chatModel)
        .defaultSystem(getSystemPrompt(promptContextStoreAskResource))
        .defaultToolCallbacks(contextStoreToolCallbacksFactory.readToolCallbacks())
        .build();
}

@Bean
ChatClient contextStoreBuildSubAgentChatClient(
    ChatModel chatModel, ContextStoreToolCallbacksFactory contextStoreToolCallbacksFactory) {

    return ChatClient.builder(chatModel)
        .defaultSystem(getSystemPrompt(promptContextStoreBuildResource))
        .defaultToolCallbacks(contextStoreToolCallbacksFactory.writeToolCallbacks())
        .build();
}
```

- [ ] **Step 5: Add the required imports** for `ContextStoreSpringAIAgent`, `ContextStoreToolCallbacksFactory`, and the four context-store services/facades.

- [ ] **Step 6: Verify compilation**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:libs:ai:ai-copilot:ai-copilot-service:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/config/CopilotConfiguration.java
git commit -m "feat(copilot): wire context store ask/build source agents and subagent clients"
```

---

## Task 7: Add `ContextStoreAgentToolCallback` + `CopilotAgentType` entries

**Files:**
- Create: `server/libs/ai/ai-copilot/ai-copilot-tool/src/main/java/com/bytechef/ai/copilot/tool/ContextStoreAgentToolCallback.java`
- Test: `.../ai-copilot-tool/src/test/.../tool/ContextStoreAgentToolCallbackTest.java`
- Modify: `server/libs/ai/ai-copilot/ai-copilot-tool/src/main/java/com/bytechef/ai/copilot/tool/CopilotAgentType.java`

**Interfaces:**
- Consumes: a `ChatClient` (the BUILD subagent) in the constructor, exactly like `SkillsAgentToolCallback`.
- Produces: `ContextStoreAgentToolCallback` exposing tool `context_store_agent`; input schema `{ "request": string }`.

- [ ] **Step 1: Write the failing test** (mirror `SkillsAgentToolCallbackTest`)

```java
package com.bytechef.ai.copilot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

class ContextStoreAgentToolCallbackTest {

    @Test
    void toolDefinitionExposesContextStoreAgent() {
        ContextStoreAgentToolCallback toolCallback =
            new ContextStoreAgentToolCallback(Mockito.mock(ChatClient.class));

        assertThat(toolCallback.getToolDefinition()
            .name()).isEqualTo("context_store_agent");
    }

    @Test
    void blankRequestReturnsError() {
        ContextStoreAgentToolCallback toolCallback =
            new ContextStoreAgentToolCallback(Mockito.mock(ChatClient.class));

        String result = toolCallback.call("{\"request\": \"  \"}", null);

        assertThat(result).contains("request is required");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:libs:ai:ai-copilot:ai-copilot-tool:test --tests "*ContextStoreAgentToolCallbackTest" -q`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement the callback**

Copy `SkillsAgentToolCallback.java` verbatim into `ContextStoreAgentToolCallback.java` and change: the class name; `getToolDefinition().name()` to `"context_store_agent"`; the `DESCRIPTION` to describe Context Store delegation (below); the input record name; and the log category. Keep the `request` blank-check, `CurrentAgentContext` handling, and delegation-to-`ChatClient` body identical.

```java
private static final String DESCRIPTION =
    """
        Delegate a user request about Context Stores to a specialised Context Store subagent.
        A Context Store ingests data from a source component on a cadence and exposes searchable,
        optionally-embedded records. The subagent owns listing, explaining, searching, and (in build
        mode) creating/updating/refreshing/enabling/deleting sources and stores. Prefer calling it over
        reasoning about context stores directly. Returns a synthesised markdown report or a summary of
        the mutations performed.""";
```

- [ ] **Step 4: Add the `CopilotAgentType` entries**

In `CopilotAgentType.java`, add (matching the existing `key`/`fallback` shape):

```java
CONTEXT_STORE_ASK("context_store_ask", false),
CONTEXT_STORE_BUILD("context_store_build", false),
CONTEXT_STORE("context_store", true),
CONTEXT_STORE_AGENT("context_store_agent", false),
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:libs:ai:ai-copilot:ai-copilot-tool:test --tests "*ContextStoreAgentToolCallbackTest" -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/libs/ai/ai-copilot/ai-copilot-tool/src/main/java/com/bytechef/ai/copilot/tool/ContextStoreAgentToolCallback.java server/libs/ai/ai-copilot/ai-copilot-tool/src/test/java/com/bytechef/ai/copilot/tool/ContextStoreAgentToolCallbackTest.java server/libs/ai/ai-copilot/ai-copilot-tool/src/main/java/com/bytechef/ai/copilot/tool/CopilotAgentType.java
git commit -m "feat(copilot): add context_store_agent subagent tool callback"
```

---

## Task 8: Register `context_store_agent` on the AI Hub; remove the flat Context Store tools

**Files:**
- Modify: `server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/config/ToolCallbackContributorConfiguration.java`
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/config/AiHubConfiguration.java`

**Interfaces:**
- Consumes: `contextStoreBuildSubAgentChatClient` (Task 6), `ContextStoreAgentToolCallback` (Task 7).
- Produces: the AI Hub agent gains a `context_store_agent` tool and loses its 11 flat Context Store tools.

- [ ] **Step 1: Register the subagent in the contributor**

In `ToolCallbackContributorConfiguration.copilotAgentToolCallbackContributor(...)`, add a provider parameter and registration mirroring the Skills wiring (`ToolCallbackContributorConfiguration.java:48,66-67`):

```java
@Qualifier("contextStoreBuildSubAgentChatClient") ObjectProvider<ChatClient> contextStoreProvider,
```

and inside the returned supplier:

```java
contextStoreProvider.ifAvailable(
    chatClient -> toolCallbacks.add(new ContextStoreAgentToolCallback(chatClient)));
```

Add the `ContextStoreAgentToolCallback` import.

- [ ] **Step 2: Remove the flat Context Store registrations from `AiHubConfiguration`**

Delete the calls to `registerContextStoreReadOnlyToolCallbacks(...)`, `registerContextStoreSemanticSearchToolCallback(...)`, and `registerContextStoreToolCallbacks(...)` at their call sites (both the ASK path near line 342-346 and the BUILD path near line 524-528), and delete the three now-unused private methods (`registerContextStoreReadOnlyToolCallbacks`, `registerContextStoreSemanticSearchToolCallback`, `registerContextStoreToolCallbacks`). Remove the now-unused imports of the moved tool classes and their `ObjectProvider`/service parameters if they become unused. Leave `ListAvailableSourceComponentsToolCallback` / `DescribeSourceComponentEntitiesToolCallback` handling to move with Task 2 (they are now in the shared lib and reachable through the subagent's read list — do not re-register them flat).

- [ ] **Step 3: Delete the old tool-class locations**

Confirm the 11 classes + their tests no longer exist under `ee/libs/ai/ai-hub/ai-hub-service/.../tool/` (they were `git mv`d in Task 2). Remove any lingering references.

- [ ] **Step 4: Compile both modules**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:libs:ai:ai-copilot:ai-copilot-service:compileJava :server:ee:libs:ai:ai-hub:ai-hub-service:compileJava -q`
Expected: BUILD SUCCESSFUL (no missing-symbol errors — proves the flat tools are fully unwired).

- [ ] **Step 5: Run AI Hub service tests**

Run: `cd /Volumes/Data/bytechef/bytechef && ./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test -q`
Expected: PASS. Fix any test that referenced the removed flat registrations (update to expect the `context_store_agent` tool instead).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(ai-hub): delegate context store to context_store_agent subagent; drop flat tools"
```

---

## Task 9: Add the Copilot trigger + post-turn invalidation to the Context Store detail page

**Files:**
- Modify: `client/src/pages/automation/context-store/ContextStoreSources.tsx`
- Test: `client/src/pages/automation/context-store/tests/ContextStoreSources.test.tsx`

**Interfaces:**
- Consumes: `useCopilotStore` (`setContext`), `useCopilotPanelStore` (`setCopilotPanelOpen`), `useCopilotPostTurnRegistry` (`register`), `Source.CONTEXT_STORE`, `MODE`. The store's `parameters` carry `{contextStoreId, environmentId, workspaceId}`.
- Produces: a header "Ask Copilot" trigger that opens the panel scoped to this store; a registered post-turn callback that refetches the store's queries.

- [ ] **Step 1: Write the failing test**

Add to `ContextStoreSources.test.tsx` (mirror the copilot assertion style in `AiSkillDetail`'s test if present; otherwise assert the click calls `setContext`):

```tsx
it('opens copilot scoped to the current context store', async () => {
    const setContext = vi.fn();
    const setCopilotPanelOpen = vi.fn();

    // mock useCopilotStore -> {setContext}; useCopilotPanelStore -> {setCopilotPanelOpen}
    // render ContextStoreSources at route /automation/context-stores/42 (reuse the test's existing render helper)

    await userEvent.click(screen.getByRole('button', {name: /ask copilot/i}));

    expect(setContext).toHaveBeenCalledWith(
        expect.objectContaining({
            mode: MODE.ASK,
            source: Source.CONTEXT_STORE,
            parameters: expect.objectContaining({contextStoreId: '42'}),
        })
    );
    expect(setCopilotPanelOpen).toHaveBeenCalledWith(true);
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Volumes/Data/bytechef/bytechef && npm --prefix client test -- ContextStoreSources`
Expected: FAIL — no "Ask Copilot" button.

- [ ] **Step 3: Add the trigger + invalidation in `ContextStoreSources.tsx`**

Add imports:

```tsx
import useCopilotPanelStore from '@/shared/components/copilot/stores/useCopilotPanelStore';
import useCopilotPostTurnRegistry from '@/shared/components/copilot/stores/useCopilotPostTurnRegistry';
import {MODE, Source, useCopilotStore} from '@/shared/components/copilot/stores/useCopilotStore';
```

Inside the component, add:

```tsx
const setContext = useCopilotStore((state) => state.setContext);
const setCopilotPanelOpen = useCopilotPanelStore((state) => state.setCopilotPanelOpen);
const registerPostTurn = useCopilotPostTurnRegistry((state) => state.register);
const queryClient = useQueryClient();

const openCopilot = (mode: MODE) => {
    setContext({
        mode,
        parameters: {
            contextStoreId: contextStoreIdParam,
            environmentId: String(currentEnvironmentId),
            workspaceId: String(currentWorkspaceId),
        },
        source: Source.CONTEXT_STORE,
    });

    setCopilotPanelOpen(true);
};

// Refresh this store's sources + the store list after a BUILD turn mutates data.
useEffect(() => {
    return registerPostTurn(Source.CONTEXT_STORE, () => {
        void queryClient.invalidateQueries({queryKey: useContextStoreSourcesQuery.getKey?.() ?? ['contextStoreSources']});
        void queryClient.invalidateQueries({queryKey: useContextStoresQuery.getKey({
            environmentId: String(currentEnvironmentId),
            workspaceId: String(currentWorkspaceId),
        })});
    });
}, [registerPostTurn, queryClient, currentEnvironmentId, currentWorkspaceId]);
```

> Use the actual generated query-key helpers from `@/shared/middleware/graphql` (the codebase's GraphQL hooks expose `.getKey(...)`). Read how `ContextStoreSources.tsx` already calls `useContextStoresQuery` and the sources hook, and reuse their exact query keys for invalidation.

In the `Header` `right` slot, add an "Ask Copilot" button beside `EnvironmentSelect` (only when `ai.copilot.enabled` — read the flag via `useApplicationInfoStore` as `App.tsx` does):

```tsx
<Button onClick={() => openCopilot(MODE.ASK)} variant="outline">
    Ask Copilot
</Button>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /Volumes/Data/bytechef/bytechef && npm --prefix client test -- ContextStoreSources`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add client/src/pages/automation/context-store/ContextStoreSources.tsx client/src/pages/automation/context-store/tests/ContextStoreSources.test.tsx
git commit -m "feat(context-store): add copilot trigger + post-turn invalidation to detail page"
```

---

## Task 10: Full-slice verification

**Files:** none (verification only).

- [ ] **Step 1: Build the touched backend modules + run their tests**

Run:
```bash
cd /Volumes/Data/bytechef/bytechef && ./gradlew \
  :server:ee:libs:automation:automation-ai:automation-ai-tool:test \
  :server:libs:ai:ai-copilot:ai-copilot-tool:test \
  :server:libs:ai:ai-copilot:ai-copilot-service:test \
  :server:ee:libs:ai:ai-hub:ai-hub-service:test -q
```
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 2: Frontend tests + typecheck**

Run: `cd /Volumes/Data/bytechef/bytechef && npm --prefix client test -- ContextStoreSources && npm --prefix client run check-types`
Expected: PASS.

- [ ] **Step 3: Drive the real flow (verify skill)**

Invoke the `verify` skill: launch the app, open a Context Store detail page, click **Ask Copilot**, confirm the panel opens scoped to the store; in the AI Hub, ask a Context Store question and confirm it routes through `context_store_agent`. Observe an actual ASK answer and a BUILD mutation (e.g. enable/disable a source) refreshing the table.

- [ ] **Step 4: Final commit (if verification produced fixes)**

```bash
git add -A
git commit -m "test(context-store): verify copilot slice end to end"
```

---

## Self-Review

**Spec coverage** (against `2026-07-17-domain-copilot-...-design.md`, Slice 1):
- Shared tool lib w/ read/write split → Tasks 2, 4. ✅
- Two panel source agents (`CONTEXT_STORE_ASK`/`_BUILD`) → Task 6. ✅
- Two stateless subagent chat clients → Task 6. ✅
- `ContextStoreAgentToolCallback` + contributor registration; remove flat tools → Tasks 7, 8. ✅
- `CopilotAgentType` entries → Task 7. ✅
- System-prompt resources → Task 5. ✅
- Frontend `Source` entry + button + post-turn invalidation → Tasks 1, 9. ✅
- New `deleteContextStore` tool → Task 3. ✅
- Testing (unit per tool, subagent test, frontend test, regression) → Tasks 3, 4, 7, 9, 10. ✅

**Placeholder scan:** the prompt files are working defaults explicitly flagged for product refinement, not TBDs. Two "read the exemplar and match exactly" notes (Task 4 constructor args, Task 6 `wrapToolCallbacks`, Task 9 query keys) point at concrete in-repo code the implementer must copy — verify these against the live files during implementation rather than inventing signatures.

**Type consistency:** `TOOL_NAME` strings (`deleteContextStore`, `context_store_agent`), bean names (`contextStoreBuildSubAgentChatClient`), and `Source.CONTEXT_STORE` are used identically across Tasks 1–9.
