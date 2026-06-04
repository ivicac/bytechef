# Expose Copilot Subagents as MCP Agent-Tools via an EE mcp-tool Tier

- **Date:** 2026-06-04
- **Status:** Design — awaiting review
- **Branch:** `0_732`
- **Author:** Ivica Cardic (with Claude)

## 1. Goal

The management MCP server (`ManagementMcpServerConfiguration`) today registers ~12 granular tool
groups directly. We want it to additionally expose the **Copilot specialist subagents as high-level
"agent" tools** (e.g. `workflow_editor_agent`), so an MCP client can delegate a natural-language
request to a prompt-guided specialist instead of orchestrating primitives itself.

The agent-as-tool callbacks currently live in the EE AI Hub module. To make them reusable by both AI
Hub **and** the MCP server without leaking EE into CE or creating a tool→feature dependency, we
introduce a proper **EE tier of the `mcp-tool` modules** and relocate the shared infrastructure down
into it.

This is **additive** to the granular tools, not a replacement: the subagents' value is their
prompt-guided expertise, and they internally use the same tools. Raw tools and agent tools coexist.

## 2. Key principles / constraints

- **CE → EE layering is preserved.** `mcp-server` is CE (`server/libs/...`, Apache). It must not
  depend on EE modules. EE capabilities reach it only through a CE-defined contributor seam.
- **EE code lives under `server/ee/`** with the Enterprise license header and `@version ee`.
- **Tool layer must not depend on the AI Hub feature layer.** Shared infra (`Agent`,
  `CurrentAgentContext`, `LogSanitizer`, `ToolErrors`) moves *down* into a shared EE `mcp-tool-api`
  so both AI Hub and the mcp-tool modules depend on it (instead of the tool modules depending on
  ai-hub).

## 3. Decisions (locked)

| # | Decision |
|---|----------|
| D1 | MCP server keeps a set of **CE granular tools wired directly** (deterministic CRUD); the agent tools are layered on top. |
| D2 | MCP-exposed agent tools wrap the **BUILD** subagent ChatClients (write-capable). |
| D3 | **Removed entirely** from the MCP server: `WorkflowValidatorTools`, `WorkflowInstructionTools` (agent-internal helpers), `FirecrawlTools`, `ConnectedUserProjectWorkflowTools`. |
| D4 | `SkillsTools` (and `ReadSkillsTools`) **move to EE** `mcp-tool-automation` and reach the MCP server via the EE contributor (not a direct CE wire). |
| D5 | The 4 shared helper classes move **fully** from `platform-ai-hub-api` into a new EE `mcp-tool-api` (~80-file import churn accepted) for clean layering. |
| D6 | The `McpToolCallbackContributor` bean lives in **EE `mcp-tool-automation`** (self-contained); it injects BUILD ChatClients by `@Qualifier` string via `ObjectProvider`, keeping `ai-copilot-service` MCP-unaware. |
| D7 | MCP agent callbacks are **bare** (not wrapped in `ProgressReportingToolCallback`, which is an AI-Hub progress-UI concern). AI Hub registration is unchanged. |

## 4. MCP server tool disposition

| Tool | Source module | Disposition on MCP server |
|------|---------------|---------------------------|
| `ProjectTools` | CE `mcp-tool-automation` | **Direct** |
| `ProjectWorkflowTools` | CE `mcp-tool-automation` | **Direct** |
| `ScriptTools` | CE `mcp-tool-automation` | **Direct** |
| `ClusterElementTools` | CE `mcp-tool-automation` | **Direct** |
| `ComponentTools` | CE `mcp-tool-platform` | **Direct** |
| `TaskTools` | CE `mcp-tool-platform` | **Direct** |
| `TaskDispatcherTools` | CE `mcp-tool-platform` | **Direct** |
| `SkillsTools` | → EE `mcp-tool-automation` | **EE-contributed** |
| `workflow_editor_agent` (BUILD) | → EE `mcp-tool-automation` | **EE-contributed** |
| `code_editor_agent` (BUILD) | → EE `mcp-tool-automation` | **EE-contributed** |
| `cluster_element_agent` (BUILD) | → EE `mcp-tool-automation` | **EE-contributed** |
| `skills_agent` (BUILD) | → EE `mcp-tool-automation` | **EE-contributed** |
| `workflow_execution_agent` (BUILD) | → EE `mcp-tool-automation` | **EE-contributed** |
| `converter_agent` (BUILD) | → EE `mcp-tool-automation` | **EE-contributed** |
| `WorkflowValidatorTools` | CE `mcp-tool-platform` | **Removed** |
| `WorkflowInstructionTools` | CE `mcp-tool-platform` | **Removed** |
| `FirecrawlTools` | CE `mcp-tool-platform` | **Removed** |
| `ConnectedUserProjectWorkflowTools` | CE `mcp-tool-integration` | **Removed** |

