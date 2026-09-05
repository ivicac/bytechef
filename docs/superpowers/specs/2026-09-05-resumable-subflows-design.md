# Resumable Subflows — Let a `subflow/v1` Child Suspend and Wake

**Date:** 2026-09-05
**Status:** Proposed
**Scope:** Server only. `atlas-execution-service` (one assertion), `task-dispatchers/subflow` (one listener, one
bridge fix), `components/workflow` (one guard), plus the docs and the client warning that describe the limit.
No new tables, no schema change, no new dispatcher.

## Problem

A job started by the `subflow/v1` task dispatcher has `parentTaskExecutionId != null`. Such a job cannot suspend
and resume. Two things enforce that, and they hide each other:

1. `JobServiceImpl.resumeToStatusStarted` opens with
   `Assert.isTrue(job.getParentTaskExecutionId() == null, "Can't resume a subflow")`. The line dates to
   `25bcdeff88d ISSUE-76 — Move atlas-service`, the original Atlas import, when "resume" meant a human restarting a
   manually stopped job and suspend/resume did not exist. No test pins it.
2. `SubflowJobStatusEventListener` treats every `STOPPED` child the same: `case STOPPED, CANCELLED` publishes a
   `StopJobEvent` for the parent job. A suspend *is* a `STOPPED` transition, so the moment a child suspends, its
   parent is stopped too — `TaskCoordinator.onStopJobEvent` marks the parent's current task `CANCELLED` and
   dispatches a `CancelControlTask`.

Together they mean: **any suspending step inside a `subflow/v1` child parks both jobs forever.** The child cannot be
resumed (1), and even if it could, the parent has already been cancelled out from under it (2). This is a
code-reading conclusion; the first task in the plan is the integration test that proves it.

Three features hit this wall today:

- **Approval gates inside a sub-workflow.** `JobFacadeImpl.resumeApproval` calls `resumeToStatusStarted` directly
  and would throw on a subflow child.
