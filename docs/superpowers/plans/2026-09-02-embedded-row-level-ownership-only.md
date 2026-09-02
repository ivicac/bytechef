# Embedded Row-Level Ownership Only — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove axis 1 (the resource owner) from embedded data tables and knowledge bases, leaving row-level ownership as the only isolation between accounts.

**Architecture:** Removal proceeds strictly top-down — the client stops using the owner fields, then the embedded GraphQL surface drops them, then the platform services drop them, then the schema drops the columns. Each task therefore compiles and passes its tests on its own; no task leaves a caller referring to something already deleted. Axis 2 (`owner_id`/`owner_type` on physical rows and on knowledge base chunks) is not touched by any task, and the seven tests listed in Task 7 are the regression gate proving it.

**Tech Stack:** Java 25 / Spring Boot 4, Spring Data JDBC, Liquibase, Spring GraphQL, React 19 + TypeScript 6, GraphQL Code Generator, Vitest, JUnit 5, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-02-embedded-row-level-ownership-only-design.md`

## Global Constraints

- **Never judge a Gradle run piped into `tail`/`grep`.** Redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`. Use `--continue`.
- **Client checks need an explicit tool timeout of 600000 ms.** `npm run check` is auto-backgrounded at the 120 s default.
- **Node must be 22.19–24, never 26.** This machine's default (`v24.15.0`) is correct; do not switch.
- **Java formatting:** run `./gradlew spotlessApply` before every server commit. Client: `npm run format`.
- **EE files** (anything under `server/ee/`) keep the ByteChef Enterprise licence header and the `@version ee` Javadoc tag.
- **Commit messages:** server `<ticket> <description>`, client `<ticket> client - <description>`. This work has no ticket number, so use the bare description form matching recent branch commits, and end every commit with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **Never `git stash`** in this repo — the stash stack is shared across worktrees.
- **Javadoc is part of each task, not a later sweep.** Where a task deletes a field or parameter, the surrounding comment that argues for per-account tables is rewritten in the same commit. A comment arguing for a design the code no longer has is worse than no comment.
- **Axis 2 is untouchable.** `Owner`, `OwnerType`, `OwnerResolver`, `ConnectedUserOwnerResolver`, both `OwnerResolverGuard`s, `ReservedColumns.OWNER_ID`/`OWNER_TYPE`, `DataTableOwnerColumnMigrator`/`Change`, `RowQuerySqlBuilder`, `DataTableWebhook.ownerId`, `KnowledgeBaseDocument.ownerId`, the chunk `owner_id`/`shared` metadata, `KnowledgeBaseVectorStoreWrapper` and `KnowledgeBaseVectorStoreOwnershipBackfill` are out of scope for every task.

## File Structure

**Deleted outright**

| Path | Why |
|---|---|
| `client/src/ee/pages/embedded/shared/components/OwnerSelect.tsx` | Both its jobs (page filter, per-row assigner) are gone; no other importer |
| `client/src/ee/pages/embedded/shared/components/useEmbeddedConnectedUsers.ts` | Only fed `OwnerSelect` and the create dialogs |
| `server/libs/.../knowledgebase/event/KnowledgeBaseOwnerAssignedEvent.java` | Published only by `assignOwner` |
| `server/libs/.../knowledgebase/event/KnowledgeBaseOwnerAssignedListener.java` | Consumes only that event |
| `server/libs/.../data_table/20260827000001_platform_data_table_add_owner.xml` | Axis 1 registry columns |
| `server/libs/.../data_table/20260831000002_platform_data_table_owner_unique_index.xml` | Axis 1 registry key |
| `server/libs/.../knowledge_base/20260827000001_platform_knowledge_base_add_owner.xml` | Axis 1 registry columns |
| `server/libs/.../knowledge_base/20260831000001_platform_knowledge_base_owner_unique_index.xml` | Axis 1 registry key — **except changeset `-4`, extracted first** |

**Created**

| Path | Responsibility |
|---|---|
| `server/libs/.../knowledge_base/20260902000001_platform_knowledge_base_drop_name_environment_unique.xml` | Carries the extracted `-4` changeset so `uk_knowledge_base_name_environment` is still dropped |
| `scripts/dev/cleanup-resource-owner.sql` | Repairs development databases that already ran the deleted changesets |

**Modified — the load-bearing ones**

| Path | Change |
|---|---|
| `server/libs/platform/platform-data-table/platform-data-table-api/.../domain/DataTableRef.java` | Four components to three; invariant and `shared()` factory go |
| `server/libs/platform/platform-data-table/platform-data-table-api/.../internal/PhysicalTableNaming.java` | Owner segment and both owner-token methods go |
| `server/libs/platform/platform-data-table/platform-data-table-api/.../service/DataTableService.java` | Nine methods lose `Optional<Owner>`; `assignOwner` deleted |
| `server/libs/platform/platform-data-table/platform-data-table-service/.../service/DataTableServiceImpl.java` | Resolution, `listTables` parse, `physicalRef`, `register` |
| `server/libs/platform/platform-knowledge-base/platform-knowledge-base-api/.../service/KnowledgeBaseService.java` | Four methods lose `Optional<Owner>`; `assignOwner` deleted |
| `server/ee/libs/embedded/embedded-data-table-graphql/**` | Schema, facade, controller |
| `server/ee/libs/embedded/embedded-knowledge-base-graphql/**` | Schema, facade, controller |
| `client/src/graphql/embedded/configuration/*.graphql` + `client/src/shared/middleware/graphql*.ts` | Operations, then regenerated types |
| `client/src/ee/pages/embedded/{data-tables,knowledge-bases}/**` | Owner filter, assigner, props |
| `client/src/shared/components/{data-tables,knowledge-bases}/**` | Scope types, hooks, create dialogs |

---

### Task 1: Client console stops using the owner

The client stops sending and reading `ownerId` while the GraphQL schema still offers it. That ordering is what makes the whole removal safe: Task 2 can then delete the fields without breaking a caller.

**Files:**
- Modify: `client/src/ee/pages/embedded/data-tables/EmbeddedDataTables.tsx`
- Modify: `client/src/ee/pages/embedded/knowledge-bases/EmbeddedKnowledgeBases.tsx`
- Modify: `client/src/ee/pages/embedded/data-tables/components/EmbeddedDataTableList.tsx`
- Modify: `client/src/ee/pages/embedded/knowledge-bases/components/EmbeddedKnowledgeBaseList.tsx`
- Modify: `client/src/shared/components/data-tables/types.ts`
- Modify: `client/src/shared/components/knowledge-bases/types.ts`
- Modify: `client/src/shared/components/data-tables/components/hooks/useDataTables.ts`
- Modify: `client/src/shared/components/knowledge-bases/components/hooks/useKnowledgeBases.ts`
- Modify: `client/src/shared/components/data-tables/components/CreateDataTableDialog.tsx`
- Modify: `client/src/shared/components/data-tables/components/hooks/useCreateDataTableDialog.ts`
- Modify: `client/src/shared/components/knowledge-bases/components/CreateKnowledgeBaseDialog.tsx`
- Modify: `client/src/shared/components/knowledge-bases/components/hooks/useCreateKnowledgeBaseDialog.ts`
- Delete: `client/src/ee/pages/embedded/shared/components/OwnerSelect.tsx`
- Delete: `client/src/ee/pages/embedded/shared/components/useEmbeddedConnectedUsers.ts`
- Test: `client/src/ee/pages/embedded/data-tables/tests/EmbeddedDataTables.test.tsx`, `client/src/shared/components/data-tables/components/hooks/tests/useDataTables.test.tsx`, `client/src/shared/components/data-tables/components/hooks/tests/useCreateDataTableDialog.test.tsx`, `client/src/shared/components/data-tables/components/tests/CreateDataTableDialog.test.tsx`, `client/src/shared/components/knowledge-bases/components/hooks/tests/useKnowledgeBases.test.ts`, `client/src/shared/components/knowledge-bases/components/hooks/tests/useCreateKnowledgeBaseDialog.test.ts`, `client/src/shared/components/knowledge-bases/components/tests/CreateKnowledgeBaseDialog.test.tsx`, `client/src/pages/automation/knowledge-bases/tests/KnowledgeBases.test.tsx`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `DataTableScopeType = {type: 'WORKSPACE'; workspaceId: number} | {type: 'EMBEDDED'}` and the matching `KnowledgeBaseScopeType`. Task 2 relies on no client code referencing `ownerId` on the embedded queries after this task.

