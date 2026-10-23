# Embedded: admin provisioning of integration instances

## Intent

A tenant admin needs to deploy **integration X, pinned to version Y, to customer Z** from the ByteChef UI —
creating Z's integration instance and its connection in one step, without Z going through ConnectDialog.
The typical reason is keeping a specific customer on an **older** version while new customers get the
latest one.

## Background (how it works today)

- An `IntegrationInstance` stores `connectedUserId`, `connectionId` and `integrationInstanceConfigurationId`.
  It has no version of its own; the version is `IntegrationInstanceConfiguration.integrationVersion`.
- An integration may have several configurations per environment — the unique constraint on
  `(environment, integration_id, integration_version)` was dropped in `20240604183120`.
- Self-serve (ConnectDialog, SDK marketplace, public REST) assumes exactly **one enabled** configuration
  per integration and environment:
  - `IntegrationInstanceConfigurationServiceImpl.getIntegrationIntegrationInstanceConfiguration` →
    `findByIntegrationIdAndEnvironmentAndEnabled` returns `Optional`, so two enabled rows throw
    `IncorrectResultSizeDataAccessException`.
  - `IntegrationInstanceConfigurationFacadeImpl.getIntegrationInstanceConfigurationIntegration` takes
    `findFirst()` over all enabled configurations — with two, it silently returns an arbitrary one.
  - `ConnectedUserIntegrationFacadeImpl.getConnectedUserIntegrations` maps every enabled configuration to
    an entry — with two, integration X appears twice.
  - (`getIntegrationInstanceConfigurationIntegration(s)` ignore their `enabled` parameter and always read
    enabled configurations.)
- Runtime paths also assume one version per environment:
  - `RequestTriggerApiController.executeWorkflow` (embedded-webhook-public-rest, ~line 128) resolves the
    workflow id with `integrationWorkflowService.fetchLastWorkflowId(workflowUuid, environment)` — the
    highest version anyone uses — then looks up the connected user's instance for that workflow id. A
    customer pinned to an older version has no instance bound to it → "Integration instance not found".
  - `IntegrationInstanceService.fetchIntegrationInstance(connectedUserId, componentName, env)` is a
    `findFirst` by component name; callers `EmbeddedMcpToolFacade:338`, `ConnectionIdHelper:57` (only
    when no instance id is given) and `AbstractConnectedUserProjectDispatcherPreSendProcessor:82` pick an
    arbitrary instance when a customer has several of one integration. This is already true today
    (multiple self-serve instances are allowed); provisioning makes it more common.
- `ConnectedUserIntegrationFacadeImpl.createIntegrationInstance` already merges the configuration's
  `connectionParameters` with the caller's, creates the connection (`PlatformType.EMBEDDED`) and the
  instance. It always uses the enabled configuration.
- Version upgrades happen on the configuration ("Upgrade Integration Version" dialog,
  `IntegrationInstanceConfigurationServiceImpl.update`); every instance bound to it moves together.
  Customers never move themselves.
- Disabling a configuration (`IntegrationInstanceConfigurationFacadeImpl.enableIntegrationInstanceConfiguration`)
  turns off the triggers of every instance on it.
- A connected user belongs to one environment (`ConnectedUser.environment`).

## Goals

1. On the Connected Users page, in a customer's detail sheet, an **"+ Add integration"** button opens a
   provisioning dialog.
2. In the dialog the admin picks:
   - the **integration**,
   - one of its **enabled configurations** (this fixes the version),
   - the customer's **connection credentials** — typed in by the admin, or for OAuth2 obtained by the
     admin signing in through the OAuth2 popup,
   - which of the configuration's **workflows** to enable, and **the inputs** of each enabled workflow.
3. Submitting creates the connection, the integration instance bound to the chosen configuration, and the
   instance workflows (enabled, with inputs) — Z is now running X on version Y.
4. Several enabled configurations of the same integration can coexist in one environment; self-serve
   keeps working and offers new customers exactly one of them (rule below).
5. Z's own embedded UI shows, for each of Z's instances, **that instance's** configuration (workflows,
   authorization), not the self-serve one.

## Non-goals

- A public/backend API for provisioning (UI only for now; the API can come later).
- Moving an existing instance to another configuration ("re-pin"). Later feature.
- Pinning a customer to a **newer** version than new customers get (early access). Explicitly not
  needed — see the self-serve rule.
