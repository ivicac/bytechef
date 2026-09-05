# Call AI Agent — A Task Dispatcher for Invoking a Published Agent

**Date:** 2026-09-05
**Status:** Proposed — depends on `2026-09-05-resumable-subflows-design.md` landing first
**Scope:** New Gradle module `server/libs/modules/task-dispatchers/call-ai-agent`, a small extraction in
`task-dispatchers/subflow`, three registration sites, and three client files that enumerate task dispatchers.

## Problem

A workflow can invoke a published AI Agent today in exactly one way: the `workflow/v1/callAiAgent` **tool**, which
only an `aiAgent` node can call, and only when an LLM decides to. There is no deterministic step — nothing a plain
workflow can place after a trigger and say "send this message to that agent and give me the answer".

Until `be3669a7ec7` the `subflow/v1` picker offered a back door: a published agent's hidden `__AI_AGENT__` project
was surfaced as `Agent > <label>`. That was removed because it exposed the agent as a generic sub-workflow —
bypassing the fixed `{message, conversationId}` contract and presenting an internal project as if it were the
user's own. The right replacement is a first-class node, mirroring how `callWorkflow` (tool) pairs with `subflow`
(dispatcher).

A **task dispatcher** rather than an action, because the deterministic "start a child job and wait" primitive in
this engine *is* the task dispatcher. An action would have to ride the agent-tool suspend bridge, which stops the
whole parent job, allows one call per job, cannot fail the step on child failure without new plumbing, and forbids
the caller from ever being a subflow itself. The dispatcher has none of those limits — **provided the called agent
can suspend while running as a subflow**, which is what the companion spec delivers. Without it, a
dispatcher-launched agent's own sub-agent and `callWorkflow` tools would refuse to run.

### Fan-out: the capability the tool cannot have

The tool cannot call two agents at once, and no amount of prompting changes that. `callAiAgent` does not return a
value — it suspends the calling agent's whole job and returns `ToolSuspendConstants.SUSPENDED_SENTINEL`, and
`SuspendableToolCallingManager` then sets `returnDirect=true`, halting Spring AI's tool-calling loop at that point.
One suspend per turn is all the loop can carry, and `SubflowToolSupport.requireGuardsPassed` enforces it explicitly:
a second suspending call in the same turn gets back *"another tool already suspended the agent in this turn; only one
suspending tool call … is supported per turn."* The bridge is job-scoped underneath too —
`AgentSubflowLauncher.extractPendingSubflowRequest` reads `fetchLastJobTaskExecution(agentJobId)`, one pending
request per job.

The failure mode is worth stating because it is confusing in practice rather than loud: a model *can* emit two
`callAiAgent` calls in one assistant message (Spring AI supports parallel tool calls, and the manager delegates the
whole round before it inspects for a suspend), so both are called — the first suspends, and the second lands in the
conversation as an error string the model then has to reason about. And because the guard tests
`actionContext.getSuspend() != null` rather than anything tool-specific, the limit is one suspending tool of *any*
kind per turn: a sub-agent call cannot be paired with an approval request either, since the HITL gate suspends
through the same sentinel protocol.

A dispatcher has none of this. It creates a real child job and never suspends its caller, so `callAiAgent/v1`
nodes fan out to N agents concurrently, each with its own child job, its own failure propagation and its own
output — a shape the tool cannot express at all. Concurrent fan-out to several agents is therefore not a side
benefit of this node; for that use case it is the only door.

Two dispatchers express the fan-out, and they are not interchangeable. `parallel/v1` holds N *different*
`callAiAgent/v1` nodes, each naming its own agent, and leaves their outputs on the individual nodes. `map/v1`
holds a single node and applies it once per item — `MapTaskDispatcher.doDispatch` reads
`iterateeWorkflowTasks.getFirst()`, so a second node under it would never run — which addresses N agents by
mapping the node over a list of agent uuids, and is the only one of the two that aggregates the replies back
into an ordered list.

## Goals

- A `callAiAgent/v1` node in the **Flows** tab: pick a published agent, give it a message, get its reply as the
  node's output.
