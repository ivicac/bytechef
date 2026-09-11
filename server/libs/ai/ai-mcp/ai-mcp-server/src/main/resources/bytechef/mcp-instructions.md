## always
ByteChef management server. Ordinary tools are deterministic CRUD; intelligent tools run an inner AI agent and may take minutes.

## tool: listProjects
Check whether the target project already exists with `listProjects`. If not, create it with
`createProject`. Either way, create the workflow inside it with `createProjectWorkflow` — its
result is the `workflowId` that every later workflow operation keys on.

## tool: createProject
Check whether the target project already exists with `listProjects`. If not, create it with
`createProject`. Either way, create the workflow inside it with `createProjectWorkflow` — its
result is the `workflowId` that every later workflow operation keys on.

## tool: createProjectWorkflow
Check whether the target project already exists with `listProjects`. If not, create it with
`createProject`. Either way, create the workflow inside it with `createProjectWorkflow` — its
result is the `workflowId` that every later workflow operation keys on.

## tool: buildWorkflow
Call `buildWorkflow` with the `workflowId` from above and a plain-language instruction describing
what the workflow should do.

Each intelligent-tool call is independent: it re-reads the current state of the workflow rather
than remembering earlier calls in this conversation. To keep building, call `buildWorkflow` again
with the next instruction and restate any context it still needs — do not assume it recalls what
was asked for previously.

`buildWorkflow` may come back with a question and numbered options instead of a finished result.
When that happens, present the options to the user, then call `buildWorkflow` again with the
**original request together with the chosen answer** — the call that asked the question is gone by
the time the answer arrives, so the answer on its own is not enough for a fresh call to act on.
Ordinary CRUD tools never do this.

## tool: importWorkflow
Call `importWorkflow` with the `workflowId` and the source workflow definition (the exported
n8n/Make/Zapier/Workato JSON). It has no project- or workflow-creation tools of its own — create
the project and the empty workflow first, using the sequence above, then import into it. Each call
is independent and may return a clarifying question instead of a result; handle it the same way.

## tool: listComponents
Before wiring a step by hand, find the component with `listComponents`, find the action with
`searchActions`, then read its full shape with `getActionDefinition` and `getProperties` to see
what parameters a task needs.

## tool: searchActions
Before wiring a step by hand, find the component with `listComponents`, find the action with
`searchActions`, then read its full shape with `getActionDefinition` and `getProperties` to see
what parameters a task needs.

## tool: getActionDefinition
Before wiring a step by hand, find the component with `listComponents`, find the action with
`searchActions`, then read its full shape with `getActionDefinition` and `getProperties` to see
what parameters a task needs.

## tool: getProperties
Before wiring a step by hand, find the component with `listComponents`, find the action with
`searchActions`, then read its full shape with `getActionDefinition` and `getProperties` to see
what parameters a task needs.

## tool: listTriggers
Same pattern for triggers: `listTriggers`/`searchTriggers` to find the one to wire up, then
`getTriggerDefinition` for its parameter shape before setting it on the workflow.

## tool: searchTriggers
Same pattern for triggers: `listTriggers`/`searchTriggers` to find the one to wire up, then
`getTriggerDefinition` for its parameter shape before setting it on the workflow.

## tool: getTriggerDefinition
Same pattern for triggers: `listTriggers`/`searchTriggers` to find the one to wire up, then
`getTriggerDefinition` for its parameter shape before setting it on the workflow.

## tool: getWorkflow
`getWorkflow` returns the current definition — MCP App-capable clients render it as an interactive
canvas alongside the conversation. `updateWorkflow` writes a new definition back. Reach for these
when a direct edit (renaming a task, tweaking one parameter) is clearer than issuing another
build instruction.

## tool: updateWorkflow
`getWorkflow` returns the current definition — MCP App-capable clients render it as an interactive
canvas alongside the conversation. `updateWorkflow` writes a new definition back. Reach for these
when a direct edit (renaming a task, tweaking one parameter) is clearer than issuing another
build instruction.

## tool: publishProject
A built or imported workflow is not live until its project is published. Once it looks right, call
`publishProject`.