- Delegated OAuth2 consent (sending Z a consent link). For OAuth2 configurations the admin signs in
  themselves — see "Credentials".
- Overriding a connected user's permission expression from the dialog.
- Any new infrastructure: no new module, table, column, broker route or executor.

## Anti-goals

- ConnectDialog or the marketplace breaking, or showing an integration twice, once a second enabled
  configuration exists.
- A pinned customer seeing workflows of a version they are not on.
- Provisioning onto a disabled configuration (instance would never run).

## Decisions

### Self-serve rule: highest version wins (no schema change)

Wherever self-serve needs "the configuration for integration X in environment E", it uses the **enabled
configuration with the highest `integrationVersion`**. Tie on version → highest `id` *(my call)*.
This replaces the single-enabled assumption in all three places listed in Background. Disabled
configurations are never offered. No Liquibase change.

Consequence the user accepted: enabling a configuration on a newer version immediately makes it what new
self-serve customers get.

### Credentials

- Non-OAuth2 authorization types: the admin types Z's credential fields.
- OAuth2 authorization types: the admin runs the same OAuth2 authorization flow a customer would, in a
  popup using the configuration's OAuth2 parameters (client id/secret from the configuration's
  `connectionParameters`), and signs in to Z's third-party account. The popup yields an authorization
  **`code`**, which is sent as a connection parameter exactly as ConnectDialog does
  (`{parameters: {code}}`); `ConnectionFacadeImpl.create` (~line 265) exchanges it for tokens
  server-side. No token fields are typed in. Reuse the admin client's `OAuth2Button`/`useOAuth2` popup
  with the authorization URL, scopes and **redirect URI** resolved by
  `OAuth2ParametersFacade.getOAuth2AuthorizationParameters` for the configuration — the same redirect
  URI the embedded flow uses, since that is what is registered with the third-party app.
- **Assumption (user's):** the admin has access to Z's third-party account ("is part of the account").

### Permission expressions are respected

The dialog only offers integrations and workflows visible to Z under Z's permission expression
(the same `embeddedPermissionEvaluator` logic as `isIntegrationVisible` / `filterWorkflows`), and the
server enforces the same check — a request that bypasses the UI is rejected. To provision something
hidden, the admin changes Z's permission expression first.

### Disabled means disabled for everyone

Unchanged behaviour. The dialog lists only enabled configurations.

### Multiple instances per customer

Z may have several instances of integration X, on different configurations or on the **same**
configuration (e.g., two Salesforce orgs). Provisioning always creates a new instance and never refuses
because one exists. This matches today's code (no duplicate guard).

### Customer-facing reads resolve configuration per instance

`getConnectedUserIntegration` / `getConnectedUserIntegrations` (and their callers) change so that:

- The integration appears **once** in the list, using the self-serve configuration for its top-level
  "connect" data (authorization, OAuth2 parameters, workflows offered to a new connection).
- Each of Z's instances carries **its own** configuration's workflows and authorization type, so toggling
  a workflow on a pinned instance only offers that version's workflows.

The exact DTO shape (per-instance configuration nested in each instance entry vs. a list of
configurations keyed by id) is left to the builder; the SDK (`sdks/frontend/embedded`) and public REST
spec must be updated in the same change so the customer UI renders per-instance workflows.

The MCP facades that read configurations for a connected user (`McpIntegrationInstance*FacadeImpl`,
`ConnectedUserMcpServerFacadeImpl`) must be audited for the same single-enabled assumption.

### Runtime paths

- **Request trigger — in scope** *(my call; otherwise every pinned customer's request-triggered
  workflow fails)*. `RequestTriggerApiController.executeIntegrationWorkflow` must resolve the workflow
  id per instance, not from the environment's last version: find the connected user's instances whose
  configuration contains the workflow uuid, then `IntegrationWorkflowService.getWorkflowId(instanceId,
  uuid)` (already per-instance via `findByIntegrationInstanceIdAndUuid`). The request carries only
  `externalUserId` and `workflowUuid`, so if several instances match (two on the same configuration),
  dispatch to the **enabled** one with the highest id *(my call; pre-existing ambiguity, now
  deterministic)*.
- **`fetchIntegrationInstance` by component name — known gap, out of scope** *(my call)*. Pre-existing
  with multiple self-serve instances; recorded as a follow-up, not fixed here.

### Provisioning operation (server)

- A new admin operation, tenant-admin only (`@PreAuthorize("isTenantAdmin()")`), on
  `IntegrationInstanceAdminFacade` — the facade that owns authorization for the admin REST surface.
- Input: `connectedUserId`, `integrationInstanceConfigurationId`, connection parameters, and a list of
  `{workflowUuid, inputs}` to enable.
- Validates: configuration is enabled; configuration's environment equals the connected user's
  environment; each workflow belongs to the configuration (an enabled `IntegrationInstanceConfigurationWorkflow`);
  the integration and each workflow are visible to the connected user under their permission expression.
- Reuses `createIntegrationInstance`'s connection-building logic (merge configuration parameters with
  admin-entered ones, authorization type from the configuration) — refactor it to take an explicit
  configuration rather than resolving the enabled one, so self-serve and admin share it.