- [ ] **Step 1: Narrow the scope types**

In `client/src/shared/components/data-tables/types.ts`, replace the `EMBEDDED` arm and rewrite the comment above it (it currently explains that an absent `ownerId` means every owner):

```ts
/**
 * Which surface a data table list is being read for. The embedded arm carries nothing beyond its tag: every table in
 * an environment is visible to every account, and the accounts are separated inside a table by the row owner rather
 * than by which tables they can see.
 */
export type DataTableScopeType = {type: 'WORKSPACE'; workspaceId: number} | {type: 'EMBEDDED'};
```

Apply the same edit to `client/src/shared/components/knowledge-bases/types.ts`.

- [ ] **Step 2: Drop the ownerId plumbing from both list hooks**

In `client/src/shared/components/data-tables/components/hooks/useDataTables.ts`, delete the `ownerId` derivation and the argument:

```ts
    const isWorkspaceScope = scope.type === 'WORKSPACE';
    const workspaceId = scope.type === 'WORKSPACE' ? scope.workspaceId : undefined;
```

```ts
    } = useEmbeddedDataTablesQuery({environmentId: String(environmentId)}, {enabled: !isWorkspaceScope});
```

Make the equivalent edit in `client/src/shared/components/knowledge-bases/components/hooks/useKnowledgeBases.ts`.

- [ ] **Step 3: Strip the owner controls from both pages**

In `client/src/ee/pages/embedded/data-tables/EmbeddedDataTables.tsx` remove the `ownerId` state, the `OwnerSelect` element, the `useAssignEmbeddedDataTableOwnerMutation` call, `handleAssign`, `useEmbeddedConnectedUsers`, the now-unused `useQueryClient`, and the `connectedUsers` / `onAssign` props passed down. `scope` becomes `{type: 'EMBEDDED'}`. Rewrite the empty-list message, which currently promises assignment:

```tsx
                        message="Data tables you create appear here. Every account in this environment sees them, and each account's rows stay its own."
```

Apply the same treatment to `client/src/ee/pages/embedded/knowledge-bases/EmbeddedKnowledgeBases.tsx`, whose empty-list copy gets the knowledge-base wording.

- [ ] **Step 4: Strip the assigner column from both list components**

In `client/src/ee/pages/embedded/data-tables/components/EmbeddedDataTableList.tsx` and `client/src/ee/pages/embedded/knowledge-bases/components/EmbeddedKnowledgeBaseList.tsx`, remove the `OwnerSelect` import, the `connectedUsers` and `onAssign` props from the `Props` interface and destructuring, the `ownerId` field from the row type, and the `OwnerSelect` element from the row. Keep the row rhythm intact — first row `min-h-8`, `gap-y-2` on the right column, second row `min-h-7` (see the "List row rhythm" note in CLAUDE.md); removing a control must not change those classes.

- [ ] **Step 5: Strip the owner picker from both create dialogs**

In `client/src/shared/components/data-tables/components/hooks/useCreateDataTableDialog.ts` remove the `ownerId` state, `handleOwnerIdChange`, both from the returned object and its interface, and the `ownerId` key from the mutation input. Delete the comment block explaining that the owner is dialog state rather than part of the scope. In `CreateDataTableDialog.tsx` remove the `connectedUsers` prop and the `OwnerSelect` field. Repeat for the knowledge base dialog and hook.

- [ ] **Step 6: Delete the two now-unused files**

```bash
git rm client/src/ee/pages/embedded/shared/components/OwnerSelect.tsx client/src/ee/pages/embedded/shared/components/useEmbeddedConnectedUsers.ts
```

- [ ] **Step 7: Update the client tests**

Remove every owner assertion, `ownerId` fixture key and `OwnerSelect` interaction from the eight test files listed above. Where a test existed only to assert owner filtering or assignment, delete the test rather than weakening it. Object keys in fixtures must stay in ascending alphabetical order — ESLint `sort-keys` does not auto-fix.

Add one test to `client/src/ee/pages/embedded/data-tables/tests/EmbeddedDataTables.test.tsx` proving the control is gone rather than merely unused:

```tsx
    it('offers no owner control, because every account sees every table', async () => {
        render(<EmbeddedDataTables />, {wrapper: createWrapper()});

        await waitFor(() => {
            expect(screen.getByText('Data Tables')).toBeInTheDocument();
        });

        expect(screen.queryByLabelText('Owner')).not.toBeInTheDocument();
    });
```

- [ ] **Step 8: Run the client checks**

```bash
cd client && npm run format && npm run check
```

Expected: PASS. Set the tool timeout to 600000 ms. If `npm run check` reports missing modules, run `npm install` first — stale `node_modules` after a rebase looks exactly like a broken change.

- [ ] **Step 9: Commit**

```bash
git add -A client && git commit -F - <<'MSG'
client - Stop the embedded consoles filtering and assigning by owner

Every data table and knowledge base in an embedded environment is visible to
every account; the accounts are separated inside a resource by the row owner.
The page-level owner filter, the per-row assigner and the owner picker in both
create dialogs described a per-account resource that is going away, so they go
with it, and OwnerSelect and useEmbeddedConnectedUsers have no other importer.

The GraphQL surface still offers ownerId at this point. Removing the client's
use of it first is what lets the schema drop it without breaking a caller.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 2: Embedded GraphQL surface drops the owner

**Files:**
- Modify: `server/ee/libs/embedded/embedded-data-table-graphql/src/main/resources/graphql/embedded-data-table.graphqls`
- Modify: `server/ee/libs/embedded/embedded-knowledge-base-graphql/src/main/resources/graphql/embedded-knowledge-base.graphqls`
- Modify: `server/ee/libs/embedded/embedded-data-table-graphql/src/main/java/com/bytechef/ee/embedded/data/table/facade/EmbeddedDataTableApiFacade.java` and `…/EmbeddedDataTableApiFacadeImpl.java`
- Modify: `server/ee/libs/embedded/embedded-data-table-graphql/src/main/java/com/bytechef/ee/embedded/data/table/web/graphql/EmbeddedDataTableGraphQlController.java`
- Modify: the three matching knowledge base files under `server/ee/libs/embedded/embedded-knowledge-base-graphql/`
- Modify: `client/src/graphql/embedded/configuration/embeddedDataTables.graphql`, `client/src/graphql/embedded/configuration/embeddedKnowledgeBases.graphql`
- Modify (generated): `client/src/shared/middleware/graphql.ts`, `client/src/shared/middleware/graphql-types.ts`
- Test: `server/ee/libs/embedded/embedded-data-table-graphql/src/test/java/com/bytechef/ee/embedded/data/table/facade/EmbeddedDataTableApiFacadeCreateTest.java`, `…/EmbeddedDataTableApiFacadeTest.java`, and the three knowledge base facade tests
- Delete: `server/ee/libs/embedded/embedded-knowledge-base-graphql/src/test/java/com/bytechef/ee/embedded/knowledgebase/facade/EmbeddedKnowledgeBaseCreateOwnerResolutionTest.java`

**Interfaces:**
- Consumes: Task 1's guarantee that no client code reads `ownerId` off `embeddedDataTables` / `embeddedKnowledgeBases`.
- Produces: `EmbeddedDataTableApiFacade.getDataTables(int environment)`, `createDataTable(int environment, String name, String description, List<ColumnSpec> columnSpecs)`; `EmbeddedKnowledgeBaseApiFacade.getKnowledgeBases(int environment)`, `createKnowledgeBase(long environmentId, String name, String description, ChunkingSettings chunkingSettings)`. `assignDataTableOwner` and `assignKnowledgeBaseOwner` no longer exist. Tasks 3 and 4 rely on no EE facade calling `assignOwner` on either platform service.

- [ ] **Step 1: Edit the two GraphQL schemas**

`embedded-data-table.graphqls` becomes:

```graphql
extend type Query {
    embeddedDataTables(environmentId: ID!): [EmbeddedDataTable!]!
}

