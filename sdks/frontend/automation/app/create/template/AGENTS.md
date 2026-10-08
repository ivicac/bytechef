# Working in this ByteChef app

This app calls external systems only through ByteChef, using the app key in `BYTECHEF_APP_KEY`. An admin decides in
ByteChef which connections this app may use and which actions, tools, proxy methods and workflows are allowed.

## Rules

1. **Every third-party system goes through `@bytechef/app`.** Never add an API key, OAuth client or vendor SDK for a
   system this app reaches through a ByteChef binding, and never call such a system's API directly.
2. **Discover before building.** Run `npm run -s bytechef:status` and use only the operations it lists. Action input
   schemas come from the component's full property definition; a field shown as an open object depends on the
   connection — ask the human what to send. `"inputSchema": null` means the schema is unavailable, not that the
   operation is forbidden.
3. **Only `src/server/` imports the ByteChef client** (`createBytechefClient`, `bytechefMiddleware`); types and pure
   helpers such as `isFinished` may be imported anywhere. Components call the server functions declared in
   `src/server/`, never the client. Nothing from ByteChef goes under a `VITE_` variable.
4. **Write purpose-built server functions** on `bytechef` from `src/server/bytechef.ts`, with `.validator(...)` and a
   concrete return type, exposing exactly what the page needs. The overview and "Try it" in `src/server/dev.ts` exist
   only in development builds; replace the placeholder page with real features, but keep `src/server/dev.ts`:
   `npm run check` reads it to find the development-only server functions. Do not use the ready-made server
   functions from `@bytechef/app/tanstack-start/server-fns` unless the human asks — each one is a public endpoint.
   `npm run check` fails when they are in the build; if the human asked for them, set
   `"bytechef": {"allowReadyMadeServerFns": true}` in `package.json` and the check only warns.
5. **When something is not allowed, ask; do not work around it.** If an operation or binding is missing from the
   status output, or a call fails with `APP_OPERATION_NOT_ALLOWED`, stop and tell the human what to ask a ByteChef
   admin for: the binding key, the component, and the action or tool names. Never fall back to the proxy, a direct
   API call or a new credential. Use the proxy only where the status output shows it enabled, with the listed
   methods.
6. **Do not retry actions automatically;** they can have side effects. Show `BytechefAppError` codes meaningfully.
7. **Anything a page shows is visible to anyone who can open the app** (there is no sign-in yet). Show a workflow's
   approval link only to its intended approver; ask the human before putting it on a page. Never expose `npm run dev`
   beyond localhost (no `--host` with public port forwarding): "Try it" executes any allowed action with the real key.
8. **If you add `src/start.ts`,** include `createCsrfMiddleware({filter: (ctx) => ctx.handlerType === 'serverFn'})`
   from `@tanstack/react-start`, or the server functions lose CSRF protection.
9. **Finish with `npm run check`.** Typecheck, build and the leak check must pass. Vercel deploys run it as their
   build command (`vercel.json`) and the Docker build runs the leak check, but run it locally before you hand work
   back.

## Setting up the app with ByteChef

Everything here runs in ByteChef's DEVELOPMENT environment; staging and production stay a person's decision. The app
is managed with the `bytechef` CLI, workflows with the ByteChef management MCP server.

1. **One-time setup, by a person.** Connect the management MCP server with its URL, an admin API key created in
   DEVELOPMENT and the header `X-ENVIRONMENT: DEVELOPMENT`. Create an automation API key in DEVELOPMENT and store it
   with `bytechef configure --host https://bytechef.example.com --token <key> --environment DEVELOPMENT --workspace-id <id>`.
   Create sign-in (OAuth) connections in the ByteChef UI; an agent cannot create them.
2. **The app first.**
   - `bytechef automation app create --name my-app`, run once in this directory, creates the app, writes
     `BYTECHEF_URL` and `BYTECHEF_APP_KEY` into `.env.local` and the app's id into `.bytechef/app.json` (commit that
     file, never `.env.local`). For an app that already exists: `bytechef automation app link --app-id <id>`.
   - `bytechef automation connection list --component-name hubspot` lists the connections; those with
     `"usable": true` can be bound.
   - `bytechef automation app bind connection --binding-key crm --component-name hubspot --actions createContact --tools searchContacts`
     binds one: actions for this app's code, tools for this app's own AI. The binding becomes exactly what the
     command names, so repeat every action and tool you still want when you change it. With several usable
     connections, pass `--connection-id`.
   - `npm run -s bytechef:status` shows what the app may now call; `bytechef automation app status` shows the
     bindings as ByteChef sees them.
