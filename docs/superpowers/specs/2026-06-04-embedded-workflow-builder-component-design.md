# Embedded Workflow Builder Component — Design

**Date:** 2026-06-04
**Status:** Approved design (pre-plan)
**Branch:** `0_732`
**Scope:** Enterprise Edition (EE), embedded

## 1. Summary

Replace the CE-located `ConnectedUserProjectWorkflowTools` Spring-AI `@Tool` class with a proper ByteChef **component**, `embedded-workflow-builder`, whose actions expose the same connected-user workflow operations as tools. The connected user's `externalUserId` and `environment` are no longer LLM-supplied `@ToolParam`s — they are injected from the request context by the embedded execution facades and read by the action, so the model can neither see nor spoof them.

The operations:

- `createConnectedUserWorkflowFromPrompt` — generate a new workflow from a natural-language prompt; returns the new workflow uuid.
- `updateConnectedUserWorkflowFromPrompt` — update an existing workflow from a prompt; returns the workflow uuid.
- `updateConnectedUserWorkflow` — replace a workflow's JSON definition; returns a confirmation.
- `deleteConnectedUserWorkflow` — delete a workflow; returns a confirmation.

**Non-goals:** changing the AiHub tool path; changing the shared `component-api` `Context` SDK; adding an always-on tool-registration mechanism (exposure stays on the existing admin `McpComponent`/`McpTool` path); changing `ConnectedUserProjectFacade`.

## 2. Background: why a context channel must be added

A ByteChef component action's `perform` receives ByteChef's `ActionContext`/`ClusterElementContext` — **not** a Spring AI `ToolContext`. Two facts from tracing both call paths:

- **Both embedded entry points converge on the same shared call** with no channel for identity:
  - REST `/tools`: `ToolApiController.executeTool` → `ToolFacadeImpl.executeTool(externalUserId, toolName, inputParameters, instanceId, environment)` → `clusterElementDefinitionFacade.executeTool(componentName, clusterElementName, inputParameters, connectionId)`.
  - MCP: `EmbeddedMcpToolFacade.getClusterElementToolCallbackFunction(externalUserId, …, environment, …)` → `clusterElementDefinitionFacade.executeTool(componentName, componentVersion, clusterElementName, MapUtils.concat(request, resolvedParameters), connectionId)`.
  - Both facades **hold** `externalUserId`+`environment` but forward only `connectionId`. The shared `ClusterElementDefinitionFacade.executeTool(...)` signature has no `externalUserId`/`environment`/context-data parameter, and `ClusterElementContext` exposes only `getEnvironmentId()` (a `Long`) — nothing for the connected user.

- **`ToolContext` is the wrong layer and is structurally absent here.** It exists only at the Spring AI `ToolCallback.call(input, ToolContext)` level. The embedded MCP tool is a `FunctionToolCallback<Map,Object>` whose function is `Function<Map,Object>` — it receives no `ToolContext`; Spring AI's MCP server does not populate one. The REST `/tools` path has no Spring AI layer at all. And even on the Spring-AI-agent path, the adapter (`AiAgentToolFacade.getFromAiToolCallbackFunction`) bridges only the single `AiAgentToolContextKey.ACTION_CONTEXT` key into a component action — AiHub's `workspaceId`/`userId`/`environmentId` `ToolContext` keys never reach a component action's `perform`. So `ToolContext` cannot deliver identity to our component action on either entry point. (AiHub uses `ToolContext` precisely because its tools are Spring AI `ToolCallback` beans, not component actions.)

**Conclusion:** the only channel present on *both* the MCP and REST `/tools` paths is the `inputParameters` map. So the facades inject `externalUserId`+`environment` as **reserved parameter keys**, and the action reads them. The shared `component-api` `Context` SDK stays untouched (no embedded concept leaks into a general SDK where it would be null for every non-embedded execution).

## 3. Architecture overview

