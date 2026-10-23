# Embedded: create, import and edit connected users from the admin UI

## Intent

Today a connected user (an embedding product's end customer) only comes into existence on its first
authenticated SDK/API call (`EmbeddedApiKeyAuthenticationProvider`, and for MCP `EmbeddedMcpServerApiKeyAuthenticationProvider` /
`EmbeddedMcpServerOAuth2AuthenticationProvider`, all fetch-or-create via `ConnectedUserService.createConnectedUser`).
Admins can list, view, enable/disable and delete connected users, but not create or edit them. That blocks
setting a customer up ahead of time — onboarding a new customer, migrating existing customers in, or creating
a test user — and, with admin provisioning (PR #6546), provisioning integrations for a customer before that
customer has ever opened the product.

## Background

- `ConnectedUser`: `externalId`, `name`, `email`, `metadata` (key/value, stored in `connected_user_metadata`),
  `environment`, `enabled`. Admin REST: `server/ee/libs/embedded/embedded-connected-user/embedded-connected-user-rest/openapi.yaml`
  (`GET /connected-users`, `GET/DELETE /connected-users/{id}`, `PATCH /connected-users/{id}/enable/{enable}`);
  facade `ConnectedUserFacade` (`@PreAuthorize("isTenantAdmin()")`).
- `ConnectedUserService.createConnectedUser(externalId, environment)` creates an enabled user and refuses
  `ConnectedUserConstants.FRONTEND_RESERVED_PATH_SEGMENTS` (`app-events`, `automation`, `components`,
  `connections`, `external`, `integration-instances`, `integrations`, `me`, `unified`, `workflows`).
- `ConnectedUserService.updateConnectedUser(externalId, environment, metadata)` (used by the public SDK
  `PUT /{externalUserId}` and the frontend `updateFrontendConnectedUser`) **merges**: `name`/`email` keys set those fields, everything else is merged into
  metadata; keys can never be removed.
- Metadata is read by embedded permission expressions (`EmbeddedPermissionEvaluator`), so it decides which
  integrations/workflows a customer sees and an admin can provision.
- There is no database uniqueness constraint on `(external_id, environment)`; uniqueness is enforced in code
  (fetch, then create).

## Goals

1. **Create one.** The Connected Users page header gets a **New connected user** action opening a form:
   external ID (required), name, email, metadata (key/value rows). It creates an enabled connected user in the
   environment currently selected in the admin UI. After saving, the new user's sheet opens (so the admin can
   go straight to "Add integration").
2. **Import many.** The header also gets **Import**: upload a CSV with a header row containing `externalId`
   (required column), optional `name` and `email`, and any other column treated as a metadata key. Each row is
   created as in (1). Afterwards a summary shows: created (count), skipped as duplicates (external IDs), and
   rejected rows (row number and reason).
3. **Edit.** The connected user's sheet gets **Edit** for name, email and metadata. The external ID is shown
   read-only. Name and email can be cleared. Saving **replaces** metadata (a key removed in the form is removed
   from the user). The save carries the user's `version` *(my call)*: if the user changed since the dialog
   loaded (for example the SDK merged metadata), the save is refused with a conflict and the dialog asks the
   admin to reload — never silently dropping keys the product wrote.

## Rules

- Tenant admins only (admin facade, `isTenantAdmin()`), like the existing connected-user admin operations.
- External ID: required, not blank, max 256 characters (column size), not a reserved path segment, and
  **no leading/trailing whitespace** — rejected, not trimmed *(my call: the SDK paths do not trim, so a trimmed ID
  would not match the customer's later JWT `sub`)*. Compared exactly (case-sensitive), as
  `findByExternalIdAndEnvironment` does.
  In the form, an external ID that already exists in the environment is refused with an error naming it.
- Import: duplicates (external ID already in the environment, or repeated earlier in the same file) are
  **skipped and reported**, never overwritten. Invalid rows (missing/blank/too long/reserved external ID) are
  **rejected and reported**; valid rows are still created.
- Import limits *(my call)*: one request, no background job; at most 1,000 data rows and 1 MB; a file over the
  limit is refused as a whole with a clear message.
- CSV parsing *(my call)*: RFC 4180 (quoted fields, embedded commas/quotes/newlines), UTF-8 with optional BOM,
  header names matched case-insensitively for `externalId`/`name`/`email`; other headers are used as metadata
  keys verbatim. An empty cell means "no value" — no metadata key is created for it, and an empty
  `name`/`email` leaves the field unset.
- `name`, `email`, metadata keys and values are VARCHAR(256) and metadata values are NOT NULL: a value over
  256 characters rejects the row (form: field error); a metadata header over 256 characters refuses the whole
  file *(my call)*.
- Email is optional; if present it is validated loosely (contains `@`) in the form and in the import, an
  invalid email rejects the row *(my call)*.
- Nothing changes on the SDK path: a customer who later authenticates with the same external ID in the same
  environment lands on the admin-created user. SDK metadata updates still **merge** over what the admin set.

## Non-goals

- A public/backend API for creating or importing connected users (admin UI only).
- Changing an existing user's external ID.
- Updating existing users through the import (no upsert).
- A database uniqueness constraint on `(external_id, environment)` *(my call)*: the create-vs-first-SDK-call
  race already exists today between two first calls; the admin create uses the same code-level check. Recorded
  as a known gap.
- Exporting connected users.

## Anti-goals

- An import that silently overwrites customers' existing data.
- An admin-created user that the SDK later duplicates instead of reusing.
- A metadata edit that cannot remove a key (because permission expressions depend on exact metadata).

## Base branch

Built on `0_732`, which has admin provisioning (#6546); the "open the sheet, then Add integration" flow
depends on it *(my call)*. A master port stacks on the provisioning branch.

## Shape of the change (decided)

- **Server** (`embedded-connected-user` modules):
  - Service: a create method taking name/email/metadata (reuse the reserved-segment check), and an
    admin update method with replace semantics for metadata (the existing merge method stays for the SDK).
  - Facade (`ConnectedUserFacade`, `isTenantAdmin()`): create, import (takes parsed rows or the CSV content —
    builder's choice where parsing happens, but the server must validate every row; the client is not trusted),
    update.
  - Admin REST in `embedded-connected-user-rest/openapi.yaml`: `POST /connected-users` (create, returns the new
    user), `POST /connected-users/import` (multipart CSV or JSON rows — builder's choice; returns the summary),
    `PUT /connected-users/{id}` (name, email, metadata, `version`; 409 on a stale version). Regenerate and commit only touched generated files.
  - `RemoteConnectedUserServiceClient` (and any other `ConnectedUserService`/`ConnectedUserFacade` implementer)
    gets `UnsupportedOperationException` stubs for new methods. GraphQL (`ConnectedUserGraphQlController`) is out of
    scope.
  - Environment comes from the request the same way the existing list endpoint takes it (`environmentId`).
- **Client** (`client/src/ee/pages/embedded/connected-users/`): header actions, a create dialog, an import dialog
  with the result summary, an edit dialog opened from the sheet; invalidate the connected users list/sheet
  queries on success. REST failures are already toasted by the global fetch interceptor; the import summary is
  shown in the dialog, not as toasts.

## Left to the builder

- DTO/model names, the exact request shape of the import endpoint, and where CSV parsing lives (server-side
  parsing preferred so validation and limits live in one place).
- Client component structure; reuse of existing form/table components.
- Copy for buttons and messages, following existing admin UI wording.

## Tests that must exist

- Service/facade IntTests: create with metadata; duplicate refused; reserved/blank/too-long refused; update
  replaces metadata (removed key gone) and leaves external ID unchanged; stale version refused; import with created/duplicate (existing
  and in-file)/rejected rows produces the right summary and creates only valid rows; import limits; tenant-admin
  guard (connected user and non-admin denied).
- WebMvc IntTests for the three endpoints.
- Client tests for the three dialogs, including the import summary.
- An IntTest that an SDK-authenticated request for an admin-created external ID reuses the same connected user.