**Result:** CE-only deployment → 7 direct CRUD tools. EE + copilot/skills deployment → those 7 plus
`SkillsTools` and the 6 BUILD agent tools.

## 5. Target architecture

### 5.1 New EE modules

**`server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api`**
- Holds the 4 shared helpers moved from `platform-ai-hub-api`:
  - `Agent` (enum), `CurrentAgentContext` → package `com.bytechef.ee.ai.mcp.tool.usage`
  - `LogSanitizer`, `ToolErrors` → package `com.bytechef.ee.ai.mcp.tool.util`
- Dependencies: `org.slf4j:slf4j-api`, jackson (`tools.jackson.*`), jspecify, findbugs annotations.
- No spring-ai, no ByteChef module deps → no cycle risk.

**`server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation`**
- Holds:
  - The 6 `*AgentToolCallback` classes moved from `platform-ai-hub-service` →
    package `com.bytechef.ee.ai.mcp.tool.automation`.
  - `SkillsTools`, `ReadSkillsTools` (+ their `SkillToolErrorType` dependency) moved from CE
    `mcp-tool-automation` → package `com.bytechef.ee.ai.mcp.tool.automation` (verify exact subpackage
    during implementation).
  - The `McpToolCallbackContributor` implementation (`@Configuration`).
- Dependencies: EE `mcp-tool-api` (helpers), CE `mcp-tool-api` (contributor interface), spring-ai
  (`spring-ai-client-chat` / `spring-ai-model` for `ChatClient`/`ToolCallback`/`ToolContext`/
  `ToolDefinition`/`ToolCallbacks`), jackson, jspecify, `platform-ai-skill-api` (for `SkillsTools`),
  `exception-api`, `commons-util`, `automation-configuration-api` (SkillsTools deps — confirm union
  during implementation).
- **Does not depend on `platform-ai-hub-*`** (the layering win) and **does not depend on
  `ai-copilot-service`** (ChatClients injected by qualifier string).

### 5.2 CE seam

The seam interface needs a CE home that `mcp-server` (CE) can depend on.

**Finding:** CE `mcp-tool-api` (`server/libs/ai/mcp/mcp-tool/mcp-tool-api`) currently exists on disk
(2 unused condition classes) but is **not registered in `settings.gradle.kts` and has no consumers**
— it is effectively dead. We **revive** it as the seam's contracts home.

Add to CE `mcp-tool-api`:

```java
public interface McpToolCallbackContributor {
    List<ToolCallback> getToolCallbacks();
}
```

- Register the module in `settings.gradle.kts` (see §5.7).
- Add `org.springframework.ai:spring-ai-model` dep to CE `mcp-tool-api` (for `ToolCallback`).
- `mcp-server` depends on CE `mcp-tool-api`; EE `mcp-tool-automation` depends on it (for the interface).

*Alternative considered:* host the interface in `mcp-tool-platform` (already registered, already an
`mcp-server` dep) — zero new wiring, but puts a contract in a tools module. Rejected in favor of the
dedicated contracts module now that we're building out the mcp-tool `-api` tier anyway.

### 5.3 EE contributor (in EE `mcp-tool-automation`)

A `@Configuration` providing a single `McpToolCallbackContributor` bean. It conditionally adds each
contribution via `ObjectProvider.ifAvailable`, so skills and copilot gates stay independent:

