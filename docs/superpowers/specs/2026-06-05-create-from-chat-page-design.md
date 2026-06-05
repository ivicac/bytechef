# "Create From Chat" Page (Embedded Sample App) — Design

**Date:** 2026-06-05
**Status:** Approved design (pre-plan)
**Repo (implementation):** `bytechef-samples/bytechef-embedded-sample-app/front-end` (Next.js App Router)
**Depends on:** the EE `embedded-workflow-builder` component (its tools are invoked via the embedded REST `/tools` endpoint)

## 1. Summary

Add a new sidebar item **"Create From Chat"** and a page that renders a chat (the same `Thread` UI as `chat-component-kit`) whose agent has two registered tools — create-workflow-from-prompt and update-workflow-from-prompt — backed by the `embedded-workflow-builder` component. The user builds and refines a workflow conversationally; when satisfied, the agent (or a manual button) redirects to the automations list, where the new workflow appears.

The two workflow tools execute through the embedded REST `POST /tools` path, which injects `externalUserId`/`environment` from the request context — so the model only ever supplies `prompt`/`workflowUuid`, never identity.

**Non-goals:** raw-JSON definition editing and delete tools (only create + update-from-prompt); changing the shared `Thread` component; admin `McpComponent` registration (not needed for the REST execute path); adding a test framework to the sample app.

## 2. Why explicit tool registration (not `GET /tools` discovery)

`chat-component-kit` discovers tools via `GET /api/embedded/v1/{externalUserId}/tools`, which enumerates the connected user's **integration** components. `embedded-workflow-builder` is a connection-less **system** component, so it is absent from that list. However, the REST `POST /tools` *execute* path resolves a tool by parsing its name into `(componentName, clusterElementName)` and calling `clusterElementDefinitionFacade.executeTool(...)` directly — it does **not** require the component to be a connected integration or a registered `McpComponent`. So the new chat route registers the two tools **explicitly** and executes them via `POST /tools`, working with zero admin setup.

## 3. Architecture

```
src/app/create-from-chat/page.tsx  (client)
  useChatRuntime(DefaultChatTransport({api: '/api/create-from-chat'}))  ──► AssistantRuntimeProvider ► <Thread/>
  header: "Back to Automations" button ──► router.push('/automations')
  useAssistantTool('returnToAutomations', execute: () => router.push('/automations'))   // agent-driven redirect

src/app/api/create-from-chat/route.ts  (server)
  tools = {
    createWorkflow: { inputSchema {prompt}, execute → POST /tools {name: EMBEDDEDWORKFLOWBUILDER_CREATE_CONNECTED_USER_WORKFLOW_FROM_PROMPT, parameters:{prompt}} },
    updateWorkflow: { inputSchema {workflowUuid, prompt}, execute → POST /tools {name: EMBEDDEDWORKFLOWBUILDER_UPDATE_CONNECTED_USER_WORKFLOW_FROM_PROMPT, parameters:{workflowUuid, prompt}} },
  }
  streamText({ model, messages, system: <steering prompt>, tools: {...tools, ...frontendTools(clientTools)}, toolChoice:'auto' })

POST /api/embedded/v1/{externalUserId}/tools  (ByteChef server, already wired)
  ToolFacadeImpl.executeTool → withConnectedUserContext (inject __externalUserId/__environment)
    → embeddedWorkflowBuilder action → ConnectedUserProjectFacade.create/updateProjectWorkflow(externalUserId, ..., env, true)
```

## 4. Files and changes

### 4.1 `src/app/layout.tsx` — nav entry
Add to the `navigation` array, immediately after the `Automations` entry:
```ts
{ name: 'Create From Chat', href: '/create-from-chat', icon: SparklesIcon },
```
Add `SparklesIcon` to the existing `lucide-react` import.

### 4.2 `src/app/create-from-chat/page.tsx` — chat page
Mirror `chat-component-kit/page.tsx` shell, with additions:
- `const router = useRouter();` (from `next/navigation`).
- Register the client tool so the agent can navigate:
  ```ts
  useAssistantTool({
    toolName: 'returnToAutomations',
    description: 'Return the user to the automations list. Call this only after the user confirms they are satisfied with the created/updated workflow.',
    parameters: z.object({}),
    execute: async () => {
      router.push('/automations');
      return 'Returning to the automations list.';
    },
  });
  ```
  (Verify the exact hook name/signature against the installed `@assistant-ui/react ^0.12.17`; if the API is `makeAssistantTool`/`tool(...)` instead, use the equivalent that runs an `execute` client-side with `router` in scope.)
