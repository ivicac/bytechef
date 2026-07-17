# Context Store Copilot — progress ledger

Plan: docs/superpowers/plans/2026-07-17-context-store-copilot.md
Worktree branch: worktree-context-store-copilot (based on 0_732 @ eede7d46)

## Pre-flight decisions
- Isolated worktree, branched from 0_732 (per user).
- FLAGGED: moving 11 tool classes (Task 2) breaks ai-hub-service (37 coupled imports in
  AiHubConfiguration) until Task 8. Plan restructure needed to avoid intermediate breakage.
- FLAGGED: ported tools read AgentToolInvocationContext from ToolContext; must verify this is
  populated on BOTH the Copilot source-agent path AND the AI-Hub-subagent nested-ChatClient path
  before committing to Tasks 2-8. => Task 0 spike.

## Task status
- (none complete yet)

## Task 0 spike — RESOLVED (approach validated)
- AgentToolInvocationContext(workspaceId+environmentId) IS populated on BOTH paths:
  - Copilot source-agent: SkillsSpringAIAgent.toolContext -> CopilotToolContextUtils.toToolContext(state);
    vendored SpringAIAgent.run hands map to chatRequest.toolContext(...).
  - AI-Hub-subagent: AiHubSpringAIAgent.toolContext populates bytechef.agentTool.* keys;
    SkillsAgentToolCallback forwards toolContext.getContext() into nested ChatClient.
- AiHub tool context carries BOTH AiHubToolInvocationContext AND AgentToolInvocationContext =>
  Task 2 swap to AgentToolInvocationContext keeps flat AiHub registration working at runtime.
- DECISION: Task 2 will ALSO repoint AiHubConfiguration imports/usages to the shared-lib package
  (keeps ai-hub-service compiling+working); Task 8 then removes flat registration for the subagent.
  No intermediate breakage. (Avoids the Task2/8 atomicity problem.)
- Task 9 refinement: env/workspace injected by CopilotRuntimeProvider from stores, NOT from
  context.parameters. Button passes parameters:{contextStoreId} only.
- Security note for reviewers: Copilot path trusts client-supplied workspaceId/environmentId (same
  as existing shared copilot tools); facade enforces access. Not a new vuln class.

## Task status
