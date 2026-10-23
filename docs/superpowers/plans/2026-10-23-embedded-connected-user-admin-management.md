# Embedded Connected User Admin Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tenant admins can create a connected user, bulk-import connected users from CSV, and edit a connected user's name, email and metadata from the Connected Users admin page.

**Architecture:** Validation and persistence live in `ConnectedUserService` (create with fields; replace-style admin update with an explicit version check). CSV parsing and the import summary live in a small parser class plus `ConnectedUserFacade` (tenant-admin guarded). Three admin REST endpoints expose them; the client adds header actions (New, Import), a create dialog, an import dialog with a summary, and an Edit dialog in the sheet.

**Tech Stack:** Spring Boot / Spring Data JDBC, openapi-generator (spring + typescript-fetch, committed output), Apache Commons CSV 1.14.1, React + TanStack Query + react-hook-form, vitest.

**Spec:** `docs/superpowers/specs/2026-10-23-embedded-connected-user-admin-management-design.md`

## Global Constraints

- Branch `0_732`, shared checkout: check `git branch --show-current` = `0_732` before each commit; stage by path; subject-only messages (server `<description>`, client `client - <description>`); never stash/amend/rebase/push.
- `server/ee/**`: ByteChef Enterprise license header + `@version ee` on new classes.
- Tenant admin only: new facade methods `@PreAuthorize("isTenantAdmin()")` on `ConnectedUserFacadeImpl`.
- External ID: required, not blank, ≤ 256 chars, no leading/trailing whitespace (rejected, not trimmed), not in `ConnectedUserConstants.FRONTEND_RESERVED_PATH_SEGMENTS`, compared exactly (case-sensitive).
- `name`, `email`, metadata keys and values ≤ 256 chars; metadata values non-null; email, when present, must contain `@`.
- Duplicate external ID in the environment: form → refused (`IllegalArgumentException`, message names the ID, → 400); import → row skipped and reported.
- Import: one request; ≤ 1,000 data rows and ≤ 1 MB, otherwise the whole file is refused (`IllegalArgumentException` → 400); RFC 4180, UTF-8 with optional BOM; headers `externalId`/`name`/`email` matched case-insensitively, any other header is a metadata key verbatim; empty cell = no value; a metadata header over 256 chars refuses the whole file.
- Admin edit replaces metadata; carries `version`; stale version → `org.springframework.dao.OptimisticLockingFailureException` (global handler maps `ConcurrencyFailureException` → 409). External ID is not editable. Name/email may be cleared.
- SDK paths unchanged; existing merge `updateConnectedUser(String, Environment, Map)` stays as is.
- Generated REST sources committed; after editing `embedded-connected-user-rest/openapi.yaml` run `:server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-rest:generateOpenAPI` and commit only files the change touches (the tree is mixed generator versions — revert unrelated churn). TS output: `client/src/ee/shared/middleware/embedded/connected-user`.
- Gradle: module-qualified task paths; Bash tool `timeout` 600000, foreground; redirect to a file, `echo $?`, grep `^> Task .* FAILED`.
- Client: REST errors are toasted globally — no per-mutation error toasts; follow `client/CLAUDE.md`.

## Review Focus

1. A CSV whose header has no `externalId` column → whole file refused with a message naming the missing column, nothing created. → Task 2 test `testImportRefusesAFileWithoutAnExternalIdColumn`.
2. The same external ID twice in one file → first created, second reported as skipped (not rejected, not created twice). → Task 2 test `testImportSkipsAnExternalIdRepeatedInTheSameFile`.
3. A row with fewer or more cells than the header (ragged CSV) → that row rejected with its row number, other rows still created. → Task 2 test `testImportRejectsARaggedRowAndKeepsTheOthers`.
4. Editing a user to empty name/email → fields become null, not empty strings. → Task 1 test `testUpdateClearsNameAndEmail`.
5. External ID with surrounding whitespace in the form → refused with a message, not silently trimmed. → Task 1 test `testCreateRefusesAnExternalIdWithSurroundingWhitespace`.

