# Agents Under Projects — Design

## Summary

An AI Agent becomes a member of a user-visible Project, alongside that project's workflows. The dedicated
**Agents** and **Agent Deployments** pages are removed. Agents are reached through an **Agents** tab on the Projects
page and an **Agents** section inside an open project.

## Problem

Every AI Agent is already a project. `AiAgentFacadeImpl.createAgent` creates a hidden `__AI_AGENT__<uuid>` system
project holding one generated workflow; the agent's publish, versions and deployments are that project's
`ProjectVersion` and `ProjectDeployment` rows. Hiding this has costs:

- Listing surfaces filter the system-project prefix, and `hasAnyDeployment` exists only to work around that filter.
- The facade calls raw `ProjectService` / `ProjectWorkflowService` to bypass `ProjectWorkflowFacade` permission gates
  that reject hidden projects.
- Agent visibility is really the hidden project's visibility.
- The Agent Deployments page duplicates Project Deployments.
- An agent and the project workflows it calls as tools version and deploy on separate timelines, so an agent can be
  deployed to an environment where its tool workflows are not.

Agents differ from the other system-project users (Knowledge Base, Context Store, Data Sync, Embedded Automation):
users author agents directly and wire them to their own workflows. Those other kinds keep their hidden projects.

## Goals

- One release unit: an agent and the workflows beside it publish, deploy and sync as one project.
- One authoring surface (Projects) and one deployment surface (Project Deployments).
- Creating an agent stays a single action; nobody has to create a project first.

## Non-goals

- No change to `AiAgentWorkflowGenerator`, channels, elements, evals, test chat or AI Hub.
- No project boundary on references: sub-agent and call-workflow tool references stay workspace-wide.
- No redirects from the removed routes.
- No data migration for existing agents (see Schema).

## Design

### Model

- `ai_agent.project_id` keeps its meaning and FK but points at an ordinary project. A project may hold any number of
  agents and workflows.
- New `ai_agent.project_workflow_uuid` (`${uuid_type}`, not null, unique) identifies the agent's generated workflow.
  `project_workflow.uuid` is stable across project versions, so the pointer survives publishing.
  `getVersionWorkflowId(projectId, projectVersion)` — which today assumes one workflow per version — is replaced by
  `ProjectWorkflowService.fetchProjectWorkflow(projectId, projectVersion, uuid)`.
- New `project_workflow.type` (`INT`, not null, default `0`): `WORKFLOW = 0`, `AI_AGENT = 1`. Stored as an ordinal,
  read through a range-checked converter. This replaces the project-name prefix test wherever a listing must tell a
  generated agent workflow from a user workflow.
- `SystemProjects.AI_AGENT_NAME_PREFIX` is deleted and removed from `NAME_PREFIXES`.

### Schema

- The `ai_agent` tables are unreleased, so `00000000000001_automation_ai_agent_init.xml` is edited in place to add
  `project_workflow_uuid`. Databases that already applied the old changeset are reset by hand (drop the `ai_agent*`
  tables and their `databasechangelog` row); existing dev agents are not migrated.
- The hidden `__AI_AGENT__<uuid>` projects those agents lived in are left behind by that reset. **One-off cleanup for
  dev databases only** (never shipped as a changeset, PostgreSQL): delete them together with their dependent rows,
  children before parents as the `automation-configuration` foreign keys require. `project_workflow.workflow_id` has
  no foreign key to `workflow`, so the workflow rows are deleted while `project_workflow` still names them. `_` is a
  `LIKE` wildcard, hence the escapes.

  ```sql
  BEGIN;

  CREATE TEMP TABLE legacy_agent_project ON COMMIT DROP AS
      SELECT id FROM project WHERE name LIKE '\_\_AI\_AGENT\_\_%' ESCAPE '\';

  DELETE FROM project_deployment_workflow_connection
  WHERE project_deployment_workflow_id IN (
      SELECT project_deployment_workflow.id
      FROM project_deployment_workflow
      JOIN project_deployment ON project_deployment.id = project_deployment_workflow.project_deployment_id
      WHERE project_deployment.project_id IN (SELECT id FROM legacy_agent_project));

  DELETE FROM project_deployment_workflow
  WHERE project_deployment_id IN (
      SELECT id FROM project_deployment WHERE project_id IN (SELECT id FROM legacy_agent_project));

  DELETE FROM project_deployment_tag
  WHERE project_deployment_id IN (
      SELECT id FROM project_deployment WHERE project_id IN (SELECT id FROM legacy_agent_project));

  DELETE FROM project_deployment WHERE project_id IN (SELECT id FROM legacy_agent_project);

  DELETE FROM workflow
  WHERE id IN (
      SELECT workflow_id FROM project_workflow WHERE project_id IN (SELECT id FROM legacy_agent_project));

  DELETE FROM project_workflow WHERE project_id IN (SELECT id FROM legacy_agent_project);

  DELETE FROM project_version WHERE project_id IN (SELECT id FROM legacy_agent_project);

  DELETE FROM project_tag WHERE project_id IN (SELECT id FROM legacy_agent_project);

  DELETE FROM project WHERE id IN (SELECT id FROM legacy_agent_project);

  COMMIT;
  ```

  Other tables with a foreign key into these — `mcp_project` (on `project_deployment`), `project_git_configuration`
  and `project_code_workflow` (on `project`) — normally hold no rows for a hidden agent project; if a delete above
  fails on one of those foreign keys, delete the offending row first and rerun the script.
- `project_workflow` is a released table, so `type` is added by a new changeset in `automation-configuration`.

### Server behaviour