- **Agent tools inside a sub-workflow.** `SubflowToolSupport.requireGuardsPassed` refuses with
  `ERROR_AGENT_IS_SUBFLOW` whenever the agent's job has a parent task execution — a fail-fast added precisely so the
  agent would not park silently (bug class #5055). That is the "1-level nesting" limit documented in
  `.agents/agents.md` and surfaced as a save-time warning in `AgentSubAgentsCard.tsx`.
- **Calling a published AI Agent deterministically from a workflow.** The agent's generated workflow contains an
  `aiAgent` node whose sub-agent and `callWorkflow` tools all suspend. Launched as a subflow, none of them work.
  This is the blocker for the companion spec, `2026-09-05-call-ai-agent-task-dispatcher-design.md`.

Because the child could never be a real subflow, the agent-tool bridge (`AgentSubflowLauncher` /
`AgentSubflowResumeListener`) had to launch its sub-workflow as a *top-level* job linked only by
`AGENT_JOB_ID` metadata. That workaround carries its own defect: `LAUNCHED_SUBFLOW_JOB_ID`, written on the caller
job as a redelivery guard, is never cleared. `resumeToStatusStarted` reuses the same job id, so a **second** bridged
call on the same job hits `containsKey(...) → return` and the job stays `STOPPED` forever. The only test covering
the key is the redelivery case; nothing distinguishes a redelivery from a legitimate later call.

## Goals

- A `subflow/v1` child job can suspend and later resume, and its parent task completes normally afterwards.
- A child that is *stopped or cancelled* (not suspended) still stops its parent, exactly as today.
- Approval gates, waits and agent tools behave identically whether the workflow runs top-level or as a subflow.
- The agent-tool bridge supports more than one sub-workflow call per job.
- `ERROR_AGENT_IS_SUBFLOW`, the `.agents/agents.md` "1-level" text and the `AgentSubAgentsCard` warning go away,
  because the limitation they describe no longer exists.

## Non-goals

- Changing how the bridge links its jobs. Top-level-plus-metadata stays; only the stale key is fixed.
- Making sub-agent calls synchronous. They remain durable (suspend + separate job).
- Auto-resuming orphaned subflows. `OrphanedJobRecoveryMonitor` filters `parentTaskExecutionId == null` before
  auto-resume today; that filter stays as is. Widening it is a separate decision.
- Any depth cap. Once a subflow can resume, nesting has no natural limit and none is added.

## Design

### 1. Drop the assertion

Delete the `Assert.isTrue(job.getParentTaskExecutionId() == null, ...)` line from
`JobServiceImpl.resumeToStatusStarted`. Everything after it — the already-`STARTED` early return, the
`isRestartable` check, the status write — applies to a subflow child unchanged.

### 2. Teach the listener the difference between "suspended" and "stopped"

In `SubflowJobStatusEventListener.onApplicationEvent`, split the `STOPPED, CANCELLED` case:

- `CANCELLED` → propagate `StopJobEvent(parent)` as today.
- `STOPPED` → read the child's last task execution (`taskExecutionService.fetchLastJobTaskExecution(childId)`).
  If its metadata contains `MetadataConstants.SUSPEND`, the child is parked and will wake itself: **do nothing**.
  Otherwise it was stopped by a user or by the engine: propagate `StopJobEvent(parent)` as today.

The `SUSPEND` metadata key is the discriminator the platform already uses for exactly this question —
`AgentSubflowLauncher.extractPendingSubflowRequest`, `AbstractTaskHandler` and
`SuspendTaskDispatcherPreSendProcessor` all read it. No new state is introduced.

With (1) and (2) in place the parent never learns that its child suspended. When the child later resumes and
reaches `COMPLETED`, the listener's existing `COMPLETED` branch completes the parent task with the callable
response, and `DefaultTaskCompletionHandler` advances a parent that is still `STARTED` — which is the state it was
in all along.

### 3. Remove the guard that no longer guards anything

`SubflowToolSupport.requireGuardsPassed` drops its third check (`getParentTaskExecutionId() != null`) and the
`ERROR_AGENT_IS_SUBFLOW` constant, along with the same-valued constants on `WorkflowCallWorkflowTool` and
`WorkflowCallAiAgentTool`. The two `testToolReturnsErrorWhenAgentIsItselfASubflow` tests are deleted. The javadoc
on `ActionContextAware.getParentTaskExecutionId` loses the sentence that says such a job cannot be resumed.

The other two guards stay: a tool still needs an agent action context, and one suspending tool call per turn is
still the rule.

### 4. Fix the second-call park in the bridge

`AgentSubflowResumeListener`, immediately after a successful `jobFacade.resumeJob(...)`, removes
`LAUNCHED_SUBFLOW_JOB_ID` from the caller job's metadata. The key then means "a launch for the *current* suspend
is in flight", which is what the redelivery guard needs, and a later suspend on the same job starts clean. This
sits in the same module and the same listener the rest of the spec touches; it is included here rather than filed
separately because leaving it would make the companion dispatcher spec inherit a known park.

### 5. Retire the documentation of the limit

- `.agents/agents.md`: the "1-level nesting limit" paragraphs in *Sub-agents* and the summary line near the end.
- `AgentSubAgentsCard.tsx`: the "sub-agents of its own … won't be callable when it runs as a sub-agent" warning and
  its two tests in `AgentSubAgentsCard.test.tsx`.
- `SubflowToolSupport` class javadoc: "the same three guards" becomes two.

## Testing

**Prove it fails first.** An integration test in `task-dispatchers/subflow` (beside `AgentSubflowBridgeIntTest`,
which already assembles the real coordinator, `SuspendTaskDispatcher` and memory repositories) runs a parent
workflow whose `subflow/v1` task points at a child containing a suspending task. Before the change it must observe
the parent `STOPPED` and the child unresumable; after it, the child resumes and the parent completes with the
child's output. Run it against the pre-change code and watch it go red before trusting it.

**Listener unit tests** (`SubflowJobStatusEventListenerTest`): a `STOPPED` child whose last task execution carries
`SUSPEND` metadata publishes nothing; a `STOPPED` child without it publishes `StopJobEvent(parent)`; `CANCELLED`
publishes regardless.

**Approval inside a subflow**: extend the same integration harness with an `approval/v1` task in the child and
resume it through `JobFacade.resumeApproval`. This is the existing-feature regression the change unblocks.

**Bridge second call**: in `AgentSubflowBridgeIntTest`, two sequential bridged calls on one agent job both launch
and both resume. Redelivery of a single `STOPPED` event still launches once — the existing assertion stays.

**Existing suites that must stay green**: every `#5055` test in `AgentSubflowBridgeIntTest`,
`AgentSubflowResumeListenerTest`, `WorkflowCallWorkflowToolTest`, `WorkflowCallAiAgentToolTest` (minus the two
deleted), `JobFacadeIntTest`, `WorkflowTestConfigurationTest`, `WebhookConfigurationTest`.

## Sequencing

1. Failing integration test for the parked subflow.
2. (1) assertion removal + (2) listener split → test green.
3. (4) bridge key clearing + its test.
4. (3) guard removal + test deletion.
5. (5) docs and client warning.

Each step is a commit; each leaves `./gradlew check` green on the touched modules.

## Risks

- **Something else relies on "a subflow never resumes".** The grep for `resumeToStatusStarted` callers found
  `TaskCoordinator.onResumeJobEvent`, `JobFacadeImpl.resumeApproval`, the EE remote controller/client pair,
  `JobServiceWrapper` and `OrphanedJobRecoveryMonitor` (which already filters subflows out). None of them branch on
  the assertion; they would all have thrown on a subflow. Residual risk is a path that *catches* that assertion
  error and treats it as a signal — the integration tests above are where that would surface.
- **A `STOPPED` child with no task executions.** `fetchLastJobTaskExecution` returns empty; the listener treats that
  as "not suspended" and propagates the stop, matching today's behaviour for an empty child.
- **Distributed EE.** The listener runs on the coordinator (`@ConditionalOnCoordinator`); `coordinator-app` already
  carries `task-dispatchers:subflow`. No new module boundary is crossed.
- **Worktree copies.** `.claude/worktrees/*` hold stale copies of every touched file; they are not part of the build
  and are not updated.