---

### Task 1: Service — create with fields, admin update with replace + version

**Files:**
- Modify: `server/ee/libs/embedded/embedded-connected-user/embedded-connected-user-api/src/main/java/com/bytechef/ee/embedded/connected/user/service/ConnectedUserService.java`
- Modify: `server/ee/libs/embedded/embedded-connected-user/embedded-connected-user-service/src/main/java/com/bytechef/ee/embedded/connected/user/service/ConnectedUserServiceImpl.java`
- Modify: `server/ee/libs/embedded/embedded-connected-user/embedded-connected-user-remote-client/src/main/java/com/bytechef/ee/embedded/connected/user/remote/client/service/RemoteConnectedUserServiceClient.java` (stubs)
- Test: `.../embedded-connected-user-service/src/test/java/com/bytechef/ee/embedded/connected/user/service/ConnectedUserServiceTest.java` (existing mocked-repository style)

**Interfaces:**
- Produces:
  - `ConnectedUser createConnectedUser(String externalId, Environment environment, @Nullable String name, @Nullable String email, Map<String, String> metadata)` — validates per Global Constraints (reuse/extend the reserved-segment check of the existing create), refuses an existing `(externalId, environment)` via `connectedUserRepository` lookup, saves enabled.
  - `ConnectedUser updateConnectedUser(long id, @Nullable String name, @Nullable String email, Map<String, String> metadata, int version)` — loads by id; if `connectedUser.getVersion() != version` throw `OptimisticLockingFailureException("Connected user " + id + " was modified")`; blank name/email → null; validates lengths/email; **replaces** metadata; saves.
  - `void validateExternalId(String externalId)` as a `public static` helper on `ConnectedUserServiceImpl` (used by Task 2) throwing `IllegalArgumentException` with a message naming the problem.
  - Remote client stubs throw `UnsupportedOperationException`.

- [ ] **Step 1: Write failing tests** in `ConnectedUserServiceTest` (look at how `ConnectedUser` metadata is set/read — `getMetadata()` / `setMetadata(Map)` — and use those):
  - `testCreateStoresNameEmailAndMetadata` — returns saved user with name `"Acme"`, email `"ops@acme.io"`, metadata `{plan: "pro"}`, enabled, environment PRODUCTION.
  - `testCreateRefusesAnExistingExternalId` — repository finds `("customer-z", PRODUCTION)` → `IllegalArgumentException` containing `customer-z`; no save.
  - `testCreateRefusesAnExternalIdWithSurroundingWhitespace` — `" customer-z"` → `IllegalArgumentException`; no save.
  - `testCreateRefusesABlankOrTooLongExternalId` (parameterized: `""`, `"   "`, 257×`a`).
  - `testCreateRefusesAnInvalidEmail` — `"not-an-email"`.
  - `testUpdateReplacesMetadata` — existing `{plan: pro, region: eu}`, update with `{plan: enterprise}` → saved metadata exactly `{plan: enterprise}`; externalId unchanged.
  - `testUpdateClearsNameAndEmail` — `""` and `null` → both null.
  - `testUpdateRefusesAStaleVersion` — loaded version 3, request version 2 → `OptimisticLockingFailureException`; no save.
- [ ] **Step 2: Run, expect failures** — `./gradlew :server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-service:test --tests '*ConnectedUserServiceTest' > /tmp/cu1.log 2>&1; echo $?` → non-zero (compile errors).
- [ ] **Step 3: Implement** the interface methods, impl and stubs.
- [ ] **Step 4: Run, expect pass**, then `:server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-service:spotlessApply check` → exit 0, no FAILED.
- [ ] **Step 5: Commit** `Add admin create and replace-style update with version check to the connected user service`.

---

### Task 2: CSV import parser + facade operations