- A header with a **"Back to Automations"** button (`router.push('/automations')`) above the `<Thread/>`.
- Same `AssistantRuntimeProvider` + `useChatRuntime({ transport: new DefaultChatTransport({ api: '/api/create-from-chat' }) })`.

### 4.3 `src/app/api/create-from-chat/route.ts` — backend route
Mirror `chat-component-kit/route.ts` (JWT via the same `getToken()` helper, `streamText`, the same model, `...frontendTools(clientTools ?? {})`, `result.toUIMessageStreamResponse()`), but:
- Build the two explicit tools with `defineTool`/`tool` + `jsonSchema`/`zod` (match how the existing route defines tools), each `execute` POSTing to `${BYTECHEF_APP_BASE_URL}/api/embedded/v1/${BYTECHEF_EXTERNAL_USER_ID}/tools` with `Authorization: Bearer <jwt>`, `X-Environment: <env>`, body `{ name, parameters }`, throwing on non-OK, returning the parsed JSON (the uuid / confirmation).
  - `createWorkflow`: `name = 'EMBEDDEDWORKFLOWBUILDER_CREATE_CONNECTED_USER_WORKFLOW_FROM_PROMPT'`, parameters `{ prompt }`.
  - `updateWorkflow`: `name = 'EMBEDDEDWORKFLOWBUILDER_UPDATE_CONNECTED_USER_WORKFLOW_FROM_PROMPT'`, parameters `{ workflowUuid, prompt }`.
- Add a `system` prompt:
  > "You help the user create and refine a ByteChef automation workflow from natural language. Use the `createWorkflow` tool to create a workflow from the user's description; it returns the new workflow's uuid. Use `updateWorkflow` (passing that uuid) to refine it when the user asks for changes. After creating or updating, briefly describe what you built and ask whether they want changes or are satisfied. When the user confirms they are done or satisfied, call `returnToAutomations` to take them back to the automations list."

The tool-name encoding (`EMBEDDEDWORKFLOWBUILDER_CREATE_CONNECTED_USER_WORKFLOW_FROM_PROMPT`) is the server's `getToolName(componentName, clusterElementName)` form, which `POST /tools` reverses via `getComponentClusterElementNames` back to component `embeddedworkflowbuilder` (case-insensitive registry lookup) + cluster element `createConnectedUserWorkflowFromPrompt`.

## 5. Data flow & redirect

1. User describes a workflow → agent calls `createWorkflow{prompt}` → route POSTs `/tools` → server injects identity → `ConnectedUserProjectFacade.createProjectWorkflow(externalUserId, prompt, env, true)` → uuid returned to the agent.
2. User requests changes → agent calls `updateWorkflow{workflowUuid, prompt}` → server updates → uuid.
3. User satisfied → agent calls `returnToAutomations` (client tool) → `/automations`. **Or** the user clicks "Back to Automations" at any time.
4. `/automations` lists the workflow (it fetches workflows on mount).

## 6. Error handling

- Tool `execute` throws on a non-OK `/tools` response (mirrors `chat-component-kit`); the agent surfaces the error to the user and can retry.
- The "Back to Automations" button is always available regardless of agent/tool state.

## 7. Testing

The sample app has no unit-test framework (scripts: `dev`/`build`/`start`/`lint`). Verification:
- `next lint` clean on the three changed files.
- `next build` succeeds.
- Manual smoke (requires the ByteChef server running with the `embedded-workflow-builder` component): open "Create From Chat", ask the agent to build a workflow, confirm the create tool returns a uuid and the workflow appears in `/automations`; confirm both the agent `returnToAutomations` tool and the manual button redirect to the list.

## 8. Open questions / verification points

- **`@assistant-ui/react` client-tool API:** confirm the exact hook/function for registering a client-executable tool in `^0.12.17` (`useAssistantTool` vs `makeAssistantTool` vs `tool(...)`), and that registered frontend tools are forwarded to the route as `clientTools` and re-enabled via `frontendTools(...)`. Adapt §4.2 accordingly.
- **Tool definition helper:** match the existing route's tool-construction helper (`defineTool` + `jsonSchema`, or ai-sdk `tool` + `zod`) for consistency.
- **`externalUserId` in the `/tools` POST URL:** uses the sample app's configured `BYTECHEF_EXTERNAL_USER_ID`; that same id is what the server injects as `__externalUserId`, so the created workflow is owned by the sample's connected user.