extend type Mutation {
    createEmbeddedDataTable(input: CreateEmbeddedDataTableInput!): Boolean!
}

input CreateEmbeddedDataTableInput {
    environmentId: ID!
    name: String!
    description: String
    columns: [EmbeddedDataTableColumnInput!]!
}

input EmbeddedDataTableColumnInput {
    name: String!
    type: ColumnType!
}

type EmbeddedDataTable {
    id: ID!
    baseName: String!
    description: String
    columns: [EmbeddedDataTableColumn!]!
    lastModifiedDate: Long
}

type EmbeddedDataTableColumn {
    id: ID!
    name: String!
    type: ColumnType!
}
```

In `embedded-knowledge-base.graphqls` delete the `ownerId` argument on the query, the `assignEmbeddedKnowledgeBaseOwner` mutation, `AssignKnowledgeBaseOwnerInput`, the `ownerId` field on `CreateEmbeddedKnowledgeBaseInput`, the `ownerId` field on `EmbeddedKnowledgeBase`, and the description on `UpdateEmbeddedKnowledgeBaseInput` that explains why the owner is absent from it — it now points at a mutation that does not exist. Keep the `updateEmbeddedKnowledgeBase` and `rechunkEmbeddedKnowledgeBase` descriptions untouched.

- [ ] **Step 2: Edit the facades and controllers**

In `EmbeddedDataTableApiFacade`, drop `assignDataTableOwner` and the `ownerId` parameters, and rewrite the Javadoc on `createDataTable` — it currently explains that naming an account produces a per-account override. In `EmbeddedDataTableApiFacadeImpl`, delete the `assignDataTableOwner` implementation and the `Owner` import, and pass `Optional.empty()` where the platform service still demands an owner (Task 4 removes the parameter). In `EmbeddedDataTableGraphQlController`, delete the `assignEmbeddedDataTableOwner` `@MutationMapping`, the `AssignDataTableOwnerInput` record, the `ownerId` component of `CreateEmbeddedDataTableInput` and of `EmbeddedDataTable`, the `ownerId` argument on the query method, and the trailing `dataTableInfo.ownerId()` argument in `toEmbeddedDataTable`. Its Javadoc says the shape is "the same as the automation `DataTable`, plus the owner" — with the owner gone it is simply the same shape, so say that.

Repeat for the three knowledge base files, deleting `assignKnowledgeBaseOwner` and its `@MutationMapping` and input record.

- [ ] **Step 3: Compile the two EE modules**

```bash
./gradlew :server:ee:libs:embedded:embedded-data-table-graphql:compileJava :server:ee:libs:embedded:embedded-knowledge-base-graphql:compileJava --continue > /tmp/build-t2.log 2>&1
echo "exit=$?"
grep -n '^> Task .* FAILED' /tmp/build-t2.log || echo "no failed tasks"
```

Expected: `exit=0`, no failed tasks.

- [ ] **Step 4: Update the EE facade tests**

Delete `EmbeddedKnowledgeBaseCreateOwnerResolutionTest.java` entirely — every case in it asserts owner resolution.

In `EmbeddedDataTableApiFacadeCreateTest.java` keep `testCreateGoesIntoTheEmbeddedPool`, which is about pool routing, and delete `testCreateForAnAccountCarriesThatAccountsOwner` and `testTheOwnerTheFacadePassesIsTheOneThePhysicalNameIsBuiltFrom` together with the class Javadoc paragraphs that set them up. Trim the owner cases from `EmbeddedDataTableApiFacadeTest`, `EmbeddedKnowledgeBaseApiFacadeTest` and `EmbeddedKnowledgeBaseApiFacadeUpdateTest`.

- [ ] **Step 5: Run the EE module tests**

```bash
./gradlew :server:ee:libs:embedded:embedded-data-table-graphql:test :server:ee:libs:embedded:embedded-knowledge-base-graphql:test --continue > /tmp/test-t2.log 2>&1
echo "exit=$?"
grep -n '^> Task .* FAILED' /tmp/test-t2.log || echo "no failed tasks"
```

Expected: `exit=0`.

- [ ] **Step 6: Commit the server half**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add -A server && git commit -F - <<'MSG'
Drop the owner from the embedded data table and knowledge base GraphQL surface

An embedded data table or knowledge base no longer belongs to one account, so
the query's ownerId filter, the two assign mutations and their inputs, and the
ownerId on both create inputs and both returned types describe a resource that
no longer exists.

The platform services still accept an owner here; the facades pass
Optional.empty() until those signatures are collapsed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

- [ ] **Step 7: Edit the client operations and regenerate**

`client/src/graphql/embedded/configuration/embeddedDataTables.graphql` becomes:

```graphql
query EmbeddedDataTables($environmentId: ID!) {
    embeddedDataTables(environmentId: $environmentId) {
        id
        baseName
        description
        columns {
            id
            name
            type
        }
        lastModifiedDate
    }
}

mutation CreateEmbeddedDataTable($input: CreateEmbeddedDataTableInput!) {
    createEmbeddedDataTable(input: $input)
}
```

Make the matching edit to `embeddedKnowledgeBases.graphql`, dropping the `$ownerId` variable and argument, the `ownerId` selection and the `AssignEmbeddedKnowledgeBaseOwner` mutation, keeping `CreateEmbeddedKnowledgeBase` and `UpdateEmbeddedKnowledgeBase`.

```bash
cd client && npx graphql-codegen
```

- [ ] **Step 8: Verify the client still checks clean**

```bash
cd client && npm run check
```

Expected: PASS, with `useAssignEmbeddedDataTableOwnerMutation` and `useAssignEmbeddedKnowledgeBaseOwnerMutation` gone from `graphql.ts`. Tool timeout 600000 ms.

- [ ] **Step 9: Commit operations and generated output separately**

```bash
git add client/src/graphql && git commit -m "client - Drop ownerId from the embedded data table and knowledge base operations

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git add client/src/shared/middleware/graphql.ts client/src/shared/middleware/graphql-types.ts
git commit -m "client - Regenerate the GraphQL client after dropping the embedded owner

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: `KnowledgeBaseService` sheds the resource owner

**Files:**
- Modify: `server/libs/platform/platform-knowledge-base/platform-knowledge-base-api/src/main/java/com/bytechef/platform/knowledgebase/service/KnowledgeBaseService.java`
- Modify: `server/libs/platform/platform-knowledge-base/platform-knowledge-base-api/src/main/java/com/bytechef/platform/knowledgebase/domain/KnowledgeBase.java`
- Modify: `server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/main/java/com/bytechef/platform/knowledgebase/service/KnowledgeBaseServiceImpl.java`
- Modify: `server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/main/java/com/bytechef/platform/knowledgebase/repository/KnowledgeBaseRepository.java`
- Modify: `server/libs/modules/components/ai/vectorstore/knowledgebase/src/main/java/com/bytechef/component/ai/vectorstore/knowledgebase/util/KnowledgeBaseOptionsUtils.java`
- Modify: `server/ee/libs/embedded/embedded-knowledge-base-graphql/src/main/java/com/bytechef/ee/embedded/knowledgebase/facade/EmbeddedKnowledgeBaseApiFacadeImpl.java`
- Delete: `server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/main/java/com/bytechef/platform/knowledgebase/event/KnowledgeBaseOwnerAssignedEvent.java`, `…/KnowledgeBaseOwnerAssignedListener.java`
- Delete: `server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/test/java/com/bytechef/platform/knowledgebase/service/KnowledgeBaseOwnershipTest.java`, `…/KnowledgeBaseOwnerUniqueIndexIntTest.java`, `…/KnowledgeBaseAssignOwnerIntTest.java`
- Test: `server/libs/modules/components/ai/vectorstore/knowledgebase/src/test/java/com/bytechef/component/ai/vectorstore/knowledgebase/util/KnowledgeBaseVectorStoreScopingTest.java`, `…/KnowledgeBaseComponentScopesByIdReadsTest.java`

