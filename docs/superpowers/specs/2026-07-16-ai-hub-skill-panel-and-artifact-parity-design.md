# AI Hub Skill Panel, Delete Tool, and Artifact Parity — Design

**Date:** 2026-07-16
**Status:** Approved (design)
**Area:** EE AI Hub (`server/ee/libs/ai/ai-hub`), `automation-ai-tool`, AI Hub client (`client/src/pages/automation/ai-hub`)

## Problem

The AI Hub agent can **create** and **update** skills (via the `skills_build` sub-agent, which
wraps `SkillsTools`), but two gaps remain:

1. **No delete.** `SkillsTools.deleteAiSkill` exists but its `@Tool` annotation is commented out,
   so the agent cannot delete a skill — inconsistent with `deleteProject` / `deleteWorkflow`,
   which are exposed.
2. **No "open skill in the right panel."** The agent can open workflows, data tables, and
   knowledge bases into the AI Hub resource panel via `openWorkflowTab` / `openDataTableTab` /
   `openKnowledgeBaseTab`, but there is no `openSkillTab`. Skills are only reachable through the
   composer @-mention picker and the settings page.

A related inconsistency surfaced while scoping: only `openWorkflowTab` **records a task artifact**
when the agent opens something. `openDataTableTab` / `openKnowledgeBaseTab` open the tab but record
nothing, even though the `DATA_TABLE_REFERENCED` / `KB_REFERENCED` artifact kinds already exist
(they are created today only by the composer plus-button attach flow). The client sidebar already
renders and reopens those kinds — the only missing piece is server-side recording on agent-open.

## Goals

- Expose an agent-callable **delete skill** tool, matching the existing delete convention.
- Add **`openSkillTab`** so the agent can open a skill in the resource panel, rendering the existing
  `AiSkillDetail` viewer (read-only in-panel).
- **Artifact parity:** opening a skill, a data table, or a knowledge base all record a task artifact
  (so each appears in the task sidebar and can be reopened), matching `openWorkflowTab`.

## Non-goals (YAGNI)

- In-panel skill **editing**. The panel viewer is read-only; edits go through the agent's
  `updateAiSkill*` tools or the skills settings page.
- New `SKILL_CREATED` / `SKILL_UPDATED` artifact kinds. Only `SKILL_REFERENCED` is added, mirroring
  how the other `open*Tab` tools record a `*_REFERENCED` artifact on open.
- Any pre-delete confirmation gate. `deleteProject` / `deleteWorkflow` delete directly and return a
  confirmation message; skill delete follows the same convention.

## Design decisions

- **Skill panel content:** reuse `AiSkillDetail` (file tree + selected-file content), read-only.
- **Delete behavior:** uncomment `@Tool`, change the return type from `void` to `String`, and return
  a confirmation message — identical in shape to `deleteProject` / `deleteWorkflow`.
- **Artifact kind:** append a single `SKILL_REFERENCED` value; reuse the existing
  `DATA_TABLE_REFERENCED` / `KB_REFERENCED` for the data-table/KB fix.

## Server changes (`ee/ai-hub-service`, `automation-ai-tool`)

1. **`SkillsTools.deleteAiSkill`** — uncomment `@Tool(description = "Delete an AI skill by its ID.
   Returns a confirmation message.")`; change `void` → `String`; return `"Deleted skill <id>."`.
2. **`AiHubTaskArtifactKind`** — append `SKILL_REFERENCED` at the very end (after `TASK_REFERENCED`),
   per the append-only ordinal rule enforced by `EnumOrdinalStabilityTest`.
3. **New `OpenSkillTabToolCallback`** — mirror `OpenDataTableTabToolCallback`: signaling-only
   `ToolCallback`, tool name `openSkillTab`, input `{skillId, name}`, records `SKILL_REFERENCED` via
   an injected `@Nullable AiHubTaskArtifactRecorder` (`record(threadId, userId, "SKILL_REFERENCED",
   skillId, name)`), and returns the `{opened, ...}` result payload the client subscriber expects.