```java
@Configuration
class McpToolCallbackContributorConfiguration {

    @Bean
    McpToolCallbackContributor copilotAgentMcpToolCallbackContributor(
        ObjectProvider<SkillsTools> skillsToolsProvider,
        @Qualifier("workflowEditorBuildSubAgentChatClient") ObjectProvider<ChatClient> workflowEditorProvider,
        @Qualifier("codeEditorBuildSubAgentChatClient") ObjectProvider<ChatClient> codeEditorProvider,
        @Qualifier("clusterElementBuildSubAgentChatClient") ObjectProvider<ChatClient> clusterElementProvider,
        @Qualifier("skillsBuildSubAgentChatClient") ObjectProvider<ChatClient> skillsProvider,
        @Qualifier("workflowExecutionBuildSubAgentChatClient") ObjectProvider<ChatClient> workflowExecutionProvider,
        @Qualifier("converterBuildSubAgentChatClient") ObjectProvider<ChatClient> converterProvider) {

        return () -> {
            List<ToolCallback> callbacks = new ArrayList<>();

            skillsToolsProvider.ifAvailable(skillsTools ->
                callbacks.addAll(List.of(ToolCallbacks.from(skillsTools))));

            workflowEditorProvider.ifAvailable(c ->
                callbacks.add(new WorkflowEditorAgentToolCallback(c)));
            codeEditorProvider.ifAvailable(c ->
                callbacks.add(new CodeEditorAgentToolCallback(c)));
            clusterElementProvider.ifAvailable(c ->
                callbacks.add(new ClusterElementAgentToolCallback(c)));
            skillsProvider.ifAvailable(c ->
                callbacks.add(new SkillsAgentToolCallback(c)));
            workflowExecutionProvider.ifAvailable(c ->
                callbacks.add(new WorkflowExecutionAgentToolCallback(c)));
            converterProvider.ifAvailable(c ->
                callbacks.add(new ConverterAgentToolCallback(c)));

            return callbacks;
        };
    }
}
```

Notes:
- The `@Qualifier` strings are the existing BUILD subagent bean names defined in
  `CopilotConfiguration` (`ai-copilot-service`). When `bytechef.ai.copilot.enabled=false` those
  beans are absent → those callbacks are skipped. When skills is unavailable (`AiSkillFacade`
  absent), `SkillsTools` is absent → skipped.
- Bare callbacks (no `ProgressReportingToolCallback`).

### 5.4 `mcp-server` changes (CE)

`ManagementMcpServerConfiguration`:
- Remove fields/ctor params/imports for `SkillsTools`, `WorkflowValidatorTools`,
  `WorkflowInstructionTools`, `FirecrawlTools`, `ConnectedUserProjectWorkflowTools`.
- Keep `ProjectTools`, `ProjectWorkflowTools`, `ScriptTools`, `ClusterElementTools`,
  `ComponentTools`, `TaskTools`, `TaskDispatcherTools` wired directly.
- Inject `ObjectProvider<McpToolCallbackContributor>`; build `toolCallbackProvider()` from the direct
  CE tools **plus** every contributor's `getToolCallbacks()`.

`build.gradle.kts`:
- Drop `mcp-tool-integration`.
- Keep `mcp-tool-automation` + `mcp-tool-platform` (direct CE tools).
- Add CE `mcp-tool-api` (contributor interface).

### 5.5 AI Hub rewires (behavior unchanged)

- `AiHubConfiguration` (`automation-ai-hub-service`): update imports for the 6 callbacks → EE
  `mcp-tool-automation`. Registration logic unchanged (still `ProgressReportingToolCallback`-wrapped,
  ASK uses ASK ChatClients, BUILD uses BUILD ChatClients). Add dep on EE `mcp-tool-automation`.
- Helper move (full): update all consumers of `Agent` / `CurrentAgentContext` / `LogSanitizer` /
  `ToolErrors` to import from `com.bytechef.ee.ai.mcp.tool.{usage,util}` and add EE `mcp-tool-api`
  dep to: `platform-ai-hub-api`, `platform-ai-hub-service`, `automation-ai-hub-service`,
  `automation-ai-hub-rest`.