- Same three inputs as the tool — `agentUuid`, `message`, optional `conversationId` — so the two surfaces cannot
  drift.
- Native dispatcher semantics: child failure fails the step, child cancel stops the caller, N calls per run,
  caller may itself be a subflow.
- **Concurrent fan-out to several agents** — N different `callAiAgent/v1` nodes under `parallel/v1`, or one node
  under `map/v1` over a list of agent uuids, run at the same time, each an independent child job. The tool is
  structurally limited to one suspending call per turn (see *Fan-out* above), so this is the only way to express it.
- The called agent runs with every tool it has, including sub-agents.
- Agents stay out of the generic `subflow/v1` picker.

## Non-goals

- Changing the `callAiAgent` tool. It keeps the bridge; it is the LLM-invoked door.
- Exposing anything but the callable response as output. Streaming, intermediate tool events and thinking are the
  tool's concern, not this node's.
- A depth cap on nested agents.

## Design

### Module

`server/libs/modules/task-dispatchers/call-ai-agent`, registered in `settings.gradle.kts` beside `subflow`.
`build.gradle.kts` mirrors `subflow`'s plus a dependency on `task-dispatchers:subflow` for the shared helper below.
Four classes, matching the `subflow` layout:

- `CallAiAgentTaskDispatcher` — `TaskDispatcher<TaskExecution>` + `TaskDispatcherResolver` for type
  `callAiAgent/v1`.
- `CallAiAgentTaskDispatcherDefinitionFactory` — the definition.
- `CallAiAgentTaskDispatcherConstants` — `CALL_AI_AGENT`, `AGENT_UUID`, `MESSAGE`, `CONVERSATION_ID`.
- `config/CallAiAgentTaskDispatcherConfiguration` — one `@ConditionalOnCoordinator` `@Bean`
  (`callAiAgentTaskDispatcherResolverFactory_v1`).

No completion handler and no job-status listener: `SubflowJobStatusEventListener` gates only on
`job.getParentTaskExecutionId() != null`, so any dispatcher whose child carries a parent task execution inherits
`COMPLETED`, `FAILED`, `STOPPED` and `CANCELLED` handling. Its `FAILED` branch publishes `TaskExecutionErrorEvent`,
which is the "fail the step" behaviour this node wants.

### Definition

```
callAiAgent/v1  "Call AI Agent"
  agentUuid       string, required, label "Agent"
                  options: CallableAiAgentDataSource.getCallableAgents(search)   — published agents only
  message         string, required, TEXT_AREA, "The message sent to the agent."
  conversationId  string, optional, "Memory-thread key passed to the agent. Left blank, the agent starts or
                  continues whatever thread its own conversationId input resolves to."
  output          object — the agent's callable response
```

The input set is deliberately static. Every agent-generated workflow's `workflowCall` channel pins
`WorkflowConstants.AI_AGENT_CALL_INPUT_SCHEMA` (`{message, conversationId}`), so there is nothing to look up per
agent — unlike `subflow`, which needs `dynamicProperties(INPUTS)` because an arbitrary sub-workflow declares its own
schema. Output mirrors `subflow`'s: `SubflowDataSource.getSubWorkflowOutputSchema(workflowUuid)` on the resolved
agent workflow, or null.