**Interfaces:**
- Consumes: Task 2's guarantee that no EE facade calls `assignOwner`.
- Produces: `KnowledgeBaseService.getKnowledgeBase(Long id, List<PlatformType> platformTypes)`, `getKnowledgeBases(PlatformType platformType)`, `getKnowledgeBases(int environment, PlatformType platformType)`, `fetchKnowledgeBase(String name, int environment, PlatformType platformType)`. `assignOwner` no longer exists. `KnowledgeBase` no longer has `getOwnerId()`/`getOwnerType()`/`setOwner()`.

- [ ] **Step 1: Confirm the axis-2 knowledge base tests are green before touching anything**

```bash
./gradlew :server:libs:modules:components:ai:vectorstore:knowledgebase:test --tests '*OwnershipTest' --tests '*OwnerScopingTest' --continue > /tmp/test-t3-before.log 2>&1
echo "exit=$?"
```

Expected: `exit=0`. This is the baseline the task must not move.

- [ ] **Step 2: Delete the axis-1 tests and the owner-assigned event pair**

```bash
git rm server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/test/java/com/bytechef/platform/knowledgebase/service/KnowledgeBaseOwnershipTest.java \
       server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/test/java/com/bytechef/platform/knowledgebase/service/KnowledgeBaseOwnerUniqueIndexIntTest.java \
       server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/test/java/com/bytechef/platform/knowledgebase/service/KnowledgeBaseAssignOwnerIntTest.java \
       server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/main/java/com/bytechef/platform/knowledgebase/event/KnowledgeBaseOwnerAssignedEvent.java \
       server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/main/java/com/bytechef/platform/knowledgebase/event/KnowledgeBaseOwnerAssignedListener.java
```

- [ ] **Step 3: Collapse the service interface**

In `KnowledgeBaseService.java` delete `assignOwner` and drop the `Optional<Owner>` parameter from `getKnowledgeBase(Long, List<PlatformType>, Optional<Owner>)`, both `getKnowledgeBases` overloads and `fetchKnowledgeBase`. Rewrite the Javadoc that argues an account may hold its own `docs`. The pool argument stays on every one of them — pools are not ownership, and `poolFor(owner)` still decides which pools a run may read.

```java
    /**
     * Pool-aware form, for every caller acting on behalf of a run.
     *
     * <p>
     * The pool is the whole scope here. A knowledge base in an embedded environment is visible to every account in it;
     * what separates two accounts is the owner on the chunks inside it, applied by
     * {@code KnowledgeBaseVectorStoreWrapper}, not which knowledge bases they can name.
     *
     * @param platformTypes the pools this run may read, from {@code poolFor(owner)}
     */
    KnowledgeBase getKnowledgeBase(Long id, List<PlatformType> platformTypes);
```

- [ ] **Step 4: Collapse the implementation, the domain and the repository**

In `KnowledgeBaseServiceImpl` delete `assignOwner` and the event publication, and drop the owner branch from the four read methods. In `KnowledgeBase` delete the `ownerId` and `ownerType` columns, `getOwnerId`, `getOwnerType`, `setOwner`, `getOwner`, their `toString` entries and the `OwnerType` import. In `KnowledgeBaseRepository` delete `findByNameAndEnvironmentAndPlatformTypeAndOwnerIdAndOwnerType` and rename `findByNameAndEnvironmentAndPlatformTypeAndOwnerIdIsNull` to `findByNameAndEnvironmentAndPlatformType`, updating its Javadoc, which currently justifies the owner in the unique index.

`KnowledgeBaseDocument.ownerId` is axis 2 and is not touched.

- [ ] **Step 5: Fix the two callers**

In `KnowledgeBaseOptionsUtils` drop the `owner` argument at lines calling `getKnowledgeBases(platformType, owner)`, `getKnowledgeBase(knowledgeBaseId, poolFor(owner), owner)` and `fetchKnowledgeBase(name, environment, platformType, owner)`. `poolFor(owner)` stays — the owner still selects the pool. Rewrite the Javadoc that says a knowledge base becomes assigned only through `assignOwner`. In `EmbeddedKnowledgeBaseApiFacadeImpl` drop the `Optional.empty()` arguments Task 2 left behind, and the
`toOwner` helper if it still exists — Task 2 removed the `ownerId` parameter that fed it, so by now it is
unreferenced.

- [ ] **Step 6: Compile and run the affected modules**

```bash
./gradlew :server:libs:platform:platform-knowledge-base:platform-knowledge-base-service:test \
          :server:libs:modules:components:ai:vectorstore:knowledgebase:test \
          :server:ee:libs:embedded:embedded-knowledge-base-graphql:test --continue > /tmp/test-t3.log 2>&1
echo "exit=$?"
grep -n '^> Task .* FAILED' /tmp/test-t3.log || echo "no failed tasks"
```

Expected: `exit=0`. `KnowledgeBaseDocumentOwnerScopingTest`, `KnowledgeBaseVectorStoreWrapperOwnershipTest` and `KnowledgeBaseVectorStoreOwnershipBackfillIntTest` must pass **unmodified**. If any of them needed an edit, stop: axis 2 has been disturbed and that is a defect in this change.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add -A server && git commit -F - <<'MSG'
Remove the resource owner from knowledge bases

A knowledge base no longer belongs to one account. The registry owner, its
unique-index half, assignOwner and the owner-assigned event and listener that
re-stamped documents behind it all go; the pool argument stays, because a pool
is not an owner.

What separates two accounts is unchanged and untouched: the owner on the chunks
inside a knowledge base, applied by KnowledgeBaseVectorStoreWrapper. Its tests
pass without edit, which is the point of doing it in this order.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 4: `DataTableRef`, `PhysicalTableNaming` and `DataTableService` collapse to one owner

The largest task, and deliberately not split: `DataTableRef` losing a component, `PhysicalTableNaming` losing the owner segment and `DataTableService` losing nine owner parameters are one compile unit. Splitting them would leave an intermediate state that does not build.