**Files:**
- Modify: `.../embedded-connected-user-service/build.gradle.kts` — add `implementation("org.apache.commons:commons-csv:1.14.1")`.
- Create: `.../embedded-connected-user-service/src/main/java/com/bytechef/ee/embedded/connected/user/facade/ConnectedUserCsvParser.java` (package-private `final class`, pure parsing, no Spring).
- Create: `.../embedded-connected-user-api/src/main/java/com/bytechef/ee/embedded/connected/user/dto/ConnectedUserImportResultDTO.java` — `record ConnectedUserImportResultDTO(int createdCount, List<String> skippedExternalIds, List<RejectedRow> rejectedRows)` with nested `record RejectedRow(int rowNumber, String reason)`.
- Modify: `ConnectedUserFacade.java` / `ConnectedUserFacadeImpl.java`.
- Test (create): `.../src/test/java/com/bytechef/ee/embedded/connected/user/facade/ConnectedUserCsvParserTest.java`; Test (modify): `ConnectedUserFacadeTest.java`, `ConnectedUserFacadeAuthorizationTest.java`.

**Interfaces:**
- Consumes: Task 1 service methods and `ConnectedUserServiceImpl.validateExternalId`.
- Produces (facade, all `@PreAuthorize("isTenantAdmin()")`):
  - `ConnectedUserDTO createConnectedUser(String externalId, Environment environment, @Nullable String name, @Nullable String email, Map<String, String> metadata)` (return the same DTO shape `getConnectedUserDTO(long)` returns).
  - `ConnectedUserImportResultDTO importConnectedUsers(Environment environment, InputStream csvInputStream)`.
  - `ConnectedUserDTO updateConnectedUser(long id, @Nullable String name, @Nullable String email, Map<String, String> metadata, int version)`.
- Parser: `static ParsedConnectedUsers parse(InputStream inputStream)` where `record ParsedConnectedUsers(List<ParsedRow> rows)` and `record ParsedRow(int rowNumber, @Nullable String externalId, @Nullable String name, @Nullable String email, Map<String, String> metadata, @Nullable String rejectionReason)`; throws `IllegalArgumentException` for whole-file failures (over 1 MB — read at most 1 MB + 1 byte; over 1,000 data rows; missing `externalId` header; metadata header > 256 chars; unparseable CSV). Row numbers are 1-based data-row numbers (header excluded). A row whose cell count differs from the header gets a `rejectionReason`.

- [ ] **Step 1: Failing parser tests** (`ConnectedUserCsvParserTest`): parses quoted commas/newlines and a BOM; header case-insensitive (`ExternalID,Name,EMAIL,plan`); empty cell → absent metadata key and null name; `testImportRefusesAFileWithoutAnExternalIdColumn`; over-limit rows (1,001) and size (1 MB + 1) refused; `testImportRejectsARaggedRowAndKeepsTheOthers` (row 2 rejected, rows 1 and 3 parsed).
- [ ] **Step 2: Failing facade tests** (`ConnectedUserFacadeTest`, mocked service like the existing tests): import of `externalId,name,plan` with rows `a,A,pro` / `b,,` / `a,Again,x` / `" bad",X,` / `existing,E,` (service reports `existing` present) → `createdCount 2` (`a`, `b`), `skippedExternalIds` exactly `["a", "existing"]` in row order (the second `a` and the pre-existing one), `rejectedRows` exactly one `(4, <whitespace message>)`; `testImportSkipsAnExternalIdRepeatedInTheSameFile`; a row whose service create throws `IllegalArgumentException` (e.g. invalid email) becomes a rejected row with that message. Authorization test: the three new methods deny a connected-user principal and a non-admin (follow the existing pattern in `ConnectedUserFacadeAuthorizationTest`).
- [ ] **Step 3: Run, expect failures** (module `test`).
- [ ] **Step 4: Implement** parser and facade. Import: parse, then for each row in order — rejected → add; external ID seen earlier in this file or already present (`connectedUserService.fetchConnectedUser(externalId, environment)`) → skip; else create, catching `IllegalArgumentException` → rejected with its message. Not transactional as a whole (each create commits; a failing row never undoes earlier rows).
- [ ] **Step 5: Run, expect pass**; `spotlessApply check` on the api and service modules → exit 0.
- [ ] **Step 6: Commit** `Add connected user create, CSV import and edit to the connected user facade`.

