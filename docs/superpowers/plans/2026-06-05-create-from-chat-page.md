# "Create From Chat" Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Create From Chat" sidebar item + page to the embedded sample app where an agent creates/refines a ByteChef workflow via the `embedded-workflow-builder` tools, then redirects to the automations list when the user is satisfied.

**Architecture:** A new backend route registers two explicit ai-sdk tools (`createWorkflow`, `updateWorkflow`) that execute through the embedded REST `POST /tools` endpoint (which injects `externalUserId`/`environment` server-side). A new client page mirrors `chat-component-kit` and registers a client-side `returnToAutomations` frontend tool (plus a manual "Back to Automations" button). A nav entry links to it.

**Tech Stack:** Next.js 16 App Router, React 19, `ai` (ai-sdk), `@assistant-ui/react` + `@assistant-ui/react-ai-sdk`, `@ai-sdk/openai`, `lucide-react`.

**Spec:** `docs/superpowers/specs/2026-06-05-create-from-chat-page-design.md` (in the main bytechef repo).

**Implementation repo (NOT the main bytechef repo):** the Next.js app at `/Volumes/Data/bytechef/bytechef-samples/bytechef-embedded-sample-app/front-end/`. All paths below are relative to that directory. Git commits go in that app's repo — determine its root with `git -C /Volumes/Data/bytechef/bytechef-samples/bytechef-embedded-sample-app/front-end rev-parse --show-toplevel` and run git there. Match the repo's existing commit-message style (check `git log --oneline -5`).

> **No test framework:** the app's scripts are `dev`/`build`/`start`/`lint` only. Verification per task = `npm run lint`; a final `npm run build` confirms the whole thing typechecks/compiles. Do NOT add a test framework. Run npm commands from the `front-end/` dir.

---

## Task 1: Backend route with explicit workflow tools

**Files:**
- Create: `src/app/api/create-from-chat/route.ts`

This mirrors `src/app/api/chat-component-kit/route.ts` but registers two explicit tools (instead of fetching all tools) plus a steering system prompt. The tool names are the server's encoded form, which `POST /tools` reverses to component `embeddedworkflowbuilder` + the cluster element.

- [ ] **Step 1: Create the route**

`src/app/api/create-from-chat/route.ts`:
```typescript
import { openai } from "@ai-sdk/openai";
import { frontendTools } from "@assistant-ui/react-ai-sdk";
import {
  streamText,
  convertToModelMessages,
  tool as defineTool,
  jsonSchema,
  type UIMessage,
  type JSONSchema7,
} from "ai";

import { getToken } from "@/lib/api";

const BYTECHEF_APP_BASE_URL =
  process.env.NEXT_PUBLIC_BYTECHEF_APP_BASE_URL || "http://localhost:5173";
const BYTECHEF_ENVIRONMENT =
  process.env.NEXT_PUBLIC_BYTECHEF_ENVIRONMENT || "DEVELOPMENT";
const BYTECHEF_EXTERNAL_USER_ID =
  process.env.NEXT_PUBLIC_BYTECHEF_EXTERNAL_USER_ID || "1234567890";

const CREATE_TOOL_NAME =
  "EMBEDDEDWORKFLOWBUILDER_CREATE_CONNECTED_USER_WORKFLOW_FROM_PROMPT";
const UPDATE_TOOL_NAME =
  "EMBEDDEDWORKFLOWBUILDER_UPDATE_CONNECTED_USER_WORKFLOW_FROM_PROMPT";

const SYSTEM_PROMPT =
  "You help the user create and refine a ByteChef automation workflow from natural language. " +
  "Use the `createWorkflow` tool to create a workflow from the user's description; it returns the new workflow's uuid. " +
  "Use the `updateWorkflow` tool (passing that uuid) to refine the workflow when the user asks for changes. " +
  "After creating or updating, briefly describe what you built and ask whether they want changes or are satisfied. " +
  "When the user confirms they are done or satisfied, call the `returnToAutomations` tool to take them back to the automations list.";

async function executeWorkflowTool(
  name: string,
  parameters: Record<string, unknown>
): Promise<unknown> {
  const jwtToken = await getToken();

  const response = await fetch(
    `${BYTECHEF_APP_BASE_URL}/api/embedded/v1/${BYTECHEF_EXTERNAL_USER_ID}/tools`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${jwtToken}`,
        "Content-Type": "application/json",
        "X-Environment": BYTECHEF_ENVIRONMENT,
      },
      body: JSON.stringify({ name, parameters }),
    }
  );

  const output = await response.json();

  if (!response.ok) {
    throw new Error(JSON.stringify(output, null, 2));
  }

  return output;
}

