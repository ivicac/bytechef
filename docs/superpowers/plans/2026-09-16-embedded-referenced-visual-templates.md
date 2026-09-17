# Embedded Referenced Visual Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/provision` work for visual embedded automation templates — a per-user deployment that runs the vendor's published workflow and follows every republish — fix the reference path's existing defects, and let a vendor hide catalog projects from the Automation Hub.

**Architecture:** One `ProjectDeployment` per (connected user, catalog project, environment) holds one `ProjectDeploymentWorkflow` row per referenced template. Rows are always written as a complete list through `ProjectDeploymentFacade.updateProjectDeployment`. Connections come from a user-scoped, slot-based resolver; the deployment row is the only record of what is wired. An after-commit listener rolls reference deployments forward on publish. Attention state is derived when references are listed. A `project.automation_hub_visible` column hides catalog projects from the hub.

**Tech Stack:** Java 25, Spring Boot 4 / Spring Data JDBC, Liquibase, Testcontainers (PostgreSQL), MapStruct, OpenAPI generator, Spring GraphQL, React 19 + TanStack Query + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-16-embedded-referenced-visual-templates-design.md` — read it before any task. Defect ids D1–D11 refer to its §2 table.

## Global Constraints

- No new tables. Schema changes are exactly: add `project.automation_hub_visible BOOLEAN NOT NULL DEFAULT TRUE`; drop `connected_user_project_workflow_connection`.
- No new endpoints. Visual-template and code-workflow references share the existing ones: `/provision` (create/change), `DELETE …/provision`, `…/workflows/{uuid}/enable`, `…/workflows/{uuid}/inputs`, `…/workflows` (list) and `DELETE …/workflows/{uuid}`.
- Copy vs reference is chosen by the caller per activation; nothing about it is stored per project.
- All new Java files under `server/ee/` use the ByteChef Enterprise license header and a class Javadoc with `@version ee` and `@author Ivica Cardic`. New files under `server/libs/` use the Apache 2.0 header of their neighbours.
- Services/facades/components in `embedded-configuration-service` carry `@ConditionalOnEEVersion`; constructors taking collaborators carry `@SuppressFBWarnings("EI")`.
- Liquibase: new changesets only, guarded with `preConditions onFail="MARK_RAN"`. Embedded files go in `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/main/resources/config/liquibase/changelog/embedded/configuration/`, named `YYYYMMDDHHMMSS_embedded_configuration_<desc>.xml`; automation files in `server/libs/automation/automation-configuration/automation-configuration-service/src/main/resources/config/liquibase/changelog/automation/configuration/`, named `YYYYMMDDHHMMSS_automation_configuration_<desc>.xml`.
- Java style from `CLAUDE.md`: blank line before control statements and after a variable modification that is then used; no chained calls outside the allowed DSLs; descriptive names; no trailing blank line in a class body; no `TODO:`; no underscores in test method names.
- Catalog code never calls `ProjectDeploymentService.getProjectDeploymentId(projectId, environment)`, `ProjectDeploymentFacade.enableProjectDeploymentWorkflow(projectId, workflowId, enable, environment)` or `ProjectDeploymentFacade.updateProjectDeployment(projectId, projectVersion, workflowUuid, connections, environmentId)` (D9).
- A connected user's connection candidates come only from `ConnectedUserConnectionFacade.getConnections(connectedUserId, componentName, List.of())` (D6).
- `ProjectDeploymentWorkflowConnection` is constructed as `(connectionId, workflowConnectionKey, workflowNodeName)` with the key taken from `ComponentConnection.key()` (D7).
- Branch `0_732` in `/Volumes/Data/bytechef/bytechef` is shared with other sessions: `git add` new files, then commit by explicit path (`git commit -m "…" -- <paths>`); never `git stash`, never amend. Messages are single-line; the first commit of this feature is `--- <description>`, later ones `- <description>`, client ones `- client - <description>`.
- Never judge a Gradle run through a pipe: redirect to a file, check `$?`, then `grep "^> Task .* FAILED"`.
- Before trusting a new IntTest, run it against the unfixed code and record that it fails.
- Client code: object keys sorted, interfaces end in `I`/`Props`, `Icon`-suffixed lucide imports, `twMerge` not `cn`, hooks ordered per `CLAUDE.md`, `vi.hoisted` for refs used in `vi.mock` factories.

Gradle module paths:

| Alias | Path |
|---|---|
| `EC_SERVICE` | `:server:ee:libs:embedded:embedded-configuration:embedded-configuration-service` |
| `EC_PUBLIC_REST` | `:server:ee:libs:embedded:embedded-configuration:embedded-configuration-public-rest` |
| `EC_GRAPHQL` | `:server:ee:libs:embedded:embedded-configuration:embedded-configuration-graphql` |
| `EC_REMOTE_CLIENT` | `:server:ee:libs:embedded:embedded-configuration:embedded-configuration-remote-client` |
| `EC_REMOTE_REST` | `:server:ee:libs:embedded:embedded-configuration:embedded-configuration-remote-rest` |
| `AC_SERVICE` | `:server:libs:automation:automation-configuration:automation-configuration-service` |

`EC` abbreviates `server/ee/libs/embedded/embedded-configuration`; `AC` abbreviates `server/libs/automation/automation-configuration`.

---

### Task 1: User-scoped, slot-based connection resolver (D4, D6, D7)

Ships on its own: it fixes the live security defect for code-workflow references before anything else changes.

**Files:**
- Create: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/WorkflowConnectionSlots.java`
- Create: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ResolvedWorkflowConnections.java`
- Create: `EC/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/exception/ConnectionNotEntitledException.java`
- Modify (rewrite): `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserWorkflowConnectionResolver.java`
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserCodeWorkflowReferenceFacadeImpl.java` (`getOrCreateReference`, `getOrCreateProjectDeployment`, `rewireConnections`)
- Create: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserWorkflowConnectionResolverTest.java`
- Modify: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserCodeWorkflowReferenceFacadeTest.java`
- Modify: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/AutomationCodeWorkflowBridgeIntTest.java`

**Interfaces:**
- Consumes: `ComponentConnectionFacade.getComponentConnections(WorkflowTrigger)` and `(WorkflowTask)` (both ungated), `WorkflowTrigger.of(Workflow)`, `Workflow.getTasks(true)`, `WorkflowService.getWorkflow(String)`, `ConnectedUserConnectionFacade.getConnections(Long, String, List<Long>)`, `ConnectedUserService.getConnectedUser(String, Environment)`.
- Produces:
  - `WorkflowConnectionSlots` (`@Component @ConditionalOnEEVersion`): `List<ComponentConnection> getSlots(String workflowId)`.
  - `record ResolvedWorkflowConnections(List<ProjectDeploymentWorkflowConnection> connections, List<String> missingComponentNames)` with `boolean isComplete()` and `@Nullable String firstMissingComponentName()`.
  - `ConnectionNotEntitledException extends RuntimeException` with `(String componentName, long connectionId)`.
  - `ResolvedWorkflowConnections ConnectedUserWorkflowConnectionResolver.resolve(String workflowId, long connectedUserId, Map<String, Long> requestedConnectionIds, List<ProjectDeploymentWorkflowConnection> currentConnections)`.

- [ ] **Step 1: Write the failing resolver test**

```java
package com.bytechef.ee.embedded.configuration.facade;

@ExtendWith({MockitoExtension.class, ObjectMapperSetupExtension.class})
class ConnectedUserWorkflowConnectionResolverTest {

    private static final long CONNECTED_USER_ID = 7L;

    @Mock
    private ConnectedUserConnectionFacade connectedUserConnectionFacade;

    @Mock
    private WorkflowConnectionSlots workflowConnectionSlots;

    private ConnectedUserWorkflowConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ConnectedUserWorkflowConnectionResolver(connectedUserConnectionFacade, workflowConnectionSlots);
    }

    @Test
    void testResolveUsesComponentConnectionKeyAndNodeName() {
        stubSlots(new ComponentConnection("slack", 1, "postMessage1", "slack", true));
        stubEntitled("slack", connection(11L, "slack"));

        ResolvedWorkflowConnections resolved = resolver.resolve("wf-1", CONNECTED_USER_ID, Map.of(), List.of());

        assertThat(resolved.connections())
            .containsExactly(new ProjectDeploymentWorkflowConnection(11L, "slack", "postMessage1"));
        assertThat(resolved.isComplete()).isTrue();
    }

    @Test
    void testResolveUsesClusterElementKeyUnderRootNode() {
        stubSlots(new ComponentConnection("openAi", 1, "aiAgent1", "openAi_1", true));
        stubEntitled("openAi", connection(21L, "openAi"));

        ResolvedWorkflowConnections resolved = resolver.resolve("wf-1", CONNECTED_USER_ID, Map.of(), List.of());

        assertThat(resolved.connections())
            .containsExactly(new ProjectDeploymentWorkflowConnection(21L, "openAi_1", "aiAgent1"));
    }

    @Test
    void testResolvePrefersRequestedConnection() {
        stubSlots(new ComponentConnection("slack", 1, "postMessage1", "slack", true));
        stubEntitled("slack", connection(11L, "slack"), connection(12L, "slack"));

        ResolvedWorkflowConnections resolved = resolver.resolve(
            "wf-1", CONNECTED_USER_ID, Map.of("slack", 12L),
            List.of(new ProjectDeploymentWorkflowConnection(11L, "slack", "postMessage1")));

        assertThat(resolved.connections())
            .extracting(ProjectDeploymentWorkflowConnection::getConnectionId)
            .containsExactly(12L);
    }

    @Test
    void testResolveKeepsCurrentlyWiredConnectionForTheComponent() {
        stubSlots(
            new ComponentConnection("slack", 1, "postMessage1", "slack", true),
            new ComponentConnection("slack", 1, "postMessage2", "slack", true));
        stubEntitled("slack", connection(11L, "slack"), connection(12L, "slack"));

        ResolvedWorkflowConnections resolved = resolver.resolve(
            "wf-1", CONNECTED_USER_ID, Map.of(),
            List.of(new ProjectDeploymentWorkflowConnection(12L, "slack", "renamedNode")));

        assertThat(resolved.connections())
            .extracting(ProjectDeploymentWorkflowConnection::getConnectionId)
            .containsExactly(12L, 12L);
    }

    @Test
    void testResolveFallsThroughWhenCurrentlyWiredConnectionIsNoLongerEntitled() {
        stubSlots(new ComponentConnection("slack", 1, "postMessage1", "slack", true));
        stubEntitled("slack", connection(11L, "slack"));

        ResolvedWorkflowConnections resolved = resolver.resolve(
            "wf-1", CONNECTED_USER_ID, Map.of(),
            List.of(new ProjectDeploymentWorkflowConnection(99L, "slack", "postMessage1")));

        assertThat(resolved.connections())
            .extracting(ProjectDeploymentWorkflowConnection::getConnectionId)
            .containsExactly(11L);
    }

    @Test
    void testResolveRejectsRequestedConnectionTheUserIsNotEntitledTo() {
        stubSlots(new ComponentConnection("slack", 1, "postMessage1", "slack", true));
        stubEntitled("slack", connection(11L, "slack"));

        assertThatThrownBy(() -> resolver.resolve("wf-1", CONNECTED_USER_ID, Map.of("slack", 99L), List.of()))
            .isInstanceOf(ConnectionNotEntitledException.class);
    }

    @Test
    void testResolveReportsMissingRequiredComponentAndSkipsOptionalOne() {
        stubSlots(
            new ComponentConnection("slack", 1, "postMessage1", "slack", true),
            new ComponentConnection("httpClient", 1, "get1", "httpClient", false));
        stubEntitled("slack");
        stubEntitled("httpClient");

        ResolvedWorkflowConnections resolved = resolver.resolve("wf-1", CONNECTED_USER_ID, Map.of(), List.of());

        assertThat(resolved.missingComponentNames()).containsExactly("slack");
        assertThat(resolved.firstMissingComponentName()).isEqualTo("slack");
        assertThat(resolved.connections()).isEmpty();
    }

    private void stubSlots(ComponentConnection... componentConnections) {
        when(workflowConnectionSlots.getSlots("wf-1")).thenReturn(List.of(componentConnections));
    }

    private void stubEntitled(String componentName, ConnectionDTO... connectionDTOs) {
        lenient().when(connectedUserConnectionFacade.getConnections(CONNECTED_USER_ID, componentName, List.of()))
            .thenReturn(List.of(connectionDTOs));
    }

    private static ConnectionDTO connection(long id, String componentName) {
        ConnectionDTO connectionDTO = mock(ConnectionDTO.class);

        lenient().when(connectionDTO.id()).thenReturn(id);
        lenient().when(connectionDTO.componentName()).thenReturn(componentName);

        return connectionDTO;
    }
}
```

(`ConnectionDTO` is a record; if the module's mock maker cannot mock records, construct it with its canonical constructor from `server/libs/platform/platform-connection/platform-connection-api/src/main/java/com/bytechef/platform/connection/dto/ConnectionDTO.java`.)

- [ ] **Step 2: Write the failing cross-user IntTest**

In `AutomationCodeWorkflowBridgeIntTest` (already boots a real schema, mocks `ComponentConnectionFacade` and `ConnectionService`) add `@MockitoBean ConnectedUserConnectionFacade connectedUserConnectionFacade` and:

```java
@Test
void testReferenceIsNeverWiredToAnotherConnectedUsersConnection() {
    // Arrange a published code-workflow catalog project with one slack task exactly as
    // testProvisionThenEnableRoundTripWithAConnection does, then:
    when(componentConnectionFacade.getComponentConnections(any(WorkflowTask.class)))
        .thenReturn(List.of(new ComponentConnection("slack", 1, "postMessage1", "slack", true)));
    when(connectionService.getConnections(PlatformType.EMBEDDED))
        .thenReturn(List.of(slackConnectionOwnedByAnotherUser(555L)));
    when(connectedUserConnectionFacade.getConnections(anyLong(), eq("slack"), eq(List.of())))
        .thenReturn(List.of());

    assertThatThrownBy(() -> connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        "user-b", catalogWorkflowUuid, Environment.PRODUCTION))
            .isInstanceOf(MissingConnectionException.class);

    assertThat(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(deploymentIdFor("user-b")))
        .allSatisfy(row -> assertThat(row.getConnections()).isEmpty());
}
```

Implement the two helpers in the test class: `slackConnectionOwnedByAnotherUser(long id)` builds a `Connection` with that id, component `slack`, type `EMBEDDED`, environment `PRODUCTION`; `deploymentIdFor(String externalUserId)` returns `projectDeploymentService.fetchProjectDeploymentByName(catalogProjectId, "__EMBEDDED__" + externalUserId + "__PRODUCTION").orElseThrow().getId()`. Also change `testProvisionThenEnableRoundTripWithAConnection` to stub `connectedUserConnectionFacade` instead of `connectionService`, and assert the row's connection equals `new ProjectDeploymentWorkflowConnection(777L, "slack", "postMessage1")` (key = component name).

- [ ] **Step 3: Run both against the current code and record the failures**

Run: `./gradlew EC_SERVICE:test --tests "*ConnectedUserWorkflowConnectionResolverTest" > /tmp/t1-unit.log 2>&1; echo $?` → non-zero (compile).
Run: `./gradlew EC_SERVICE:testIntegration --tests "*AutomationCodeWorkflowBridgeIntTest" > /tmp/t1-before.log 2>&1; echo $?` → non-zero; the cross-user test fails because a row is wired to connection `555` (D6) and the round-trip test fails on the key (D7). Keep both excerpts.

- [ ] **Step 4: Implement**

`WorkflowConnectionSlots.java`:

```java
package com.bytechef.ee.embedded.configuration.facade;

