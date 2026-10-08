---
name: ByteChef App Builder
description: This skill should be used when the user asks to "create a ByteChef app", "set up my app with ByteChef", "bind a connection to my app", "give my app a workflow", "put the ByteChef app key in .env.local", or works in an app made from the create-bytechef-app template and wants Claude to create the app, bind its connections, and build, deploy and bind the workflows it calls.
transports:
  required: [cli]
  optional: [mcp]
---

# ByteChef App Builder

An app made from the `create-bytechef-app` template reaches third-party systems only through ByteChef, with one app
key. This skill runs the loop that sets it up: create the app and put its key in `.env.local`, bind connections, and,
only when the app needs one, build, deploy and bind a workflow. Everything runs in ByteChef's DEVELOPMENT environment;
staging and production stay a person's decision. The template's own `AGENTS.md` carries the same loop for agents
without this plugin.

## One-time setup, by a person

- The CLI manages the app: it needs an automation API key created in DEVELOPMENT.
- The management MCP server builds and deploys workflows: an admin API key created in DEVELOPMENT and the header
  `X-ENVIRONMENT: DEVELOPMENT` (see the **ByteChef Management MCP Setup** skill).
- Sign-in (OAuth) connections are created by a person in the ByteChef UI; Claude cannot create them.

## The app

<!-- transport: cli uses: configure, automation app create, automation app link, automation app list, automation app status, automation connection list, automation app bind connection, automation app unbind connection, automation app key rotate -->
Configure the CLI once with the DEVELOPMENT automation API key, then run the app commands in the app's directory.
Every `automation app` command and `automation connection list` refuses any environment but DEVELOPMENT.

```bash
bytechef configure --profile dev --host https://bytechef.example.com --token <AUTOMATION_API_KEY> \
  --environment DEVELOPMENT --workspace-id 1
bytechef automation app create --name my-app --profile dev
bytechef automation connection list --component-name hubspot --profile dev
bytechef automation app bind connection --binding-key crm --component-name hubspot \
  --actions createContact --tools searchContacts --profile dev
bytechef automation app status --profile dev
```

- `create` writes `BYTECHEF_URL` and `BYTECHEF_APP_KEY` into `.env.local` (owner-only, other lines kept) and
  `.bytechef/app.json` (no secret; commit it). It never prints the key; never print or echo it yourself. If a file
  write fails after the app was created, the error names the app id: `bytechef automation app link --app-id <id>`
  restores `.bytechef/app.json` and `bytechef automation app key rotate` writes a new key.
- Later commands find the app through `.bytechef/app.json`, walking up from the current directory, or take
  `--app-id`. `bytechef automation app list` lists the workspace's apps.
- `bind connection` makes the binding exactly what it names: an omitted `--actions`, `--tools` or `--proxy` means
  none, so repeat everything you still want. Actions are for the app's code, tools for the app's own AI. With exactly
  one usable connection of the component the CLI picks it; otherwise it lists the candidates and you pass
  `--connection-id`. Unknown action or tool names fail with the allowed ones listed.
  `bytechef automation app unbind connection --binding-key crm` removes a binding.
- Then `npm run -s bytechef:status` in the app shows what it may call.
<!-- /transport -->

## Workflows, only when the app needs one

<!-- transport: mcp uses: deployProject, listConnections, listAppCallableWorkflows edition: ee -->
An app calls a ByteChef workflow only when the workflow is app-callable: its trigger is the Call-workflow trigger
(`workflow/v1/newWorkflowCall`) with an `inputSchema` (a string holding a JSON Schema) describing every value the app
sends. A final `workflow/v1/responseToWorkflowCall` task with an `outputSchema` (also a string holding a JSON Schema)
describes what the app gets back; without it the app receives the job outputs. The app loop runs in DEVELOPMENT only:
the MCP client sends `X-ENVIRONMENT: DEVELOPMENT` with an admin API key created in DEVELOPMENT, and these tools refuse
any other `environment`.

1. Create the project with `createProject`, always passing `workspaceId`.
2. Build each workflow with `buildWorkflow` and `appCallable: true`. It reports each saved `workflowId` as app-callable
   or names what is missing; then call it again naming that `workflowId`.
3. Publish with `publishProject`. `deployProject` refuses a project with unpublished changes.
4. Deploy with `deployProject`. It keeps one DEVELOPMENT deployment per project, picks a connection for each slot
   when exactly one usable candidate exists and otherwise lists the candidates (pass them in `connections`;
   `listConnections` shows every connection with `usable` and `aiProvider`), and enables the app-callable workflows.
   Its result gives, per app-callable workflow, the `bytechef automation app bind workflow` command to run in the
   app's directory, and the `draftWorkflowId` for the next change.
5. To change a workflow: `buildWorkflow` naming the `draftWorkflowId` → `publishProject` → `deployProject`. The
   app's binding stays valid. `listAppCallableWorkflows` lists what is deployed and callable.

If `buildWorkflow` is not registered, a ByteChef admin must set `bytechef.ai.copilot.enabled: true` with a chat model
provider (optionally chosen with `bytechef.ai.copilot.provider`).
<!-- /transport -->

## Binding a deployed workflow

<!-- transport: cli uses: automation app bind workflow, automation app unbind workflow, automation app status -->
Bind the deployed workflow under the key the app's code passes to `workflows.execute`:

```bash
bytechef automation app bind workflow --workflow-key onboard-customer --deployment-id 7 \
  --workflow-uuid 3f1c2a9e-0d4b-4c55-9a43-6a1f0e2b7c11 --profile dev
```

`bytechef automation app status` shows each workflow binding with `available`;
`bytechef automation app unbind workflow --workflow-key onboard-customer` removes one.
<!-- /transport -->