const workflowTools = {
  createWorkflow: defineTool({
    description:
      "Create a new ByteChef automation workflow from a natural language prompt. Returns the new workflow's uuid.",
    inputSchema: jsonSchema<{ prompt: string }>({
      type: "object",
      properties: {
        prompt: {
          type: "string",
          description: "Natural language description of the workflow to build.",
        },
      },
      required: ["prompt"],
      additionalProperties: false,
    }),
    execute: async ({ prompt }) =>
      executeWorkflowTool(CREATE_TOOL_NAME, { prompt }),
  }),
  updateWorkflow: defineTool({
    description:
      "Update an existing ByteChef workflow from a natural language prompt. Pass the workflowUuid returned by createWorkflow. Returns the workflow uuid.",
    inputSchema: jsonSchema<{ workflowUuid: string; prompt: string }>({
      type: "object",
      properties: {
        workflowUuid: {
          type: "string",
          description: "The uuid of the workflow to update.",
        },
        prompt: {
          type: "string",
          description: "Natural language description of the changes to apply.",
        },
      },
      required: ["workflowUuid", "prompt"],
      additionalProperties: false,
    }),
    execute: async ({ workflowUuid, prompt }) =>
      executeWorkflowTool(UPDATE_TOOL_NAME, { workflowUuid, prompt }),
  }),
};

export async function POST(req: Request) {
  const {
    messages,
    tools: clientTools,
  }: {
    messages: UIMessage[];
    system?: string;
    tools?: Record<string, { description?: string; parameters: JSONSchema7 }>;
  } = await req.json();

  const result = streamText({
    model: openai.chat("gpt-5"),
    messages: await convertToModelMessages(messages),
    system: SYSTEM_PROMPT,
    tools: {
      ...workflowTools,
      ...frontendTools(clientTools ?? {}),
    },
    toolChoice: "auto",
  });

  return result.toUIMessageStreamResponse();
}
```

> Notes: imports and helpers mirror `chat-component-kit/route.ts` exactly (`tool as defineTool`, `jsonSchema`, `frontendTools`, `openai.chat("gpt-5")`, `toUIMessageStreamResponse()`). If `jsonSchema<T>(...)` (generic) is rejected by the installed `ai` version, drop the generic (`jsonSchema({...})`) and type the execute params as `(params: { prompt: string }) =>` / `(params: { workflowUuid: string; prompt: string }) =>` instead. The `returnToAutomations` tool is NOT defined here — it is a client frontend tool (Task 2); `frontendTools(clientTools)` makes the client-registered version callable.

- [ ] **Step 2: Lint the new file**

Run (from `front-end/`): `npm run lint`
Expected: no new lint errors for `src/app/api/create-from-chat/route.ts`. (If the project lints all files, ensure no errors are introduced by this file.)

- [ ] **Step 3: Commit**

```bash
cd /Volumes/Data/bytechef/bytechef-samples/bytechef-embedded-sample-app/front-end
git add src/app/api/create-from-chat/route.ts
git commit -m "Add create-from-chat API route with workflow-builder agent tools"
```
(Match the repo's commit-message convention if it differs — check `git log --oneline -5` first.)

---

## Task 2: Client page (chat + redirect tool + back button)

**Files:**
- Create: `src/app/create-from-chat/page.tsx`

Mirrors `chat-component-kit/page.tsx` plus a header with a "Back to Automations" button and a client-side `returnToAutomations` tool. The tool hook (`useAssistantTool`) MUST run inside `AssistantRuntimeProvider`, so it lives in a small child component.

- [ ] **Step 1: Create the page**

`src/app/create-from-chat/page.tsx`:
```tsx
"use client";

import { Thread } from "@/components/assistant-ui/thread";
import { AssistantRuntimeProvider, useAssistantTool } from "@assistant-ui/react";
import { useChatRuntime } from "@assistant-ui/react-ai-sdk";
import { DefaultChatTransport } from "ai";
import { useRouter } from "next/navigation";

function ReturnToAutomationsTool() {
  const router = useRouter();

  useAssistantTool({
    toolName: "returnToAutomations",
    description:
      "Return the user to the automations list. Call this only after the user confirms they are satisfied with the created or updated workflow.",
    parameters: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
    type: "frontend",
    execute: async () => {
      router.push("/automations");

      return { ok: true };
    },
  });

  return null;
}