/**
 * Every connection slot of a workflow: triggers, every task including nested ones, and cluster elements (added by
 * the cluster root connection factory). The same enumeration {@code ProjectDeploymentFacadeImpl} uses to check that
 * required connections are set.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class WorkflowConnectionSlots {

    private final ComponentConnectionFacade componentConnectionFacade;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public WorkflowConnectionSlots(ComponentConnectionFacade componentConnectionFacade, WorkflowService workflowService) {
        this.componentConnectionFacade = componentConnectionFacade;
        this.workflowService = workflowService;
    }

    public List<ComponentConnection> getSlots(String workflowId) {
        Workflow workflow = workflowService.getWorkflow(workflowId);

        return CollectionUtils.concat(
            WorkflowTrigger.of(workflow)
                .stream()
                .flatMap(workflowTrigger -> CollectionUtils.stream(
                    componentConnectionFacade.getComponentConnections(workflowTrigger)))
                .toList(),
            workflow.getTasks(true)
                .stream()
                .flatMap(workflowTask -> CollectionUtils.stream(
                    componentConnectionFacade.getComponentConnections(workflowTask)))
                .toList());
    }
}
```

`ResolvedWorkflowConnections.java`:

```java
package com.bytechef.ee.embedded.configuration.facade;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public record ResolvedWorkflowConnections(
    List<ProjectDeploymentWorkflowConnection> connections, List<String> missingComponentNames) {

    public ResolvedWorkflowConnections {
        connections = List.copyOf(connections);
        missingComponentNames = List.copyOf(missingComponentNames);
    }

    @Nullable
    public String firstMissingComponentName() {
        return missingComponentNames.isEmpty() ? null : missingComponentNames.getFirst();
    }

    public boolean isComplete() {
        return missingComponentNames.isEmpty();
    }
}
```

`ConnectionNotEntitledException.java`:

```java
package com.bytechef.ee.embedded.configuration.exception;

/**
 * A caller asked to wire a connection the connected user is not entitled to. Mapped to 400.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class ConnectionNotEntitledException extends RuntimeException {

    public ConnectionNotEntitledException(String componentName, long connectionId) {
        super("Connection %s is not a %s connection of this connected user".formatted(connectionId, componentName));
    }
}
```

`ConnectedUserWorkflowConnectionResolver.java` — replace the body (rewrite the class Javadoc: candidates come only from the connected user's entitled connections; slots from `WorkflowConnectionSlots`; choice order requested → currently wired → first entitled):

```java
@Component
@ConditionalOnEEVersion
public class ConnectedUserWorkflowConnectionResolver {

    private final ConnectedUserConnectionFacade connectedUserConnectionFacade;
    private final WorkflowConnectionSlots workflowConnectionSlots;

    @SuppressFBWarnings("EI")
    public ConnectedUserWorkflowConnectionResolver(
        ConnectedUserConnectionFacade connectedUserConnectionFacade, WorkflowConnectionSlots workflowConnectionSlots) {

        this.connectedUserConnectionFacade = connectedUserConnectionFacade;
        this.workflowConnectionSlots = workflowConnectionSlots;
    }

    public ResolvedWorkflowConnections resolve(
        String workflowId, long connectedUserId, Map<String, Long> requestedConnectionIds,
        List<ProjectDeploymentWorkflowConnection> currentConnections) {

        Map<String, List<Long>> entitledConnectionIdsByComponentName = new HashMap<>();
        List<ProjectDeploymentWorkflowConnection> connections = new ArrayList<>();
        Set<String> missingComponentNames = new LinkedHashSet<>();

        for (ComponentConnection slot : workflowConnectionSlots.getSlots(workflowId)) {
            String componentName = slot.componentName();

            List<Long> entitledConnectionIds = entitledConnectionIdsByComponentName.computeIfAbsent(
                componentName, name -> getEntitledConnectionIds(connectedUserId, name));

            Long connectionId = selectConnectionId(
                componentName, entitledConnectionIds, requestedConnectionIds.get(componentName),
                currentConnections);

            if (connectionId == null) {
                if (slot.required()) {
                    missingComponentNames.add(componentName);
                }

                continue;
            }

            connections.add(new ProjectDeploymentWorkflowConnection(connectionId, slot.key(), slot.workflowNodeName()));
        }

        return new ResolvedWorkflowConnections(connections, List.copyOf(missingComponentNames));
    }

    private List<Long> getEntitledConnectionIds(long connectedUserId, String componentName) {
        return connectedUserConnectionFacade.getConnections(connectedUserId, componentName, List.of())
            .stream()
            .map(ConnectionDTO::id)
            .toList();
    }

    @Nullable
    private static Long selectConnectionId(
        String componentName, List<Long> entitledConnectionIds, @Nullable Long requestedConnectionId,
        List<ProjectDeploymentWorkflowConnection> currentConnections) {

        if (requestedConnectionId != null) {
            if (!entitledConnectionIds.contains(requestedConnectionId)) {
                throw new ConnectionNotEntitledException(componentName, requestedConnectionId);
            }

            return requestedConnectionId;
        }

        for (ProjectDeploymentWorkflowConnection currentConnection : currentConnections) {
            if (entitledConnectionIds.contains(currentConnection.getConnectionId())) {
                return currentConnection.getConnectionId();
            }
        }

        return entitledConnectionIds.isEmpty() ? null : entitledConnectionIds.getFirst();
    }
}
```

The "currently wired" loop matches by membership in this component's entitled list, so a wired connection of another component is never chosen.

In `ConnectedUserCodeWorkflowReferenceFacadeImpl` (minimal, until Task 3 rewrites it): inject `ConnectedUserService`; in `getOrCreateReference` and `rewireConnections`, resolve the connected user with `connectedUserService.getConnectedUser(externalUserId, environment)` (`rewireConnections` gets the external user id and environment passed in from `enableReference`), call `resolve(catalogWorkflowId, connectedUser.getId(), Map.of(), currentConnections)` — `List.of()` in `getOrCreateReference`, the existing row's `getConnections()` in `rewireConnections` — and use `resolved.connections()` directly as the `ProjectDeploymentWorkflowConnection` list. Replace every `new ProjectDeploymentWorkflowConnection(entry.getValue(), entry.getKey(), entry.getKey())`. Where the old code caught `MissingConnectionException`, test `resolved.isComplete()` and throw `new MissingConnectionException(resolved.firstMissingComponentName())` at the same point. The bookkeeping writes to `ConnectedUserProjectWorkflowConnectionRepository` take `connection.getWorkflowNodeName()` and `connection.getConnectionId()` from each resolved connection (they are removed in Task 2).

Update `ConnectedUserCodeWorkflowReferenceFacadeTest`: stub `resolve(eq("catalog-wf-1"), anyLong(), eq(Map.of()), anyList())` with `ResolvedWorkflowConnections` instances instead of `resolve(String)` maps/throws; stub `connectedUserService.getConnectedUser(...)` to return a `ConnectedUser` with an id.

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew EC_SERVICE:test EC_SERVICE:testIntegration --tests "*AutomationCodeWorkflowBridgeIntTest" --continue > /tmp/t1.log 2>&1; echo $?`
Expected: `0`.

- [ ] **Step 6: Commit**

```bash
git add <the four new files>
git commit -m "--- Wire embedded references only to the connected user's own connections with platform connection keys" -- <every file of this task>
```

---

### Task 2: Drop the duplicate reference connection table (D11)

**Files:**
- Create: `EC/embedded-configuration-service/src/main/resources/config/liquibase/changelog/embedded/configuration/20260917100000_embedded_configuration_dropped_connected_user_project_workflow_connection.xml`
- Delete: `EC/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/domain/ConnectedUserProjectWorkflowConnection.java`
- Delete: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/repository/ConnectedUserProjectWorkflowConnectionRepository.java`
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserCodeWorkflowReferenceFacadeImpl.java`
- Modify: every test referencing the two deleted types

**Interfaces:**
- Produces: `ConnectedUserCodeWorkflowReferenceFacadeImpl` no longer has a `ConnectedUserProjectWorkflowConnectionRepository` constructor parameter.

- [ ] **Step 1: Find every use**

Run: `grep -rn "ConnectedUserProjectWorkflowConnection\b\|ConnectedUserProjectWorkflowConnectionRepository\|connected_user_project_workflow_connection" server --include='*.java' --include='*.xml' | grep -v /build/`
Expected: the domain class, the repository, the reference facade, its tests (`ConnectedUserCodeWorkflowReferenceFacadeTest`, `ConnectedUserCodeWorkflowReferenceFacadeAuthorizationTest`, `AutomationCodeWorkflowBridgeIntTest` and any IntTest configuration mocking the repository) and the `20260727120000` changeset. Anything else that *reads* the table stops this task: report it instead of deleting.

- [ ] **Step 2: Add the changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <changeSet id="20260917100000-1" author="Ivica Cardic">
        <preConditions onFail="MARK_RAN">
            <tableExists tableName="connected_user_project_workflow_connection"/>
        </preConditions>
        <dropTable tableName="connected_user_project_workflow_connection" cascadeConstraints="true"/>
        <rollback/>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 3: Remove the code**

Delete the two Java files. In the reference facade remove the constructor parameter, field and every loop that saves or deletes bookkeeping connection rows (`getOrCreateReference`, `rewireConnections`, `deleteReference`). Remove the corresponding `@Mock`s, constructor arguments, `verify(...)` calls and `@MockitoBean` entries from the tests found in Step 1.

- [ ] **Step 4: Verify**

Run: `./gradlew EC_SERVICE:test EC_SERVICE:testIntegration --tests "*AutomationCodeWorkflowBridgeIntTest" --tests "*AutomationWorkflowProjectFacadeIntTest" EC_PUBLIC_REST:testIntegration --continue > /tmp/t2.log 2>&1; echo $?`
Expected: `0`; the IntTests prove Liquibase applies the drop on a fresh schema.

- [ ] **Step 5: Commit**

```bash
git add <changeset>
git rm <two deleted Java files>
git commit -m "- Drop the unused embedded reference connection table" -- <every file of this task>
```

---

### Task 3: Versioned per-user reference deployment — provision, enable, delete, copy guard (D1, D2, D3, D8, D9)

**Files:**
- Create: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserReferenceDeploymentManager.java`
- Create: `EC/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/exception/CodeWorkflowNotCopyableException.java`
- Create: `EC/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/exception/MissingInputException.java`
- Modify (rewrite): `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserCodeWorkflowReferenceFacadeImpl.java`
- Modify: `EC/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserCodeWorkflowReferenceFacade.java`
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserProjectFacadeImpl.java` (`copyWorkflowTemplate`, ~188)
- Modify: `EC/embedded-configuration-remote-client/src/main/java/com/bytechef/ee/embedded/configuration/remote/client/facade/RemoteConnectedUserCodeWorkflowReferenceFacadeClient.java`
- Modify: `EC/embedded-configuration-remote-rest/src/main/java/com/bytechef/ee/embedded/configuration/remote/web/rest/facade/RemoteConnectedUserCodeWorkflowReferenceFacadeController.java`
- Create: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/ConnectedUserReferenceRolloutIntTest.java` (annotations, `@MockitoBean` list and configuration copied from `AutomationCodeWorkflowBridgeIntTest`, plus `@MockitoBean ConnectedUserConnectionFacade`)
- Modify: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserCodeWorkflowReferenceFacadeTest.java`
- Create: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserReferenceDeploymentManagerTest.java`
- Modify: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserProjectFacadeCopyTemplateAuthorizationTest.java`

**Interfaces:**
- Consumes: `ConnectedUserWorkflowConnectionResolver.resolve(String, long, Map<String, Long>, List<ProjectDeploymentWorkflowConnection>)`, `ResolvedWorkflowConnections` (Task 1); `Workflow.getInputs()` → `List<Workflow.Input>` with `Input(String name, String label, String type, boolean required, Map<String, Object> extensions)`.
- Produces:
  - `ConnectedUserReferenceDeploymentManager` (`@Component @ConditionalOnEEVersion @SkipAutomationAuthorization`) — the one place that knows how a reference row is built; the facade, the rollout (Task 5) and the attention resolver (Task 6) all depend on it and never on each other:
    - `record RowSpec(ResolvedWorkflowConnections resolved, boolean enabled, @Nullable Map<String, ?> inputs)` — `inputs == null` keeps the row's current inputs.
    - `record ReferenceResolution(RowSpec rowSpec, @Nullable String missingComponentName, @Nullable String missingInputName)`
    - `ReferenceResolution resolveReference(long connectedUserId, String workflowId, boolean enable, Map<String, Long> requestedConnectionIds, List<ProjectDeploymentWorkflowConnection> currentConnections, Map<String, ?> inputs)` — `rowSpec.enabled()` is `enable && no missing component && no missing input`.
    - `@Nullable String findMissingRequiredInput(String workflowId, Map<String, ?> inputs)`
    - `ProjectDeployment getDeployment(long projectDeploymentId)`
    - `String getDeploymentName(String externalUserId, Environment environment)` → `"__EMBEDDED__" + externalUserId + "__" + environment.name()`
    - `int getLastPublishedVersion(long catalogProjectId)`
    - `long getOrCreateDeployment(long catalogProjectId, String externalUserId, Environment environment)`
    - `String getWorkflowId(long catalogProjectId, int projectVersion, String catalogWorkflowUuid)`
    - `Optional<ProjectDeploymentWorkflow> fetchRow(long projectDeploymentId, String catalogWorkflowUuid)`
    - `void putWorkflows(long projectDeploymentId, int projectVersion, Map<String, RowSpec> rowSpecsByCatalogWorkflowUuid)` — same version: rows not in the map are kept; different version: rows not in the map are dropped.
    - `void removeWorkflow(long projectDeploymentId, String catalogWorkflowUuid)` — deletes the deployment when no rows remain.
    - `void deleteDeployment(long projectDeploymentId)` — writes an empty row list (disabling every trigger) then deletes the deployment.
  - On `ConnectedUserCodeWorkflowReferenceFacade`: `ConnectedUserProjectWorkflow getOrCreateReference(String externalUserId, String catalogWorkflowUuid, Environment environment, Map<String, Long> requestedConnectionIds)`; the 3-arg form becomes a `default` passing `Map.of()`.
  - `CodeWorkflowNotCopyableException extends RuntimeException` with `(String workflowUuid)`.
  - `MissingInputException extends RuntimeException` with `(String inputName)` and `getInputName()` — not an `AbstractException` (those map to 400); the controller maps it to 409, like `MissingConnectionException`.

- [ ] **Step 1: Write the failing IntTests**

In `ConnectedUserReferenceRolloutIntTest`:

```java
private static final String EXTERNAL_USER_ID = "rollout-user-1";

private static final String SLACK_WORKFLOW_DEFINITION = """
    {"label":"Post","triggers":[],"tasks":[{"name":"postMessage1","type":"slack/v1/postMessage","parameters":{}}]}
    """;

@Test
void testTwoTemplatesFromOneVisualProjectShareOneDeploymentAtThePublishedVersion() {
    long catalogProjectId = createCatalogProject("Two Templates");

    String firstUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);
    String secondUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);
    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    ConnectedUserProjectWorkflow first = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, firstUuid, Environment.PRODUCTION);
    ConnectedUserProjectWorkflow second = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, secondUuid, Environment.PRODUCTION);

    assertThat(second.getProjectDeploymentId()).isEqualTo(first.getProjectDeploymentId());
    assertThat(projectDeploymentService.getProjectDeployment(first.getProjectDeploymentId())
        .getProjectVersion()).isEqualTo(2);
    assertThat(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(first.getProjectDeploymentId()))
        .hasSize(2)
        .allSatisfy(row -> assertThat(row.getConnections())
            .containsExactly(new ProjectDeploymentWorkflowConnection(777L, "slack", "postMessage1")));
}

@Test
void testEnableReferenceAfterRepublishDoesNotFail() {
    long catalogProjectId = createCatalogProject("Republish Enable");

    String workflowUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION);
    connectedUserCodeWorkflowReferenceFacade.enableReference(
        EXTERNAL_USER_ID, workflowUuid, false, Environment.PRODUCTION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    assertThatCode(() -> connectedUserCodeWorkflowReferenceFacade.enableReference(
        EXTERNAL_USER_ID, workflowUuid, true, Environment.PRODUCTION))
            .doesNotThrowAnyException();
}

@Test
void testDeleteLastReferenceRemovesItsRowAndTheDeployment() {
    long catalogProjectId = createCatalogProject("Delete Row");

    String workflowUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    ConnectedUserProjectWorkflow reference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION);

    connectedUserCodeWorkflowReferenceFacade.deleteReference(EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION);

    assertThat(projectDeploymentService.fetchProjectDeploymentByName(
        catalogProjectId, "__EMBEDDED__" + EXTERNAL_USER_ID + "__PRODUCTION")).isEmpty();
    assertThat(connectedUserProjectWorkflowRepository.findById(reference.getId())).isEmpty();
}

@Test
void testCodeWorkflowProjectTwoTemplatesShareOneDeployment() {
    long catalogProjectId = createCatalogProject("Code Two Templates");

    when(projectCodeWorkflowService.getCodeWorkflowProjectIds()).thenReturn(List.of(catalogProjectId));

    String firstUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);
    String secondUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);
    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    ConnectedUserProjectWorkflow first = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, firstUuid, Environment.PRODUCTION);
    ConnectedUserProjectWorkflow second = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, secondUuid, Environment.PRODUCTION);

    assertThat(second.getProjectDeploymentId()).isEqualTo(first.getProjectDeploymentId());
    assertThat(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(first.getProjectDeploymentId()))
        .hasSize(2);
}

private long createCatalogProject(String name) {
    return automationWorkflowProjectFacade.createProject(name + " " + UUID.randomUUID(), "", null, List.of(), null);
}

private String addWorkflow(long catalogProjectId, String definition) {
    String workflowId = automationWorkflowProjectFacade.createProjectWorkflow(catalogProjectId, definition, null);

    return projectWorkflowService.getWorkflowProjectWorkflow(workflowId)
        .getUuidAsString();
}

private void stubEntitledSlackConnection(long connectionId) {
    ConnectionDTO connectionDTO = mock(ConnectionDTO.class);
    Connection connection = new Connection();

    connection.setComponentName("slack");
    connection.setEnvironment(Environment.PRODUCTION);
    connection.setId(connectionId);

    ComponentConnection slot = new ComponentConnection("slack", 1, "postMessage1", "slack", true);

    when(connectionDTO.id()).thenReturn(connectionId);
    when(connectionDTO.componentName()).thenReturn("slack");
    when(connectedUserConnectionFacade.getConnections(anyLong(), eq("slack"), eq(List.of())))
        .thenReturn(List.of(connectionDTO));
    when(componentConnectionFacade.getComponentConnections(any(WorkflowTask.class))).thenReturn(List.of(slot));
    when(componentConnectionFacade.getComponentConnection(anyString(), eq("postMessage1"), eq("slack")))
        .thenReturn(slot);
    when(connectionService.getConnection(connectionId)).thenReturn(connection);
}
```

`ProjectDeploymentFacadeImpl` validates a saved enabled row's connection through `connectionService.getConnection` and `componentConnectionFacade.getComponentConnection` — hence those two stubs. Match `Connection`'s real setters (environment may be an ordinal setter). Stub `connectedUserService.getConnectedUser(EXTERNAL_USER_ID, Environment.PRODUCTION)` and `embeddedPermissionEvaluator.evaluate(any(), any())` → `true` in `@BeforeEach`, as `AutomationWorkflowProjectFacadeIntTest.setUp` does.

In `ConnectedUserProjectFacadeCopyTemplateAuthorizationTest` add:

```java
@Test
void testCopyWorkflowTemplateRejectsCodeWorkflowTemplate() {
    stubVisibleCatalogTemplate("template-uuid", true);

    assertThatThrownBy(() -> connectedUserProjectFacade.copyWorkflowTemplate(
        "external-user", "template-uuid", Environment.PRODUCTION))
            .isInstanceOf(CodeWorkflowNotCopyableException.class);
}
```

where `stubVisibleCatalogTemplate(String uuid, boolean codeWorkflowProject)` stubs `automationWorkflowProjectFacade.getPublishedProjects(anyString(), any())` with one `AutomationWorkflowProjectDTO` whose templates include that uuid and whose `codeWorkflowProject` is the flag (reuse the class's existing DTO construction).

- [ ] **Step 2: Run against the current code and record the failures**

Run: `./gradlew EC_SERVICE:testIntegration --tests "*ConnectedUserReferenceRolloutIntTest" > /tmp/t3-before.log 2>&1; echo $?`
Expected: non-zero — two-templates tests fail on `hasSize(2)` or version `1` (D1, D2); republish-enable throws from `getProjectDeploymentWorkflow` (D3); delete leaves the deployment (D8).

- [ ] **Step 3: Implement `ConnectedUserReferenceDeploymentManager`**

```java
package com.bytechef.ee.embedded.configuration.facade;

/**
 * Owns the per-(connected user, catalog project, environment) {@link ProjectDeployment} every reference to that
 * project's templates shares. Rows are always written as the complete list through
 * {@link ProjectDeploymentFacade#updateProjectDeployment(ProjectDeployment, List, List)}, which deletes rows not
 * passed, carries inputs forward by workflow uuid, and (re)registers triggers of enabled rows. Never resolves a
 * deployment by (project, environment): a catalog project has one deployment per connected user.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@SkipAutomationAuthorization
public class ConnectedUserReferenceDeploymentManager {

    private static final String MARKER = "__EMBEDDED__";

    private final ConnectedUserWorkflowConnectionResolver connectedUserWorkflowConnectionResolver;
    private final ProjectDeploymentFacade projectDeploymentFacade;
    private final ProjectDeploymentService projectDeploymentService;
    private final ProjectDeploymentWorkflowService projectDeploymentWorkflowService;
    private final ProjectService projectService;
    private final ProjectWorkflowService projectWorkflowService;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public ConnectedUserReferenceDeploymentManager(
        ConnectedUserWorkflowConnectionResolver connectedUserWorkflowConnectionResolver,
        ProjectDeploymentFacade projectDeploymentFacade, ProjectDeploymentService projectDeploymentService,
        ProjectDeploymentWorkflowService projectDeploymentWorkflowService, ProjectService projectService,
        ProjectWorkflowService projectWorkflowService, WorkflowService workflowService) {

        this.connectedUserWorkflowConnectionResolver = connectedUserWorkflowConnectionResolver;
        this.projectDeploymentFacade = projectDeploymentFacade;
        this.projectDeploymentService = projectDeploymentService;
        this.projectDeploymentWorkflowService = projectDeploymentWorkflowService;
        this.projectService = projectService;
        this.projectWorkflowService = projectWorkflowService;
        this.workflowService = workflowService;
    }

    public void deleteDeployment(long projectDeploymentId) {
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        projectDeploymentFacade.updateProjectDeployment(projectDeployment, List.of(), List.of());
        projectDeploymentFacade.deleteProjectDeployment(projectDeploymentId);
    }

    @Nullable
    public String findMissingRequiredInput(String workflowId, Map<String, ?> inputs) {
        Workflow workflow = workflowService.getWorkflow(workflowId);

        for (Workflow.Input input : workflow.getInputs()) {
            Object value = inputs.get(input.name());

            if (input.required() && (value == null || StringUtils.isBlank(String.valueOf(value)))) {
                return input.name();
            }
        }

        return null;
    }

    /**
     * A row is enabled only when enabling was asked for AND every required connection resolved AND every required
     * input has a value: {@code ProjectDeploymentFacadeImpl} refuses to save an enabled row that misses either.
     */
    public ReferenceResolution resolveReference(
        long connectedUserId, String workflowId, boolean enable, Map<String, Long> requestedConnectionIds,
        List<ProjectDeploymentWorkflowConnection> currentConnections, Map<String, ?> inputs) {

        ResolvedWorkflowConnections resolved = connectedUserWorkflowConnectionResolver.resolve(
            workflowId, connectedUserId, requestedConnectionIds, currentConnections);

        String missingInputName = findMissingRequiredInput(workflowId, inputs);

        boolean enabled = enable && resolved.isComplete() && missingInputName == null;

        return new ReferenceResolution(
            new RowSpec(resolved, enabled, null), resolved.firstMissingComponentName(), missingInputName);
    }

    public Optional<ProjectDeploymentWorkflow> fetchRow(long projectDeploymentId, String catalogWorkflowUuid) {
        return Optional.ofNullable(getRowsByCatalogWorkflowUuid(projectDeploymentId).get(catalogWorkflowUuid));
    }

    public ProjectDeployment getDeployment(long projectDeploymentId) {
        return projectDeploymentService.getProjectDeployment(projectDeploymentId);
    }

    public String getDeploymentName(String externalUserId, Environment environment) {
        return MARKER + externalUserId + "__" + environment.name();
    }

    public int getLastPublishedVersion(long catalogProjectId) {
        Project project = projectService.getProject(catalogProjectId);

        ProjectVersion lastPublishedProjectVersion = project.getLastPublishedProjectVersion();

        if (lastPublishedProjectVersion == null) {
            throw new IllegalArgumentException("Catalog project id=%s is not published".formatted(catalogProjectId));
        }

        return lastPublishedProjectVersion.getVersion();
    }

    public long getOrCreateDeployment(long catalogProjectId, String externalUserId, Environment environment) {
        String name = getDeploymentName(externalUserId, environment);

        return projectDeploymentService.fetchProjectDeploymentByName(catalogProjectId, name)
            .map(ProjectDeployment::getId)
            .orElseGet(() -> {
                ProjectDeployment projectDeployment = new ProjectDeployment();

                projectDeployment.setEnabled(true);
                projectDeployment.setEnvironment(environment);
                projectDeployment.setName(name);
                projectDeployment.setProjectId(catalogProjectId);
                projectDeployment.setProjectVersion(getLastPublishedVersion(catalogProjectId));

                return projectDeploymentFacade.createProjectDeployment(projectDeployment, List.of(), List.of());
            });
    }

    public String getWorkflowId(long catalogProjectId, int projectVersion, String catalogWorkflowUuid) {
        return projectWorkflowService.fetchProjectWorkflow(catalogProjectId, projectVersion, catalogWorkflowUuid)
            .map(ProjectWorkflow::getWorkflowId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Catalog workflow %s is not in version %s of project id=%s".formatted(
                    catalogWorkflowUuid, projectVersion, catalogProjectId)));
    }

    public void putWorkflows(
        long projectDeploymentId, int projectVersion, Map<String, RowSpec> rowSpecsByCatalogWorkflowUuid) {

        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        boolean sameVersion = projectDeployment.getProjectVersion() == projectVersion;
        Map<String, ProjectDeploymentWorkflow> existingRowsByUuid = getRowsByCatalogWorkflowUuid(projectDeploymentId);

        List<ProjectDeploymentWorkflow> rows = new ArrayList<>();

        if (sameVersion) {
            for (Map.Entry<String, ProjectDeploymentWorkflow> entry : existingRowsByUuid.entrySet()) {
                if (!rowSpecsByCatalogWorkflowUuid.containsKey(entry.getKey())) {
                    rows.add(copyOf(entry.getValue()));
                }
            }
        }

        for (Map.Entry<String, RowSpec> entry : rowSpecsByCatalogWorkflowUuid.entrySet()) {
            RowSpec rowSpec = entry.getValue();
            ProjectDeploymentWorkflow existingRow = existingRowsByUuid.get(entry.getKey());

            ResolvedWorkflowConnections resolved = rowSpec.resolved();

            ProjectDeploymentWorkflow row = new ProjectDeploymentWorkflow();

            row.setConnections(resolved.connections());
            row.setEnabled(rowSpec.enabled());
            row.setInputs(
                rowSpec.inputs() != null || existingRow == null ? rowSpec.inputs() : existingRow.getInputs());
            row.setProjectDeploymentId(projectDeploymentId);
            row.setWorkflowId(getWorkflowId(projectDeployment.getProjectId(), projectVersion, entry.getKey()));

            rows.add(row);
        }

        projectDeployment.setProjectVersion(projectVersion);

        projectDeploymentFacade.updateProjectDeployment(projectDeployment, rows, List.of());
    }

    public void removeWorkflow(long projectDeploymentId, String catalogWorkflowUuid) {
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        List<ProjectDeploymentWorkflow> rows = getRowsByCatalogWorkflowUuid(projectDeploymentId)
            .entrySet()
            .stream()
            .filter(entry -> !Objects.equals(entry.getKey(), catalogWorkflowUuid))
            .map(entry -> copyOf(entry.getValue()))
            .toList();

        projectDeploymentFacade.updateProjectDeployment(projectDeployment, rows, List.of());

        if (rows.isEmpty()) {
            projectDeploymentFacade.deleteProjectDeployment(projectDeploymentId);
        }
    }

    private Map<String, ProjectDeploymentWorkflow> getRowsByCatalogWorkflowUuid(long projectDeploymentId) {
        Map<String, ProjectDeploymentWorkflow> rowsByUuid = new LinkedHashMap<>();

        for (ProjectDeploymentWorkflow row : projectDeploymentWorkflowService.getProjectDeploymentWorkflows(
            projectDeploymentId)) {

            ProjectWorkflow projectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(row.getWorkflowId());

            rowsByUuid.put(projectWorkflow.getUuidAsString(), row);
        }

        return rowsByUuid;
    }

    private static ProjectDeploymentWorkflow copyOf(ProjectDeploymentWorkflow existingRow) {
        ProjectDeploymentWorkflow row = new ProjectDeploymentWorkflow();

        row.setConnections(existingRow.getConnections());
        row.setEnabled(existingRow.isEnabled());
        row.setInputs(existingRow.getInputs());
        row.setProjectDeploymentId(existingRow.getProjectDeploymentId());
        row.setWorkflowId(existingRow.getWorkflowId());

        return row;
    }

    public record ReferenceResolution(
        RowSpec rowSpec, @Nullable String missingComponentName, @Nullable String missingInputName) {
    }

    public record RowSpec(ResolvedWorkflowConnections resolved, boolean enabled, @Nullable Map<String, ?> inputs) {
    }
}
```

Confirm before relying on them: `ProjectWorkflowService.fetchProjectWorkflow(long, int, String)` and `getWorkflowProjectWorkflow(String)` exist; `ProjectDeploymentService.getProjectDeployment(long)` exists; `ProjectDeploymentWorkflow.setInputs(null)` is a no-op; `createProjectDeployment(ProjectDeployment, List<ProjectDeploymentWorkflow>, List<Tag>)` accepts an empty row list (it rejects an unpublished project or a DRAFT version, which `getLastPublishedVersion` avoids). If `deleteProjectDeployment`'s `@PreAuthorize` still denies, `@SkipAutomationAuthorization` is not applied to that call — the IntTest shows it.

- [ ] **Step 4: Rewrite the reference facade**

Interface:

```java
default ConnectedUserProjectWorkflow getOrCreateReference(
    String externalUserId, String catalogWorkflowUuid, Environment environment) {

    return getOrCreateReference(externalUserId, catalogWorkflowUuid, environment, Map.of());
}