**Files:**
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/domain/DataTableRef.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/internal/PhysicalTableNaming.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/configuration/service/DataTableService.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/configuration/domain/DataTable.java`, `…/DataTableInfo.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/configuration/service/DataTableServiceImpl.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/configuration/repository/DataTableRepository.java`
- Modify: `server/ee/libs/platform/platform-data-table/platform-data-table-remote-client/src/main/java/com/bytechef/ee/platform/data/table/remote/client/service/RemoteDataTableServiceClient.java`
- Modify: `server/libs/modules/components/data-table/src/main/java/com/bytechef/component/datatable/util/DataTableUtils.java`
- Modify: `server/ee/libs/embedded/embedded-data-table-graphql/src/main/java/com/bytechef/ee/embedded/data/table/facade/EmbeddedDataTableApiFacadeImpl.java`
- Modify: every remaining call site the compiler names (13 `listTables`, 5 `createTable`, 3 each `dropTable`/`duplicateTable`/`addColumn`, 2 each `renameTable`/`removeColumn`/`renameColumn`/`fetchDataTable`)
- Delete: `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableOwnershipTest.java`, `…/DataTableOwnerUniqueIndexIntTest.java`, `…/DataTableOwnerResolutionTest.java`, `…/DataTableListTablesOwnedPhysicalTableIntTest.java`, `…/execution/service/DataTableEmbeddedOwnedTableIntTest.java`, `…/configuration/service/DataTableWebhookOwnedTableIntTest.java`
- Test: `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/domain/DataTableRefTest.java`, `…/internal/PhysicalTableNamingTest.java`

**Interfaces:**
- Consumes: Task 2's facade (no `assignOwner` caller) and Task 3's knowledge base collapse (no shared helper left expecting a resource owner).
- Produces: `DataTableRef(String baseName, long environmentId, PlatformType platformType, @Nullable Owner runOwner)` with `DataTableRef.unowned(String, long, PlatformType)`, `runOwner()`, `runOwnerId()`, `physicalName()`. `PhysicalTableNaming.buildPhysicalName(PlatformType, long, String)` and `prefix(PlatformType, long)`. `DataTableService.fetchDataTableResolution(String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner)` is the only method still taking an owner.

- [ ] **Step 1: Write the two structural tests first**

These are the only genuinely new behaviour in the task, so they are written before the change and must fail. Add to `DataTableRefTest.java`:

```java
    /**
     * The physical name is what a run addresses. If an owner could reach it, two accounts would have two tables again
     * and the row predicate would stop being the thing that separates them.
     */
    @Test
    void testNoPhysicalNameCarriesAnOwnerSegment() {
        DataTableRef ownedRun = new DataTableRef("orders", 1, PlatformType.EMBEDDED, Owner.connectedUser(42));
        DataTableRef vendorRun = DataTableRef.unowned("orders", 1, PlatformType.EMBEDDED);

        assertThat(ownedRun.physicalName())
            .as("the run owner scopes rows, never the table")
            .isEqualTo(vendorRun.physicalName())
            .isEqualTo("edt_1_orders");
    }
```

Add a new test class `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableServiceOwnerParameterTest.java`:

```java
    /**
     * Asserted reflectively rather than by reading the interface, so the parameter cannot creep back one signature at a
     * time. fetchDataTableResolution is the single exception: its owner is the run owner that binds into the row
     * predicate, not a table selector.
     */
    @Test
    void testOnlyResolutionAcceptsAnOwner() {
        List<String> offenders = Arrays.stream(DataTableService.class.getMethods())
            .filter(method -> !"fetchDataTableResolution".equals(method.getName()))
            .filter(method -> Arrays.stream(method.getGenericParameterTypes())
                .map(Type::getTypeName)
                .anyMatch(typeName -> typeName.contains("com.bytechef.platform.owner.Owner")))
            .map(Method::getName)
            .toList();

        assertThat(offenders)
            .as("only fetchDataTableResolution may take an owner; a DDL method taking one is a table selector")
            .isEmpty();
    }
```

- [ ] **Step 2: Run both new tests and watch them fail**

```bash
./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:test --tests '*DataTableRefTest' --tests '*DataTableServiceOwnerParameterTest' --continue > /tmp/test-t4-red.log 2>&1
echo "exit=$?"
grep -n 'testNoPhysicalNameCarriesAnOwnerSegment\|testOnlyResolutionAcceptsAnOwner' /tmp/test-t4-red.log
```

Expected: FAIL — `DataTableRefTest` fails to compile against the four-component record and `unowned` does not exist; `DataTableServiceOwnerParameterTest` reports nine offending methods.

- [ ] **Step 3: Collapse `DataTableRef`**

```java
public record DataTableRef(
    String baseName, long environmentId, PlatformType platformType, @Nullable Owner runOwner) {

    private static final int MAX_IDENTIFIER_BYTES = 63;

    public DataTableRef {
        Assert.hasText(baseName, "baseName must not be empty");
        Assert.notNull(platformType, "platformType must not be null");

        baseName = baseName.toLowerCase(Locale.ROOT);

        Assert.isTrue(!baseName.startsWith("dt_"), "baseName must not start with 'dt_'");
        Assert.isTrue(baseName.matches("[a-z_][a-z0-9_]*"), "Invalid base name: " + baseName);

        String physicalName = PhysicalTableNaming.buildPhysicalName(platformType, environmentId, baseName);
        byte[] bytes = physicalName.getBytes(StandardCharsets.UTF_8);

        Assert.isTrue(
            bytes.length <= MAX_IDENTIFIER_BYTES,
            "Physical table name '" + physicalName + "' exceeds " + MAX_IDENTIFIER_BYTES + " bytes");
    }

    public static DataTableRef unowned(String baseName, long environmentId, PlatformType platformType) {
        return new DataTableRef(baseName, environmentId, platformType, null);
    }

    public @Nullable Long runOwnerId() {
        return runOwner == null ? null : runOwner.id();
    }

    public String physicalName() {
        return PhysicalTableNaming.buildPhysicalName(platformType, environmentId, baseName);
    }
}
```

Rewrite the class Javadoc. Its current thesis — that a ref stops a caller pairing a base name with an owner of its own and reaching another account's table — describes a table that no longer exists. The claim that remains true and is worth stating: a ref is the only place a row statement learns whose run it is, and every DDL and DML statement takes one so no call site can supply a base name and let the callee guess. Keep the identifier-length note but drop the sentence blaming the owner for the overflow; a long base name is enough.

- [ ] **Step 4: Collapse `PhysicalTableNaming`**

```java
    public static String buildPhysicalName(PlatformType platformType, long environmentId, String baseName) {
        return prefix(platformType, environmentId) + baseName.toLowerCase(Locale.ROOT);
    }

    public static String prefix(PlatformType platformType, long environmentId) {
        return poolToken(platformType) + "_" + environmentId + "_";
    }

    public static String poolToken(PlatformType platformType) {
        return platformType == PlatformType.EMBEDDED ? "edt" : "dt";
    }
```

Delete `ownerTypeToken(OwnerType)`, `ownerType(String)` and the `OwnerType` and `Optional` imports. Delete the paragraphs arguing for the id-then-type token order and the underscore removal. Keep the pool paragraph — the pool still lives in both the name and the `platform_type` column and the two must still agree. In `PhysicalTableNamingTest`, delete the token round-trip and collision cases and keep the pool and prefix cases.

- [ ] **Step 5: Collapse `DataTableService` and its implementation**

Remove `Optional<Owner>` from `addColumn`, `createTable`, `dropTable`, `duplicateTable`, `fetchDataTable(String, PlatformType, Optional<Owner>)`, `listTables`, `removeColumn`, `renameColumn` and `renameTable`. Delete `assignOwner`. Leave `fetchDataTableResolution` alone and rewrite its Javadoc so the surviving owner is unambiguous:

```java
    /**
     * Resolves a base name to the one physical table that holds it, and binds the run's owner into the ref.
     *
     * <p>
     * The owner here is the run owner and nothing else: it selects rows inside the table, never the table. There is one
     * table per base name per environment per pool, so resolution has nothing to choose between.
     *
     * @param owner the caller's owner, or empty for a run with no named account
     */