```
                 reserved keys injected by each embedded facade
                 ┌───────────────────────────────────────────┐
REST /tools  →  ToolFacadeImpl.executeTool ──► inputParameters + {externalUserId, environment} ─┐
MCP server   →  EmbeddedMcpToolFacade      ──► params + {externalUserId, environment} ───────────┤
                 (also: tolerate connection-less components)                                      │
                                                                                                 ▼
                          clusterElementDefinitionFacade.executeTool(component, action, inputParameters, connectionId=null)
                                                                                                 │
                                                                                                 ▼
   embeddedWorkflowBuilder action.perform(inputParameters, connectionParameters, actionContext)
       externalUserId = inputParameters.getRequiredString(EXTERNAL_USER_ID)   // reserved
       environment    = resolveEnvironment(inputParameters.getString(ENVIRONMENT))
       prompt/workflowUuid/definition = inputParameters.get…                   // LLM-facing
       → connectedUserProjectFacade.create/update/deleteProjectWorkflow(externalUserId, …, environment)
```

## 4. Components and changes

### 4.1 Reserved-key constants (shared)

A constants holder in a module both embedded facades **and** the new component depend on:
`server/ee/libs/embedded/embedded-execution/embedded-execution-api` (depended on by `embedded-execution-service` which hosts `ToolFacadeImpl`, by `embedded-ai-mcp-server` which hosts `EmbeddedMcpToolFacade`, and by the new component).

```java
// com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants  (@version ee)
public final class EmbeddedToolConstants {
    /** Reserved parameter key carrying the connected user's external id, injected by the embedded tool facades. */
    public static final String EXTERNAL_USER_ID = "__externalUserId";
    /** Reserved parameter key carrying the environment name, injected by the embedded tool facades. */
    public static final String ENVIRONMENT = "__environment";

    private EmbeddedToolConstants() {
    }
}
```

The `__` prefix marks these as server-injected and avoids collision with any LLM-facing tool property (which would never be named with that prefix).

### 4.2 New EE component `embedded-workflow-builder`

Location: `server/ee/libs/modules/components/embedded-workflow-builder/`
Package: `com.bytechef.ee.component.embeddedworkflowbuilder`
Template: `context-store` (Spring-DI, connection-less).

- **`EmbeddedWorkflowBuilderComponentHandler`** — `@Component("embeddedWorkflowBuilder_v1_ComponentHandler")`, `@ConditionalOnEEVersion`, constructor-injects `ConnectedUserProjectFacade`. Builds the definition in the constructor:

  ```java
  component("embeddedWorkflowBuilder")
      .title("Embedded Workflow Builder")
      .description("Create, update, and delete an embedded connected user's workflows via AI Copilot.")
      .icon("path:assets/embedded-workflow-builder.svg")
      .categories(ComponentCategory.HELPERS)
      .actions(
          CreateConnectedUserWorkflowFromPromptAction.of(connectedUserProjectFacade),
          UpdateConnectedUserWorkflowFromPromptAction.of(connectedUserProjectFacade),
          UpdateConnectedUserWorkflowAction.of(connectedUserProjectFacade),
          DeleteConnectedUserWorkflowAction.of(connectedUserProjectFacade))
      .clusterElements(
          tool(/* each action */))   // exposes the actions as tools
      .version(1);
  ```

  No `.connection(...)` — the component is connection-less. Actions are also wrapped as `tool(...)` cluster elements so the MCP/REST tool layer can invoke them.