---

### Task 3: Admin REST endpoints

**Files:**
- Modify: `server/ee/libs/embedded/embedded-connected-user/embedded-connected-user-rest/openapi.yaml`, controller `ConnectedUserApiController.java`, `ConnectedUserMapper.java` (if needed), generated Java under `embedded-connected-user-rest/generated`, generated TS under `client/src/ee/shared/middleware/embedded/connected-user`.
- Test (create): `.../embedded-connected-user-rest/src/test/java/com/bytechef/ee/embedded/connected/user/web/rest/ConnectedUserApiControllerIntTest.java` (+ a minimal test configuration class if the module has none — model it on `EmbeddedConfigurationRestTestConfiguration` in `embedded-configuration-rest-impl` tests).

**Interfaces:**
- Consumes: Task 2 facade methods.
- Produces (spec), tag `connected-user`:
  - `POST /connected-users` — operationId `createConnectedUser`; body `CreateConnectedUserRequest {externalId: string (required), name: string, email: string, metadata: object<string,string>, environmentId: int64 (required)}`; 200 → `ConnectedUser`.
  - `POST /connected-users/import` — operationId `importConnectedUsers`; query `environmentId` (int64, required); `multipart/form-data` with `file` (binary, required); 200 → `ConnectedUserImportResult {createdCount: int32, skippedExternalIds: [string], rejectedRows: [{rowNumber: int32, reason: string}]}`.
  - `PUT /connected-users/{id}` — operationId `updateConnectedUser`; body `UpdateConnectedUserRequest {name: string, email: string, metadata: object<string,string>, version: int32 (required)}`; 200 → `ConnectedUser`.
  - Environment from `environmentId` via `Environment.values()[(int) environmentId]` or the existing helper the list endpoint uses — match it.

- [ ] **Step 1: Failing WebMvc IntTest** (`@WebMvcTest(ConnectedUserApiController.class)`, mocked `ConnectedUserFacade`): create posts JSON and passes the fields through; import posts a multipart CSV and returns the summary JSON; update passes `version`; facade throwing `OptimisticLockingFailureException` → 409; facade throwing `IllegalArgumentException` → 400.
- [ ] **Step 2: Run, expect failure** (404/compile).
- [ ] **Step 3: Spec + regenerate + controller.**
- [ ] **Step 4: Run, expect pass**; `spotlessApply check` on the rest module → exit 0.
- [ ] **Step 5: Commit** (server + generated Java + generated TS) `Add admin REST endpoints to create, import and edit connected users`.

---

### Task 4: Client — New connected user and Import

**Files:**
- Modify: `client/src/ee/shared/mutations/embedded/connectedUsers.mutations.ts` — `useCreateConnectedUserMutation`, `useImportConnectedUsersMutation`.
- Create: `client/src/ee/pages/embedded/connected-users/components/ConnectedUserCreateDialog.tsx`, `ConnectedUserImportDialog.tsx`, `ConnectedUserMetadataFields.tsx` (shared key/value rows editor, also used by Task 5), tests under `components/tests/`.
- Modify: `client/src/ee/pages/embedded/connected-users/ConnectedUsers.tsx` — `right` side of the main `Header`: `New connected user` and `Import` buttons.

**Interfaces:**
- Consumes: generated `ConnectedUserApi.createConnectedUser({createConnectedUserRequest})`, `importConnectedUsers({environmentId, file})`; `useEnvironmentStore` `currentEnvironmentId`; `useConnectedUserSheetStore` `setConnectedUserId` / `setConnectedUserSheetOpen`; `ConnectedUserKeys`.
- Produces: `ConnectedUserMetadataFields({control, name})` (react-hook-form field array of `{key, value}`; converts to `Record<string,string>` on submit, dropping empty keys).

