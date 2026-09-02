# Embedded Shared Connections Flag Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the host-declared `sharedConnectionIds` id list with a `shared` boolean the tenant admin ticks on the connection itself, so the entitlement is verifiable server-side and visible in the UI.

**Architecture:** One new `BOOLEAN` column on the existing `connection` table. `ConnectedUserConnectionMembership` — the single class both the connection picker and the authorization resolver route through — swaps its third entitlement source from a configuration-binding walk to a query on that column. The client surfaces the flag as an opt-in `Switch` in `ConnectionDialog`, offered only by the `/embedded/connections` admin page. `EmbeddedWorkflowBuilder` loses its prop; `AutomationHub` keeps a deprecated one.

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Data JDBC / Liquibase / JUnit 5 + Mockito + Testcontainers; React 19 / TypeScript 6 / react-hook-form / TanStack Query / Vitest.

**Spec:** `docs/superpowers/specs/2026-08-31-embedded-shared-connections-flag-design.md`

## Global Constraints

- **Enum ordinals are persisted as INT** — append new values at the end, never reorder. (No enum changes in this plan; the constraint bites if you are tempted to add a `ResourceVisibility` value instead of the boolean — the spec rejects that.)
- **EE files** (anything under `server/ee/`) use the **ByteChef Enterprise license header**, not Apache 2.0, and carry a `@version ee` Javadoc tag. Spotless picks the header by the `@version ee` **content**, not the path.
- **Java blank lines:** exactly one empty line before `if`/`for`/`while`/`switch`/`try` (except immediately after an opening `{`, and short top-of-method guard clauses), and one after a variable modification that precedes a statement using that variable. No blank line before a class's closing `}`.
- **No inline code comments explaining the change** — rationale goes in the commit message.
- **No method chaining** except the idiomatic exceptions (builders, Streams, `Optional`, query DSLs).
- **Descriptive variable names** everywhere, including lambda parameters (`connection`, not `c`).
- **Test naming:** camelCase, no underscores, in *all* test methods including private helpers. Unit tests end `Test`; integration tests end `IntTest`.
- **Client ESLint:** object keys in ascending alphabetical order (`--fix` does NOT fix this); named imports sorted alphabetically inside `{}`; interfaces end `I` or `Props`; Lucide icons imported with the `Icon` suffix; `twMerge` not `cn()`.
- **Commit messages:** server `1051 <description>`; client `1051 client - <description>`. End every commit with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **Never judge a Gradle run piped into `tail`/`grep`.** Redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`.
- **Never use bare `git stash`** — the stash stack is shared across worktrees.

---

### Task 1: Persist `shared` on the connection

**Files:**
- Create: `server/libs/platform/platform-connection/platform-connection-service/src/main/resources/config/liquibase/changelog/platform/connection/20260831000001_platform_connection_shared.xml`
- Modify: `server/libs/platform/platform-connection/platform-connection-api/src/main/java/com/bytechef/platform/connection/domain/Connection.java`
- Modify: `server/libs/platform/platform-connection/platform-connection-api/src/main/java/com/bytechef/platform/connection/dto/ConnectionDTO.java`
- Test: `server/libs/platform/platform-connection/platform-connection-service/src/test/java/com/bytechef/platform/connection/service/ConnectionServiceIntTest.java` (add a method; create the class only if absent)

**Interfaces:**
- Consumes: nothing.
- Produces: `Connection#isShared()` / `Connection#setShared(boolean)`, `Connection.Builder#shared(boolean)`, and a `shared` component on the `ConnectionDTO` record (position: after `visibility`, before `managed`).

- [ ] **Step 1: Write the failing test**

Add to `ConnectionServiceIntTest`:

```java
@Test
void testCreateAndReadSharedConnection() {
    Connection savedConnection = connectionService.create(
        embeddedConnection("House Slack", Environment.PRODUCTION, true));

    Connection fetchedConnection = connectionService.getConnection(savedConnection.getId());

    Assertions.assertTrue(fetchedConnection.isShared());
}

@Test
void testConnectionIsNotSharedByDefault() {
    Connection savedConnection = connectionService.create(
        embeddedConnection("Personal Slack", Environment.PRODUCTION, false));

    Assertions.assertFalse(savedConnection.isShared());
}

private static Connection embeddedConnection(String name, Environment environment, boolean shared) {
    Connection connection = new Connection();

    connection.setComponentName("slack");
    connection.setConnectionVersion(1);
    connection.setEnvironmentId(environment.ordinal());
    connection.setName(name);
    connection.setShared(shared);
    connection.setType(PlatformType.EMBEDDED);

    return connection;
}
```

`Connection.Builder` has no `environmentId` method and this plan does not add one — a builder method
no production path needs is scope creep. The fixture uses `new Connection()` plus setters, matching
this file's own existing style.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:libs:platform:platform-connection:platform-connection-service:testIntegration --tests '*ConnectionServiceIntTest*' > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|error:' /tmp/t1.log | head
```

Expected: FAIL — `cannot find symbol: method shared(boolean)`.

- [ ] **Step 3: Add the Liquibase changeset**

Create the changelog file. The directory is pulled in by `<includeAll>` in `config/liquibase/master.xml`, so no master edit is needed.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <changeSet id="20260831000001-01" author="Ivica Cardic">
        <addColumn tableName="connection">
            <column name="shared" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
        </addColumn>

        <comment>Embedded connections only. TRUE means every connected user in the same environment
            may use this connection; it stays outside the OWNED set, so an end user still cannot
            delete or reauthorize it.</comment>

        <rollback>
            <dropColumn tableName="connection" columnName="shared"/>
        </rollback>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 4: Add the domain field**

In `Connection.java`, add the field alphabetically among the `@Column` fields (after `parameters`, before `status`):

```java
    @Column
    private boolean shared;
```

Add the accessors beside the other getters/setters, and a builder method mirroring `Builder#visibility`:

```java
    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean shared) {
        this.shared = shared;
    }
```

