# Docs coverage backlog

Tracks UI surfaces found in the code that are **not yet documented with a dedicated page**, so
they can be prioritized later. Produced during the 2026-07 documentation deep pass. Everything
here was verified to exist in the client/server code; the reason each is deferred is noted.

Pages created during that pass (for context, not backlog): Agent Memories, Users & Invitations,
OAuth2 Clients, API Keys, API Connectors, and the three embedded editor/API pages
(integration workflows, automation workflows, Unified API). AI Hub connectors and the AI Agent
cross-link were folded into existing pages.

## Deferred — feature-flagged or "coming soon"

These surfaces are gated behind feature flags and/or the docs deliberately track the released
version, which still marks them "coming soon". Document them when they ship / the flag defaults on.

| Surface | Evidence | Suggested home |
|---|---|---|
| **AI Gateway** detail sections — Providers, Routing Policies, Prompts, Playground, Traces/Sessions, Scores, Alerts, Exports, Datasets, Experiments, Budget, Rate Limits (~17 CRUD sub-surfaces) | `client/src/pages/automation/ai/gateway/components/**`; servers `automation-ai-gateway`, `platform-ai-observability`, `platform-ai-eval` | Expand `platform/ai-gateway.md` into a section per surface (page is currently a single overview and marked "coming soon") |
| **Project settings-menu extras** — Share, Share with Community, Pull from Git, Git Configuration, Project History | `client/src/pages/automation/project/**/settings-menu/**` and `project-list/ProjectListItem.tsx` (flags `ff_1042`, `ff_2939`, `ff_1039`) | `automation/build/projects.mdx` (community sharing) + an EE Git-integration page for per-project Git config/pull |

## Deferred — needs design or a natural home

| Surface | Evidence | Suggested home |
|---|---|---|
| **Voice test sessions** in the workflow test chat panel (Start/Stop voice button, browser-support gating on triggers carrying a `websocketTasks` extension) | `client/src/pages/platform/workflow-editor/components/workflow-test-chat/WorkflowTestChatPanel.tsx` (`WorkflowTestVoiceModeButton`, `useWorkflowTestVoiceSession`); `client/src/shared/lib/browser-voice/**` | A "Testing" subsection in `automation/build/workflows.mdx` or on the workflow-chats pages |
| **Project editor header controls** — version-history sheet, Deploy button, Run/Test, Output-panel toggle | `client/src/pages/automation/project/components/project-header/**` (`ProjectVersionHistorySheet.tsx`, `DeployButton.tsx`) | A fuller "editor header" subsection in the `automation/deploy` cluster (currently only Publish is documented) |

## Verified-absent / do NOT document

Found during the pass but confirmed **not** reachable UI or not implemented — recorded so they are
not re-flagged:

- **Connection reassignment dialog** — `ConnectionReassignmentDialog.tsx` exists but is not imported/rendered anywhere (dead code).
- **Deployment "Duplicate" action** — the deployment ellipsis menu exposes only Edit, Change Project Version, and Delete.
- **AI Hub personal-agent workspace sharing** — server `WorkspaceAiHubPersonalAgentService` exists, but no client sharing UI was found; personal agents remain per-user in the UI.
- **Unified API models beyond Account** — `CrmModelType`/`AccountingModelType` enums list many models, but only the **Account** resource is REST-exposed today (other paths are commented out in the OpenAPI spec). The Unified API page documents Account endpoints and flags the rest as not yet wired.

## Notes for whoever picks this up

- The `reference/` section (component/flow-control pages) is **auto-generated** by
  `./gradlew generateDocumentation` — do not hand-edit those.
- Screenshot placeholders use `{/* TODO screenshot: ... */}` in `.mdx` and `<!-- TODO screenshot: ... -->`
  in `.md`; grep `TODO screenshot` to find every spot awaiting a real image.