export default function CreateFromChatPage() {
  const router = useRouter();

  const runtime = useChatRuntime({
    transport: new DefaultChatTransport({ api: "/api/create-from-chat" }),
  });

  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ReturnToAutomationsTool />

      <div className="flex h-[calc(100vh-2rem)] w-full flex-col">
        <div className="flex items-center justify-between border-b px-4 py-2">
          <h1 className="text-sm font-medium">Create From Chat</h1>

          <button
            className="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            onClick={() => router.push("/automations")}
            type="button"
          >
            Back to Automations
          </button>
        </div>

        <Thread />
      </div>
    </AssistantRuntimeProvider>
  );
}
```

> Verification points (resolve against the installed `@assistant-ui/react ^0.12.17` types):
> - Confirm `useAssistantTool` is exported from `@assistant-ui/react` and accepts `{ toolName, description, parameters, type: "frontend", execute }`. If the field is named differently (e.g. the schema field is `parameters` as JSONSchema7 — confirmed by the type `FrontendTool.parameters: StandardSchemaV1 | JSONSchema7`), keep the JSONSchema7 object shown. If `type: "frontend"` is rejected, remove it (an `execute`-bearing tool is frontend by default).
> - `useAssistantTool` must be called inside `AssistantRuntimeProvider` — hence `ReturnToAutomationsTool` is a child component. Do NOT call it in `CreateFromChatPage` before the provider renders.
> - If the empty-object `parameters` causes the model to omit the tool, it is still fine — the tool needs no arguments.

- [ ] **Step 2: Lint**

Run (from `front-end/`): `npm run lint`
Expected: no new lint errors for `src/app/create-from-chat/page.tsx`.

- [ ] **Step 3: Commit**

```bash
cd /Volumes/Data/bytechef/bytechef-samples/bytechef-embedded-sample-app/front-end
git add src/app/create-from-chat/page.tsx
git commit -m "Add create-from-chat page with chat and return-to-automations tool"
```

---

## Task 3: Sidebar nav entry

**Files:**
- Modify: `src/app/layout.tsx` (the `lucide-react` import block + the `navigation` array)

- [ ] **Step 1: Add the icon import**

In `src/app/layout.tsx`, add `SparklesIcon` to the existing `lucide-react` import (keep the import list's existing ordering/style; it currently imports `CalendarIcon, ChartPieIcon, FilesIcon, FoldersIcon, HomeIcon, MessageCircleIcon, SquareIcon, UsersIcon, WebhookIcon, WorkflowIcon, ZapIcon`):
```tsx
  SparklesIcon,
```

- [ ] **Step 2: Add the nav item**

In the `navigation` array, add this entry immediately AFTER the `Automations` entry (`{ name: 'Automations', href: '/automations', icon: WorkflowIcon },`):
```tsx
  { name: 'Create From Chat', href: '/create-from-chat', icon: SparklesIcon },
```

- [ ] **Step 3: Lint**

Run (from `front-end/`): `npm run lint`
Expected: no new lint errors; `SparklesIcon` is now used (no unused-import error).

- [ ] **Step 4: Commit**

```bash
cd /Volumes/Data/bytechef/bytechef-samples/bytechef-embedded-sample-app/front-end
git add src/app/layout.tsx
git commit -m "Add Create From Chat sidebar navigation item"
```

---

## Final verification

- [ ] **Build the app** (typecheck + compile the whole thing):
  Run (from `front-end/`): `npm run build`
  Expected: BUILD succeeds. Fix any TypeScript errors surfaced (most likely in the `useAssistantTool` call shape or the `jsonSchema` generic — adjust per the Task 1/Task 2 verification notes). If `next build` requires env/secrets that aren't present in this environment and fails for unrelated reasons, fall back to `npx tsc --noEmit` to typecheck the three new/changed files and report the build limitation.
- [ ] **Manual smoke (requires the ByteChef server running with the `embedded-workflow-builder` component + the sample app's token backend):**
  - `npm run dev`, open the app, click **Create From Chat** in the sidebar → the chat renders.
  - Ask: "Create a workflow that sends a Slack message every morning." → the agent calls `createWorkflow`, which returns a uuid (no error).
  - Open `/automations` (via the **Back to Automations** button) → the new workflow appears in the list.
  - Ask for a change → the agent calls `updateWorkflow` with the uuid.
  - Say "I'm done / that's perfect" → the agent calls `returnToAutomations` and the app navigates to `/automations`.

---

## Notes for the executor

- **Different repo:** all code is in `bytechef-samples/bytechef-embedded-sample-app/front-end`, a separate git repo from the main bytechef repo. Commit there; do not stage these into the main repo.
- **No tests:** verification is `npm run lint` per task + a final `npm run build`; there is no unit-test framework and none should be added.
- **assistant-ui API drift:** the `useAssistantTool` shape is the one verification point with version risk — confirm the exact field names against `node_modules/@assistant-ui/react` type declarations before finalizing, and adjust the Task 2 call accordingly. The behavioral contract is: a client-side tool named `returnToAutomations` with no args whose `execute` calls `router.push("/automations")`.
- **Server dependency:** the workflow tools only succeed at runtime if the ByteChef server has the `embedded-workflow-builder` component (built separately) and the sample app's token backend is running. Lint/build do not require the server; the manual smoke does.