Behaviour: create dialog fields External ID (required), Name, Email, Metadata rows; on success invalidate the connected users list, close, and open the new user's sheet. Import dialog: file input accepting `.csv`, a one-line format hint (`Columns: externalId (required), name, email, plus any metadata columns`), Import button; on success show the summary in the dialog — `Created N`, the skipped external IDs, and the rejected rows as `Row R: reason` — and invalidate the list; Close button.

- [ ] **Step 1: Failing tests**: create submits `{externalId, name, email, metadata: {plan: 'pro'}, environmentId}` and opens the sheet with the returned id; empty key rows dropped; import uploads the file with `environmentId` and renders `Created 2`, `existing`, `Row 4: …`.
- [ ] **Step 2: Run, expect failure** — from `client/`: `npx vitest run src/ee/pages/embedded/connected-users`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect pass**, 0 `Unhandled`; prettier, eslint (`--max-warnings=0`), `npm run typecheck` clean.
- [ ] **Step 5: Commit** `client - Add creating and importing connected users to the connected users page`.

---

### Task 5: Client — Edit in the connected user sheet

**Files:**
- Modify: `connectedUsers.mutations.ts` — `useUpdateConnectedUserMutation`.
- Create: `client/src/ee/pages/embedded/connected-users/components/connected-user-sheet/ConnectedUserEditDialog.tsx` + test.
- Modify: the sheet's profile panel or actions (`ConnectedUserSheetPanelProfile.tsx` / `ConnectedUserSheetActions.tsx`) to add an `Edit` action.

**Interfaces:**
- Consumes: generated `ConnectedUserApi.updateConnectedUser({id, updateConnectedUserRequest})`; Task 4 `ConnectedUserMetadataFields`; the sheet's `ConnectedUser` (with `__version`).

Behaviour: pre-filled name, email, metadata; external ID shown read-only; submit sends `version: connectedUser.__version`; on success invalidate `ConnectedUserKeys.connectedUser(id)` and the list, close. On a 409 response show in the dialog `This connected user changed since you opened it. Reload to see the latest values.` with a Reload button that refetches and resets the form (detect 409 from the thrown `ResponseError`'s `response.status`; this is custom behaviour, so an `onError` is justified).

- [ ] **Step 1: Failing tests**: submit sends replaced metadata (a removed row is absent) and the version; clearing name sends empty → server clears; 409 shows the message and Reload resets the form from refetched data.
- [ ] **Step 2–4:** run (fail), implement, run (pass) + prettier/eslint/typecheck.
- [ ] **Step 5: Commit** `client - Edit a connected user's name, email and metadata from the sheet`.

---

### Task 6: SDK reuses an admin-created user

**Files:**
- Test: the existing unit test for `EmbeddedApiKeyAuthenticationProvider` in `server/ee/libs/embedded/embedded-security-web/embedded-security-web-impl/src/test/...` (create `EmbeddedApiKeyAuthenticationProviderTest` if none).

- [ ] **Step 1:** test `testAuthenticationReusesAnExistingConnectedUser` — service `fetchConnectedUser("customer-z", environmentId)` returns a user → `createConnectedUser` never called and the authenticated principal carries that user. (Behaviour already exists — this pins it; prove it is meaningful by temporarily making the provider always create and seeing it fail, then revert.)
- [ ] **Step 2:** module `check` → exit 0.
- [ ] **Step 3: Commit** `Pin that an embedded API request reuses an existing connected user`.

---

### Task 7: Whole-feature verification

- [ ] `./gradlew --continue` `check` on `embedded-connected-user-api`, `-service`, `-rest`, `-remote-client`, `embedded-security-web-impl` + `compileJava compileTestJava` → exit 0, no FAILED.
- [ ] Client: `npm run lint`, `npm run typecheck`, `npx vitest run` (0 Unhandled).