The definition factory and the dispatcher both take `CallableAiAgentDataSource` through `ObjectProvider` +
`getIfAvailable()`, exactly like the `workflow` component's `callAiAgent` tool, and hold it as a `@Nullable` field.
A required constructor dependency was considered and is wrong: this dispatcher (or the modules carrying the
literal dispatcher lists that register it) reaches four apps — `configuration-app`, `webhook-app`,
`coordinator-app` and `runtime-job-app` — none of which carry `automation-ai-agent-service` on their classpath, so
a required bean would fail those apps at boot rather than only the `callAiAgent/v1` dispatch path. With the bean
absent, `getAgentOptions`/`output` in the definition factory degrade to empty options and no output schema, and
`CallAiAgentTaskDispatcher.dispatch` throws a clear `IllegalStateException` ("Call AI Agent is not available in
this deployment") at dispatch time instead of vanishing silently. Do not restore the required-dependency version.

### Dispatch

```
resolvedAgent = callableAiAgentDataSource.resolveAgent(agentUuid, editorEnvironment)
subflow       = subflowResolver.resolveSubflow(resolvedAgent.workflowUuid(), NEW_WORKFLOW_CALL, editorEnvironment)
inputs        = { message, conversationId? }
→ SubflowChildJobLauncher.launch(taskExecution, job, subflow, inputs)
```

Resolution failures — agent deleted or never published, callable trigger removed — throw out of `dispatch`, and
the coordinator's normal error path fails the task. No error *strings*: there is no LLM on this path to read them.
That is the intended difference from the tool.

`editorEnvironment` comes from `job.getMetadata()` under `MetadataConstants.EDITOR_ENVIRONMENT`, exactly as
`SubflowTaskDispatcher` reads it, and is passed to `resolveAgent` so the editor's Test button keeps
`CallableAiAgentDataSourceImpl`'s deliberately permissive draft resolution while deployed runs require a published
version.

### Shared child-job launch

The tail of `SubflowTaskDispatcher.dispatch` — wrap the caller-supplied input values under `subflow.inputsName()`,
add `__triggerName`, copy the parent job's metadata, construct `JobParametersDTO` with the parent task execution
id, call `childJobPrincipalFactory.createChildJob` — is extracted into a helper in the `subflow` module
(`SubflowChildJobLauncher.launch(taskExecution, job, subflow, inputValues)`) that both dispatchers call. The
helper receives the *content* map: `subflow` passes what it reads from `WorkflowConstants.INPUTS`, `callAiAgent`
passes `{message, conversationId?}`. `SubflowTaskDispatcher`'s behaviour and its tests do not change; the
extraction is the one edit to that module.

### Which deployment the agent runs under

`ChildJobPrincipalFactory.createChildJob` links the child to the **caller's** principal — for a same-project
sub-workflow that is the right deployment. An agent has its *own* deployment (Agent Deployments page, keyed on the
agent's hidden project). The existing bridge, via `createPrincipalLinkedJob`, also links to the caller's principal
today, so there is precedent for running an agent under whoever called it.

This spec **keeps caller-principal semantics** to match the tool, and records the consequence: the agent's tool
connections are resolved from the calling deployment. If a called agent needs connections the caller's deployment
does not carry, that surfaces as a missing-connection failure at run time, the same as it would through the tool.
Resolving the agent's own deployment in the caller's environment is a follow-up that should change both surfaces
together, not this node alone.

### Registration — four sites, all required

1. `CallAiAgentTaskDispatcherConfiguration` `@Bean` — the production coordinator.
2. `WorkflowTestConfiguration` (`platform-workflow-test-service`) — the editor's Test button builds its resolver
   chain from a literal `List.of(...)` at line ~318.
3. `WebhookConfiguration` (`platform-webhook-impl`) — same shape at line ~256.
4. `settings.gradle.kts` `include(...)`, and `build.gradle.kts` dependencies wherever `task-dispatchers:subflow`
   is already declared: `server-app`, `coordinator-app`, `runtime-job-app`, `platform-workflow-test-service`,
   `platform-webhook-impl`, `automation-ai-mcp-server`, `embedded-ai-mcp-server`.

`WorkflowTestConfigurationTest` and `WebhookConfigurationTest` scan `com.bytechef.task.dispatcher` and fail when a
dispatcher on the classpath is missing from their list, so (2) and (3) are self-guarding once (4) is done.

### Client

Task dispatchers are enumerated by name in three places; each gets a `callAiAgent` entry shaped like `subflow`'s:

- `shared/constants.tsx` — the dispatcher names list, `CHILDLESS_TASK_DISPATCHER_NAMES`, and the
  `<name> → <name>Data` key map.
- `workflow-editor/utils/taskDispatcherConfig.tsx` — a generic leaf entry (`buildGenericNodeData`,
  `contextIdentifier: 'callAiAgentId'`, `dataKey: 'callAiAgentData'`, `getSubtasks: () => []`).
- `project-deployments/.../projectDeploymentDialog-utils.ts` — **deliberately untouched.** It walks
  `task.type.startsWith('subflow')` to fold a sub-workflow's connections into the parent deployment. An agent's
  connections belong to the agent's own deployment; folding them here would be wrong under either principal
  semantics.

## Testing

- `CallAiAgentTaskDispatcherTest` — resolves the agent, resolves the subflow, launches with `{message}` and with
  `{message, conversationId}`; a `resolveAgent` failure propagates as an exception; `resolve()` matches only
  `callAiAgent/v1`.
- `CallAiAgentTaskDispatcherDefinitionFactoryTest` — `JsonFileAssert` snapshot `definition/callAiAgent_v1.json`.
  Expect the two-run regeneration: the first run writes the file to `src/test/resources` and throws
  `NullPointerException: url` on the missing classpath copy; the second passes.
- End-to-end in the `subflow` integration harness: a parent workflow calls an agent workflow whose `aiAgent` node
  is stubbed to suspend once (a sub-agent call) and then reply. The parent completes with the reply. This is the
  test that proves the companion spec was necessary — it parks without it.
- **Fan-out** — both dispatchers, since they fan out by different mechanisms: a parent with two `callAiAgent/v1`
  nodes under `parallel/v1` targeting two different agents, and a parent with one node under `map/v1` over a list of
  two agent uuids. In each, both child jobs must exist concurrently (assert both are non-terminal at the same moment,
  not merely that both eventually completed — sequential execution would satisfy the weaker assertion), each must
  carry its own `parentTaskExecutionId`, and the parent must complete with both replies; the `map/v1` case must also
  aggregate the replies in item order. This is the claim in *Fan-out* above and the one capability the tool cannot
  match, so it needs tests that would fail if the calls serialised.
- `WorkflowTestConfigurationTest` / `WebhookConfigurationTest` — unchanged, and now covering the new type.
- Client: `taskDispatcherConfig` and `constants` have existing per-dispatcher tests; add the `callAiAgent` cases.

## Sequencing

1. Companion spec lands (resumable subflows). Hard prerequisite.
2. Extract `SubflowChildJobLauncher`; `subflow` tests green.
3. New module, four classes, snapshot test, Gradle wiring.
4. Registration sites (2) and (3); config tests green.
5. Client entries.
6. End-to-end test.

## Risks

- **Landing before the companion spec.** The node would work for agents without suspending tools and park for
  agents with them — the worst kind of partial. Sequencing is not optional.
- **Component index.** Task dispatchers are registered as `TaskDispatcherDefinitionFactory` beans, not through
  `ComponentDefinitionRegistry`, so `component-index.json` is not involved and nothing needs regenerating. `subflow`
  ships the same way.
- **`callAiAgent` as both a tool name and a dispatcher name.** They live in different namespaces —
  `workflow/v1/callAiAgent` is a cluster element keyed under a component; `callAiAgent/v1` is a dispatcher type.
  `WorkflowNodeType.ofType` distinguishes them by shape. The shared operation word is intentional: it is the same
  capability on two surfaces.
- **Missing connections at run time** under caller-principal semantics (see above). Known, documented, matches the
  tool.
- **The fan-out claim is observed, in both shapes.** `CallAiAgentFanOutIntTest` drives `parallel/v1` and `map/v1`
  end to end: both child jobs are launched and observed suspended *at the same moment* before either is resumed —
  a state serialised execution cannot reach — each carries its own `parentTaskExecutionId`, and both replies reach
  the parent, in item order under `map/v1`. Completion flows back through `SubflowJobStatusEventListener` →
  `TaskExecutionCompleteEvent` → the hosting dispatcher's completion handler, as reasoned. This resolves the open
  question this section originally recorded; the narrative above is corrected where it named `map/v1` for the
  multiple-node shape, which `map/v1` cannot host.