3. **Workflows, only when the app needs one** (several steps, an approval). Through the MCP server: `createProject`
   (pass `workspaceId`) → `buildWorkflow` with `appCallable: true` → `publishProject` → `deployProject`. Then run the
   `bytechef automation app bind workflow --workflow-key … --deployment-id … --workflow-uuid …` command that
   `deployProject` prints; the workflow key is the key your code passes to `workflows.execute`. To change a workflow:
   `buildWorkflow` naming the `draftWorkflowId` from the last `deployProject` result → `publishProject` →
   `deployProject`; the binding stays valid. If `buildWorkflow` is missing, ask a ByteChef admin to enable Copilot.
4. **Use it from the app** with the recipes below: actions from server functions; tools, and optionally workflows,
   for an in-app agent through `toAiSdkTools`.
5. **Loop rules.** Never print or echo `BYTECHEF_APP_KEY`: the CLI writes it to `.env.local` and never prints it, and
   `bytechef automation app key rotate` replaces it the same way. Stay in DEVELOPMENT. Ask a person to create
   sign-in connections. Without a configured CLI, rule 5 under "Rules" applies: ask a ByteChef admin for the binding.
   `bytechef automation app unbind connection --binding-key crm` and
   `bytechef automation app unbind workflow --workflow-key onboard-customer` remove bindings;
   `bytechef automation app list` lists the workspace's apps.

## Recipes

All server functions live in `src/server/`. `bytechef` is the middleware from `src/server/bytechef.ts`; it puts a
client on `context.bytechef`.

### An action

```ts
import {createServerFn} from '@tanstack/react-start';

import {bytechef} from './bytechef';

interface Contact {
    email: string;
    id: string;
}

function createContactInput(input: unknown): {email: string; name: string} {
    if (typeof input !== 'object' || input === null) {
        throw new TypeError('input must be an object');
    }

    const {email, name} = input as Record<string, unknown>;

    if (typeof email !== 'string' || typeof name !== 'string') {
        throw new TypeError('email and name must be strings');
    }

    return {email, name};
}

export const createContact = createServerFn({method: 'POST'})
    .middleware([bytechef])
    .validator(createContactInput)
    .handler(({context, data}) => context.bytechef.actions.execute<Contact>('crm', 'createContact', data));
```

### A tool

```ts
import {createServerFn} from '@tanstack/react-start';

import {bytechef} from './bytechef';

export const searchContacts = createServerFn({method: 'POST'})
    .middleware([bytechef])
    .validator((input: unknown) => ({query: String((input as {query?: unknown}).query ?? '')}))
    .handler(({context, data}) =>
        context.bytechef.tools.executeByName<{id: string; name: string}[]>('CRM__SEARCH_CONTACTS', data)
    );
```

### A workflow run

Start the run and return it; poll from the page until it is finished. A server function gets no abort signal, so do
not wait inside it. Return a purpose-built shape, never the whole `WorkflowRun`: pick the result fields the page needs
with a type guard, and leave `approvalFormUrl` out. `getOnboarding` takes any run id a visitor sends, so it refuses a
run of another workflow.

```ts
import type {WorkflowRun} from '@bytechef/app';
import {createServerFn} from '@tanstack/react-start';

import {bytechef} from './bytechef';

const WORKFLOW_KEY = 'onboard-customer';

export interface Onboarding {
    customerId: string | null;
    error: string | null;
    runId: string;
    status: WorkflowRun['status'];
}

function customerIdOf(result: unknown): string | null {
    if (typeof result !== 'object' || result === null) {
        return null;
    }

    const {customerId} = result as {customerId?: unknown};

    return typeof customerId === 'string' ? customerId : null;
}

function toOnboarding(run: WorkflowRun): Onboarding {
    return {
        customerId: run.status === 'COMPLETED' ? customerIdOf(run.result) : null,
        error: run.error?.message ?? null,
        runId: run.runId,
        status: run.status,
    };
}

export const startOnboarding = createServerFn({method: 'POST'})
    .middleware([bytechef])
    .validator((input: unknown) => ({email: String((input as {email?: unknown}).email ?? '')}))
    .handler(
        async ({context, data}): Promise<Onboarding> =>
            toOnboarding(await context.bytechef.workflows.execute(WORKFLOW_KEY, data, {waitSeconds: 10}))
    );

export const getOnboarding = createServerFn({method: 'GET'})
    .middleware([bytechef])
    .validator((input: unknown) => ({runId: String((input as {runId?: unknown}).runId ?? '')}))
    .handler(async ({context, data}): Promise<Onboarding> => {
        const run = await context.bytechef.workflows.getRun(data.runId);

        if (run.workflowKey !== WORKFLOW_KEY) {
            throw new Error(`Run ${data.runId} is not an onboarding run`);
        }

        return toOnboarding(run);
    });
```