ConnectedUserProjectWorkflow getOrCreateReference(
    String externalUserId, String catalogWorkflowUuid, Environment environment,
    Map<String, Long> requestedConnectionIds);
```

Implementation collaborators become: `AutomationWorkflowProjectFacade`, `ConnectedUserProjectWorkflowManager`, `ConnectedUserProjectWorkflowRepository`, `ConnectedUserReferenceDeploymentManager`, `ConnectedUserService` (the resolver is now reached through the manager). Delete `getOrCreateProjectDeployment` and `rewireConnections`. Rename `validateCatalogWorkflowTemplateVisible` to `getVisibleCatalogProject` returning the matching `AutomationWorkflowProjectDTO` (same stream, `filter(...).findFirst().orElseThrow(() -> new IllegalArgumentException("Not a published catalog workflow template: " + catalogWorkflowUuid))`).

```java
@Override
@Transactional(noRollbackFor = MissingConnectionException.class)
public ConnectedUserProjectWorkflow getOrCreateReference(
    String externalUserId, String catalogWorkflowUuid, Environment environment,
    Map<String, Long> requestedConnectionIds) {

    ConnectedUserProject connectedUserProject = connectedUserProjectWorkflowManager
        .getOrCreateConnectedUserProject(externalUserId, environment);

    Optional<ConnectedUserProjectWorkflow> existing = connectedUserProjectWorkflowRepository
        .findByConnectedUserProjectIdAndCatalogWorkflowUuid(connectedUserProject.getId(), catalogWorkflowUuid);

    if (existing.isPresent()) {
        ConnectedUserProjectWorkflow reference = existing.get();

        if (!requestedConnectionIds.isEmpty() && !reference.isDangling()) {
            return applyWorkflow(
                reference, externalUserId, environment, reference.isEnabled(), false, requestedConnectionIds);
        }

        return reference;
    }

    AutomationWorkflowProjectDTO catalogProject = getVisibleCatalogProject(
        externalUserId, catalogWorkflowUuid, environment);

    long projectDeploymentId = connectedUserReferenceDeploymentManager.getOrCreateDeployment(
        catalogProject.id(), externalUserId, environment);

    ConnectedUserProjectWorkflow reference = new ConnectedUserProjectWorkflow();

    reference.setCatalogWorkflowUuid(catalogWorkflowUuid);
    reference.setConnectedUserProjectId(connectedUserProject.getId());
    reference.setEnabled(false);
    reference.setProjectDeploymentId(projectDeploymentId);

    return applyWorkflow(
        connectedUserProjectWorkflowRepository.save(reference), externalUserId, environment, true, false,
        requestedConnectionIds);
}