- **Create.** `createAgent` takes an optional `projectId`. When absent it creates a project named after the agent's
  title (suffixing on a name collision) — the same thing it does today, minus the prefix and the "Do not edit"
  description. It then adds the generated workflow with `type = AI_AGENT` and stores its uuid on the agent.
- **Permissions.** The facade keeps its `AGENT_VIEW` / `AGENT_CREATE` / `AGENT_EDIT` / `AGENT_DELETE` gates, so a
  custom role can still grant agent access without workflow access. Adding an agent to an existing project
  additionally passes that project's `WORKFLOW_CREATE` gate on `ProjectWorkflowService.addWorkflow`. Agent
  visibility stays derived from the project (`AiAgentVisibilityProvider` is unchanged); what is removed is the
  ability to set it per agent — the EE `AiAgentSharingFacade` module and its GraphQL operations are deleted.
- **Audit.** `AiAgentProjectAuditSubjectResolver`, which re-labels a hidden project's audit events as agent events,
  is deleted; a visible project's events are project events.
- **Listings.** Sites that skip system projects to hide agent workflows — project workflow lists, the call-workflow
  options in `SubflowDataSourceImpl`, MCP project-workflow pickers, search asset providers — filter on
  `type = WORKFLOW` instead. Generated agent workflows therefore stay out of those lists, as they are today.
- **Publish.** `publishAgent` is removed. Publishing the project publishes its agents. A new
  `ProjectPublishPreListener` SPI in `automation-configuration-api` is invoked at the top of
  `ProjectServiceImpl.publishProject` — the one method every publish path reaches — and the agent module implements
  it to validate and regenerate every agent workflow in the project before the snapshot. An agent that fails
  validation (for example no model) blocks the project publish with the same error `publishAgent` raises today. The
  agent versions sheet lists the project's versions.
- **Deployments.** `getAgentDeployments` stays as the read model that feeds channel lists (it resolves static
  webhook URLs the REST deployment model does not carry) but each row is narrowed to the agent's own workflow.
  Agent deployment tags are removed — they were the project deployment's tags under another name. Per-agent
  enablement is the existing `ProjectDeploymentWorkflow.enabled` flag on the agent's workflow.
- **Delete.** Deleting an agent removes its rows and its generated workflow in every project version, never the
  project. It is refused only while that workflow is enabled in a deployment. Deleting a project deletes its agents
  through a new `ProjectDeleteEventListener` SPI, which refuses while an agent outside the project references one
  of them as a sub-agent.
- **Import/export.** Single-agent export is unchanged. Import accepts a target project or creates one. Project
  export/import carries the project's agents.
- **MCP tools.** `createAiAgent` gains optional `projectId`; `listAiAgents` gains an optional `projectId` filter and
  returns each agent's `projectId`. `publishAiAgent` is removed in favour of `publishProject`.

### Client

- **Projects page.** A tab strip, **Projects | Agents**. The Agents tab lives at `/automation/projects/agents` — a
  path rather than a query parameter, so the agent list's relative `?agentId=` / `?tagId=` filter links keep
  working. The Agents tab is the current
  `AgentList` with a project badge per row and a left sidebar filtering by project and tag. The header offers
  **New Agent** on both tabs. The New Agent dialog has a project select whose default is "New project named after
  this agent".
- **Inside a project.** `ProjectsLeftSidebar` shows two sections, **Workflows** and **Agents**. New route
  `/automation/projects/:projectId/agents/:agentId` renders `ProjectsLeftSidebar`, `AgentDetailHeader`,
  `AgentDetailContent` and `AgentTestChatPanel`. `AgentDetailHeader` keeps Publish and Deploy, which now act on the
  project, and loses its visibility control.
- **Project list rows.** The collapsible under each project shows agents as well as workflows, and the counter reads
  "N workflows · M agents".
- **Project Deployments.** A deployment row renders `AgentDeploymentChannelList` for the agents it contains.
- **Removed.** The `Agents` and `Agent Deployments` sidebar entries, the `agents`, `agents/:agentId` and
  `agent-deployments` routes, `pages/automation/agent-deployments/`, `AgentsLeftSidebarNav`,
  `AgentVisibilityDialog` and the agent-deployment GraphQL operations. `pages/automation/agents/components/` stays
  as the home of the agent list and detail components.

## Trade-offs accepted

- Editing an agent's prompt and publishing bumps the whole project's version and redeploys its workflows. A user who
  wants an independent release cadence keeps one agent per project, which is what the default create flow produces.
- "Agents" loses its top-level sidebar slot. If discoverability suffers, a sidebar entry linking to
  `/automation/projects/agents` is a one-line addition.
- Per-agent visibility is gone; visibility is per project.

## Phasing

1. **Server model.** Schema, `project_workflow.type`, facade rewrite onto real projects and normal gates, listing
   filters, publish/delete semantics. The existing Agents pages keep working against the new model.
2. **Client move.** Projects tab, project sidebar section, new agent route, removal of the Agents pages and nav entry.
3. **Deployments merge.** Channels in Project Deployments; removal of Agent Deployments client and server code.

## Testing

- `AiAgentFacade` integration tests: create with and without `projectId`; two agents plus a workflow in one project
  resolve their own generated workflows across a publish; delete leaves the project and sibling workflows intact;
  delete refused while enabled in a deployment.
- Listing tests proving `type = AI_AGENT` workflows are absent from project workflow lists, call-workflow options
  and search, and that a formerly prefixed project name is no longer special-cased.
- Permission tests: agent edits are gated by `AGENT_EDIT` (see Permissions above) — a user without it cannot edit
  the project's agents, whatever workflow scopes they hold.
- Client: Projects tab switching and filters, New Agent default-project flow, sidebar section rendering, agent route
  inside the project layout.