- **Action classes** (one file each, `action/` package), each a static `of(ConnectedUserProjectFacade)` factory + private `build()` + package-private `perform`. LLM-facing properties only; identity from reserved keys. Example:

  ```java
  // CreateConnectedUserWorkflowFromPromptAction
  action("createConnectedUserWorkflowFromPrompt")
      .title("Create Workflow From Prompt")
      .description("Generate a new workflow for the connected user from a natural language prompt. "
          + "Returns the new workflow uuid.")
      .properties(
          string("prompt")
              .label("Prompt")
              .description("Natural language description of the workflow to build.")
              .required(true))
      .output(outputSchema(string().description("The new workflow uuid.")))
      .perform(this::perform);

  String perform(Parameters inputParameters, Parameters connectionParameters, ActionContext actionContext) {
      String externalUserId = inputParameters.getRequiredString(EmbeddedToolConstants.EXTERNAL_USER_ID);
      Environment environment = resolveEnvironment(inputParameters.getString(EmbeddedToolConstants.ENVIRONMENT));
      String prompt = inputParameters.getRequiredString("prompt");

      return connectedUserProjectFacade.createProjectWorkflow(externalUserId, prompt, environment, true);
  }
  ```

  The other three actions mirror the existing tool semantics:
  - `updateConnectedUserWorkflowFromPrompt` — props `workflowUuid`, `prompt`; `updateProjectWorkflow(externalUserId, workflowUuid, prompt, environment, true)` → uuid.
  - `updateConnectedUserWorkflow` — props `workflowUuid`, `definition`; `updateProjectWorkflow(externalUserId, workflowUuid, definition, environment)` (void) → returns a confirmation string.
  - `deleteConnectedUserWorkflow` — props `workflowUuid`; `deleteProjectWorkflow(externalUserId, workflowUuid, environment)` (void) → confirmation string.

  A private `resolveEnvironment(@Nullable String)` (default `PRODUCTION`) is shared (a small util or duplicated per action; prefer a package-private helper class `EmbeddedWorkflowBuilderUtils`).

  Error handling: on failure, throw a ByteChef `ProviderException`/`ExecutionException` with a clear message and a component-local error-type enum `EmbeddedWorkflowBuilderErrorType` (CREATE_WORKFLOW / UPDATE_WORKFLOW / DELETE_WORKFLOW), mirroring the existing class's error typing. Keep it minimal.

- **`build.gradle.kts`** (mirrors `context-store`): `spring-context`, `component-api`, `commons-util`, `embedded-configuration-api` (for `ConnectedUserProjectFacade`), `embedded-execution-api` (for `EmbeddedToolConstants`), `platform-component-api`.
- **Asset:** `src/main/resources/assets/embedded-workflow-builder.svg`.
- **`settings.gradle.kts`:** add `include("server:ee:libs:modules:components:embedded-workflow-builder")`.

`ConnectedUserProjectFacade` signatures (confirmed) used by the actions:
```java
String createProjectWorkflow(String externalUserId, String prompt, Environment environment, boolean generate);
String updateProjectWorkflow(String externalUserId, String workflowUuid, String prompt, Environment environment, boolean generate);
void   updateProjectWorkflow(String externalUserId, String workflowUuid, String definition, Environment environment);
void   deleteProjectWorkflow(String externalUserId, String workflowUuid, Environment environment);
```

### 4.3 Inject reserved keys at both embedded facades

- **`ToolFacadeImpl.executeTool`** (`embedded-execution-service`): before calling `clusterElementDefinitionFacade.executeTool(...)`, merge the reserved keys into a copy of `inputParameters`:

  ```java
  Map<String, Object> parameters = new HashMap<>(inputParameters);
  parameters.put(EmbeddedToolConstants.EXTERNAL_USER_ID, externalUserId);
  parameters.put(EmbeddedToolConstants.ENVIRONMENT, environment.name());

  return clusterElementDefinitionFacade.executeTool(
      result.componentName(), result.clusterElementName(), parameters, connectionId);
  ```

- **`EmbeddedMcpToolFacade.getClusterElementToolCallbackFunction`** (`embedded-ai-mcp-server`): inject the same reserved keys into the merged parameter map passed to `executeTool` (the closure already holds `externalUserId` + `environment`).

Injecting these on every embedded tool invocation is harmless for other components (they ignore unknown keys) and makes the connected-user identity available to any future embedded tool.

### 4.4 Tolerate connection-less components in the MCP facade