@Override
public void enableReference(
    String externalUserId, String catalogWorkflowUuid, boolean enable, Environment environment) {

    ConnectedUserProjectWorkflow reference = requireReference(externalUserId, catalogWorkflowUuid, environment);

    if (reference.isDangling()) {
        if (enable) {
            throw new ConfigurationException(
                "Reference to catalog workflow %s is dangling".formatted(catalogWorkflowUuid),
                WorkflowErrorType.WORKFLOW_NOT_FOUND);
        }

        return;
    }

    applyWorkflow(reference, externalUserId, environment, enable, true, Map.of());
}

@Override
public void deleteReference(String externalUserId, String catalogWorkflowUuid, Environment environment) {
    ConnectedUserProjectWorkflow reference = requireReference(externalUserId, catalogWorkflowUuid, environment);

    if (!reference.isDangling()) {
        connectedUserReferenceDeploymentManager.removeWorkflow(
            reference.getProjectDeploymentId(), catalogWorkflowUuid);
    }

    connectedUserProjectWorkflowRepository.deleteById(reference.getId());
}

/**
 * Resolves the reference at its deployment's current version, writes its row (other rows are kept) and saves the
 * reference. When enabling was asked for, a missing required connection throws {@link MissingConnectionException}
 * and a missing required input throws {@link MissingInputException} -- in both cases AFTER the reference is saved
 * disabled. On provisioning ({@code throwOnMissingInput == false}) a missing input is not an error: inputs are
 * written after provisioning in both the hub and the API flow, so the reference is simply left disabled.
 */
private ConnectedUserProjectWorkflow applyWorkflow(
    ConnectedUserProjectWorkflow reference, String externalUserId, Environment environment, boolean enable,
    boolean throwOnMissingInput, Map<String, Long> requestedConnectionIds) {

    ConnectedUser connectedUser = connectedUserService.getConnectedUser(externalUserId, environment);
    ProjectDeployment projectDeployment = connectedUserReferenceDeploymentManager.getDeployment(
        reference.getProjectDeploymentId());

    int projectVersion = projectDeployment.getProjectVersion();
    String catalogWorkflowUuid = reference.getCatalogWorkflowUuid();

    String workflowId = connectedUserReferenceDeploymentManager.getWorkflowId(
        projectDeployment.getProjectId(), projectVersion, catalogWorkflowUuid);

    Optional<ProjectDeploymentWorkflow> currentRow = connectedUserReferenceDeploymentManager.fetchRow(
        reference.getProjectDeploymentId(), catalogWorkflowUuid);

    ConnectedUserReferenceDeploymentManager.ReferenceResolution resolution =
        connectedUserReferenceDeploymentManager.resolveReference(
            connectedUser.getId(), workflowId, enable, requestedConnectionIds,
            currentRow.map(ProjectDeploymentWorkflow::getConnections)
                .orElse(List.of()),
            currentRow.<Map<String, ?>>map(ProjectDeploymentWorkflow::getInputs)
                .orElse(Map.of()));

    connectedUserReferenceDeploymentManager.putWorkflows(
        reference.getProjectDeploymentId(), projectVersion, Map.of(catalogWorkflowUuid, resolution.rowSpec()));

    ConnectedUserReferenceDeploymentManager.RowSpec rowSpec = resolution.rowSpec();

    reference.setEnabled(rowSpec.enabled());

    ConnectedUserProjectWorkflow saved = connectedUserProjectWorkflowRepository.save(reference);

    if (enable && resolution.missingComponentName() != null) {
        throw new MissingConnectionException(resolution.missingComponentName());
    }

    if (enable && throwOnMissingInput && resolution.missingInputName() != null) {
        throw new MissingInputException(resolution.missingInputName());
    }

    return saved;
}
```

Call sites: both `applyWorkflow` calls in `getOrCreateReference` pass `throwOnMissingInput = false`; `enableReference` passes `true`. The class-level `@Transactional(noRollbackFor = MissingConnectionException.class)` on `getOrCreateReference` is extended to `{MissingConnectionException.class, MissingInputException.class}` on `enableReference` so the disabled reference survives both 409s.

`MissingInputException`:

```java
package com.bytechef.ee.embedded.configuration.exception;

/**
 * Enabling a reference was refused because a required workflow input has no value. Mapped to 409, like
 * {@link MissingConnectionException}; deliberately not an {@code AbstractException}, which would map to 400.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class MissingInputException extends RuntimeException {

    private final String inputName;

    public MissingInputException(String inputName) {
        super("No value for required input: " + inputName);

        this.inputName = inputName;
    }

    public String getInputName() {
        return inputName;
    }
}
```

Keep `requireReference` and `markDanglingReferences` (add `reference.setEnabled(false);` beside `setDangling(true)` there).

`ConnectedUserProjectFacadeImpl.copyWorkflowTemplate` — replace the `anyMatch` visibility check with a lookup that keeps the project:

```java
AutomationWorkflowProjectDTO catalogProject = automationWorkflowProjectFacade
    .getPublishedProjects(externalUserId, environment)
    .stream()
    .filter(project -> CollectionUtils.stream(project.workflowTemplates())
        .anyMatch(workflowTemplate -> Objects.equals(workflowTemplate.workflowUuid(), workflowUuid)))
    .findFirst()
    .orElseThrow(() -> new IllegalArgumentException("Not a published catalog workflow template: " + workflowUuid));

if (catalogProject.codeWorkflowProject()) {
    throw new CodeWorkflowNotCopyableException(workflowUuid);
}
```

`CodeWorkflowNotCopyableException`:

```java
package com.bytechef.ee.embedded.configuration.exception;

/**
 * A code workflow template cannot be copied into a connected user's project; it can only be referenced. Mapped to 409.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class CodeWorkflowNotCopyableException extends RuntimeException {

    public CodeWorkflowNotCopyableException(String workflowUuid) {
        super("Catalog workflow template %s is a code workflow and can only be referenced".formatted(workflowUuid));
    }
}
```

Remote client/controller: add the 4-arg method; send `requestedConnectionIds` as a JSON body on the existing remote `getOrCreateReference` endpoint (`@RequestBody(required = false) Map<String, Long>`; null → `Map.of()`). Update `RemoteConnectedUserCodeWorkflowReferenceFacadeClientTest` and `...ControllerTest`.

`ConnectedUserCodeWorkflowReferenceFacadeTest`: rebuild the `@Mock` list and constructor for the new collaborators; rewrite the tests that used `createProjectDeployment`, `getProjectDeploymentWorkflow` or the bookkeeping repository so they stub `connectedUserReferenceDeploymentManager` (`getOrCreateDeployment` → `900L`; `getDeployment(900L)` → a `ProjectDeployment` with project id `500` and version `1`; `getWorkflowId(500L, 1, "catalog-uuid")` → `"catalog-wf-1"`; `fetchRow` → `Optional.empty()`; `resolveReference(...)` → a `ReferenceResolution` built for the case) and verify `putWorkflows(900L, 1, Map.of("catalog-uuid", <that RowSpec>))`, and for delete `removeWorkflow(900L, "catalog-uuid")`. Add `testEnableReferenceThrowsMissingInputWhenARequiredInputHasNoValue` (resolution with `missingInputName = "channel"` ⇒ `MissingInputException`, reference saved disabled) and `testGetOrCreateReferenceLeavesReferenceDisabledWithoutErrorWhenARequiredInputHasNoValue`. The resolver's own behaviour now belongs to a new `ConnectedUserReferenceDeploymentManagerTest` covering `resolveReference` (enabled only when complete and no missing input) and `findMissingRequiredInput` (blank counts as missing; optional inputs ignored).

- [ ] **Step 5: Run to verify**

Run: `./gradlew EC_SERVICE:test EC_SERVICE:testIntegration --tests "*ConnectedUserReferenceRolloutIntTest" --tests "*AutomationCodeWorkflowBridgeIntTest" EC_REMOTE_CLIENT:test EC_REMOTE_REST:test EC_PUBLIC_REST:testIntegration --continue > /tmp/t3.log 2>&1; echo $?`
Expected: `0`.

- [ ] **Step 6: Commit**

```bash
git add <new files>
git commit -m "- Provision embedded references of any template into one versioned per-user deployment" -- <every file of this task>
```

---

### Task 4: Inputs on references (D5)

**Files:**
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserReferenceDeploymentManager.java`
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserProjectFacadeImpl.java` (`updateProjectWorkflowInputs` 534-561, `getReferenceRows` 710-752, constructor)
- Test: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/ConnectedUserReferenceRolloutIntTest.java`

**Interfaces:**
- Consumes: `putWorkflows`, `fetchRow`, `RowSpec` (Task 3).
- Produces:
  - `Map<String, ?> ConnectedUserReferenceDeploymentManager.getInputs(long projectDeploymentId, String catalogWorkflowUuid)`
  - `void ConnectedUserReferenceDeploymentManager.updateInputs(long projectDeploymentId, String catalogWorkflowUuid, Map<String, ?> inputs)`

- [ ] **Step 1: Write the failing IntTest**

```java
private static final String SLACK_WORKFLOW_WITH_CHANNEL_INPUT_DEFINITION = """
    {"label":"Post","inputs":[{"name":"channel","label":"Channel","type":"string","required":true}],
     "triggers":[],"tasks":[{"name":"postMessage1","type":"slack/v1/postMessage","parameters":{}}]}
    """;

@Test
void testInputsCanBeSetOnAReferenceAndAreListed() {
    long catalogProjectId = createCatalogProject("Inputs");

    String workflowUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_WITH_CHANNEL_INPUT_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION);

    long environmentId = Environment.PRODUCTION.ordinal();

    when(environmentService.getEnvironment(environmentId)).thenReturn(Environment.PRODUCTION);

    connectedUserProjectFacade.updateProjectWorkflowInputs(
        EXTERNAL_USER_ID, workflowUuid, Map.of("channel", "#alerts"), environmentId);

    assertThat(connectedUserProjectFacade.getConnectedUserProjectWorkflows(EXTERNAL_USER_ID, Environment.PRODUCTION))
        .filteredOn(workflow -> Objects.equals(workflow.catalogWorkflowUuid(), workflowUuid))
        .singleElement()
        .extracting(ConnectedUserProjectWorkflowDTO::inputValues)
        .isEqualTo(Map.of("channel", "#alerts"));
}
```

Use the accessor names `ConnectedUserProjectWorkflowDTO` actually declares. Also add:

```java
@Test
void testProvisionWithAMissingRequiredInputSucceedsDisabledAndEnableRefusesUntilItIsSet() {
    long catalogProjectId = createCatalogProject("Required Input");

    String workflowUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_WITH_CHANNEL_INPUT_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    ConnectedUserProjectWorkflow reference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION);

    assertThat(reference.isEnabled()).isFalse();

    assertThatThrownBy(() -> connectedUserCodeWorkflowReferenceFacade.enableReference(
        EXTERNAL_USER_ID, workflowUuid, true, Environment.PRODUCTION))
            .isInstanceOf(MissingInputException.class)
            .extracting("inputName")
            .isEqualTo("channel");

    connectedUserReferenceDeploymentManager.updateInputs(
        reference.getProjectDeploymentId(), workflowUuid, Map.of("channel", "#alerts"));

    connectedUserCodeWorkflowReferenceFacade.enableReference(
        EXTERNAL_USER_ID, workflowUuid, true, Environment.PRODUCTION);

    assertThat(connectedUserProjectWorkflowRepository.findById(reference.getId()))
        .get()
        .extracting(ConnectedUserProjectWorkflow::isEnabled)
        .isEqualTo(true);
}
```

Run: `./gradlew EC_SERVICE:testIntegration --tests "*ConnectedUserReferenceRolloutIntTest" > /tmp/t4.log 2>&1; echo $?` → non-zero: the inputs test with `WORKFLOW_NOT_FOUND`; against the Task 3 code the required-input test fails on provisioning itself (`ProjectDeploymentFacadeImpl` refuses the enabled row) — which is the defect this task closes.

- [ ] **Step 2: Implement**

Manager:

```java
public Map<String, ?> getInputs(long projectDeploymentId, String catalogWorkflowUuid) {
    return fetchRow(projectDeploymentId, catalogWorkflowUuid)
        .<Map<String, ?>>map(ProjectDeploymentWorkflow::getInputs)
        .orElse(Map.of());
}

/**
 * Rewrites the row through {@link #putWorkflows} so an enabled row's triggers re-register with the new inputs. An
 * empty map cannot clear inputs: {@code ProjectDeploymentWorkflow.setInputs} ignores it, the same limit the copy path
 * has.
 */