- `ai-copilot-service` (`CopilotConfiguration`): `SkillsTools`/`ReadSkillsTools` imports → EE
  `mcp-tool-automation`; add that dep. (It already used them; only the source module changes.)

### 5.6 CE `mcp-tool-automation` cleanup

- Remove `SkillsTools`, `ReadSkillsTools`, `SkillToolErrorType` (moved to EE).
- Drop the now-unused `platform-ai-skill-api` dependency (it was there only for those classes —
  confirm no other usage during implementation).

### 5.7 `settings.gradle.kts`

Add explicit includes:
```
include("server:libs:ai:mcp:mcp-tool:mcp-tool-api")          // revive currently-unregistered CE module
include("server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api")
include("server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation")
```

## 6. Dependency direction (cycle check)

- EE `mcp-tool-api` → (slf4j, jackson, jspecify) only. No ByteChef edges. ✔
- `platform-ai-hub-api` → EE `mcp-tool-api` (new). EE `mcp-tool-api` ↛ `platform-ai-hub-api`. No cycle. ✔
- EE `mcp-tool-automation` → EE `mcp-tool-api` + CE `mcp-tool-api` + spring-ai + `platform-ai-skill-api`. Does **not** depend on `platform-ai-hub-*` or `ai-copilot-*`. ✔
- `ai-copilot-service` → EE `mcp-tool-automation` (for SkillsTools; already needed). ✔
- CE `mcp-server` → CE `mcp-tool-api` (+ existing CE tool modules). No EE edge. ✔

## 7. Testing

- **Moved callback unit tests** (6 `*AgentToolCallbackTest`) move with the classes to EE
  `mcp-tool-automation` and pass unchanged.
- **Contributor test** (new, EE `mcp-tool-automation`): with all BUILD ChatClient providers +
  SkillsTools present → 7 callbacks contributed; with copilot providers absent → only SkillsTools;
  with everything absent → empty list.
- **`mcp-server` test**: contributed callbacks land in the `ToolCallbackProvider` alongside the 7
  direct CE tools; with no contributor on the classpath → just the 7 direct tools.
- **Helper-move regression**: `EnumOrdinalStabilityTest` (Agent ordinals) and existing AI Hub usage
  tests pass after the package move.
- **Build graph**: `./gradlew :server:libs:ai:mcp:mcp-server:compileJava` and the touched EE modules
  compile; `./gradlew check` for the affected modules.

## 8. Risks / edge cases

- **`SkillToolErrorType` coupling.** `SkillsTools` imports `…tool.automation.exception.SkillToolErrorType`
  (CE). Move it with `SkillsTools` to EE; verify no remaining CE consumer.
- **Wide helper churn.** `ToolErrors` (~69), `LogSanitizer` (~23), `Agent` (~13),
  `CurrentAgentContext` (~12) consumers — all EE. Pure package-rename + dep-add; mechanical but broad.
  Use a scripted find/replace then compile per module.
- **CE-only MCP server** exposes only the 7 direct CRUD tools (no skills, no agents). Intended.
- **Stringly-typed qualifiers** in the contributor couple EE `mcp-tool-automation` to bean names
  owned by `CopilotConfiguration`. A rename there must be mirrored. Acceptable trade for keeping
  `ai-copilot-service` MCP-unaware; covered by the contributor test.
- **`ProgressReportingToolCallback` stays in `platform-ai-hub-service`** (uses `SubagentProgressEmitter`).
  AI Hub keeps wrapping; MCP does not.
- **Reviving CE `mcp-tool-api` is conflict-free** (verified): its two condition classes
  (`ConditionalOnAiEnabled`, `OnAiEnabledCondition`) are defined only there and referenced only by
  themselves — registering the module adds no duplicate FQCN and changes no existing resolution.

## 9. Out of scope

- Changing subagent prompts, tool catalogs, or ASK/BUILD behavior.
- Exposing ASK variants over MCP (BUILD only).
- Personal-agent callbacks (`*AiHubPersonalAgent*ToolCallback`) — they stay in
  `platform-ai-hub-service`.
- Any client-side change.