`EmbeddedMcpToolFacade.getClusterElementToolCallbackFunction` currently returns a "connection required" response whenever `fetchConnectionId(...)` is null. For a connection-less component (no `.connection(...)`), null is expected, not an error. The facade must short-circuit with the connection-required response **only when the component actually declares a connection**. Determine connection-requirement from the component definition (e.g. `componentDefinition.getConnection().isPresent()` via the component-definition service the facade already uses); when the component is connection-less, proceed with `connectionId = null`.

The REST `ToolFacadeImpl` path already tolerates a null `connectionId` (it passes it straight through), so no change is needed there for connection-less behavior.

### 4.5 Remove the old CE module

Delete `server/libs/ai/mcp/mcp-tool/mcp-tool-integration/` entirely:
- `ConnectedUserProjectWorkflowTools.java` and `exception/ConnectedUserProjectWorkflowToolErrorType.java`.
- The module's `include(...)` in `settings.gradle.kts`.
- Any module depending on `:server:libs:ai:mcp:mcp-tool:mcp-tool-integration` (search build files; remove the dependency). The functionality is fully replaced by the new component.

### 4.6 Exposure

Via the existing admin `McpComponent`/`McpTool` path (`createMcpComponentWithTools`): an admin registers the `embeddedWorkflowBuilder` component (version 1) and its tool actions on the relevant embedded MCP server. No new always-on registration wiring. The connection-less handling in §4.4 is what makes a connection-less system component actually resolve and execute on the MCP path.

## 5. Error handling

- Missing reserved key (`EXTERNAL_USER_ID` absent) → `getRequiredString` throws; this indicates a facade wiring bug (the facades always inject it), surfaced as an execution error. Acceptable — it is an internal invariant, not user input.
- Facade failures (create/update/delete) → wrapped in `ExecutionException` with `EmbeddedWorkflowBuilderErrorType`, surfaced to the tool caller (MCP or REST) as an error.

## 6. Testing strategy

- **Component definition test:** `EmbeddedWorkflowBuilderComponentHandlerTest` — generates/asserts the component JSON definition (component-test snapshot pattern); delete stale `definition/*.json` from `src/test/resources` and `build/resources/test` before regenerating.
- **Action unit tests:** each action's `perform` with a mocked `ConnectedUserProjectFacade` — pass `inputParameters` containing the reserved keys (`__externalUserId`, `__environment`) plus the LLM props; assert the facade is called with the right `(externalUserId, …, environment)` and the result is returned. Include a test that `environment` defaults to `PRODUCTION` when the reserved key is blank/absent.
- **`ToolFacadeImpl` test:** asserts the reserved keys are merged into the parameters passed to `clusterElementDefinitionFacade.executeTool(...)` (verify with a captor) and that `externalUserId`/`environment.name()` are present.
- **`EmbeddedMcpToolFacade` tests:** (a) reserved keys injected into the executed parameter map; (b) a connection-less component proceeds (no "connection required" response) while a connection-requiring component with no connection still returns the connection-required response.
- **EE conventions:** every new file under `server/ee/**` carries the Enterprise license header and `@version ee`. Test class names end in `Test`; methods camelCase without underscores.

## 7. Documentation

After implementation, update the embedded docs to describe the `embedded-workflow-builder` tools (the four operations, that `externalUserId`/`environment` come from the connected-user context and are not tool inputs, and how an admin exposes them via an MCP server). Target the embedded section of `docs/` (the same surface touched by prior embedded-docs updates). This is a post-implementation step, tracked separately from the code plan.

## 8. Open questions / future work

- **Connection-requirement detection** in `EmbeddedMcpToolFacade` (§4.4): the exact API to test "does this component declare a connection" is a plan-time detail (component-definition service already injected into the facade). If no clean predicate exists, fall back to a guard keyed on the component being connection-less by definition lookup.
- **Reserved-key collision:** the `__`-prefixed keys are assumed never to be declared as tool properties. If ByteChef ever validates/strips unknown parameters before `perform`, revisit (the current path does not — `ParametersFactory.create(map)` passes the merged map through unfiltered).