public void updateInputs(long projectDeploymentId, String catalogWorkflowUuid, Map<String, ?> inputs) {
    ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);
    ProjectDeploymentWorkflow row = fetchRow(projectDeploymentId, catalogWorkflowUuid)
        .orElseThrow(() -> new IllegalArgumentException(
            "Catalog workflow %s is not deployed in deployment id=%s".formatted(
                catalogWorkflowUuid, projectDeploymentId)));

    RowSpec rowSpec = new RowSpec(
        new ResolvedWorkflowConnections(row.getConnections(), List.of()), row.isEnabled(), inputs);

    putWorkflows(projectDeploymentId, projectDeployment.getProjectVersion(), Map.of(catalogWorkflowUuid, rowSpec));
}
```

`ConnectedUserProjectFacadeImpl` — inject `ConnectedUserReferenceDeploymentManager`; in `updateProjectWorkflowInputs` after `connectedUserProject` is resolved:

```java
Optional<ConnectedUserProjectWorkflow> reference = connectedUserProjectWorkflowRepository
    .findByConnectedUserProjectIdAndCatalogWorkflowUuid(connectedUserProject.getId(), workflowUuid);

if (reference.isPresent()) {
    ConnectedUserProjectWorkflow connectedUserProjectWorkflow = reference.get();

    connectedUserReferenceDeploymentManager.updateInputs(
        connectedUserProjectWorkflow.getProjectDeploymentId(), workflowUuid, inputs);

    return;
}
```

In `getReferenceRows` replace the trailing `Map.of()` passed to `ConnectedUserProjectWorkflowDTO.ofReference` with:

```java
reference.isDangling()
    ? Map.of()
    : connectedUserReferenceDeploymentManager.getInputs(
        reference.getProjectDeploymentId(), reference.getCatalogWorkflowUuid())
```

Fix every construction of `ConnectedUserProjectFacadeImpl` in tests (`grep -rn "new ConnectedUserProjectFacadeImpl(" server --include='*.java'`) with a mock argument.

- [ ] **Step 3: Verify**

Run: `./gradlew EC_SERVICE:test EC_SERVICE:testIntegration --tests "*ConnectedUserReferenceRolloutIntTest" EC_PUBLIC_REST:testIntegration --continue > /tmp/t4.log 2>&1; echo $?` → `0`.

- [ ] **Step 4: Commit**

```bash
git commit -m "- Store and list input values on embedded automation references" -- <files>
```

---

### Task 5: Republish rollout

**Files:**
- Create: `EC/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/event/CatalogProjectPublishedEvent.java`
- Create: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserReferenceRolloutService.java`
- Create: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/listener/CatalogProjectPublishedEventListener.java`
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/AutomationWorkflowProjectFacadeImpl.java` (`publishProject`; inject `ApplicationEventPublisher`)
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/AutomationWorkflowProjectCodeWorkflowFacadeImpl.java` (after `markDanglingReferences`, ~187; inject `ApplicationEventPublisher`)
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserCodeWorkflowReferenceFacadeImpl.java` (lazy catch-up)
- Test: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/ConnectedUserReferenceRolloutIntTest.java`
- Create: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/CatalogProjectPublishedEventListenerIntTest.java`
- Create: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserReferenceRolloutServiceTest.java`

**Interfaces:**
- Consumes: manager `resolveReference`, `ReferenceResolution`, `getDeployment`, `getLastPublishedVersion`, `getWorkflowId`, `fetchRow`, `putWorkflows`, `deleteDeployment`, `RowSpec` (Task 3); `ConnectedUserProjectService.getConnectedUserProject(long)` and `ConnectedUserProject.getConnectedUserId()`; `ProjectDeploymentService.getAllProjectDeployments(long)`.
- Produces:
  - `record CatalogProjectPublishedEvent(long projectId)`
  - `ConnectedUserReferenceRolloutService` (`@Service @ConditionalOnEEVersion @SkipAutomationAuthorization`): `void rollOut(long catalogProjectId)`, `void rollOutDeploymentIfBehind(long projectDeploymentId)`. It depends on the manager only — never on the reference facade — so no `@Lazy` and no constructor cycle.

- [ ] **Step 1: Write the failing IntTests**

In `ConnectedUserReferenceRolloutIntTest` add `@MockitoBean private CatalogProjectPublishedEventListener catalogProjectPublishedEventListener;` (so publishing never starts an async rollout racing the direct calls), autowire `ConnectedUserReferenceRolloutService`, `ConnectedUserReferenceDeploymentManager` and `WorkflowService`, and add:

```java
private static final String SLACK_AND_JIRA_WORKFLOW_DEFINITION = """
    {"label":"Post","triggers":[],"tasks":[{"name":"postMessage1","type":"slack/v1/postMessage","parameters":{}},
     {"name":"createIssue1","type":"jira/v1/createIssue","parameters":{}}]}
    """;

@Test
void testRepublishMovesReferencesToTheNewVersionAndKeepsInputs() {
    long catalogProjectId = createCatalogProject("Rollout");

    String firstUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_WITH_CHANNEL_INPUT_DEFINITION);
    String secondUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    ConnectedUserProjectWorkflow first = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, firstUuid, Environment.PRODUCTION);

    connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, secondUuid, Environment.PRODUCTION);

    connectedUserReferenceDeploymentManager.updateInputs(
        first.getProjectDeploymentId(), firstUuid, Map.of("channel", "#alerts"));

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    connectedUserReferenceRolloutService.rollOut(catalogProjectId);

    long projectDeploymentId = first.getProjectDeploymentId();

    assertThat(projectDeploymentService.getProjectDeployment(projectDeploymentId)
        .getProjectVersion()).isEqualTo(2);
    assertThat(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(projectDeploymentId)).hasSize(2);
    assertThat(connectedUserReferenceDeploymentManager.getInputs(projectDeploymentId, firstUuid))
        .isEqualTo(Map.of("channel", "#alerts"));
    assertThat(connectedUserProjectWorkflowRepository.findById(first.getId()))
        .get()
        .extracting(ConnectedUserProjectWorkflow::isEnabled)
        .isEqualTo(true);
}

@Test
void testRepublishAddingAConnectionTheUserLacksDisablesOnlyThatReference() {
    long catalogProjectId = createCatalogProject("Rollout Missing");

    String slackUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);
    String otherUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    ConnectedUserProjectWorkflow slackReference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, slackUuid, Environment.PRODUCTION);
    ConnectedUserProjectWorkflow otherReference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, otherUuid, Environment.PRODUCTION);

    String draftWorkflowId = projectWorkflowService.getLastWorkflowId(slackUuid);
    Workflow draftWorkflow = workflowService.getWorkflow(draftWorkflowId);

    workflowService.update(draftWorkflowId, SLACK_AND_JIRA_WORKFLOW_DEFINITION, draftWorkflow.getVersion());

    when(componentConnectionFacade.getComponentConnections(any(WorkflowTask.class)))
        .thenAnswer(invocation -> switch (invocation.<WorkflowTask>getArgument(0)
            .getName()) {
            case "postMessage1" -> List.of(new ComponentConnection("slack", 1, "postMessage1", "slack", true));
            case "createIssue1" -> List.of(new ComponentConnection("jira", 1, "createIssue1", "jira", true));
            default -> List.of();
        });
    when(connectedUserConnectionFacade.getConnections(anyLong(), eq("jira"), eq(List.of()))).thenReturn(List.of());

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    connectedUserReferenceRolloutService.rollOut(catalogProjectId);

    assertThat(connectedUserProjectWorkflowRepository.findById(slackReference.getId()))
        .get()
        .extracting(ConnectedUserProjectWorkflow::isEnabled)
        .isEqualTo(false);
    assertThat(connectedUserProjectWorkflowRepository.findById(otherReference.getId()))
        .get()
        .extracting(ConnectedUserProjectWorkflow::isEnabled)
        .isEqualTo(true);
}

@Test
void testRepublishWithoutATemplateMarksItsReferenceDangling() {
    long catalogProjectId = createCatalogProject("Rollout Removed");

    String keptUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);
    String removedUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    ConnectedUserProjectWorkflow keptReference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, keptUuid, Environment.PRODUCTION);
    ConnectedUserProjectWorkflow removedReference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, removedUuid, Environment.PRODUCTION);

    automationWorkflowProjectFacade.deleteProjectWorkflow(removedUuid);
    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    connectedUserReferenceRolloutService.rollOut(catalogProjectId);

    assertThat(connectedUserProjectWorkflowRepository.findById(removedReference.getId()))
        .get()
        .satisfies(reference -> {
            assertThat(reference.isDangling()).isTrue();
            assertThat(reference.isEnabled()).isFalse();
        });
    assertThat(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(
        keptReference.getProjectDeploymentId())).hasSize(1);
}

@Test
void testRepublishWithoutAnyReferencedTemplateDeletesTheDeployment() {
    long catalogProjectId = createCatalogProject("Rollout All Removed");

    String keptUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);
    String onlyReferencedUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    ConnectedUserProjectWorkflow reference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, onlyReferencedUuid, Environment.PRODUCTION);

    automationWorkflowProjectFacade.deleteProjectWorkflow(onlyReferencedUuid);
    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    connectedUserReferenceRolloutService.rollOut(catalogProjectId);

    assertThat(connectedUserProjectWorkflowRepository.findById(reference.getId()))
        .get()
        .extracting(ConnectedUserProjectWorkflow::isDangling)
        .isEqualTo(true);
    assertThat(projectDeploymentService.fetchProjectDeploymentByName(
        catalogProjectId, "__EMBEDDED__" + EXTERNAL_USER_ID + "__PRODUCTION")).isEmpty();
    assertThat(keptUuid).isNotNull();
}

@Test
void testEnableCatchesUpADeploymentLeftBehind() {
    long catalogProjectId = createCatalogProject("Rollout Lazy");

    String workflowUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    stubEntitledSlackConnection(777L);

    ConnectedUserProjectWorkflow reference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
        EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    connectedUserCodeWorkflowReferenceFacade.enableReference(
        EXTERNAL_USER_ID, workflowUuid, true, Environment.PRODUCTION);

    assertThat(projectDeploymentService.getProjectDeployment(reference.getProjectDeploymentId())
        .getProjectVersion()).isEqualTo(2);
}
```

`CatalogProjectPublishedEventListenerIntTest` — annotations and `@MockitoBean` list of `AutomationWorkflowProjectFacadeIntTest`, plus `@MockitoBean ConnectedUserReferenceRolloutService connectedUserReferenceRolloutService`:

```java
@Test
void testPublishProjectHandsTheRolloutToTheListenerAfterCommit() {
    long catalogProjectId = automationWorkflowProjectFacade.createProject(
        "Listener " + UUID.randomUUID(), "", null, List.of(), null);

    automationWorkflowProjectFacade.createProjectWorkflow(
        catalogProjectId, "{\"label\":\"x\",\"triggers\":[],\"tasks\":[]}", null);

    automationWorkflowProjectFacade.publishProject(catalogProjectId);

    verify(connectedUserReferenceRolloutService, timeout(5000)).rollOut(catalogProjectId);
}
```

`ConnectedUserReferenceRolloutServiceTest` (Mockito): two deployments (901, 902) of project 500, one reference each, both behind version 2; `putWorkflows(eq(901L), eq(2), anyMap())` throws `IllegalStateException`; assert `putWorkflows(eq(902L), eq(2), anyMap())` is still invoked and no exception escapes `rollOut(500L)`. A second test: a deployment whose only reference is already dangling ⇒ `deleteDeployment(901L)` is called and `putWorkflows` is not. Construct the service with a `PlatformTransactionManager` mock whose `getTransaction(any())` returns `new SimpleTransactionStatus()`.

Run: `./gradlew EC_SERVICE:test EC_SERVICE:testIntegration --tests "*ConnectedUserReferenceRolloutIntTest" --tests "*CatalogProjectPublishedEventListenerIntTest" --continue > /tmp/t5.log 2>&1; echo $?` → non-zero.

- [ ] **Step 2: Event, publishers, listener**

```java
package com.bytechef.ee.embedded.configuration.event;