In the `Builder`, add `private boolean shared;`, the fluent method, and the assignment in `build()`:

```java
        public Builder shared(boolean shared) {
            this.shared = shared;

            return this;
        }
```

```java
            connection.setShared(shared);
```

- [ ] **Step 5: Add `shared` to `ConnectionDTO`**

Add the component to the record header after `ResourceVisibility visibility` and before `boolean managed`, then thread it through the convenience constructor (`connection.isShared()`), `toConnection()` (`connection.setShared(shared)`), and the `Builder`.

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew :server:libs:platform:platform-connection:platform-connection-service:testIntegration --tests '*ConnectionServiceIntTest*' > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t1.log
```

Expected: exit=0, no FAILED lines. Testcontainers builds the schema from scratch, which also proves the changeset applies.

- [ ] **Step 7: Compile every consumer of the DTO**

Adding a record component breaks every positional constructor call.

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t1c.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t1c.log
```

Fix each break by passing `false` (or the connection's own value where one is in scope).

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply > /tmp/t1s.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-connection
git commit -m "$(cat <<'EOF'
1051 Add a shared flag to the connection

Embedded connections only: TRUE means every connected user in the same
environment may use this connection. It deliberately stays outside the OWNED
set, so an end user still cannot delete or reauthorize a shared connection.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Read the shared connections of an environment

**Files:**
- Modify: `server/libs/platform/platform-connection/platform-connection-service/src/main/java/com/bytechef/platform/connection/repository/ConnectionRepository.java`
- Modify: `server/libs/platform/platform-connection/platform-connection-api/src/main/java/com/bytechef/platform/connection/service/ConnectionService.java`
- Modify: `server/libs/platform/platform-connection/platform-connection-service/src/main/java/com/bytechef/platform/connection/service/ConnectionServiceImpl.java`
- Test: `server/libs/platform/platform-connection/platform-connection-service/src/test/java/com/bytechef/platform/connection/service/ConnectionServiceIntTest.java`

**Interfaces:**
- Consumes: `Connection#isShared()` (Task 1).
- Produces: `List<Connection> ConnectionService.getSharedConnections(Environment environment, PlatformType type)` — used by Task 3.

- [ ] **Step 1: Write the failing test**

Reuse the `embeddedConnection(name, environment, shared)` helper Task 1 added to this file, and add
an `automationConnection(name, environment, shared)` beside it that differs only in
`setType(PlatformType.AUTOMATION)`.

```java
@Test
void testGetSharedConnectionsIsScopedByEnvironmentAndType() {
    connectionService.create(embeddedConnection("Shared production", Environment.PRODUCTION, true));
    connectionService.create(embeddedConnection("Shared development", Environment.DEVELOPMENT, true));
    connectionService.create(embeddedConnection("Not shared", Environment.PRODUCTION, false));
    connectionService.create(automationConnection("Shared automation", Environment.PRODUCTION, true));

    List<Connection> sharedConnections = connectionService.getSharedConnections(
        Environment.PRODUCTION, PlatformType.EMBEDDED);

    Assertions.assertEquals(1, sharedConnections.size());

    Connection sharedConnection = sharedConnections.getFirst();

    Assertions.assertEquals("Shared production", sharedConnection.getName());
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:libs:platform:platform-connection:platform-connection-service:testIntegration --tests '*ConnectionServiceIntTest*' > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|error:' /tmp/t2.log | head
```

Expected: FAIL — `cannot find symbol: method getSharedConnections`.

- [ ] **Step 3: Add the repository method**

In `ConnectionRepository`, beside `findAllByVisibilityAndTypeOrderByName`:

```java
    List<Connection> findAllBySharedIsTrueAndEnvironmentAndTypeOrderByName(int environment, int type);
```

- [ ] **Step 4: Add the service method**

In `ConnectionService`, beside `getConnectionsByVisibility`:

```java
    List<Connection> getSharedConnections(Environment environment, PlatformType type);
```

In `ConnectionServiceImpl`, mirroring `getConnectionsByVisibility` exactly:

```java
    @Override
    @Transactional(readOnly = true)
    public List<Connection> getSharedConnections(Environment environment, PlatformType type) {
        return connectionRepository.findAllBySharedIsTrueAndEnvironmentAndTypeOrderByName(
            environment.ordinal(), type.ordinal());
    }
```

`Environment` is `com.bytechef.platform.configuration.domain.Environment`; there is no environment table, so the ordinal *is* the stored `environmentId`.

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :server:libs:platform:platform-connection:platform-connection-service:testIntegration --tests '*ConnectionServiceIntTest*' > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t2.log
```

Expected: exit=0, no FAILED lines.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply > /tmp/t2s.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-connection
git commit -m "$(cat <<'EOF'
1051 Read the shared connections of an environment

Scoped by environment and platform type, mirroring getConnectionsByVisibility.
There is no environment table, so the Environment ordinal is the stored
environmentId.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Make `shared` the third entitlement source

This is the load-bearing task: `ConnectedUserConnectionMembership` is the single definition both the connection picker and `ConnectedUserResourceMembershipResolver` route through, so this one edit moves both.

**Files:**
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/security/ConnectedUserConnectionMembership.java`
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserConnectionFacadeImpl.java` (Javadoc only)
- Test: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/security/ConnectedUserConnectionEntitlementParityTest.java`
- Test: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserConnectionFacadeTest.java`

**Interfaces:**
- Consumes: `ConnectionService#getSharedConnections(int environmentId, PlatformType type)` (Task 2).
  **Note the `int`.** Task 2 could not take an `Environment`: `platform-configuration-api`, which owns
  that enum, depends on `platform-connection-api` and not the reverse. Pass `environment.ordinal()` —
  there is no environment table, so the ordinal is the stored column value.
- Produces: unchanged public signatures — `getConnectionIds(long, Environment)` and `getOwnedConnectionIds(long, Environment)`. Only the *contents* of the entitled set change.

- [ ] **Step 1: Write the failing test**

There is **no** `ConnectedUserConnectionMembershipTest`. The real file is
`ConnectedUserConnectionEntitlementParityTest`, and Task 3 breaks it by construction: it stubs
`IntegrationInstanceConfigurationWorkflowService` and its
`testTheAgreedSetIsTheUnionOfAllThreeSources` asserts the three-source union including the
configuration-binding walk.

Rewrite that one test so source 3 is the shared flag. Its two siblings —
`testThePickerAndTheResolverAgreeOnEveryConnectionId` and
`testTheListedSetIsReadableButOnlyTheOwnedSubsetIsMutable` — must keep passing **unchanged**: they
are the invariants this whole design leans on, and a change to either is a signal you have broken
something, not a test to update.

Then add these three. The first is the case the old derivation could not express and is the reason
this work exists.

```java
@Test
void testSharedConnectionReachesConnectedUserWithNoIntegrationInstance() {
    Mockito.when(integrationInstanceService.getConnectedUserIntegrationInstances(1L, Environment.PRODUCTION))
        .thenReturn(List.of());
    Mockito.when(connectedUserConnectionService.getConnectionIds(1L))
        .thenReturn(List.of());
    Mockito.when(connectionService.getSharedConnections(
        Environment.PRODUCTION.ordinal(), PlatformType.EMBEDDED))
        .thenReturn(List.of(connection(50L)));

    Set<Long> connectionIds = connectedUserConnectionMembership.getConnectionIds(1L, Environment.PRODUCTION);

    Assertions.assertEquals(Set.of(50L), connectionIds);
}

@Test
void testSharedConnectionIsNotOwned() {
    Mockito.when(integrationInstanceService.getConnectedUserIntegrationInstances(1L, Environment.PRODUCTION))
        .thenReturn(List.of());
    Mockito.when(connectedUserConnectionService.getConnectionIds(1L))
        .thenReturn(List.of(7L));

    Set<Long> ownedConnectionIds = connectedUserConnectionMembership.getOwnedConnectionIds(
        1L, Environment.PRODUCTION);

    Assertions.assertEquals(Set.of(7L), ownedConnectionIds);

    Mockito.verify(connectionService, Mockito.never())
        .getSharedConnections(Mockito.any(), Mockito.any());
}

@Test
void testOwnedAndSharedAreUnioned() {
    Mockito.when(integrationInstanceService.getConnectedUserIntegrationInstances(1L, Environment.PRODUCTION))
        .thenReturn(List.of(integrationInstance(3L)));
    Mockito.when(connectedUserConnectionService.getConnectionIds(1L))
        .thenReturn(List.of(7L));
    Mockito.when(connectionService.getSharedConnections(
        Environment.PRODUCTION.ordinal(), PlatformType.EMBEDDED))
        .thenReturn(List.of(connection(50L)));

    Set<Long> connectionIds = connectedUserConnectionMembership.getConnectionIds(1L, Environment.PRODUCTION);

    Assertions.assertEquals(Set.of(3L, 7L, 50L), connectionIds);
}

private static Connection connection(long id) {
    Connection connection = new Connection();

    connection.setId(id);

    return connection;
}

private static IntegrationInstance integrationInstance(long connectionId) {
    IntegrationInstance integrationInstance = new IntegrationInstance();

    integrationInstance.setConnectionId(connectionId);

    return integrationInstance;
}
```

Declare `@Mock private ConnectionService connectionService;` and drop the
`IntegrationInstanceConfigurationWorkflowService` mock. Follow the file's existing conventions —
it uses AssertJ (`assertThat`) with statically imported Mockito, not `Assertions.assertEquals`
with qualified `Mockito.` calls; match what is already there rather than the sketch above.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:test --tests '*ConnectedUserConnectionEntitlementParityTest*' > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|error:' /tmp/t3.log | head
```

Expected: FAIL — the constructor still takes `IntegrationInstanceConfigurationWorkflowService`.

- [ ] **Step 3: Swap the third source**

In `ConnectedUserConnectionMembership`: replace the `IntegrationInstanceConfigurationWorkflowService` constructor parameter and field with `ConnectionService connectionService`, delete `getIntegrationInstanceConfigurationConnectionIds` entirely, and change `getConnectionIds`:

```java
    @Transactional(readOnly = true)
    public Set<Long> getConnectionIds(long connectedUserId, Environment environment) {
        List<IntegrationInstance> integrationInstances =
            integrationInstanceService.getConnectedUserIntegrationInstances(connectedUserId, environment);

        Set<Long> connectionIds = getOwnedConnectionIds(connectedUserId, integrationInstances);

        for (Connection connection : connectionService.getSharedConnections(
            environment.ordinal(), PlatformType.EMBEDDED)) {
            connectionIds.add(connection.getId());
        }

        return connectionIds;
    }
```

Remove the now-unused imports (`IntegrationInstanceConfigurationWorkflow`, `IntegrationInstanceConfigurationWorkflowConnection`, `IntegrationInstanceConfigurationWorkflowService`, `Objects`).

- [ ] **Step 4: Rewrite the class Javadoc**

The existing Javadoc argues at length for the derivation being deleted; leaving it makes the class lie. Replace the "Three sources" list's third item and the two paragraphs that follow it ("The third source is what a shared connection is in this data model…" and "The third source starts from THIS caller's instances…") with:

```
 * <li>the connections a tenant admin marked {@code shared}, in this environment.</li>
 * </ol>
 *
 * <p>
 * The third source replaces both the host's {@code sharedConnectionIds} request parameter and the
 * configuration-binding derivation that briefly stood in for it. The parameter was a caller assertion the server
 * could not verify and is now ignored; the derivation could not express a connection no configuration binds -- a
 * vendor's house connection -- and left sharing inferred rather than stated. A column on the row is both verifiable
 * and legible to the admin who sets it, and unticking it revokes the entitlement on the next request.
 *
 * <p>
 * Source 3 is deliberately absent from {@link #getOwnedConnectionIds(long, Environment)}. A shared connection is
 * entitled to every connected user in the environment, so deleting or reauthorizing it through one end user's
 * credentials would act on all of them at once.
```

Keep the environment-axis paragraph but rewrite it: source 3 now applies the environment filter directly in its own query rather than inheriting it from source 1.

- [ ] **Step 5: Update the facade Javadoc**

In `ConnectedUserConnectionFacadeImpl`, the `getConnections` Javadoc describes sharing as "the connections bound at the configuration level". Rewrite that sentence to name the `shared` flag. Keep the `connectionIds`-is-ignored paragraph and the `logUnentitledRequestedConnectionIds` Javadoc — both are still accurate (spec decision 5).

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:test --tests '*ConnectedUserConnection*' > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t3.log
```

Expected: exit=0. Fix `ConnectedUserConnectionFacadeTest` where it stubs the deleted derivation.

- [ ] **Step 7: Check no other module wired the deleted collaborator**

```bash
grep -rn "ConnectedUserConnectionMembership(" --include="*.java" server/ | grep -v "/security/ConnectedUserConnectionMembership.java"
./gradlew compileJava compileTestJava --continue > /tmp/t3c.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t3c.log
```

Any `*IntTestConfiguration` or `@TestConfiguration` that hand-assembles this bean needs a `ConnectionService` mock added.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply > /tmp/t3s.log 2>&1; echo "exit=$?"
git add server/ee/libs/embedded/embedded-configuration
git commit -m "$(cat <<'EOF'
1051 Entitle connected users to connections marked shared

Replaces the configuration-binding derivation, which could not express a
connection no configuration binds -- a vendor's house connection offered to
users building their own workflows -- and left sharing inferred rather than
stated.

Source 3 stays out of the OWNED set, so requireOwned keeps refusing an end
user who tries to delete or reauthorize a shared connection. Because the
picker and ConnectedUserResourceMembershipResolver both route through this
class, they cannot drift.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Refuse `shared` from a connected user

The client gate in Task 6 is UX. This is the security boundary: a connected user marking their own connection shared would hand their credentials to every other connected user in the environment.

**Files:**
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserConnectionFacadeImpl.java`
- Modify: `server/libs/platform/platform-connection/platform-connection-api/src/main/java/com/bytechef/platform/connection/dto/ConnectionDTO.java` (add the Builder copy constructor)
- Test: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserConnectionFacadeTest.java`

**Interfaces:**
- Consumes: `ConnectionDTO#shared` (Task 1).
- Produces: no signature change to `createConnectedUserConnection(long, ConnectionDTO)`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void testCreateConnectedUserConnectionForcesSharedFalse() {
    ConnectionDTO connectionDTO = ConnectionDTO.builder()
        .componentName("slack")
        .name("My Slack")
        .shared(true)
        .build();

    Mockito.when(connectionFacade.create(Mockito.any(ConnectionDTO.class), Mockito.eq(PlatformType.EMBEDDED)))
        .thenReturn(42L);

    connectedUserConnectionFacade.createConnectedUserConnection(1L, connectionDTO);

    ArgumentCaptor<ConnectionDTO> connectionDTOArgumentCaptor = ArgumentCaptor.forClass(ConnectionDTO.class);

    Mockito.verify(connectionFacade)
        .create(connectionDTOArgumentCaptor.capture(), Mockito.eq(PlatformType.EMBEDDED));

    ConnectionDTO capturedConnectionDTO = connectionDTOArgumentCaptor.getValue();

    Assertions.assertFalse(capturedConnectionDTO.shared());
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:test --tests '*ConnectedUserConnectionFacadeTest*' > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t4.log
```

Expected: FAIL — `expected: <false> but was: <true>`.

- [ ] **Step 3: Force the flag off**

```java
    @Override
    public long createConnectedUserConnection(long connectedUserId, ConnectionDTO connectionDTO) {
        ConnectionDTO unsharedConnectionDTO = ConnectionDTO.builder(connectionDTO)
            .shared(false)
            .build();

        long connectionId = connectionFacade.create(unsharedConnectionDTO, PlatformType.EMBEDDED);

        connectedUserConnectionService.create(connectedUserId, connectionId);

        return connectionId;
    }
```

`ConnectionDTO.Builder` has **no** copy constructor today, so add one first:
`public static Builder builder(ConnectionDTO connectionDTO)`, seeding every component from the
argument. Open-coding a 20-argument record construction at this call site would rot on the next
component added — which is precisely what Task 1 demonstrated by breaking every positional caller.

Add a Javadoc line on the method naming the boundary — this is the one place a comment is warranted, because it is a security invariant rather than a description of the change:

```java
    /**
     * Forces {@code shared} off regardless of the request body. A connected user who could mark their own connection
     * shared would hand their credentials to every other connected user in the environment; the flag is settable only
     * from the tenant admin surface.
     */
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:test --tests '*ConnectedUserConnectionFacadeTest*' > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t4.log
```

Expected: exit=0.

- [ ] **Step 5: Write the two remaining boundary tests**

The spec names both; neither is implied by the tests above.

A shared connection is entitled but not owned, so the mutating paths must still refuse it — this is
the assertion that keeps entitlement and ownership from quietly collapsing into one set:

```java
@Test
void testDeleteConnectedUserConnectionRefusesSharedConnection() {
    Mockito.when(connectedUserService.getConnectedUser(1L))
        .thenReturn(connectedUser(1L, Environment.PRODUCTION));
    Mockito.when(connectedUserConnectionMembership.getOwnedConnectionIds(1L, Environment.PRODUCTION))
        .thenReturn(Set.of());

    Assertions.assertThrows(
        NoSuchElementException.class,
        () -> connectedUserConnectionFacade.deleteConnectedUserConnection(1L, 50L));

    Mockito.verify(connectionFacade, Mockito.never())
        .delete(Mockito.anyLong());
}

@Test
void testReauthorizeConnectedUserConnectionRefusesSharedConnection() {
    Mockito.when(connectedUserService.getConnectedUser(1L))
        .thenReturn(connectedUser(1L, Environment.PRODUCTION));
    Mockito.when(connectedUserConnectionMembership.getOwnedConnectionIds(1L, Environment.PRODUCTION))
        .thenReturn(Set.of());

    Assertions.assertThrows(
        NoSuchElementException.class,
        () -> connectedUserConnectionFacade.reauthorizeConnectedUserConnection(1L, 50L, Map.of()));

    Mockito.verify(connectionFacade, Mockito.never())
        .replaceAuthorizationParameters(Mockito.anyLong(), Mockito.anyMap());
}
```

And the `connectionIds` parameter must grant nothing, which is the regression test for the original
defect — a host declaring an id it was never given:

```java
@Test
void testGetConnectionsIgnoresRequestedConnectionIds() {
    Mockito.when(connectedUserService.getConnectedUser(1L))
        .thenReturn(connectedUser(1L, Environment.PRODUCTION));
    Mockito.when(connectedUserConnectionMembership.getConnectionIds(1L, Environment.PRODUCTION))
        .thenReturn(Set.of(7L));
    Mockito.when(connectionFacade.getConnections(List.of(7L), PlatformType.EMBEDDED))
        .thenReturn(List.of());

    connectedUserConnectionFacade.getConnections(1L, null, List.of(999L));

    Mockito.verify(connectionFacade)
        .getConnections(List.of(7L), PlatformType.EMBEDDED);
}
```

Reuse the file's existing `connectedUser(...)` helper if it has one; otherwise add it as a private
static factory with a camelCase name.

- [ ] **Step 6: Run the tests**

```bash
./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:test --tests '*ConnectedUserConnectionFacadeTest*' > /tmp/t4b.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t4b.log
```

Expected: exit=0. These three should pass without production changes — they pin behaviour Task 3
already established. If any fails, that is a real finding, not a test bug.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply > /tmp/t4s.log 2>&1; echo "exit=$?"
git add server/ee/libs/embedded/embedded-configuration
git commit -m "$(cat <<'EOF'
1051 Refuse the shared flag on a connected user's own connection

A connected user who could mark their own connection shared would hand their
credentials to every other connected user in the environment. The flag is
settable only from the tenant admin surface; the client gate is UX, this is
the boundary.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Accept `shared` on the admin REST surface

**Files:**
- Modify: `server/libs/platform/platform-connection/platform-connection-rest/openapi/components/schemas/objects/connection_base.yaml`
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-rest/embedded-configuration-rest-impl/openapi.yaml` (the `UpdateConnectionRequest` schema)
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-rest/embedded-configuration-rest-impl/src/main/java/com/bytechef/ee/embedded/configuration/web/rest/ConnectionApiController.java`
- Modify: `server/libs/platform/platform-connection/platform-connection-api/src/main/java/com/bytechef/platform/connection/facade/ConnectionFacade.java`
- Modify: `server/libs/platform/platform-connection/platform-connection-service/src/main/java/com/bytechef/platform/connection/facade/ConnectionFacadeImpl.java`
- Modify: `server/libs/platform/platform-connection/platform-connection-api/src/main/java/com/bytechef/platform/connection/service/ConnectionService.java`
- Modify: `server/libs/platform/platform-connection/platform-connection-service/src/main/java/com/bytechef/platform/connection/service/ConnectionServiceImpl.java`
- Test: `server/libs/platform/platform-connection/platform-connection-service/src/test/java/com/bytechef/platform/connection/service/ConnectionServiceIntTest.java`

**Interfaces:**
- Consumes: `Connection#setShared` (Task 1).
- Produces: `ConnectionService#update(long id, String name, List<Long> tagIds, boolean shared, int version)` and `ConnectionFacade#update(long id, String name, List<Tag> tags, boolean shared, int version)`. Both are **overloads** — the four-argument forms stay for the automation surface.

One write, one version bump. A separate `updateShared` call alongside `update` would bump the version twice and make the second write fail optimistic locking.

- [ ] **Step 1: Write the failing test**

```java
@Test
void testUpdateTogglesShared() {
    Connection savedConnection = connectionService.create(
        embeddedConnection("House Slack", Environment.PRODUCTION, false));

    Connection sharedConnection = connectionService.update(
        savedConnection.getId(), "House Slack", List.of(), true, savedConnection.getVersion());

    Assertions.assertTrue(sharedConnection.isShared());

    Connection unsharedConnection = connectionService.update(
        sharedConnection.getId(), "House Slack", List.of(), false, sharedConnection.getVersion());

    Assertions.assertFalse(unsharedConnection.isShared());
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:libs:platform:platform-connection:platform-connection-service:testIntegration --tests '*ConnectionServiceIntTest*' > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|error:' /tmp/t5.log | head
```

Expected: FAIL — no five-argument `update`.

- [ ] **Step 3: Add the service overload**

In `ConnectionServiceImpl`, make the existing four-argument `update` delegate so the body is not duplicated:

```java
    @Override
    public Connection update(long id, String name, List<Long> tagIds, int version) {
        return updateNameTagsAndShared(id, name, tagIds, null, version);
    }

    @Override
    public Connection update(long id, String name, List<Long> tagIds, boolean shared, int version) {
        return updateNameTagsAndShared(id, name, tagIds, shared, version);
    }

    private Connection updateNameTagsAndShared(
        long id, String name, List<Long> tagIds, @Nullable Boolean shared, int version) {
        rejectIfAiProviderConnection(id);

        Connection curConnection = getConnection(id);

        validateOwnerOrAdmin(curConnection);

        if (name != null) {
            curConnection.setName(name);
        }

        if (tagIds != null) {
            curConnection.setTagIds(tagIds);
        }

        if (shared != null) {
            curConnection.setShared(shared);
        }

        curConnection.setVersion(version);

        if (curConnection.getCredentialStoreType() != CredentialStoreType.DATABASE) {
            curConnection.setParameters(Map.of());
        }

        return connectionRepository.save(curConnection);
    }
```

Keep the existing trailing comment about clearing populated parameters — it explains pre-existing behaviour, not this change.

The private helper is deliberately **not** a third `update` overload. `update(…, boolean, …)` and
`update(…, Boolean, …)` differ only by boxing, and which one a call resolves to would then depend on
whether the argument happens to be primitive — a trap for the next editor. Declare both public
overloads in `ConnectionService`.

- [ ] **Step 4: Add the facade overload**

In `ConnectionFacade` add `void update(long id, String name, List<Tag> tags, boolean shared, int version);` and implement it in `ConnectionFacadeImpl` exactly as the four-argument form, passing `shared` through.

- [ ] **Step 5: Add `shared` to the OpenAPI schemas**

In `connection_base.yaml`, alphabetically between `parameters` and `tags`:

```yaml
  shared:
    description: "Embedded only. When true, every connected user in the same environment may use\
            \ this connection. Ignored on the automation surface, and always forced to false when a\
            \ connected user creates a connection for themselves."
    type: "boolean"
    default: false
```

In the embedded `openapi.yaml`, add to `UpdateConnectionRequest.properties`:

```yaml
        shared:
          description: "Whether every connected user in the same environment may use this connection."
          type: "boolean"
```

- [ ] **Step 6: Wire the controller**

In the embedded admin `ConnectionApiController.updateConnection`, call the five-argument facade overload:

```java
        connectionFacade.update(
            id, updateConnectionRequestModel.getName(), list,
            Boolean.TRUE.equals(updateConnectionRequestModel.getShared()),
            Objects.requireNonNull(updateConnectionRequestModel.getVersion()));
```

`createConnection` needs no change — `shared` rides the `ConnectionModel` into `ConnectionDTO`. Verify the generated `ConnectionModel` carries it after regeneration.

- [ ] **Step 7: Run tests to verify they pass**

```bash
./gradlew :server:libs:platform:platform-connection:platform-connection-service:testIntegration --tests '*ConnectionServiceIntTest*' > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t5.log
./gradlew compileJava compileTestJava --continue > /tmp/t5c.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t5c.log
```

Expected: exit=0 on both.

- [ ] **Step 8: Regenerate the client API models**

```bash
cd client && npx @openapitools/openapi-generator-cli version > /dev/null 2>&1
```

The embedded configuration models under `client/src/ee/shared/middleware/embedded/configuration/models/` are generated. Regenerate with the project's own task and confirm `Connection.ts` and `UpdateConnectionRequest.ts` gained `shared`. If the generator is not wired for this module, hand-edit `Connection.ts`, `UpdateConnectionRequest.ts` and their `docs/*.md` to match the schema exactly — including the `FromJSON`/`ToJSON` mappers, which is where a hand edit is most often left incomplete.

- [ ] **Step 9: Format and commit**

```bash
cd .. && ./gradlew spotlessApply > /tmp/t5s.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-connection server/ee/libs/embedded/embedded-configuration client/src/ee/shared/middleware
git commit -m "$(cat <<'EOF'
1051 Accept the shared flag on the embedded admin connection API

One update overload rather than a separate updateShared call: two writes would
bump the version twice and the second would fail optimistic locking.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Offer the Shared Connection switch in `ConnectionDialog`

**Files:**
- Modify: `client/src/shared/components/connection/ConnectionDialog.tsx`
- Test: `client/src/shared/components/connection/ConnectionDialog.test.tsx`

**Interfaces:**
- Consumes: the `shared` field on the generated `Connection` model (Task 5).
- Produces: `ConnectionDialogProps.showSharedOption?: boolean` and `ConnectionDialogFormProps.shared: boolean` — Task 7 passes the former.

Opt-in rather than derived from `currentType`: `usePlatformTypeStore` is `persist`-ed to localStorage on `bytechef.mode-type`, and the builder iframe is same-origin with the admin app, so **both** the admin page and the connected-user builder read `EMBEDDED`. Gating on it would put the switch in front of end users.

- [ ] **Step 1: Write the failing test**

```tsx
it('does not render the shared switch by default', () => {
    renderConnectionDialog({});

    expect(screen.queryByLabelText('Shared Connection')).not.toBeInTheDocument();
});

it('renders the shared switch when showSharedOption is set', () => {
    renderConnectionDialog({showSharedOption: true});

    expect(screen.getByLabelText('Shared Connection')).toBeInTheDocument();
});

it('renders the shared switch when editing an existing connection', () => {
    renderConnectionDialog({connection: {id: 5, name: 'House Slack', version: 1}, showSharedOption: true});

    expect(screen.getByLabelText('Shared Connection')).toBeInTheDocument();
});
```

Follow the file's existing render helper and mock setup rather than inventing new ones.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/shared/components/connection/ConnectionDialog.test.tsx
```

Expected: FAIL — the switch is never rendered.

- [ ] **Step 3: Add the prop and the form field**

Add to `ConnectionDialogProps`, alphabetically before `showOrganizationOption`, with the doc comment explaining the opt-in:

```tsx
    /**
     * Offers the "Shared Connection" switch. Opt-in because the obvious gate does not work:
     * `usePlatformTypeStore` is persisted to localStorage and the builder iframe is same-origin with
     * the admin app, so the connected-user builder also reads EMBEDDED. Only the `/embedded/connections`
     * admin page may pass this -- a connected user marking their own connection shared would hand
     * their credentials to every other connected user in the environment.
     */
    showSharedOption?: boolean;
```

Add `shared: boolean;` to `ConnectionDialogFormProps` (alphabetically, after `selectedScopes`), destructure `showSharedOption` in the component signature, and add `shared: connection?.shared ?? false,` to `defaultValues` (alphabetically, after `registeringExisting`).

- [ ] **Step 4: Render the switch**

Place it immediately after the visibility picker block, so create-mode shows visibility then sharing. Follow the `registeringExisting` FormField pattern already in the file:

```tsx
{showSharedOption && (
    <FormField
        control={control}
        name="shared"
        render={({field}) => (
            <FormItem>
                <FormControl>
                    <Switch
                        checked={field.value}
                        description="Every connected user in this environment will be able to use this connection."
                        label="Shared Connection"
                        onCheckedChange={field.onChange}
                    />
                </FormControl>
            </FormItem>
        )}
    />
)}
```

- [ ] **Step 5: Send it on create and on edit**

In `getNewConnection`, add `shared` to the destructure and to the returned object gated on the prop:

```tsx
        const {componentName, name, parameters, shared, tags, visibility} = getValues();
```

```tsx
            ...(showSharedOption ? {shared} : {}),
            ...(visibilityFeatureEnabled ? {visibility} : {}),
```

In `saveConnection`'s `connection?.id` branch — the edit path, which spec decision 3 requires to carry the flag:

```tsx
        if (connection?.id) {
            const {name, shared, tags} = getValues();

            connectionMutation.mutate({
                id: connection?.id,
                name,
                tags,
                version: connection.version,
                ...(showSharedOption ? {shared} : {}),
            } as ConnectionI);
        }
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd client && npx vitest run src/shared/components/connection/ConnectionDialog.test.tsx
```

Expected: all pass.

- [ ] **Step 7: Check and commit**

```bash
cd client && npm run check
```

Set the tool timeout to 600000 — `npm run check` is auto-backgrounded at 120s. Fix sort-keys manually; `--fix` does not handle them.

```bash
git add client/src/shared/components/connection
git commit -m "$(cat <<'EOF'
1051 client - Offer a Shared Connection switch in ConnectionDialog

Opt-in via showSharedOption rather than derived from usePlatformTypeStore:
that store is persisted to localStorage and the builder iframe is same-origin
with the admin app, so the connected-user builder also reads EMBEDDED and
would show the switch to end users.

Rendered on edit as well as create, unlike the visibility picker beside it --
un-sharing a connection shared by mistake must not require deleting the
credentials and starting over.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Surface sharing on `/embedded/connections`

**Files:**
- Modify: `client/src/ee/pages/embedded/connections/Connections.tsx`
- Modify: `client/src/ee/pages/embedded/connections/components/connection-list/ConnectionListItem.tsx`

**Interfaces:**
- Consumes: `showSharedOption` (Task 6), `Connection.shared` (Task 5).
- Produces: nothing consumed downstream.

- [ ] **Step 1: Pass the prop at both call sites**

`Connections.tsx` renders `ConnectionDialog` twice — the header and the empty state. Add `showSharedOption` to both, keeping props alphabetical:

```tsx
                                showSharedOption
                                triggerNode={<Button label="New Connection" />}
```

No other `ConnectionDialog` call site gets it — not `HubConnectionDialog`, not `AiHubConnectConnectionDialog`, not the workflow-editor pickers, not the automation connections page.

- [ ] **Step 2: Add the Shared badge**

In `ConnectionListItem.tsx`, inside the existing badge row (`<div className="flex min-h-8 flex-wrap items-center justify-end gap-2">`), add:

```tsx
{connection.shared && <Badge label="Shared" variant="secondary" />}
```

Match the `variant` and prop shape of the badges already in that row rather than introducing a new style.

- [ ] **Step 3: Verify in the browser**

Start the app, go to `/embedded/connections`, create a connection with the switch on, confirm the badge appears in the list and that reopening the connection shows the switch still on. Then confirm the switch is **absent** in the workflow builder's connection dialog.

- [ ] **Step 4: Check and commit**

```bash
cd client && npm run check
```

```bash
git add client/src/ee/pages/embedded/connections
git commit -m "$(cat <<'EOF'
1051 client - Let admins mark a connection shared on the embedded connections page

Both ConnectionDialog call sites on this page opt into the switch; no other
call site does, so the connected-user surfaces never offer it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Remove the SDK prop and the builder relay

**Files:**
- Modify: `sdks/frontend/embedded/library/src/components/embedded-workflow-builder/EmbeddedWorkflowBuilder.tsx`
- Modify: `sdks/frontend/embedded/library/src/components/automation-hub/AutomationHub.tsx`
- Modify: `client/src/ee/pages/embedded/workflow-builder/hooks/useWorkflowBuilder.ts`
- Modify: `client/src/ee/pages/embedded/workflow-builder/WorkflowBuilder.tsx`
- Modify: `client/src/ee/shared/queries/embedded/connections.queries.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `getConnectedUserConnectionsQuery(connectedUserId: number)` — the `connectionIds` second parameter is **removed**.

`WorkflowBuilder.tsx` is the only caller of `getConnectedUserConnectionsQuery` in the client; the hub reaches the same endpoint through its own `useGetComponentConnectionsQuery` in `automationHub.queries.ts`. So the builder relay and the hub relay are already disjoint and this task cannot disturb the hub.

- [ ] **Step 1: Remove the prop from `EmbeddedWorkflowBuilder`**

Delete the `sharedConnectionIds: number[];` property and its doc block, the destructured parameter, and both `propsRef` occurrences. In the `connectionDialogAllowed` doc block, replace the sentence naming the prop:

```
     * When false, users can only use existing connections. Those existing connections can be
     * either connections a tenant admin marked as shared inside ByteChef's '/embedded/connections'
     * page or integration connections created via `ConnectDialog`.
```

- [ ] **Step 2: Deprecate the prop on `AutomationHub`**

Keep the prop; mark it and say plainly that it no longer does anything, so a host reading only the type does not assume otherwise:

```tsx
    /**
     * @deprecated No longer has any effect. The server derives shared connections from the
     * `shared` flag a tenant admin sets on the connection itself at '/embedded/connections';
     * ids sent here are ignored. Will be removed in a future release.
     * @default []
     */
    sharedConnectionIds?: number[];
```

Rewrite the two other doc blocks that explain themselves in terms of the prop — `tabs.connections` ("Connections shared by the vendor through `sharedConnectionIds` are deliberately NOT listed here…") and `connectionDialogAllowed` — to describe the flag instead. Leave the runtime relay wired: it still sends the ids, the server still ignores and logs them.

- [ ] **Step 3: Strip the builder relay**

In `useWorkflowBuilder.ts`: delete the `sharedConnectionIds` `useState`, the `setSharedConnectionIds` call inside `useEmbedHandshake`, the `setSharedConnectionIds(hubContext.sharedConnectionIds)` line in the `hubContext` effect, and the key in the returned object.

In `WorkflowBuilder.tsx`: remove `sharedConnectionIds` from the `useWorkflowBuilder()` destructure and simplify the provider value:

```tsx
                                useGetConnectionsQuery: getConnectedUserConnectionsQuery(
                                    connectedUserProjectWorkflow.connectedUserId!
                                ),
```

In `connections.queries.ts`: drop the second parameter and the `connectionIds` key it fed.

Leave `EmbedInitParamsI.sharedConnectionIds` in `useEmbedHandshake.ts`, and leave `hubBuilderContext.ts`, `HubBuilderView.tsx`, `useAutomationHubStore.ts` and `automationHub.queries.ts` untouched — the hub still sends the field.

- [ ] **Step 4: Update the affected tests**

`HubBuilderView.test.tsx` asserts the builder probe renders `hubContext?.sharedConnectionIds.join(',')`. The context field survives, so that test still compiles — confirm it still passes. Remove any builder test that asserts the id list reaches the query. The hub tests seeding `sharedConnectionIds` all stay.

- [ ] **Step 5: Run the checks**

```bash
cd client && npm run check
```

```bash
cd sdks/frontend/embedded/library && npm run lint && npm run test -- --run
```

If `node_modules` looks stale after a rebase, run `npm install` first — stale modules fail in a way that looks exactly like a botched rebase.

- [ ] **Step 6: Commit**

```bash
git add sdks/frontend/embedded/library client/src/ee/pages/embedded/workflow-builder client/src/ee/shared/queries/embedded
git commit -m "$(cat <<'EOF'
1051 client - Remove sharedConnectionIds from EmbeddedWorkflowBuilder

The id list reached the server through the browser and was unioned into the
entitled set without a check, so any connected user could read any embedded
connection in the tenant by guessing its id. Sharing is now the shared flag a
tenant admin sets on the connection.

AutomationHub keeps the prop for one release, marked deprecated and documented
as inert. getConnectedUserConnectionsQuery loses its connectionIds parameter:
WorkflowBuilder was its only caller, and the hub reaches the same endpoint
through useGetComponentConnectionsQuery.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Documentation

**Files:**
- Modify: `docs/content/docs/platform/embedded/get-started/quick-start/index.mdx`
- Modify: `docs/content/docs/platform/embedded/build/automations/automation-workflows.mdx`

**Interfaces:**
- Consumes: the finished behaviour of Tasks 1–8.
- Produces: nothing.

- [ ] **Step 1: Fix the quick-start example**

Around line 184 the `WorkflowEditor` example takes and forwards `sharedConnectionIds`. Remove it from the destructured props, the inline prop type, and the JSX. Add a sentence after the example:

> To let every connected user reach a connection you own — a shared Slack app, a house API key — create it on the **Connections** page of your ByteChef embedded workspace and turn on **Shared Connection**. Sharing is per environment.

- [ ] **Step 2: Rewrite the prop table row**

At line 138 of `automation-workflows.mdx`, the row reads:

```
| `sharedConnectionIds` | Connections shared into the session that the user can reuse. |
```

Replace with:

```
| `sharedConnectionIds` | **Deprecated, no effect.** Mark a connection **Shared Connection** on the Connections page instead; every connected user in that environment can then reuse it. |
```

- [ ] **Step 3: Leave the plan record alone**

Do **not** edit `docs/superpowers/plans/2026-08-17-embedded-automation-hub.md`. It records what was built then.

- [ ] **Step 4: Write the release note**

The spec flags this as a breaking behavioural change, not merely a changelog line: a host currently
passing ids to `EmbeddedWorkflowBuilder` loses those connections on upgrade until an admin ticks the
box. Add to the repository's release-notes file for the next version (find it with
`ls docs/content/docs/**/release* 2>/dev/null; git log --oneline -20 --name-only | grep -i changelog`;
if the project has none, say so in the PR description instead of inventing a file):

> **Breaking — embedded.** `EmbeddedWorkflowBuilder` no longer accepts `sharedConnectionIds`, and the
> server ignores the ids sent by `AutomationHub`. The parameter was a caller assertion the server
> could not verify. Mark connections **Shared Connection** on the Connections page of your embedded
> workspace instead; sharing is per environment. Connections previously reached through the id list
> are not visible to connected users until an admin ticks the box.

- [ ] **Step 5: Verify no stale references remain**

```bash
grep -rn "sharedConnectionIds" docs/content/ client/src/ee/pages/embedded/workflow-builder/ sdks/frontend/embedded/library/src/components/embedded-workflow-builder/
```

Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add docs/content
git commit -m "$(cat <<'EOF'
1051 Document the Shared Connection flag

Removes sharedConnectionIds from the copy-pasteable quick-start example and
marks the AutomationHub prop deprecated in the prop table.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Final verification

- [ ] **Full server check**

```bash
./gradlew spotlessApply > /tmp/final-spotless.log 2>&1; echo "exit=$?"
./gradlew check --continue > /tmp/final-check.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/final-check.log
```

SpotBugs findings are in the HTML report, not the XML — read `build/reports/spotbugs/*.html`.

- [ ] **Full client check**

```bash
cd client && npm run check
```

Tool timeout 600000.

- [ ] **Manual acceptance, against the spec's own claims**

1. Admin marks a connection shared on `/embedded/connections` → a connected user with **no integration instance at all** sees it in the builder's connection picker. (The case the old derivation could not express.)
2. That connected user cannot delete or reauthorize it — `requireOwned` refuses.
3. A connection shared in DEVELOPMENT does not appear for a PRODUCTION connected user.
4. Unticking the switch removes it from the picker on the next fetch.
5. The switch is absent in the builder's own connection dialog and in the hub's.