- Request workflows are identified by `workflowUuid`; resolve each to the workflow id of the chosen
  configuration's version (`IntegrationWorkflowService.getWorkflowId(instanceId, uuid)` once the instance
  exists).
- Enables each selected workflow through the existing `IntegrationInstanceFacade.enableIntegrationInstanceWorkflow`
  path and stores inputs via the existing update-inputs path, so trigger handling (including the
  disabled-connected-user branch, which defers triggers) is identical to self-serve. Inputs are stored
  **before** the workflow is enabled.
- Failure handling *(my call)*: trigger enablement can register webhooks/listeners in the third-party
  system, which a database rollback does not undo. So: create connection, instance and instance-workflow
  rows (with inputs) first; then enable workflows one by one; if enabling workflow N fails, disable the
  workflows already enabled (1..N-1), delete the instance and connection, and return the error.
- Exposed on the admin REST spec `embedded-configuration-rest-impl/openapi.yaml` (the one the Connected
  Users page client already uses), e.g. `POST /integration-instances`. Returns the new instance id.
- A companion admin **read** operation feeds the dialog: for a connected user, the integrations (with
  their enabled configurations and each configuration's enabled workflows) that pass that user's
  permission expression, plus which configuration self-serve would pick. The admin surface has no such
  query today; evaluating permission expressions client-side is not an option.
- Failure cleanup reuses the existing delete path (`deleteIntegrationInstance` removes instance
  workflows and the instance); the builder confirms it also removes the connection, and adds that if not.

### Admin UI

- Entry point **B**: "+ Add integration" button in the Integrations list of the connected user sheet
  (`ConnectedUserSheetPanelIntegrationList.tsx`). Not a table row action.
- Dialog fields, in order: integration → configuration (shows name, version, and which one self-serve
  uses, i.e. the highest enabled) → connection fields → workflows (checkbox each) with inputs for checked
  ones → Provision.
- Environment is the connected user's environment, not a selector.
- Connection fields come from the configuration's component connection definition for the
  configuration's authorization type; fields already set in the configuration's `connectionParameters`
  (shared client id/secret) are hidden *(my call: hide, not show read-only)*. For OAuth2 types the
  section is a "Sign in" button that runs the popup flow, plus any non-shared fields.
- Integration and workflow lists are filtered by Z's permission expression.
- On success, the sheet's integration list refreshes; the new instance appears with its configuration
  name and version.

## Constraints

- EE code: ByteChef Enterprise license header, `@version ee`.
- Authorization lives on the facade; controllers call the admin facade, never the shared one.
- Tests must cover the case of **two enabled configurations** for one integration: self-serve picks the
  higher version, the marketplace lists the integration once, a pinned instance shows its own workflows,
  and a request trigger from a customer pinned to the **older** version dispatches to that customer's
  instance. A missed single-enabled caller compiles and only fails in that case.
- The Integration Instance Configurations admin page must not prevent a second configuration of the
  same integration from being enabled; the builder checks for a client-side guard and removes it if one
  exists (the facade has none).
- Existing data: at most one enabled configuration per integration today, so the highest-version rule
  picks the same one — no migration.

## Left to the builder

- DTO/OpenAPI shape for per-instance configuration in the connected-user responses, and the matching SDK
  change.
- Exact admin endpoint path and request model names.
- Client component structure for the dialog, reuse of existing connection-property and workflow-input
  form components.
- Whether the configuration picker hides integrations with no enabled configuration in the user's
  environment (expected: yes).