/**
 * Published after an embedded automation catalog project is published, so reference deployments can follow it.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public record CatalogProjectPublishedEvent(long projectId) {
}
```

`AutomationWorkflowProjectFacadeImpl.publishProject` — last statement `applicationEventPublisher.publishEvent(new CatalogProjectPublishedEvent(projectId));`. `AutomationWorkflowProjectCodeWorkflowFacadeImpl.save` — right after `markDanglingReferences(...)`: `applicationEventPublisher.publishEvent(new CatalogProjectPublishedEvent(project.getId()));`.

```java
package com.bytechef.ee.embedded.configuration.listener;

/**
 * Hands a catalog project publish to the reference rollout once the publishing transaction commits. Runs on
 * {@code workerExecutor}: re-registering triggers runs component code and may call external APIs.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class CatalogProjectPublishedEventListener {

    private final ConnectedUserReferenceRolloutService connectedUserReferenceRolloutService;

    @SuppressFBWarnings("EI")
    public CatalogProjectPublishedEventListener(
        ConnectedUserReferenceRolloutService connectedUserReferenceRolloutService) {

        this.connectedUserReferenceRolloutService = connectedUserReferenceRolloutService;
    }

    @Async("workerExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCatalogProjectPublished(CatalogProjectPublishedEvent catalogProjectPublishedEvent) {
        connectedUserReferenceRolloutService.rollOut(catalogProjectPublishedEvent.projectId());
    }
}
```

- [ ] **Step 3: Rollout service**

```java
package com.bytechef.ee.embedded.configuration.facade;

/**
 * Brings every reference deployment of a catalog project to the project's last published version, each deployment
 * in its own transaction so one user's failure never blocks another's. Idempotent: a deployment already at the target
 * version is skipped. {@code @SkipAutomationAuthorization} lives here, not on the listener: its aspect applies on the
 * thread that calls this bean, and the publishing thread's bypass does not follow {@code @Async}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnEEVersion
@SkipAutomationAuthorization
public class ConnectedUserReferenceRolloutService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectedUserReferenceRolloutService.class);

    private final ConnectedUserProjectService connectedUserProjectService;
    private final ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;
    private final ConnectedUserReferenceDeploymentManager connectedUserReferenceDeploymentManager;
    private final ProjectDeploymentService projectDeploymentService;
    private final ProjectWorkflowService projectWorkflowService;
    private final TransactionTemplate requiresNewTransactionTemplate;

    @SuppressFBWarnings("EI")
    public ConnectedUserReferenceRolloutService(
        ConnectedUserProjectService connectedUserProjectService,
        ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository,
        ConnectedUserReferenceDeploymentManager connectedUserReferenceDeploymentManager,
        PlatformTransactionManager platformTransactionManager, ProjectDeploymentService projectDeploymentService,
        ProjectWorkflowService projectWorkflowService) {

        this.connectedUserProjectService = connectedUserProjectService;
        this.connectedUserProjectWorkflowRepository = connectedUserProjectWorkflowRepository;
        this.connectedUserReferenceDeploymentManager = connectedUserReferenceDeploymentManager;
        this.projectDeploymentService = projectDeploymentService;
        this.projectWorkflowService = projectWorkflowService;

        TransactionTemplate transactionTemplate = new TransactionTemplate(platformTransactionManager);

        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.requiresNewTransactionTemplate = transactionTemplate;
    }

    public void rollOut(long catalogProjectId) {
        int lastPublishedVersion = connectedUserReferenceDeploymentManager.getLastPublishedVersion(catalogProjectId);

        Map<Long, List<ConnectedUserProjectWorkflow>> referencesByDeploymentId = getReferencesByDeploymentId();

        for (ProjectDeployment projectDeployment : projectDeploymentService.getAllProjectDeployments(
            catalogProjectId)) {

            List<ConnectedUserProjectWorkflow> references = referencesByDeploymentId.getOrDefault(
                projectDeployment.getId(), List.of());

            if (references.isEmpty() || projectDeployment.getProjectVersion() >= lastPublishedVersion) {
                continue;
            }

            try {
                requiresNewTransactionTemplate.executeWithoutResult(
                    status -> rollOutDeployment(projectDeployment, lastPublishedVersion, references));
            } catch (RuntimeException exception) {
                logger.error(
                    "Rolling out catalog project id={} to deployment id={} failed", catalogProjectId,
                    projectDeployment.getId(), exception);
            }
        }
    }

    public void rollOutDeploymentIfBehind(long projectDeploymentId) {
        ProjectDeployment projectDeployment = connectedUserReferenceDeploymentManager.getDeployment(
            projectDeploymentId);

        int lastPublishedVersion = connectedUserReferenceDeploymentManager.getLastPublishedVersion(
            projectDeployment.getProjectId());

        if (projectDeployment.getProjectVersion() >= lastPublishedVersion) {
            return;
        }

        rollOutDeployment(
            projectDeployment, lastPublishedVersion,
            getReferencesByDeploymentId().getOrDefault(projectDeploymentId, List.of()));
    }

    // Dangling references are included on purpose: a deployment whose every reference dangles must still be
    // emptied and deleted, or its old triggers keep running.
    private Map<Long, List<ConnectedUserProjectWorkflow>> getReferencesByDeploymentId() {
        return connectedUserProjectWorkflowRepository.findAll()
            .stream()
            .filter(reference -> reference.getCatalogWorkflowUuid() != null &&
                reference.getProjectDeploymentId() != null)
            .collect(Collectors.groupingBy(ConnectedUserProjectWorkflow::getProjectDeploymentId));
    }

    private void rollOutDeployment(
        ProjectDeployment projectDeployment, int lastPublishedVersion,
        List<ConnectedUserProjectWorkflow> references) {

        long catalogProjectId = projectDeployment.getProjectId();
        long projectDeploymentId = projectDeployment.getId();

        Set<String> publishedUuids = projectWorkflowService.getProjectWorkflows(catalogProjectId, lastPublishedVersion)
            .stream()
            .map(ProjectWorkflow::getUuidAsString)
            .collect(Collectors.toSet());

        Map<String, ConnectedUserReferenceDeploymentManager.RowSpec> rowSpecs = new LinkedHashMap<>();
        List<ConnectedUserProjectWorkflow> removedReferences = new ArrayList<>();

        for (ConnectedUserProjectWorkflow reference : references) {
            String catalogWorkflowUuid = reference.getCatalogWorkflowUuid();

            if (reference.isDangling() || !publishedUuids.contains(catalogWorkflowUuid)) {
                removedReferences.add(reference);

                continue;
            }

            ConnectedUserProject connectedUserProject = connectedUserProjectService.getConnectedUserProject(
                reference.getConnectedUserProjectId());
            Optional<ProjectDeploymentWorkflow> currentRow = connectedUserReferenceDeploymentManager.fetchRow(
                projectDeploymentId, catalogWorkflowUuid);

            String workflowId = connectedUserReferenceDeploymentManager.getWorkflowId(
                catalogProjectId, lastPublishedVersion, catalogWorkflowUuid);

            ConnectedUserReferenceDeploymentManager.ReferenceResolution resolution =
                connectedUserReferenceDeploymentManager.resolveReference(
                    connectedUserProject.getConnectedUserId(), workflowId, reference.isEnabled(), Map.of(),
                    currentRow.map(ProjectDeploymentWorkflow::getConnections)
                        .orElse(List.of()),
                    currentRow.<Map<String, ?>>map(ProjectDeploymentWorkflow::getInputs)
                        .orElse(Map.of()));

            ConnectedUserReferenceDeploymentManager.RowSpec rowSpec = resolution.rowSpec();

            rowSpecs.put(catalogWorkflowUuid, rowSpec);

            reference.setEnabled(rowSpec.enabled());
        }

        if (rowSpecs.isEmpty()) {
            connectedUserReferenceDeploymentManager.deleteDeployment(projectDeploymentId);
        } else {
            connectedUserReferenceDeploymentManager.putWorkflows(projectDeploymentId, lastPublishedVersion, rowSpecs);
        }

        for (ConnectedUserProjectWorkflow reference : references) {
            if (removedReferences.contains(reference) && !reference.isDangling()) {
                reference.setDangling(true);
                reference.setDanglingReason("Removed from the catalog project on publish");
                reference.setEnabled(false);
            }

            connectedUserProjectWorkflowRepository.save(reference);
        }
    }
}
```

`putWorkflows` passes rows at a new version as the whole list, so the rows of removed templates are dropped (the facade disables their triggers first); when nothing remains the deployment itself goes. The reference saves run inside the same `REQUIRES_NEW` transaction as the deployment update. A reference the rollout could not enable (missing connection or input) is not an error here — the derived attention reason (Task 6) tells the user why.

- [ ] **Step 4: Lazy catch-up**

In `ConnectedUserCodeWorkflowReferenceFacadeImpl` inject `ConnectedUserReferenceRolloutService connectedUserReferenceRolloutService` (plain constructor injection: the rollout depends on the manager, not on the facade, so there is no cycle). In `enableReference` after `requireReference`, and in the existing-reference branch of `getOrCreateReference` before anything else:

```java
if (!reference.isDangling()) {
    connectedUserReferenceRolloutService.rollOutDeploymentIfBehind(reference.getProjectDeploymentId());

    reference = connectedUserProjectWorkflowRepository.findById(reference.getId())
        .orElseThrow();
}
```

- [ ] **Step 5: Verify**

Run: `./gradlew EC_SERVICE:test EC_SERVICE:testIntegration --tests "*ConnectedUserReferenceRolloutIntTest" --tests "*CatalogProjectPublishedEventListenerIntTest" --tests "*AutomationCodeWorkflowBridgeIntTest" --tests "*AutomationWorkflowProjectFacadeIntTest" --continue > /tmp/t5.log 2>&1; echo $?` → `0`. If `AutomationWorkflowProjectFacadeIntTest` fails because its context lacks the listener's collaborators, add `ConnectedUserReferenceRolloutService.class` to its `@MockitoBean` list.

- [ ] **Step 6: Commit**

```bash
git add <new files>
git commit -m "- Roll embedded references forward when a catalog project is published" -- <every file of this task>
```

---

### Task 6: Derived attention reason and public API (provision body, copy guard response)

**Files:**
- Create: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserReferenceAttentionResolver.java`
- Create: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserReferenceAttentionResolverTest.java`
- Modify: `EC/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/dto/ConnectedUserProjectWorkflowDTO.java` (add `@Nullable String attentionReason`)
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserProjectFacadeImpl.java` (`getReferenceRows`)
- Modify: `EC/embedded-configuration-public-rest/openapi.yaml`
- Modify: `EC/embedded-configuration-public-rest/src/main/java/com/bytechef/ee/embedded/configuration/public_/web/rest/ConnectedUserProjectWorkflowApiController.java`
- Modify: `EC/embedded-configuration-public-rest/src/main/java/com/bytechef/ee/embedded/configuration/public_/web/rest/mapper/ConnectUserProjectWorkflowMapper.java`
- Test: `EC/embedded-configuration-public-rest/src/test/java/com/bytechef/ee/embedded/configuration/public_/web/rest/ConnectedUserProjectWorkflowApiControllerFrontendProvisionIntTest.java`
- Test: `EC/embedded-configuration-public-rest/src/test/java/com/bytechef/ee/embedded/configuration/public_/web/rest/ConnectedUserProjectWorkflowApiControllerReferenceIntTest.java`
- Test: `EC/embedded-configuration-public-rest/src/test/java/com/bytechef/ee/embedded/configuration/public_/web/rest/ConnectedUserProjectWorkflowApiControllerCopyIntTest.java`

**Interfaces:**
- Consumes: `WorkflowConnectionSlots.getSlots` (Task 1); manager `getDeployment`, `getLastPublishedVersion`, `getWorkflowId`, `fetchRow`, `findMissingRequiredInput` (Task 3); `getOrCreateReference(..., Map<String, Long>)` (Task 3); `ConnectionNotEntitledException`, `CodeWorkflowNotCopyableException`, `MissingInputException`.
- Produces:
  - `ConnectedUserReferenceAttentionResolver` (`@Component @ConditionalOnEEVersion`): `@Nullable String resolve(ConnectedUserProjectWorkflow reference)` returning `UPDATE_PENDING`, `MISSING_CONNECTION:<component>`, `INPUT_REQUIRED:<name>` or null, checked in that order; never throws — a reference whose catalog project cannot be resolved yields null and a WARN, so one broken row cannot fail the listing.
  - OpenAPI `ProvisionWorkflowReferenceRequest { connections: map<string,int64> }` (optional body on both provision POSTs); `attentionReason: string` on `ConnectedUserProjectWorkflow`; `CodeWorkflowNotCopyableError { reason: enum[CODE_WORKFLOW_NOT_COPYABLE] }` as a 409 on both `/copy` POSTs; `MissingInputError { missingInputName: string }` as a second 409 body on all four enable endpoints.

- [ ] **Step 1: Write the failing tests**

`ConnectedUserReferenceAttentionResolverTest` (Mockito; mocks `ConnectedUserReferenceDeploymentManager`, `WorkflowConnectionSlots`):

```java
@Test
void testDanglingReferenceHasNoAttentionReason() {
    ConnectedUserProjectWorkflow reference = reference();

    reference.setDangling(true);

    assertThat(resolver.resolve(reference)).isNull();
}

@Test
void testDeploymentBehindPublishedVersionIsUpdatePending() {
    stubDeployment(1);
    when(connectedUserReferenceDeploymentManager.getLastPublishedVersion(500L)).thenReturn(2);

    assertThat(resolver.resolve(reference())).isEqualTo("UPDATE_PENDING");
}

@Test
void testRequiredSlotWithoutConnectionIsMissingConnection() {
    stubCurrentDeploymentWithRow(List.of(), Map.of());
    when(workflowConnectionSlots.getSlots("catalog-wf-1"))
        .thenReturn(List.of(new ComponentConnection("slack", 1, "postMessage1", "slack", true)));

    assertThat(resolver.resolve(reference())).isEqualTo("MISSING_CONNECTION:slack");
}

@Test
void testMissingRequiredInputIsInputRequired() {
    stubCurrentDeploymentWithRow(
        List.of(new ProjectDeploymentWorkflowConnection(11L, "slack", "postMessage1")), Map.of());
    when(workflowConnectionSlots.getSlots("catalog-wf-1"))
        .thenReturn(List.of(new ComponentConnection("slack", 1, "postMessage1", "slack", true)));
    when(connectedUserReferenceDeploymentManager.findMissingRequiredInput("catalog-wf-1", Map.of()))
        .thenReturn("channel");

    assertThat(resolver.resolve(reference())).isEqualTo("INPUT_REQUIRED:channel");
}

@Test
void testUnresolvableCatalogProjectYieldsNoAttentionReasonInsteadOfFailing() {
    stubDeployment(1);
    when(connectedUserReferenceDeploymentManager.getLastPublishedVersion(500L))
        .thenThrow(new IllegalArgumentException("not published"));

    assertThat(resolver.resolve(reference())).isNull();
}

@Test
void testCompleteReferenceHasNoAttentionReason() {
    stubCurrentDeploymentWithRow(
        List.of(new ProjectDeploymentWorkflowConnection(11L, "slack", "postMessage1")), Map.of("channel", "#a"));
    when(workflowConnectionSlots.getSlots("catalog-wf-1"))
        .thenReturn(List.of(new ComponentConnection("slack", 1, "postMessage1", "slack", true)));

    assertThat(resolver.resolve(reference())).isNull();
}
```

with helpers: `reference()` → a `ConnectedUserProjectWorkflow` with catalog uuid `"catalog-uuid"` and deployment id `900`; `stubDeployment(int version)` → `getDeployment(900L)` returns a `ProjectDeployment` with project id `500` and that version; `stubCurrentDeploymentWithRow(connections, inputs)` → `stubDeployment(2)`, `getLastPublishedVersion(500L)` → `2`, `getWorkflowId(500L, 2, "catalog-uuid")` → `"catalog-wf-1"`, `fetchRow(900L, "catalog-uuid")` → a row with those connections and inputs.

Controller IntTests — follow each class's existing request/authentication helpers:
- FrontendProvision: `POST /api/embedded/v1/automation/workflow-templates/catalog-uuid/provision` with body `{"connections":{"slack":12}}` → 204 and `verify(connectedUserCodeWorkflowReferenceFacade).getOrCreateReference(<user>, "catalog-uuid", Environment.PRODUCTION, Map.of("slack", 12L))`; without a body → `Map.of()`; facade throwing `ConnectionNotEntitledException` → 400.
- Reference: listing returns `$[0].attentionReason == "MISSING_CONNECTION:slack"` when the facade's DTO carries it.
- Copy: facade throwing `CodeWorkflowNotCopyableException` → 409 with `$.reason == "CODE_WORKFLOW_NOT_COPYABLE"`.
- Enable (`...ReferenceIntTest`): facade throwing `MissingInputException("channel")` from `enableReference` → 409 with `$.missingInputName == "channel"`.

Run: `./gradlew EC_SERVICE:test --tests "*ConnectedUserReferenceAttentionResolverTest" EC_PUBLIC_REST:testIntegration --continue > /tmp/t6.log 2>&1; echo $?` → non-zero.

- [ ] **Step 2: Implement the resolver**

```java
package com.bytechef.ee.embedded.configuration.facade;

/**
 * Why a reference needs the connected user's attention, derived from its deployment row on every read and never
 * stored, so it can never disagree with what is actually wired.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class ConnectedUserReferenceAttentionResolver {

    static final String INPUT_REQUIRED_PREFIX = "INPUT_REQUIRED:";
    static final String MISSING_CONNECTION_PREFIX = "MISSING_CONNECTION:";
    static final String UPDATE_PENDING = "UPDATE_PENDING";

    private static final Logger logger = LoggerFactory.getLogger(ConnectedUserReferenceAttentionResolver.class);

    private final ConnectedUserReferenceDeploymentManager connectedUserReferenceDeploymentManager;
    private final WorkflowConnectionSlots workflowConnectionSlots;

    @SuppressFBWarnings("EI")
    public ConnectedUserReferenceAttentionResolver(
        ConnectedUserReferenceDeploymentManager connectedUserReferenceDeploymentManager,
        WorkflowConnectionSlots workflowConnectionSlots) {

        this.connectedUserReferenceDeploymentManager = connectedUserReferenceDeploymentManager;
        this.workflowConnectionSlots = workflowConnectionSlots;
    }

    @Nullable
    public String resolve(ConnectedUserProjectWorkflow reference) {
        if (reference.isDangling() || reference.getProjectDeploymentId() == null) {
            return null;
        }

        try {
            return doResolve(reference);
        } catch (RuntimeException exception) {
            logger.warn("Attention reason of reference id={} could not be derived", reference.getId(), exception);

            return null;
        }
    }

    @Nullable
    private String doResolve(ConnectedUserProjectWorkflow reference) {
        ProjectDeployment projectDeployment = connectedUserReferenceDeploymentManager.getDeployment(
            reference.getProjectDeploymentId());

        int lastPublishedVersion = connectedUserReferenceDeploymentManager.getLastPublishedVersion(
            projectDeployment.getProjectId());

        if (projectDeployment.getProjectVersion() < lastPublishedVersion) {
            return UPDATE_PENDING;
        }

        Optional<ProjectDeploymentWorkflow> row = connectedUserReferenceDeploymentManager.fetchRow(
            projectDeployment.getId(), reference.getCatalogWorkflowUuid());

        if (row.isEmpty()) {
            return null;
        }

        ProjectDeploymentWorkflow projectDeploymentWorkflow = row.get();

        String workflowId = connectedUserReferenceDeploymentManager.getWorkflowId(
            projectDeployment.getProjectId(), projectDeployment.getProjectVersion(),
            reference.getCatalogWorkflowUuid());

        List<ProjectDeploymentWorkflowConnection> connections = projectDeploymentWorkflow.getConnections();

        for (ComponentConnection slot : workflowConnectionSlots.getSlots(workflowId)) {
            boolean wired = connections.stream()
                .anyMatch(connection -> Objects.equals(connection.getWorkflowNodeName(), slot.workflowNodeName()) &&
                    Objects.equals(connection.getWorkflowConnectionKey(), slot.key()));

            if (slot.required() && !wired) {
                return MISSING_CONNECTION_PREFIX + slot.componentName();
            }
        }

        String missingInputName = connectedUserReferenceDeploymentManager.findMissingRequiredInput(
            workflowId, projectDeploymentWorkflow.getInputs());

        return missingInputName == null ? null : INPUT_REQUIRED_PREFIX + missingInputName;
    }
}
```

`ConnectedUserProjectWorkflowDTO` — append `@Nullable String attentionReason`; `ofReference` takes it as a new last parameter; every other factory passes `null`. Fix other constructor call sites: `grep -rn "new ConnectedUserProjectWorkflowDTO(\|ConnectedUserProjectWorkflowDTO.ofReference(" server --include='*.java'`.

`ConnectedUserProjectFacadeImpl.getReferenceRows` — inject `ConnectedUserReferenceAttentionResolver`; pass `connectedUserReferenceAttentionResolver.resolve(reference)` to `ofReference`.

- [ ] **Step 3: OpenAPI and controller**

Add to `components.schemas`:

```yaml
    ProvisionWorkflowReferenceRequest:
      type: "object"
      description: "Connections the connected user chose, keyed by component name. Components not listed keep their current connection or are auto-matched."
      properties:
        connections:
          type: "object"
          additionalProperties:
            type: "integer"
            format: "int64"
    CodeWorkflowNotCopyableError:
      type: "object"
      description: "Returned when a code workflow template is copied; code workflow templates can only be referenced."
      properties:
        reason:
          type: "string"
          enum:
            - "CODE_WORKFLOW_NOT_COPYABLE"
    MissingInputError:
      type: "object"
      description: "Returned when a reference cannot be enabled because a required workflow input has no value yet."
      properties:
        missingInputName:
          description: "The name of the required input that has no value."
          type: "string"
```

On all four enable endpoints (`…/workflows/{uuid}/enable` POST and DELETE, frontend and external-user) change the `"409"` content schema to `oneOf: [$ref MissingConnectionError, $ref MissingInputError]`.

On both provision POSTs add `requestBody: {required: false, content: {application/json: {schema: {$ref: "#/components/schemas/ProvisionWorkflowReferenceRequest"}}}}`, add a `"400"` response `"A requested connection is not one of the connected user's connections."`, and change the descriptions from "catalog code workflow" to "catalog workflow template". On both `/copy` POSTs add a `"409"` with `CodeWorkflowNotCopyableError`. In the `ConnectedUserProjectWorkflow` schema add:

```yaml
        attentionReason:
          description: "Why a REFERENCE needs attention: MISSING_CONNECTION:<component>, INPUT_REQUIRED:<input> or UPDATE_PENDING. Null when healthy or for COPY rows."
          type: "string"
```

Regenerate: `./gradlew EC_PUBLIC_REST:generateOpenAPI > /tmp/t6gen.log 2>&1; echo $?` → `0`.

Controller — both provision methods take the generated request model and call:

```java
Map<String, Long> requestedConnectionIds = provisionWorkflowReferenceRequestModel == null ||
    provisionWorkflowReferenceRequestModel.getConnections() == null
        ? Map.of()
        : provisionWorkflowReferenceRequestModel.getConnections();

connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
    externalUserId, workflowUuid, getEnvironment(xEnvironment), requestedConnectionIds);
```

Add handlers beside the `MissingConnectionException` one:

```java
@ExceptionHandler(CodeWorkflowNotCopyableException.class)
public ResponseEntity<Object> handleCodeWorkflowNotCopyableException(
    CodeWorkflowNotCopyableException codeWorkflowNotCopyableException) {

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("reason", "CODE_WORKFLOW_NOT_COPYABLE"));
}

@ExceptionHandler(ConnectionNotEntitledException.class)
public ResponseEntity<Void> handleConnectionNotEntitledException(
    ConnectionNotEntitledException connectionNotEntitledException) {

    return ResponseEntity.badRequest()
        .build();
}

@ExceptionHandler(MissingInputException.class)
public ResponseEntity<Object> handleMissingInputException(MissingInputException missingInputException) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("missingInputName", missingInputException.getInputName()));
}
```

`RemoteConnectedUserCodeWorkflowReferenceFacadeController` (distributed) needs the same two mappings — 400 for `ConnectionNotEntitledException`, 409 `{"missingInputName": …}` for `MissingInputException` — and `RemoteConnectedUserCodeWorkflowReferenceFacadeClient` must translate them back, the way it already does for `MissingConnectionException`.

The `/copy` methods catch `IllegalArgumentException` only, so `CodeWorkflowNotCopyableException` reaches the handler. Mapper: `@Mapping(target = "attentionReason", source = "attentionReason")`.

- [ ] **Step 4: Verify**

Run: `./gradlew EC_SERVICE:test EC_PUBLIC_REST:test EC_PUBLIC_REST:testIntegration --continue > /tmp/t6.log 2>&1; echo $?` → `0`. Then the CLI client consuming this spec: `./gradlew :cli:clients:embedded-configuration:compileJava > /tmp/t6cli.log 2>&1; echo $?` → `0` (run its `generateClient` task first if the new schemas break it).

- [ ] **Step 5: Commit**

```bash
git add <new files and regenerated sources under EC/embedded-configuration-public-rest/generated and client/src/ee/shared/middleware/embedded/public>
git commit -m "- Accept connection choices on reference provisioning and derive why a reference needs attention" -- <every file of this task>
```

---

### Task 7: Show in Automation Hub flag and permission-filtered hub catalog (D10)

**Files:**
- Create: `AC/automation-configuration-service/src/main/resources/config/liquibase/changelog/automation/configuration/20260917100100_automation_configuration_added_automation_hub_visible.xml`
- Modify: `AC/automation-configuration-api/src/main/java/com/bytechef/automation/configuration/domain/Project.java` (next to `permissionExpression`, line ~70)
- Modify: `AC/automation-configuration-api/src/main/java/com/bytechef/automation/configuration/service/ProjectService.java` (next to `updatePermissionExpression`, line ~100)
- Modify: `AC/automation-configuration-service/src/main/java/com/bytechef/automation/configuration/service/ProjectServiceImpl.java` (next to `updatePermissionExpression`, line ~257)
- Modify: every other `ProjectService` implementation (`grep -rln "implements ProjectService" server --include='*.java'`)
- Modify: `EC/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/dto/AutomationWorkflowProjectDTO.java`
- Modify: `EC/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/facade/AutomationWorkflowProjectFacade.java`, `AutomationWorkflowProjectAdminFacade.java`
- Modify: `EC/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/AutomationWorkflowProjectFacadeImpl.java`, `AutomationWorkflowProjectAdminFacadeImpl.java`
- Modify: `EC/embedded-configuration-remote-client/src/main/java/com/bytechef/ee/embedded/configuration/remote/client/facade/RemoteAutomationWorkflowProjectFacadeClient.java`
- Modify: `EC/embedded-configuration-graphql/src/main/resources/graphql/automation-workflow-project.graphqls`
- Modify: `EC/embedded-configuration-graphql/src/main/java/com/bytechef/ee/embedded/configuration/web/graphql/AutomationWorkflowProjectGraphQlController.java`
- Modify: `EC/embedded-configuration-public-rest/src/main/java/com/bytechef/ee/embedded/configuration/public_/web/rest/AutomationWorkflowProjectApiController.java` (`getFrontendProjects`, line 56)
- Modify: every `new AutomationWorkflowProjectDTO(` call site (list in Step 4)
- Test: `EC/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/AutomationWorkflowProjectFacadeIntTest.java`
- Test: `EC/embedded-configuration-public-rest/src/test/java/com/bytechef/ee/embedded/configuration/public_/web/rest/AutomationWorkflowProjectApiControllerIntTest.java`
- Test: `EC/embedded-configuration-graphql/src/test/java/com/bytechef/ee/embedded/configuration/web/graphql/AutomationWorkflowProjectGraphQlControllerTest.java`

**Interfaces:**
- Produces:
  - `Project.isAutomationHubVisible()` / `setAutomationHubVisible(boolean)`; `Project ProjectService.updateAutomationHubVisible(long id, boolean automationHubVisible)`.
  - `AutomationWorkflowProjectDTO(..., boolean codeWorkflowProject, boolean automationHubVisible)` — new last component.
  - `long AutomationWorkflowProjectFacade.createProject(String name, String description, String category, List<String> tags, String permissionExpression, @Nullable Boolean automationHubVisible)` (replaces the 5-arg form; null = true) and `void updateProject(long projectId, String name, String description, String category, List<String> tags, String permissionExpression, @Nullable Boolean automationHubVisible)` (null = unchanged). Same on the admin facade; the remote client throws `UnsupportedOperationException`.
  - GraphQL: `automationHubVisible: Boolean!` on `AutomationWorkflowProject`; optional `automationHubVisible: Boolean` on both create/update mutations.

Tasks 3–5 call `createProject(name, description, category, tags, permissionExpression)` with five arguments; when this task lands, append `null` in `ConnectedUserReferenceRolloutIntTest` and `CatalogProjectPublishedEventListenerIntTest` too.

- [ ] **Step 1: Write the failing tests**

`AutomationWorkflowProjectFacadeIntTest` (update existing `createProject`/`updateProject` calls to pass a trailing `null`):

```java
@Test
void testAutomationHubVisibleDefaultsToTrueAndRoundTrips() {
    long projectId = automationWorkflowProjectFacade.createProject(
        "Hub Visible " + UUID.randomUUID(), "", null, List.of(), null, null);

    assertThat(automationWorkflowProjectFacade.getProject(projectId)
        .automationHubVisible()).isTrue();

    automationWorkflowProjectFacade.updateProject(
        projectId, "Hub Visible", "", null, List.of(), null, false);

    assertThat(automationWorkflowProjectFacade.getProject(projectId)
        .automationHubVisible()).isFalse();

    automationWorkflowProjectFacade.updateProject(projectId, "Hub Visible", "", null, List.of(), null, null);

    assertThat(automationWorkflowProjectFacade.getProject(projectId)
        .automationHubVisible()).isFalse();
}
```

`AutomationWorkflowProjectApiControllerIntTest` — add, using the class's existing stubbing of `AutomationWorkflowProjectFacade` and its JWT/API-key helpers:
- `testFrontendProjectsOmitHiddenProjectsAndUseThePermissionFilter`: stub `getPublishedProjects(<authenticated external user>, Environment.PRODUCTION)` to return one visible and one hidden project; `GET /api/embedded/v1/automation/projects` returns only the visible one; `verify(automationWorkflowProjectFacade, never()).getPublishedProjects()`.
- `testApiKeyProjectsKeepHiddenProjects`: the same stub; `GET /api/embedded/v1/{externalUserId}/automation/projects` returns both.

`AutomationWorkflowProjectGraphQlControllerTest`: `updateAutomationWorkflowProject(..., false)` delegates `updateProject(..., false)` to the admin facade.

Run: `./gradlew EC_SERVICE:compileTestJava > /tmp/t7.log 2>&1; echo $?` → non-zero.

- [ ] **Step 2: Schema, domain, service**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <changeSet id="20260917100100-1" author="Ivica Cardic">
        <preConditions onFail="MARK_RAN">
            <not>
                <columnExists tableName="project" columnName="automation_hub_visible"/>
            </not>
        </preConditions>
        <addColumn tableName="project">
            <column name="automation_hub_visible" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
        </addColumn>
        <rollback/>
    </changeSet>
</databaseChangeLog>
```

Match the context filtering the automation configuration changelog directory uses (compare `202506041200010_automation_configuration_added_permission_expression.xml`).

`Project`: `@Column("automation_hub_visible") private boolean automationHubVisible = true;` with getter/setter, included in `toString`. `ProjectService.updateAutomationHubVisible(long id, boolean automationHubVisible)`; `ProjectServiceImpl` mirrors `updatePermissionExpression` exactly (load, set, `projectRepository.save`), with the same annotations. `ProjectServiceImpl.update` stays unchanged — like `permission_expression`, the column is only written through its dedicated method.

- [ ] **Step 3: DTO, facades, GraphQL, public REST**

`AutomationWorkflowProjectFacadeImpl`: in `createProject`, after `projectService.create`, when `automationHubVisible != null && !automationHubVisible` call `projectService.updateAutomationHubVisible(project.getId(), false)`; in `updateProject`, when non-null call `projectService.updateAutomationHubVisible(projectId, automationHubVisible)`. Pass `project.isAutomationHubVisible()` (or `project.automationHubVisible()` for DTO inputs) as the new last DTO argument in `toDTO`, `toPublishedDTO` and `filterWorkflowTemplates`.

GraphQL schema: add the field and the two optional arguments; controller passes `@Argument Boolean automationHubVisible` through.

`AutomationWorkflowProjectApiController.getFrontendProjects`:

```java
@Override
@CrossOrigin
public ResponseEntity<List<AutomationWorkflowProjectModel>> getFrontendProjects(EnvironmentModel xEnvironment) {
    String externalUserId = OptionalUtils.get(SecurityUtils.fetchCurrentUserLogin(), "User not found");

    List<AutomationWorkflowProjectModel> models = automationWorkflowProjectFacade
        .getPublishedProjects(externalUserId, getEnvironment(xEnvironment))
        .stream()
        .filter(AutomationWorkflowProjectDTO::automationHubVisible)
        .map(project -> conversionService.convert(project, AutomationWorkflowProjectModel.class))
        .toList();

    return ResponseEntity.ok(models);
}
```

Keep the annotations the existing method carries and reuse whatever conversion `getProjects` uses. If `toAutomationWorkflowProjectModels()` has no other caller afterwards, delete it.

- [ ] **Step 4: DTO call sites**

Append `true` to each `new AutomationWorkflowProjectDTO(` in tests: `embedded-webhook/embedded-webhook-public-rest/src/test/.../RequestTriggerApiControllerAutomationBridgeTest.java:782`; `EC/embedded-configuration-public-rest/src/test/.../AutomationProjectCodeWorkflowApiControllerListAuthorizationIntTest.java:92`; `.../AutomationWorkflowProjectApiControllerIntTest.java:80,122,172`; `EC/embedded-configuration-service/src/test/.../security/ConnectedUserResourceMembershipEnforcementIntTest.java:470`; `.../security/ConnectedUserResourceMembershipResolverTest.java:575,604,1018`; `.../facade/ConnectedUserProjectFacadeWorkflowListTest.java:159`; `.../facade/ConnectedUserCodeWorkflowReferenceFacadeTest.java:647`; `.../facade/ConnectedUserProjectFacadeCopyTemplateAuthorizationTest.java:125`; `.../facade/ConnectedUserCodeWorkflowReferenceFacadeAuthorizationTest.java:201`; `EC/embedded-configuration-graphql/src/test/.../AutomationWorkflowProjectGraphQlControllerTest.java:42,45`; plus any added since. Confirm with `grep -rn "new AutomationWorkflowProjectDTO(" server --include='*.java' | grep -v /build/` and read each hit. Update every other `createProject(`/`updateProject(` caller of the two facades the same way.

- [ ] **Step 5: Verify**

Run: `./gradlew AC_SERVICE:test EC_SERVICE:test EC_SERVICE:testIntegration --tests "*AutomationWorkflowProjectFacadeIntTest" --tests "*ConnectedUserReferenceRolloutIntTest" --tests "*CatalogProjectPublishedEventListenerIntTest" EC_GRAPHQL:test EC_PUBLIC_REST:testIntegration EC_REMOTE_CLIENT:compileJava :server:ee:libs:embedded:embedded-webhook:embedded-webhook-public-rest:compileTestJava --continue > /tmp/t7.log 2>&1; echo $?` → `0`.

- [ ] **Step 6: Commit**

```bash
git add <changeset>
git commit -m "- Hide embedded catalog projects from the Automation Hub and apply permission expressions to its catalog" -- <every file of this task>
```

---

### Task 8: Admin UI — Show in Automation Hub

**Files:**
- Modify: `client/src/graphql/embedded/configuration/automationWorkflowProjects.graphql`
- Modify (regenerated): `client/src/shared/middleware/graphql.ts`, `client/src/shared/middleware/graphql-types.ts`
- Modify: `client/src/ee/pages/embedded/automation-workflows/components/automation-workflow-project-dialog/AutomationWorkflowProjectDialog.tsx`
- Modify: `client/src/ee/pages/embedded/automation-workflows/AutomationWorkflows.tsx` (121-159)
- Modify: `client/src/ee/pages/embedded/automation-workflows/components/automation-workflow-project-list/AutomationWorkflowProjectListItem.tsx` (194-209)
- Create: `client/src/ee/pages/embedded/automation-workflows/components/automation-workflow-project-dialog/tests/AutomationWorkflowProjectDialog.test.tsx`
- Modify: `client/src/ee/pages/embedded/automation-workflows/tests/AutomationWorkflowProjectList.test.tsx` (fixtures)

**Interfaces:**
- Consumes: GraphQL `automationHubVisible` (Task 7).
- Produces: `AutomationWorkflowProjectFormValuesI.automationHubVisible: boolean`.

- [ ] **Step 1: Operations and codegen**

Add `automationHubVisible` to the `automationWorkflowProjects` selection and `$automationHubVisible: Boolean` to both mutations, passed as `automationHubVisible: $automationHubVisible`.

Run: `cd client && npx graphql-codegen > /tmp/t8gen.log 2>&1; echo $?` → `0`.

- [ ] **Step 2: Write the failing dialog test**

```tsx
import AutomationWorkflowProjectDialog from '@/ee/pages/embedded/automation-workflows/components/automation-workflow-project-dialog/AutomationWorkflowProjectDialog';
import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

describe('AutomationWorkflowProjectDialog', () => {
    it('shows new projects in the Automation Hub by default', async () => {
        const onSubmit = vi.fn();

        render(<AutomationWorkflowProjectDialog categories={[]} onClose={vi.fn()} onSubmit={onSubmit} tags={[]} />);

        fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'New project'}});
        fireEvent.click(screen.getByRole('button', {name: 'Save'}));

        await waitFor(() =>
            expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({automationHubVisible: true}))
        );
    });

    it('submits a project hidden from the Automation Hub', async () => {
        const onSubmit = vi.fn();

        render(<AutomationWorkflowProjectDialog categories={[]} onClose={vi.fn()} onSubmit={onSubmit} tags={[]} />);

        fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'API only'}});
        fireEvent.click(screen.getByLabelText('Show in Automation Hub'));
        fireEvent.click(screen.getByRole('button', {name: 'Save'}));

        await waitFor(() =>
            expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({automationHubVisible: false}))
        );
    });
});
```

Run: `cd client && npx vitest run src/ee/pages/embedded/automation-workflows/components/automation-workflow-project-dialog/tests > /tmp/t8.log 2>&1; echo $?` → non-zero.

- [ ] **Step 3: Implement**

Dialog: add `automationHubVisible: boolean` to both form interfaces (keys sorted), default `project?.automationHubVisible ?? true`, include it in `saveProject`, and render after the permission expression field using the repo's `Switch` (`@/components/ui/switch`; check its export):

```tsx
<FormField
    control={form.control}
    name="automationHubVisible"
    render={({field}) => (
        <FormItem className="flex items-center justify-between gap-4">
            <div>
                <FormLabel htmlFor="automation-hub-visible">Show in Automation Hub</FormLabel>

                <p className="text-sm text-muted-foreground">
                    Turn off for flows you only activate through the API.
                </p>
            </div>

            <FormControl>
                <Switch checked={field.value} id="automation-hub-visible" onCheckedChange={field.onChange} />
            </FormControl>
        </FormItem>
    )}
/>
```

`AutomationWorkflows.tsx`: pass `automationHubVisible: values.automationHubVisible` in both mutation variable objects (keys sorted). `handleUpdateTags` omits it (server treats null as unchanged).

`AutomationWorkflowProjectListItem.tsx`: in the badge cluster add `{!project.automationHubVisible && <Badge label="Hidden from hub" styleType="secondary-outline" />}` before the published badge. Add `automationHubVisible: true` to `AutomationWorkflowProjectList.test.tsx` fixtures.

- [ ] **Step 4: Verify**

Run: `cd client && npx vitest run src/ee/pages/embedded/automation-workflows > /tmp/t8.log 2>&1; echo $?` → `0`; `npm run lint > /tmp/t8lint.log 2>&1; echo $?` → `0`; `npm run typecheck > /tmp/t8tsc.log 2>&1; echo $?` → `0`.

- [ ] **Step 5: Commit**

```bash
git add client/src/ee/pages/embedded/automation-workflows/components/automation-workflow-project-dialog/tests/AutomationWorkflowProjectDialog.test.tsx
git commit -m "- client - Add Show in Automation Hub to the embedded catalog project dialog" -- <every file of this task>
```

---

### Task 9: Automation Hub — send connection choices, show why a reference needs attention

**Files:**
- Modify: `client/src/ee/pages/embedded/automation-hub/mutations/automationHub.mutations.ts` (`useProvisionReferenceMutation`)
- Modify: `client/src/ee/pages/embedded/automation-hub/wizard/useActivationFlow.ts` (REFERENCE branch of `activate`; `undoPartialActivation` unchanged)
- Modify: `client/src/ee/pages/embedded/automation-hub/views/components/AutomationCard.tsx` (75, 86)
- Modify: `client/src/ee/pages/embedded/automation-hub/views/components/TemplateCard.tsx` (86-92)
- Create: `client/src/ee/pages/embedded/automation-hub/utils/attentionReason.ts`
- Create: `client/src/ee/pages/embedded/automation-hub/utils/tests/attentionReason.test.ts`
- Modify: `client/src/ee/pages/embedded/automation-hub/tests/ActivationWizard.test.tsx`
- Modify: `client/src/ee/pages/embedded/automation-hub/tests/AutomationsView.test.tsx`

**Interfaces:**
- Consumes: regenerated TS client `provisionFrontendWorkflowReference({provisionWorkflowReferenceRequest, workflowUuid})` and `ConnectedUserProjectWorkflow.attentionReason` (Task 6).
- Produces: `ProvisionReferenceRequestI {connections: Record<string, number>; workflowUuid: string}`; `describeAttentionReason(attentionReason: string | undefined): string | undefined`.

- [ ] **Step 1: Write the failing tests**

`attentionReason.test.ts`:

```ts
import {describeAttentionReason} from '@/ee/pages/embedded/automation-hub/utils/attentionReason';
import {describe, expect, it} from 'vitest';

describe('describeAttentionReason', () => {
    it('describes a missing connection', () => {
        expect(describeAttentionReason('MISSING_CONNECTION:slack')).toBe('Connect slack to keep this running');
    });

    it('describes a required input', () => {
        expect(describeAttentionReason('INPUT_REQUIRED:channel')).toBe('Fill in channel to keep this running');
    });

    it('describes a pending update', () => {
        expect(describeAttentionReason('UPDATE_PENDING')).toBe('Turn it on again to apply the latest update');
    });

    it('returns undefined when healthy', () => {
        expect(describeAttentionReason(undefined)).toBeUndefined();
    });
});
```

`ActivationWizard.test.tsx` — add, reusing `renderWizard`, `walkToActivateStep`, `selectConnection` and `clickButton`:

```ts
it('sends the selected connections when provisioning a reference', async () => {
    renderWizard('REFERENCE');

    selectConnection('Slack connection', 'My Other Slack');

    walkToActivateStep();

    clickButton('Activate');

    await waitFor(() =>
        expect(provisionMutateAsyncMock).toHaveBeenCalledWith({
            connections: {slack: 2},
            workflowUuid: 'tpl-1',
        })
    );
});
```

Use the id the file's Slack fixture assigns to `'My Other Slack'`. Change existing `provisionMutateAsyncMock` expectations from `('tpl-1')` to `(expect.objectContaining({workflowUuid: 'tpl-1'}))`.

`AutomationsView.test.tsx` — add a fixture `{...referenceAutomation, attentionReason: 'MISSING_CONNECTION:slack'}` (use the file's reference fixture name) and assert its card shows `Needs attention` and `Connect slack to keep this running`, and that its enable switch is not disabled.

Run: `cd client && npx vitest run src/ee/pages/embedded/automation-hub > /tmp/t9.log 2>&1; echo $?` → non-zero.

- [ ] **Step 2: Implement**

`attentionReason.ts`:

```ts
const INPUT_REQUIRED_PREFIX = 'INPUT_REQUIRED:';
const MISSING_CONNECTION_PREFIX = 'MISSING_CONNECTION:';

export const describeAttentionReason = (attentionReason: string | undefined): string | undefined => {
    if (!attentionReason) {
        return undefined;
    }

    if (attentionReason.startsWith(MISSING_CONNECTION_PREFIX)) {
        return `Connect ${attentionReason.slice(MISSING_CONNECTION_PREFIX.length)} to keep this running`;
    }

    if (attentionReason.startsWith(INPUT_REQUIRED_PREFIX)) {
        return `Fill in ${attentionReason.slice(INPUT_REQUIRED_PREFIX.length)} to keep this running`;
    }

    return 'Turn it on again to apply the latest update';
};
```

Mutation:

```ts
export interface ProvisionReferenceRequestI {
    connections: Record<string, number>;
    workflowUuid: string;
}

export const useProvisionReferenceMutation = () => {
    const queryClient = useQueryClient();

    return useMutation<void, Error, ProvisionReferenceRequestI>({
        mutationFn: ({connections, workflowUuid}) =>
            new ConnectedUserProjectWorkflowApi().provisionFrontendWorkflowReference({
                provisionWorkflowReferenceRequest: {connections},
                workflowUuid,
            }),
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: AutomationHubKeys.automations});
        },
    });
};
```

`useActivationFlow.activate`, REFERENCE branch:

```ts
const connections = Object.fromEntries(
    Object.entries(state.selections).filter(
        (entry): entry is [string, number] => entry[1] !== undefined && entry[1] !== null
    )
);

await provisionReference({connections, workflowUuid: templateUuid});
```

Add `state.selections` to the `useCallback` dependencies.

`AutomationCard.tsx`: `const attentionDescription = describeAttentionReason(automation.attentionReason);` and `const needsAttention = automation.dangling || !!attentionDescription;`. Line 75 uses `!needsAttention` instead of `!automation.dangling`; line 86 renders the badge when `needsAttention`, followed by `{attentionDescription && <p className="text-sm text-muted-foreground">{attentionDescription}</p>}`. Leave line 99 and `AutomationStatusButton` (disabled only for `dangling`) unchanged — enabling is how a derived reason clears.

`TemplateCard.tsx`: inside `CardTitle` after `AutomationVersion`, `{automation && describeAttentionReason(automation.attentionReason) && <Badge variant="destructive">Needs attention</Badge>}`, and the description paragraph under the card description.

- [ ] **Step 3: Verify**

Run: `cd client && npx vitest run src/ee/pages/embedded/automation-hub > /tmp/t9.log 2>&1; echo $?` → `0`; `npm run lint` and `npm run typecheck`, each redirected, → `0`.

- [ ] **Step 4: Commit**

```bash
git add client/src/ee/pages/embedded/automation-hub/utils/attentionReason.ts client/src/ee/pages/embedded/automation-hub/utils/tests/attentionReason.test.ts
git commit -m "- client - Send connection choices when provisioning a hub reference and show why it needs attention" -- <every file of this task>
```

---

### Task 10: Documentation and full verification

**Files:**
- Modify: `.agents/embedded-bridge.md`

- [ ] **Step 1: Document**

Add a section "Referenced catalog templates" to `.agents/embedded-bridge.md`, one short paragraph each:
- copy vs reference is the caller's per-activation choice through the same endpoints for visual and code templates; code-workflow templates cannot be copied;
- one deployment per (connected user, catalog project, environment); catalog code never resolves a deployment by (project, environment);
- the deployment row is the only record of wired connections; candidates come only from `ConnectedUserConnectionFacade`; choice order requested → currently wired → first entitled; key semantics of `ProjectDeploymentWorkflowConnection`;
- rollout: after-commit listener on `workerExecutor`, `@SkipAutomationAuthorization` on the service because the aspect does not follow `@Async`, per-deployment `REQUIRES_NEW`, lazy catch-up on provision/enable;
- attention reason is derived on read, never stored;
- `project.automation_hub_visible` hides a project from the hub catalog only; provisioning ignores it; the hub catalog is permission-filtered.

- [ ] **Step 2: Format and checks**

```bash
./gradlew spotlessApply > /tmp/t10-spotless.log 2>&1; echo $?
```

```bash
./gradlew AC_SERVICE:check EC_SERVICE:check EC_PUBLIC_REST:check EC_GRAPHQL:check EC_REMOTE_CLIENT:check EC_REMOTE_REST:check :server:ee:libs:embedded:embedded-webhook:embedded-webhook-public-rest:check --continue > /tmp/t10-check.log 2>&1; echo $?
```

```bash
./gradlew AC_SERVICE:testIntegration EC_SERVICE:testIntegration EC_PUBLIC_REST:testIntegration --continue > /tmp/t10-int.log 2>&1; echo $?
```

```bash
./gradlew :server:apps:server-app:compileJava :server:ee:apps:configuration-app:compileJava :server:ee:apps:execution-app:compileJava :cli:clients:embedded-configuration:compileJava --continue > /tmp/t10-apps.log 2>&1; echo $?
```

```bash
cd client && npm run format && npm run check > /tmp/t10-client.log 2>&1; echo $?
```

Expected: every command `0`; `grep "^> Task .* FAILED" /tmp/t10-*.log` prints nothing. For any failure you believe pre-existing, prove it by running the same task on the base commit in a separate worktree before calling it pre-existing.

- [ ] **Step 3: Boot check**

With the dev infra up, start the server (`./gradlew -p server/apps/server-app bootRun`), confirm `SELECT id FROM databasechangelog WHERE id IN ('20260917100000-1', '20260917100100-1')` returns both, then:
1. in the admin UI create a catalog project with **Show in Automation Hub** off, add a one-task visual workflow, publish;
2. `POST /api/embedded/v1/{externalUserId}/automation/workflow-templates/{uuid}/provision` with an API key → 204;
3. `GET /api/embedded/v1/{externalUserId}/automation/projects` lists the project; the hub's catalog (as that connected user) does not;
4. edit and republish the workflow; within seconds the user's deployment `project_version` advances (`SELECT project_version FROM project_deployment WHERE name = '__EMBEDDED__<externalUserId>__PRODUCTION'`).

- [ ] **Step 4: Commit**

```bash
git commit -m "- Document referenced embedded catalog templates" -- .agents/embedded-bridge.md
```