```

In `DataTableServiceImpl`:
- `fetchDataTable(String, PlatformType)` becomes a single `dataTableRepository.findByNameAndPlatformType(normalizedBaseName, platformType.ordinal())`.
- `physicalRef(DataTable, String, long, PlatformType, Optional<Owner>)` becomes `new DataTableRef(baseName, environmentId, platformType, owner.orElse(null))`.
- `createTable` builds `DataTableRef.unowned(...)` and calls `register(baseName, description, platformType)`.
- `listTables` drops the `parseBaseNameAndOwner` call, the `BaseNameAndOwner` record, `OWNED_PHYSICAL_NAME_SUFFIX`, the `PHYSICAL_NAME` pattern's owner group, the `isReadableBy` filter and the class-level `REDOS` suppression justification that refers to the optional group. The base name is `tableName.substring(prefix.length())`; the `startsWith` guard stays, because `LIKE` still treats `_` as a wildcard.
- Delete `ownerOf`, `isReadableBy` and `findOwnedDataTable`.

In `DataTableRepository`, delete `findByNameAndPlatformTypeAndOwnerIdAndOwnerType` and rename `findByNameAndPlatformTypeAndOwnerIdIsNull` to `findByNameAndPlatformType`. In `DataTable` delete `ownerId`, `ownerType` and their accessors; in `DataTableInfo` delete the `ownerId` component. `DataTableWebhook` is axis 2 and is not touched, but its Javadoc paragraph explaining that a shared table is one of two kinds now describes the only kind — rewrite that paragraph.

- [ ] **Step 6: Fix every call site the compiler names**

```bash
./gradlew compileJava --continue > /tmp/build-t4.log 2>&1
echo "exit=$?"
grep -n 'error:' /tmp/build-t4.log | head -60
```

Work the list down. Most sites drop an `Optional.empty()` or an `owner` argument. `RemoteDataTableServiceClient` follows the interface, deleting its `assignOwner` stub. `DataTableUtils` keeps `effectiveOwner` and passes its result to `fetchDataTableResolution` only. `EmbeddedDataTableApiFacadeImpl` drops the `Optional.empty()` arguments Task 2 left behind. Repeat until `exit=0`.

- [ ] **Step 7: Delete the axis-1 tests, trim the mixed ones**

```bash
git rm server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableOwnershipTest.java \
       server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableOwnerUniqueIndexIntTest.java \
       server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableOwnerResolutionTest.java \
       server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableListTablesOwnedPhysicalTableIntTest.java \
       server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/execution/service/DataTableEmbeddedOwnedTableIntTest.java \
       server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableWebhookOwnedTableIntTest.java
```

`DataTableWebhookOwnedTableIntTest` covers webhook registration against a per-account physical table and has no
surviving subject. `DataTableWebhookRowOwnerIntTest` is the axis-2 sibling and stays.

Then trim the owned-table cases from `DataTableServiceImplBaseNameTest`, `DataTableEmbeddedActionTest` and
`DataTableServiceTest`.

In `DataTableEditorAccountScopingTest`, delete `testTheDropdownOffersTheNamedAccountsTables`,
`testTheDropdownIgnoresAnAccountNamedByARunThatAlreadyBelongsToOne` and
`testTheDynamicPropertiesDescribeTheNamedAccountsColumns`. All three assert that a step naming an account is
offered THAT ACCOUNT'S tables and columns, which is the concept this task deletes; they also call
`listTables(env, pool, Optional.empty())` and so will not compile. Leave the file's fixtures and helpers in
place — Task 5 re-points it at the guard that survives.

Run `./gradlew compileTestJava --continue` until clean.

- [ ] **Step 8: Run the full data-table suite**

```bash
./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:test \
          :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration \
          :server:libs:modules:components:data-table:test --continue > /tmp/test-t4.log 2>&1
echo "exit=$?"
grep -n '^> Task .* FAILED' /tmp/test-t4.log || echo "no failed tasks"
```

Expected: `exit=0`, both new structural tests green. `DataTableRowOwnerScopingIntTest`, `DataTableWebhookRowOwnerIntTest`, `DataTableRowPoolScopingIntTest` and `OwnerTypeOrdinalStabilityTest` must pass **unmodified**. Docker must be running for the integration tests; on this machine Testcontainers needs the OrbStack socket, since `/var/run/docker.sock` is a dangling symlink.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add -A server && git commit -F - <<'MSG'
Collapse the data table ref and service to one owner

A data table no longer belongs to one account, so there is one physical table
per base name per environment per pool and nothing for resolution to choose
between. DataTableRef drops resourceOwner and with it the invariant relating
the two, PhysicalTableNaming drops the owner segment and both owner-token
methods, and nine DataTableService methods drop the owner they used to pick a
registry row with.

fetchDataTableResolution keeps its owner. That one is the run owner, which
binds into the row predicate and is now the only thing standing between two
accounts' data -- so two structural tests hold the line: no physical name can
carry an owner segment, and no other method on the interface may take an Owner.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 5: Narrow the data table component's account-dependent lookups

Separately rejectable from Task 4: nothing here is needed to compile, and it is a behavioural change to the editor rather than a removal.

**Files:**
- Modify: `server/libs/modules/components/data-table/src/main/java/com/bytechef/component/datatable/action/DataTableClearTableAction.java`, `…/DataTableCreateRecordsAction.java`, `…/DataTableDeleteRecordsAction.java`, `…/DataTableFindRecordsAction.java`, `…/DataTableGetRecordAction.java`, `…/DataTableUpdateRecordAction.java`
- Modify: `server/libs/modules/components/data-table/src/main/java/com/bytechef/component/datatable/util/DataTableUtils.java`
- Test: `server/libs/modules/components/data-table/src/test/java/com/bytechef/component/datatable/util/DataTableEditorAccountScopingTest.java`

**Interfaces:**
- Consumes: Task 4's collapsed `DataTableService`.
- Produces: no signature changes. `DataTableUtils.accountProperty()` and `effectiveOwner(Optional<Owner>, Long)` keep their shapes.

- [ ] **Step 1: Write the failing test**

Add to `DataTableEditorAccountScopingTest.java`:

```java
    /**
     * Every account sees the same tables with the same columns, so re-asking either lookup when the account changes
     * costs a round trip and can only return the same answer. The sample row is the exception and is asserted below.
     */
    @Test
    void testTableAndColumnLookupsDoNotDependOnTheAccount() {
        ActionDefinition actionDefinition = DataTableCreateRecordsAction.ACTION_DEFINITION;

        List<? extends Property> properties = OptionalUtils.get(actionDefinition.getProperties());

        Property tableProperty = properties.stream()
            .filter(property -> TABLE.equals(property.getName()))
            .findFirst()
            .orElseThrow();

        OptionsDataSource<?> optionsDataSource = ((DynamicOptionsProperty<?>) tableProperty)
            .getOptionsDataSource()
            .orElseThrow();

        assertThat(optionsDataSource.getOptionsLookupDependsOn())
            .as("a table list is the same for every account, so there is nothing to re-ask on")
            .isEmpty();
    }
```

`getOptionsDataSource()` lives on `DynamicOptionsProperty`, not on `Property`, and returns
`Optional<OptionsDataSource<?>>`; `getOptionsLookupDependsOn()` returns `Optional<List<String>>`, so the assertion is
`isEmpty()` on the Optional. Import `com.bytechef.component.definition.DynamicOptionsProperty` and
`com.bytechef.component.definition.OptionsDataSource`.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :server:libs:modules:components:data-table:test --tests '*DataTableEditorAccountScopingTest' --continue > /tmp/test-t5-red.log 2>&1
echo "exit=$?"
```

Expected: FAIL — the dependency list still contains `accountId`.

- [ ] **Step 3: Drop the two dependencies**

In all six actions, change `.optionsLookupDependsOn(ACCOUNT_ID)` on the `TABLE` property to no `optionsLookupDependsOn` at all, and in `DataTableCreateRecordsAction` and `DataTableUpdateRecordAction` change `.propertiesLookupDependsOn(TABLE, ACCOUNT_ID)` to `.propertiesLookupDependsOn(TABLE)`. Leave every `sampleOutput` lookup and every `inputParameters.getLong(ACCOUNT_ID)` call untouched — a sample row is still one account's row.

Then restore the coverage Task 4 removed from `DataTableEditorAccountScopingTest`, re-pointed at what survives. The
guard is no longer observable in which tables are listed — every account is offered the same ones — so assert it where
it now shows, in the owner handed to resolution:

```java
    /**
     * The account selector is the vendor's. A run that already belongs to an account may not name another, or an
     * account's own workflow would read another account's rows out of the shared table. Once per-account tables are
     * gone this guard is the whole of the isolation story for a vendor workflow, so it is asserted on the owner that
     * reaches resolution rather than on which tables come back.
     */
    @Test
    void testARunBelongingToAnAccountCannotNameAnother() {
        assertThat(DataTableUtils.effectiveOwner(Optional.of(Owner.connectedUser(ACCOUNT_ID)), OTHER_ACCOUNT_ID))
            .as("a run that already belongs to an account keeps its own owner")
            .isEqualTo(Optional.of(Owner.connectedUser(ACCOUNT_ID)));
    }

    @Test
    void testAVendorRunMayActForANamedAccount() {
        assertThat(DataTableUtils.effectiveOwner(Optional.empty(), ACCOUNT_ID))
            .as("a vendor run naming an account acts for it")
            .isEqualTo(Optional.of(Owner.connectedUser(ACCOUNT_ID)));
    }
```

If `effectiveOwner` is not visible to the test, widen it to package-private rather than routing the assertion through
an options function — the options functions no longer vary by account, which is what makes them the wrong probe.

In `DataTableUtils`, rewrite the two Javadoc paragraphs that explain the dependency (they describe a vendor step being offered the shared table's columns while acting on an account's), and keep the `effectiveOwner` guard and its paragraph, which is now the whole of the isolation story for a vendor workflow.

- [ ] **Step 4: Run the test and the component suite**

```bash
./gradlew :server:libs:modules:components:data-table:test --continue > /tmp/test-t5.log 2>&1
echo "exit=$?"
grep -n '^> Task .* FAILED' /tmp/test-t5.log || echo "no failed tasks"
```

Expected: `exit=0`. If the component definition snapshot under `src/test/resources/definition/` fails, delete it **and** its copy under `build/resources/test/definition/`, then run twice — the first run writes the snapshot to `src` and throws `NullPointerException: url` on the still-missing classpath copy, which is the expected midpoint rather than a bug.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add -A server && git commit -F - <<'MSG'
Stop the data table editor re-asking its lookups when the account changes

Every account now sees the same tables with the same columns, so the table
dropdown's optionsLookupDependsOn(ACCOUNT_ID) and the column properties'
ACCOUNT_ID dependency cost a round trip that can only return the same answer.

The sample-output lookup keeps its dependency. A sample row is still one
account's row, and one built without the run owner would show an account a row
of another's.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 6: Delete the axis-1 changesets and repair development databases

Last, because a column cannot be dropped while a Java entity still maps it. Tasks 3 and 4 removed the mappings.

**Files:**
- Create: `server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/main/resources/config/liquibase/changelog/platform/knowledge_base/20260902000001_platform_knowledge_base_drop_name_environment_unique.xml`
- Create: `scripts/dev/cleanup-resource-owner.sql`
- Delete: the four changesets named in the File Structure table

**Interfaces:**
- Consumes: Tasks 3 and 4 — no entity maps `data_table.owner_id` or `knowledge_base.owner_id`.
- Produces: a fresh database whose `data_table` is keyed on `(name, platform_type)` and whose `knowledge_base` is keyed on `(name, platform_type, environment)`.

- [ ] **Step 1: Extract the knowledge base name-uniqueness changeset before deleting its file**

This is the one trap in the migration. `20260831000001_platform_knowledge_base_owner_unique_index.xml` carries a fifth changeset, `-4`, that drops `uk_knowledge_base_name_environment` — a constraint that arrived from 0_732 independently, keys on `(name, environment)` with no `platform_type`, and left standing forbids the same knowledge base name existing in both pools. Create the new file first:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd"
                   logicalFilePath="config/liquibase/changelog/platform/knowledge_base/20260902000001_platform_knowledge_base_drop_name_environment_unique.xml">

    <!--
        uk_knowledge_base_name_environment comes from
        20260831000001_platform_knowledge_base_name_unique_per_environment and keys the name on (name, environment)
        with no platform_type, so it forbids the same name existing in both the AUTOMATION and EMBEDDED pools. The
        registry key is (name, platform_type, environment); this constraint is strictly narrower and must go.

        It was dropped by the owner unique-index changeset, which has been deleted along with the rest of resource
        ownership. This drop has nothing to do with owners and is kept on its own.

        Preconditioned because the constraint exists only where that changeset ran.
    -->
    <changeSet id="20260902000001-1" author="Ivica Cardic">
        <preConditions onFail="MARK_RAN">
            <uniqueConstraintExists tableName="knowledge_base" constraintName="uk_knowledge_base_name_environment"/>
        </preConditions>

        <dropUniqueConstraint tableName="knowledge_base" constraintName="uk_knowledge_base_name_environment"/>

        <rollback>
            <addUniqueConstraint
                tableName="knowledge_base" columnNames="name, environment"
                constraintName="uk_knowledge_base_name_environment"/>
        </rollback>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 2: Delete the four axis-1 changesets and their stale build copies**

The directories are wired with `<includeAll>` in `server/libs/config/liquibase-config/src/main/resources/config/liquibase/master.xml`, so deleting a file is the whole edit — there are no `<include>` lines. Stale copies under `build/resources/` must go too, or Liquibase sees both sets on the classpath.

```bash
git rm server/libs/platform/platform-data-table/platform-data-table-service/src/main/resources/config/liquibase/changelog/platform/data_table/20260827000001_platform_data_table_add_owner.xml \
       server/libs/platform/platform-data-table/platform-data-table-service/src/main/resources/config/liquibase/changelog/platform/data_table/20260831000002_platform_data_table_owner_unique_index.xml \
       server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/main/resources/config/liquibase/changelog/platform/knowledge_base/20260827000001_platform_knowledge_base_add_owner.xml \
       server/libs/platform/platform-knowledge-base/platform-knowledge-base-service/src/main/resources/config/liquibase/changelog/platform/knowledge_base/20260831000001_platform_knowledge_base_owner_unique_index.xml
find server -path '*/build/resources/*' \( -name '*_add_owner.xml' -o -name '*_owner_unique_index.xml' \) -delete
```

- [ ] **Step 3: Write the development cleanup script**

Create `scripts/dev/cleanup-resource-owner.sql`, following `scripts/dev/cleanup-synthetic-deployment-orphans.sql` as the house pattern. It must be idempotent:

```sql
-- Repairs a development database that ran the axis-1 changesets before they were deleted.
--
-- Resource ownership never shipped: no ownership commit is an ancestor of origin/master, so the changesets were
-- deleted rather than reversed and a fresh database never grows these columns. A database that already ran them
-- keeps orphan columns, orphan owned physical tables, and registry rows pointing at physical names that no longer
-- resolve. Run this once per affected schema.
--
-- Owned rows are dropped rather than merged into the shared table of the same base name: the two can have divergent
-- column sets, and no shipped data is at stake.
--
--   psql "$BYTECHEF_DEV_DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/dev/cleanup-resource-owner.sql

BEGIN;

-- Registry rows whose physical table is an owned one, and the tables themselves.
DO $$
DECLARE
    owned_table_name text;
BEGIN
    FOR owned_table_name IN
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_type = 'BASE TABLE'
          AND table_name ~ '^edt_[0-9]+_[0-9]+_[a-z]+_'
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I CASCADE', owned_table_name);
    END LOOP;
END $$;

DELETE FROM data_table WHERE owner_id IS NOT NULL;
DELETE FROM knowledge_base WHERE owner_id IS NOT NULL;

-- The axis-1 registry key, and the columns behind it.
DROP INDEX IF EXISTS uk_data_table_name_platform_type_owner;
DROP INDEX IF EXISTS uk_data_table_name_platform_type_shared;
DROP INDEX IF EXISTS idx_data_table_owner;
ALTER TABLE data_table DROP COLUMN IF EXISTS owner_id;
ALTER TABLE data_table DROP COLUMN IF EXISTS owner_type;

DROP INDEX IF EXISTS uk_knowledge_base_name_platform_type_environment_owner;
DROP INDEX IF EXISTS uk_knowledge_base_name_platform_type_environment_shared;
DROP INDEX IF EXISTS idx_knowledge_base_owner;
ALTER TABLE knowledge_base DROP COLUMN IF EXISTS owner_id;
ALTER TABLE knowledge_base DROP COLUMN IF EXISTS owner_type;

-- The pre-ownership keys, which the deleted changesets had dropped.
ALTER TABLE data_table DROP CONSTRAINT IF EXISTS uk_data_table_name_platform_type;
ALTER TABLE data_table ADD CONSTRAINT uk_data_table_name_platform_type UNIQUE (name, platform_type);

ALTER TABLE knowledge_base DROP CONSTRAINT IF EXISTS uk_knowledge_base_name_platform_type_environment;
ALTER TABLE knowledge_base
    ADD CONSTRAINT uk_knowledge_base_name_platform_type_environment UNIQUE (name, platform_type, environment);

-- The deleted changesets' own bookkeeping, so Liquibase does not report them as unexpected.
DELETE FROM databasechangelog
WHERE filename LIKE '%_platform_data_table_add_owner.xml'
   OR filename LIKE '%_platform_data_table_owner_unique_index.xml'
   OR filename LIKE '%_platform_knowledge_base_add_owner.xml'
   OR filename LIKE '%_platform_knowledge_base_owner_unique_index.xml';

COMMIT;
```

Note: the physical-table regex matches the owned form `edt_<env>_<ownerId>_<ownerTypeToken>_<base>`. Shared tables are `edt_<env>_<base>` and a base name cannot start with a digit, so they can never match.

- [ ] **Step 4: Prove the schema with an integration test, not with bootRun**

The `liquibase` Spring profile does not apply migrations via `bootRun` — it exits 0 having created nothing. Testcontainers builds the schema from scratch, which is stronger evidence anyway.

```bash
./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration \
          :server:libs:platform:platform-knowledge-base:platform-knowledge-base-service:testIntegration --continue > /tmp/test-t6.log 2>&1
echo "exit=$?"
grep -n '^> Task .* FAILED' /tmp/test-t6.log || echo "no failed tasks"
```

Expected: `exit=0` on a schema built without the deleted changesets.

- [ ] **Step 5: Commit**

```bash
git add -A server scripts && git commit -F - <<'MSG'
Delete the resource owner changesets and repair development databases

Resource ownership is in no released version -- no ownership commit is an
ancestor of origin/master -- so the four changesets are deleted rather than
reversed and a fresh database never grows the columns.

The knowledge base index file carried a fifth changeset that drops
uk_knowledge_base_name_environment, which arrived from 0_732 independently and,
left standing, forbids the same name existing in both pools. It has nothing to
do with owners and is extracted into its own file, preconditioned so a database
that already ran the original simply marks it run.

Deleting the two index files also means the pre-ownership keys are never
dropped, which restores them without a changeset saying so.

scripts/dev/cleanup-resource-owner.sql repairs a shared development
database that already ran the deleted set.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 7: Whole-tree verification and the regression gate

**Files:**
- Modify: `docs/superpowers/specs/2026-09-02-embedded-row-level-ownership-only-design.md` (implementation notes only, if reality diverged)

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces: nothing; this is the gate.

- [ ] **Step 1: Prove no resource-owner reference survives**

```bash
grep -rn "resourceOwner\|assignOwner\|ownerTypeToken\|findOwnedDataTable\|isReadableBy" --include='*.java' server --exclude-dir=build | grep -v '/src/test/'
grep -rn "OwnerSelect\|useEmbeddedConnectedUsers\|assignEmbeddedDataTableOwner\|assignEmbeddedKnowledgeBaseOwner" client/src
```

Expected: no output from either command.

- [ ] **Step 2: Full server build**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/build-final.log 2>&1
echo "exit=$?"
grep -n '^> Task .* FAILED' /tmp/build-final.log || echo "no failed tasks"
```

Expected: `exit=0`. Grep for `^> Task .* FAILED`, never for `error:` — that matches module paths such as `:server:libs:core:error:`.

- [ ] **Step 3: Run the axis-2 regression gate, unmodified**

**This gate was respecified during execution, and the original list below it was wrong.** Task 4's four
data-table gate files were chosen by NAME when this plan was written, without reading them. Three did not survive
contact: `DataTableRowPoolScopingIntTest`'s own javadoc says "Row-by-row scoping used to be tested here at length.
It is gone" — it is the file that REPLACED row scoping when axis 1 landed, so it could never have failed when axis 1
was removed. `OwnerTypeOrdinalStabilityTest` had two of four cases calling `DataTable.setOwnerType`, which this plan
itself deletes. `DataTableWebhookRowOwnerIntTest` had one axis-1 case in five. Keeping the original list would have
forced either a false BLOCKED or a fake green.

The authoritative gate is eight files in two groups, and the rule is **no gate test's ASSERTIONS or FIXTURE VALUES
may change; signature-only adaptations are allowed, and adding a case is allowed**. Byte-identity was never the
property worth wanting — a test that calls a changed API cannot stay byte-identical while the API changes.

| File | Authorised change |
|---|---|
| `KnowledgeBaseDocumentOwnerScopingTest` | one Mockito stub line, 3-arg → 2-arg |
| `KnowledgeBaseVectorStoreWrapperOwnershipTest` | none |
| `KnowledgeBaseVectorStoreOwnershipBackfillIntTest` | none |
| `DataTableRowOwnerSourceGuardTest` | 2 signature-only lines |
| `DataTablePoolIsolationIntTest` | signature-only lines, plus one ADDED case and the imports and helpers it needs |
| `DataTableRowOwnerScopingIntTest` | 4 signature-only lines |
| `DataTableWebhookRowOwnerIntTest` | one axis-1 case deleted; the other four intact |
| `OwnerTypeOrdinalStabilityTest` | 2 enum cases intact; 2 re-pointed from `DataTable` to `DataTableWebhook` |

`DataTableRowOwnerSourceGuardTest` is the primary gate. It already existed when this plan was written and pins
exactly what the spec called "the test most worth having" — the ref is the only source of the owner.

Verify each file's diff against `49d732112d4..HEAD` and report any that exceeds its authorised change. Then:

```bash
./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:test \
          :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration \
          :server:libs:platform:platform-knowledge-base:platform-knowledge-base-service:test \
          :server:libs:platform:platform-knowledge-base:platform-knowledge-base-service:testIntegration \
          :server:libs:modules:components:ai:vectorstore:knowledgebase:test \
          :server:libs:modules:components:data-table:test --continue > /tmp/test-final.log 2>&1
echo "exit=$?"
grep -n '^> Task .* FAILED' /tmp/test-final.log || echo "no failed tasks"
```

Expected: `exit=0`. A failure here means row-level isolation was disturbed, which is a defect in the change and not in the test.

- [ ] **Step 4: Full client check**

```bash
cd client && npm run check
```

Expected: PASS. Tool timeout 600000 ms. Re-run `npm run format` if prettier reports drift — formatters run first in CI and targeted test runs never touch them.

- [ ] **Step 5: Record any divergence in the spec, then commit if anything changed**

If implementation diverged from the spec — a caller the spec did not anticipate, a test that turned out mixed rather than pure — amend the spec's relevant section to describe what was built, and commit. If nothing diverged, skip this step rather than inventing a commit.

```bash
git add -A docs && git commit -m "docs - Record implementation notes on the row-level ownership spec

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