Poll with a bound: pass an `AbortSignal` (aborted when the page goes away) and a maximum number of attempts. A run
in `APPROVAL_REQUIRED` waits for its approver; its approval link stays in ByteChef (rule 7).

```tsx
import {isFinished} from '@bytechef/app';

import {getOnboarding, startOnboarding, type Onboarding} from '@/server/onboarding';

function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(resolve, milliseconds);

        signal.addEventListener(
            'abort',
            () => {
                clearTimeout(timer);
                reject(signal.reason);
            },
            {once: true}
        );
    });
}

export async function runOnboarding(email: string, signal: AbortSignal, maxAttempts = 100): Promise<Onboarding> {
    let onboarding = await startOnboarding({data: {email}, signal});

    for (let attempt = 0; attempt < maxAttempts && !isFinished(onboarding.status); attempt++) {
        await delay(3000, signal);

        onboarding = await getOnboarding({data: {runId: onboarding.runId}, signal});
    }

    if (!isFinished(onboarding.status)) {
        throw new Error(`Run ${onboarding.runId} did not finish after ${maxAttempts} attempts`);
    }

    return onboarding;
}
```

### The proxy

Only where `bytechef:status` shows `proxy.enabled` and the method is listed.

```ts
import {createServerFn} from '@tanstack/react-start';

import {bytechef} from './bytechef';

export const getContactRaw = createServerFn({method: 'GET'})
    .middleware([bytechef])
    .validator((input: unknown) => ({id: String((input as {id?: unknown}).id ?? '')}))
    .handler(async ({context, data}) => {
        const response = await context.bytechef.proxy('crm', {
            method: 'GET',
            path: `/crm/v3/objects/contacts/${encodeURIComponent(data.id)}`,
        });

        return {body: response.body, bodyEncoding: response.bodyEncoding, status: response.status};
    });
```

### An AI chat with ByteChef tools

Needs `ai`, `zod`, a provider package (for example `@ai-sdk/anthropic`) and a model API key. That key is the app's
own secret, not governed by ByteChef — ask the human before adding it. A prompt endpoint without sign-in is a public
executor of every tool it is given, so `include` only read-only tools unless the human decides otherwise. Workflows
given to the model put their approval link into the model's reply, so leave them out.

```ts
import {anthropic} from '@ai-sdk/anthropic';
import {toAiSdkTools} from '@bytechef/app/ai-sdk';
import {createServerFn} from '@tanstack/react-start';
import {generateText, stepCountIs} from 'ai';

import {bytechef} from './bytechef';

export const ask = createServerFn({method: 'POST'})
    .middleware([bytechef])
    .validator((input: unknown) => ({prompt: String((input as {prompt?: unknown}).prompt ?? '')}))
    .handler(async ({context, data}) => {
        const result = await generateText({
            abortSignal: AbortSignal.timeout(60_000),
            model: anthropic('claude-sonnet-5-5'),
            prompt: data.prompt,
            stopWhen: stepCountIs(5),
            tools: await toAiSdkTools(context.bytechef, {include: ['CRM__SEARCH_CONTACTS']}),
        });

        return result.text;
    });
```

## Deploy

- **Vercel:** `vercel env add BYTECHEF_URL production` and `vercel env add BYTECHEF_APP_KEY production`, then
  `vercel deploy --prod`. `vercel.json` makes `npm run check` the build command, so a failing check fails the deploy.
- **Self-host:** `docker build -t my-app .`; run it with `BYTECHEF_URL` and `BYTECHEF_APP_KEY` set.
- After the key is rotated in ByteChef, update the host's variable and redeploy.