4. **`OpenDataTableTabToolCallback` + `OpenKnowledgeBaseTabToolCallback`** — add a
   `@Nullable AiHubTaskArtifactRecorder` constructor parameter and record `DATA_TABLE_REFERENCED` /
   `KB_REFERENCED` on a successful open.
   - **Open implementation question — dedup.** `OpenWorkflowTab` uses the specialized
     `recordWorkflowReference(...)`, which is known to be dedup-aware. Skill / data-table / KB would
     use the generic `record(threadId, userId, kind, artifactId, name)`, whose dedup behavior is
     **not yet confirmed**. The plan must verify it: if the generic `record` already collapses on
     `(threadId, kind, artifactId)`, use it as-is; if not, either dedup in the tool before recording
     or add a dedup-aware overload to `AiHubTaskArtifactRecorder`. Repeated opens of the same
     resource must not stack duplicate sidebar artifacts.
5. **`AiHubConfiguration`** — register `OpenSkillTabToolCallback`; pass the recorder to the
   data-table/KB tools at the **recorder-enabled** registration site (the one where
   `OpenWorkflowTabToolCallback(aiHubTaskArtifactRecorder)` is used). Leave the **null-recorder** site
   unchanged so the existing "this tool set does not record" distinction is preserved. Apply the same
   to `DataAnalystConfiguration` if it constructs those tools.
6. **`prompt_ai_hub_ask.txt` + `prompt_ai_hub_build.txt`** — add an `openSkillTab({skillId, name})`
   line adjacent to the other `open*Tab` entries.

## Client changes (`client/src/pages/automation/ai-hub`)

7. **`useAiHubTabsStore`** — add `{id, kind: 'skill', skillId, name}` to `AiHubTabType` and an
   `openSkillTab(skillId, name)` action that dedups by `skillId` (mirror `openDataTableTab`).
8. **`AiHubResourcePanel`** — render `kind === 'skill'` via `<AiSkillDetail>`; adapt `AiSkillDetail`
   to accept a `skillId` prop so it can be embedded outside the settings route (read-only).
9. **`AiHubRuntimeProvider`** — add an `else if (toolCallName === 'openSkillTab')` branch that
   validates the result and calls `openSkillTab(...)`, mirroring the `openDataTableTab` branch.
10. **`AiHubTasksSidebar`** — render and reopen `SKILL_REFERENCED` (icon/label + `openSkillTab`),
    mirroring the existing `DATA_TABLE_REFERENCED` handling.
11. **`useSwitchTask`** — replay `openSkillTab` for `SKILL_REFERENCED` artifacts on task switch,
    mirroring the data-table/KB replay.

## Data flow (open a skill)

1. Agent calls `openSkillTab({skillId, name})`.
2. `OpenSkillTabToolCallback` records a `SKILL_REFERENCED` artifact (server-side, dedup-aware) and
   returns `{opened: true, skillId, name}`.
3. `AiHubRuntimeProvider` intercepts the tool-call result event and calls
   `useAiHubTabsStore.openSkillTab(skillId, name)`.
4. `AiHubResourcePanel` renders the new `skill` tab with `AiSkillDetail`.
5. The artifact appears in `AiHubTasksSidebar`; clicking it replays `openSkillTab`.

## Error handling

- `OpenSkillTabToolCallback` returns a `toolError` for a missing/blank `skillId` or `name`, matching
  the data-table tool. Artifact recording failures are logged and swallowed (never fail the open).
- The client `surfaceTabOpenFailure` path already handles an unparseable / `opened: false` result.

## Testing

- **`EnumOrdinalStabilityTest`** — pin the `SKILL_REFERENCED` ordinal.
- **Server** — `OpenSkillTabToolCallbackTest` (mirror the data-table tool test): valid open returns
  `opened: true` and records the artifact; blank inputs return `toolError`. Extend the data-table/KB
  tool tests to assert they now record their reference artifact.
- **Client** — `useAiHubTabsStore` `openSkillTab` (open + dedup); `AiHubTasksSidebar`
  `SKILL_REFERENCED` render + reopen.

## Rollout / compatibility

- Append-only enum change keeps all existing `ai_hub_task_artifact.kind` ordinals stable.
- Re-enabling delete is additive; existing behavior is unchanged for callers that never invoke it.
- The data-table/KB recording change only adds artifacts on agent-open; the composer attach flow is
  untouched. Repeated opens must not stack duplicate artifacts — see the dedup implementation
  question under server change #4.
