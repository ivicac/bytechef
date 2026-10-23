# Embedded Integration Instance Provisioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A tenant admin provisions integration X, pinned to a chosen configuration (and so its version), to connected user Z from the Connected Users page — creating Z's connection, instance and enabled workflows in one step — while self-serve keeps working with several enabled configurations.

**Architecture:** No new infrastructure. Three server seams change: (1) every "the enabled configuration" lookup becomes "the enabled configuration with the highest version"; (2) the customer-facing read DTO carries each instance's **own** configuration so a pinned customer sees its own workflows; (3) the request-trigger endpoint resolves the workflow id per instance. On top of that, one admin-guarded operation (plus one admin read feeding the dialog) is added to the existing admin facade/REST, and a dialog is added to the connected-user sheet.

**Tech Stack:** Spring Boot 4 / Spring Data JDBC, openapi-generator (spring + typescript-fetch, committed output), React 19 + TanStack Query + react-hook-form, vitest; embedded SDK (`sdks/frontend/embedded/library`).

**Spec:** `docs/superpowers/specs/2026-10-23-embedded-integration-instance-provisioning-design.md`

## Global Constraints

- All server files live under `server/ee/...`: ByteChef Enterprise license header and `@version ee` Javadoc tag on every class.
- No Liquibase change, no new table/column/module/executor/broker route.
- Authorization sits on facades: `IntegrationInstanceAdminFacadeImpl` is `@PreAuthorize("isTenantAdmin()")`; the shared `ConnectedUserIntegrationFacade` stays unguarded; controllers call the admin facade only.
- Self-serve configuration = enabled configuration with the highest `integrationVersion`; tie → highest `id`.
- Dialog lists only **enabled** configurations; server refuses a disabled one.
- Permission expressions are enforced server-side on provisioning (integration and each workflow) with `EmbeddedPermissionEvaluator.evaluate(expression, connectedUser)`.
- Request-trigger disambiguation when several instances carry the uuid: the **enabled** instance with the highest id.
- OAuth2 credentials arrive as `connectionParameters.code` (authorization code); `ConnectionFacadeImpl.create` exchanges it. No token fields.
- Generated REST sources are committed: after any `openapi.yaml` edit run the module's `generateOpenAPI` task and commit the output (Java + `client/src/ee/shared/middleware/...` TypeScript) in the same commit as the spec.
- Commit subjects only (no body, no trailers): server `<description>`, client `client - <description>`. Stage by path.
- Checkstyle: test method names camelCase, no `TODO:` comments. Variable names descriptive (no `u`, `o`, `cfg`).
- Blank line before control statements and after a variable modification that is then used (see CLAUDE.md).

## Review Focus

1. Two enabled configurations on the same version (allowed by the facade only across versions, but the service `create` + `updateEnabled` path has no such guard): self-serve must pick deterministically (highest id), never throw. → Task 1 test `testSelfServeConfigurationTieBreaksOnHighestId`.
2. A customer with instances on two configurations of one integration must see **one** marketplace entry whose per-instance workflows differ. → Task 2 test `testPinnedInstanceCarriesItsOwnConfigurationWorkflows`.
3. A customer whose only instance sits on a configuration that is **not** the self-serve one must still be able to toggle its workflows through the public REST (`enableIntegrationInstanceWorkflow` resolves `getWorkflowId(instanceId, uuid)` per instance — unchanged, but pin it). → Task 2 test `testWorkflowToggleOnPinnedInstanceResolvesItsOwnVersion`.
4. A request trigger for a uuid the customer has **no** instance for must fall through to the automation bridge, not 500. → Task 3 test `testNoInstanceForUuidFallsThroughToTheAutomationBridge`.
5. Provisioning when workflow N's trigger enablement throws must leave no instance, no connection and no enabled triggers for workflows 1..N-1. → Task 4 test `testProvisioningRollsBackInstanceConnectionAndTriggersWhenAWorkflowFailsToEnable`.

---

### Task 1: Self-serve rule — highest enabled version wins

**Files:**
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/repository/IntegrationInstanceConfigurationRepository.java:35`
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/service/IntegrationInstanceConfigurationServiceImpl.java:62-68`
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/IntegrationInstanceConfigurationFacadeImpl.java:320-380`
- Test (create): `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/service/IntegrationInstanceConfigurationServiceIntTest.java`
- Test (modify): `.../src/test/java/com/bytechef/ee/embedded/configuration/facade/IntegrationInstanceConfigurationFacadeIntTest.java`

**Interfaces:**
- Consumes: `IntegrationInstanceConfigurationService.getIntegrationIntegrationInstanceConfiguration(long integrationId, Environment environment, boolean enabled)` (signature unchanged, semantics: highest version).
- Produces: `static Comparator<IntegrationInstanceConfiguration> IntegrationInstanceConfigurationServiceImpl.SELF_SERVE_ORDER` = `comparing(getIntegrationVersion, nullsFirst(naturalOrder())).thenComparing(getId)`; the maximum under this order is the self-serve configuration. Facade `getIntegrationInstanceConfigurationIntegrations(enabled, env)` now returns **one** DTO per integration (the self-serve one).

- [ ] **Step 1: Write the failing service IntTest**

`IntegrationInstanceConfigurationServiceIntTest` (`@SpringBootTest(classes = IntegrationIntTestConfiguration.class, properties = {"bytechef.edition=EE", "spring.liquibase.contexts=configuration"})`, `@Import(PostgreSQLContainerConfiguration.class)`, `@AfterEach` deletes `integrationInstanceConfigurationRepository` and `integrationRepository`; create rows through `integrationService.create(new Integration())` + `integrationInstanceConfigurationService.create(...)` + `updateEnabled(id, true)` exactly as `ConnectedUserIntegrationFacadeIntTest.createIntegrationInstanceConfiguration` does, with an `integrationVersion` parameter):

```java
@Test
void testGetIntegrationIntegrationInstanceConfigurationPicksTheHighestEnabledVersion() {
    long integrationId = createIntegration();
    createEnabledConfiguration(integrationId, 1);
    long version3Id = createEnabledConfiguration(integrationId, 3);
    createEnabledConfiguration(integrationId, 2);
    createDisabledConfiguration(integrationId, 5);

    assertThat(integrationInstanceConfigurationService
        .getIntegrationIntegrationInstanceConfiguration(integrationId, Environment.PRODUCTION, true)
        .getId()).isEqualTo(version3Id);
}

@Test
void testSelfServeConfigurationTieBreaksOnHighestId() {
    long integrationId = createIntegration();
    createEnabledConfiguration(integrationId, 2);
    long laterId = createEnabledConfiguration(integrationId, 2);

    assertThat(integrationInstanceConfigurationService
        .getIntegrationIntegrationInstanceConfiguration(integrationId, Environment.PRODUCTION, true)
        .getId()).isEqualTo(laterId);
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:testIntegration --tests '*IntegrationInstanceConfigurationServiceIntTest' > /tmp/t1.log 2>&1; echo $?`
Expected: non-zero; log shows `IncorrectResultSizeDataAccessException` for the first test.

- [ ] **Step 3: Replace the repository method and the service pick**

In `IntegrationInstanceConfigurationRepository` replace `Optional<...> findByIntegrationIdAndEnvironmentAndEnabled(...)` with `List<IntegrationInstanceConfiguration> findAllByIntegrationIdAndEnvironmentAndEnabled(long integrationId, int environment, boolean enabled)`. In `IntegrationInstanceConfigurationServiceImpl` add `public static final Comparator<IntegrationInstanceConfiguration> SELF_SERVE_ORDER` (see Interfaces) and make `getIntegrationIntegrationInstanceConfiguration` return `stream().max(SELF_SERVE_ORDER).orElseThrow(...)` keeping the current exception type/message from `OptionalUtils.get`.

- [ ] **Step 4: Run the service IntTest to verify it passes**

Same command as Step 2. Expected: exit 0.

- [ ] **Step 5: Write the failing facade IntTest**

Add to `IntegrationInstanceConfigurationFacadeIntTest` (use its `admin()`, `authenticate`, `createPublishedIntegration()`, `newIntegrationInstanceConfigurationDTO(integrationId, version)` helpers; publish the integration twice so versions 1 and 2 exist, create both DTOs through `integrationInstanceConfigurationFacade.createIntegrationInstanceConfiguration` and enable both with `enableIntegrationInstanceConfiguration(id, true)`; the DTO helper uses `Environment.STAGING`):

```java
@Test
void testIntegrationsReadReturnsOneEntryPerIntegrationOnTheHighestEnabledVersion() {
    // arrange two enabled configurations, versions 1 and 2
    List<IntegrationInstanceConfigurationDTO> integrations =
        integrationInstanceConfigurationFacade.getIntegrationInstanceConfigurationIntegrations(true, Environment.STAGING);

    assertThat(integrations).filteredOn(dto -> dto.integrationId() == integrationId).hasSize(1)
        .first().extracting(IntegrationInstanceConfigurationDTO::integrationVersion).isEqualTo(2);
    assertThat(integrationInstanceConfigurationFacade
        .getIntegrationInstanceConfigurationIntegration(integrationId, true, Environment.STAGING)
        .integrationVersion()).isEqualTo(2);
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:testIntegration --tests '*IntegrationInstanceConfigurationFacadeIntTest' > /tmp/t1b.log 2>&1; echo $?`
Expected: non-zero, `hasSize(1)` assertion fails with size 2.

- [ ] **Step 7: Dedupe in the facade**

In `IntegrationInstanceConfigurationFacadeImpl.getIntegrationInstanceConfigurationIntegrations(boolean enabled, Environment environment)`, after loading the enabled configurations, reduce them to one per `integrationId` with `Collectors.groupingBy(getIntegrationId, maxBy(SELF_SERVE_ORDER))` **before** building DTOs; `getIntegrationInstanceConfigurationIntegration(...)` keeps delegating to it (its `findFirst()` is now unique). Keep the `@PreAuthorize` annotations as they are.

- [ ] **Step 8: Run both IntTests to verify they pass, then spotless**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:spotlessApply testIntegration --tests '*IntegrationInstanceConfiguration*IntTest' > /tmp/t1c.log 2>&1; echo $?`
Expected: exit 0.

- [ ] **Step 9: Commit**

```bash
git add server/ee/libs/embedded/embedded-configuration/embedded-configuration-service
git commit -m "Pick the highest enabled integration instance configuration version for self-serve"
```

---

### Task 2: Customer-facing reads carry each instance's own configuration (server + public REST + SDK)

**Files:**
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/dto/ConnectedUserIntegrationDTO.java`
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserIntegrationFacadeImpl.java` (`getConnectedUserIntegration` ~line 222, `toConnectedUserIntegrationDTO`, `filterWorkflows`, `getIntegrationInstances`)
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-public-rest/openapi.yaml` (`IntegrationInstance` schema ~line 2350)
- Modify: `.../embedded-configuration-public-rest/src/main/java/com/bytechef/ee/embedded/configuration/public_/web/rest/mapper/ConnectedUserIntegrationMapper.java`
- Modify: `sdks/frontend/embedded/library/src/components/connect-dialog/types.ts`, `index.tsx:153-192`
- Test (modify): `.../embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/facade/ConnectedUserIntegrationFacadeIntTest.java`
- Test (modify): `.../embedded-configuration-public-rest/src/test/java/com/bytechef/ee/embedded/configuration/public_/web/rest/mapper/ConnectedUserIntegrationMapperTest.java`
- Test (modify): `sdks/frontend/embedded/library/src/components/connect-dialog/index.modes.test.tsx`

**Interfaces:**
- Consumes: Task 1's dedupe (the `integrationInstanceConfiguration` on the DTO is the self-serve one).
- Produces: `ConnectedUserIntegrationDTO.ConnectedUserIntegrationInstance` gains a final component `IntegrationInstanceConfigurationDTO integrationInstanceConfiguration` (that instance's own configuration, workflows already filtered). Public REST `IntegrationInstance` gains `integrationInstanceConfigurationId` (int64, readOnly), `integrationVersion` (int32, readOnly), `availableWorkflows` (array of `IntegrationWorkflow`, readOnly). SDK `IntegrationInstanceType` gains `availableWorkflows?: IntegrationWorkflowType[]; integrationVersion?: number`.

- [ ] **Step 1: Write the failing facade IntTest**

In `ConnectedUserIntegrationFacadeIntTest`'s `@Nested` class holding `createIntegrationRow` / `createIntegrationInstanceConfiguration` / `addWorkflow`: add an `int integrationVersion` parameter to both `createIntegrationInstanceConfiguration(long integrationId)` and `addWorkflow(...)` (today both hard-code `1`; keep existing call sites passing `1`). Then:

```java
@Test
void testPinnedInstanceCarriesItsOwnConfigurationWorkflows() {
    String externalUserId = createConnectedUser(Map.of());
    long integrationId = createIntegrationRow(null);
    long version1ConfigurationId = createIntegrationInstanceConfiguration(integrationId, 1);
    long version2ConfigurationId = createIntegrationInstanceConfiguration(integrationId, 2);
    IntegrationWorkflow version1Workflow = addWorkflow(integrationId, version1ConfigurationId, 1, "v1-only", true, null);
    addWorkflow(integrationId, version2ConfigurationId, 2, "v2-only", true, null);
    ConnectedUser connectedUser = connectedUserService.getConnectedUser(externalUserId, Environment.PRODUCTION);
    IntegrationInstance pinnedInstance = integrationInstanceService.create(
        connectedUser.getId(), CONNECTION_ID, version1ConfigurationId);
    stubConnection(); // same mock(Connection) + connectionService.getConnections stub as the sibling test

    List<ConnectedUserIntegrationDTO> integrations =
        connectedUserIntegrationFacade.getConnectedUserIntegrations(externalUserId, true, Environment.PRODUCTION);

    assertThat(integrations).filteredOn(dto -> dto.integrationInstanceConfiguration().integrationId() == integrationId).hasSize(1);
    ConnectedUserIntegrationDTO integration = /* that one */;
    assertThat(integration.integrationInstanceConfiguration().integrationVersion()).isEqualTo(2);
    assertThat(integration.integrationInstanceConfiguration().integrationInstanceConfigurationWorkflows())
        .extracting(IntegrationInstanceConfigurationWorkflowDTO::workflowUuid).containsExactly(/* v2-only uuid */);
    ConnectedUserIntegrationDTO.ConnectedUserIntegrationInstance instance = integration.integrationInstances().getFirst();
    assertThat(instance.integrationInstance().getId()).isEqualTo(pinnedInstance.getId());
    assertThat(instance.integrationInstanceConfiguration().integrationVersion()).isEqualTo(1);
    assertThat(instance.integrationInstanceConfiguration().integrationInstanceConfigurationWorkflows())
        .extracting(IntegrationInstanceConfigurationWorkflowDTO::workflowUuid)
        .containsExactly(version1Workflow.getUuidAsString());
}

@Test
void testWorkflowToggleOnPinnedInstanceResolvesItsOwnVersion() {
    // same arrangement; then
    assertThat(integrationWorkflowService.getWorkflowId(pinnedInstance.getId(), version1Workflow.getUuidAsString()))
        .isEqualTo(version1Workflow.getWorkflowId());
}
```

`addWorkflow` must publish the uuid-sharing case realistically: the v1 and v2 workflows are different rows (different `workflowId`, different uuid) — that is enough for these assertions.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:testIntegration --tests '*ConnectedUserIntegrationFacadeIntTest*' > /tmp/t2.log 2>&1; echo $?`
Expected: compile failure on `instance.integrationInstanceConfiguration()`.

- [ ] **Step 3: Extend the DTO**

In `ConnectedUserIntegrationDTO`: add `IntegrationInstanceConfigurationDTO integrationInstanceConfiguration` as the last component of `ConnectedUserIntegrationInstance` (update its 3-arg convenience constructor to a 4-arg one; `withMcp`/`attachInstanceMcpData` must copy it through). Change both `ConnectedUserIntegrationDTO` convenience constructors and `toIntegrationInstances(...)` to take `Map<Long, IntegrationInstanceConfigurationDTO> integrationInstanceConfigurationsById` and build each instance's workflows from **its own** configuration's `integrationInstanceConfigurationWorkflows()` (the map is keyed by configuration id; the self-serve DTO is in the map too).

- [ ] **Step 4: Resolve per-instance configurations in the facade**

In `ConnectedUserIntegrationFacadeImpl` add `private Map<Long, IntegrationInstanceConfigurationDTO> getInstanceConfigurations(List<IntegrationInstance> integrationInstances, IntegrationInstanceConfigurationDTO selfServeConfiguration, ConnectedUser connectedUser, List<McpWorkflowInfo> mcpWorkflows)`: for each distinct `getIntegrationInstanceConfigurationId()` not equal to the self-serve id, load `integrationInstanceConfigurationFacade.getIntegrationInstanceConfiguration(id)` and pass it through the existing `filterWorkflows(...)`. Call it from both `getConnectedUserIntegration` and `toConnectedUserIntegrationDTO` and pass the map into the DTO constructors. `getIntegrationInstances(...)` is unchanged (it already spans every configuration of the integration).

- [ ] **Step 5: Run the facade IntTest to verify it passes**

Same command as Step 2. Expected: exit 0 (including the pre-existing tests in the class).

- [ ] **Step 6: Write the failing mapper test**

In `ConnectedUserIntegrationMapperTest` add `testIntegrationInstanceMapsItsOwnConfigurationVersionAndAvailableWorkflows`: build a `ConnectedUserIntegrationInstance` whose `integrationInstanceConfiguration` has `integrationVersion(3)`, `id(77L)` and one `IntegrationInstanceConfigurationWorkflowDTO` with `workflowUuid("u-1")` and `workflow.label("L")`; assert the mapped `IntegrationInstanceModel` has `getIntegrationVersion() == 3`, `getIntegrationInstanceConfigurationId() == 77L`, and `getAvailableWorkflows()` with one element `workflowUuid "u-1"`, `label "L"`.

- [ ] **Step 7: Extend the public spec, regenerate, map**

In `openapi.yaml` `IntegrationInstance` add the three readOnly properties (see Interfaces). Run `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-public-rest:generateOpenAPI > /tmp/gen2.log 2>&1; echo $?` (exit 0). In `ConnectedUserIntegrationMapper`, in **both** nested mappers' `IntegrationInstanceModel map(ConnectedUserIntegrationInstance ...)` add `@Mapping(target = "integrationInstanceConfigurationId", source = "integrationInstanceConfiguration.id")`, `@Mapping(target = "integrationVersion", source = "integrationInstanceConfiguration.integrationVersion")`, `@Mapping(target = "availableWorkflows", source = "integrationInstanceConfiguration.integrationInstanceConfigurationWorkflows")` (the `IntegrationWorkflowModel map(IntegrationInstanceConfigurationWorkflowDTO)` method already exists in the full mapper; add the equivalent to the basic mapper or ignore `availableWorkflows` there with `@Mapping(target = "availableWorkflows", ignore = true)` — choose **ignore** in the basic mapper, it is the list view).

- [ ] **Step 8: Run the mapper test and the public-rest module tests**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-public-rest:spotlessApply test > /tmp/t2b.log 2>&1; echo $?`
Expected: exit 0.

- [ ] **Step 9: Commit server + generated sources**

```bash
git add server/ee/libs/embedded/embedded-configuration/embedded-configuration-api server/ee/libs/embedded/embedded-configuration/embedded-configuration-service server/ee/libs/embedded/embedded-configuration/embedded-configuration-public-rest client/src/ee/shared/middleware/embedded/public
git commit -m "Expose each connected user integration instance's own configuration workflows"
```
(`generateOpenAPITypeScriptFetch` of this module writes to `client/src/ee/shared/middleware/embedded/public`.)

- [ ] **Step 10: Write the failing SDK test**

In `index.modes.test.tsx`, using the `slackIntegration(...)` fixture pattern, add a test `uses the current instance's availableWorkflows instead of the integration workflows`: fetch mock returns an integration with `workflows: [{workflowUuid: 'w-v2', label: 'V2'}]` and one instance `{id: 5, availableWorkflows: [{workflowUuid: 'w-v1', label: 'V1'}], workflows: [{workflowUuid: 'w-v1', enabled: true}]}`; open the dialog on instance 5 (the same way the sibling tests select an instance) and assert `lastRenderedProps(render).workflows.map(w => w.workflowUuid)` equals `['w-v1']`. Also assert that with no `availableWorkflows` on the instance the list falls back to `['w-v2']`.

- [ ] **Step 11: Run it to verify it fails**

Run: `cd sdks/frontend/embedded/library && npx vitest run src/components/connect-dialog/index.modes.test.tsx > /tmp/sdk2.log 2>&1; echo $?`
Expected: non-zero.

- [ ] **Step 12: Implement in the SDK**

`types.ts`: add `availableWorkflows?: IntegrationWorkflowType[]; integrationVersion?: number` to `IntegrationInstanceType`. `index.tsx` `mergedWorkflows`: compute `currentInstance` first, then iterate `(currentInstance?.availableWorkflows ?? integration.workflows)`; keep the early return when both are absent.

- [ ] **Step 13: Run SDK tests + lint**

Run: `cd sdks/frontend/embedded/library && npx vitest run > /tmp/sdk2b.log 2>&1; echo $?; npm run lint > /tmp/sdk2c.log 2>&1; echo $?`
Expected: both exit 0.

- [ ] **Step 14: Commit**

```bash
git add sdks/frontend/embedded/library/src/components/connect-dialog
git commit -m "client - Render the current integration instance's own workflows in the embedded ConnectDialog"
```

- [ ] **Step 15: MCP audit (verification only)**

Run: `grep -rn "getIntegrationIntegrationInstanceConfiguration\|getIntegrationInstanceConfigurationIntegrations\|findByIntegrationIdAndEnvironmentAndEnabled" server/ee/libs/embedded/embedded-ai --include='*.java' | grep -v /test/`
Expected: no output (the MCP facades resolve configurations by id). If anything prints, switch that caller to the instance's configuration id and add it to this task's commit.

---

### Task 3: Request trigger resolves the workflow per instance

**Files:**
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-service/src/main/java/com/bytechef/ee/embedded/configuration/repository/IntegrationInstanceRepository.java`
- Modify: `.../embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/service/IntegrationInstanceService.java`, `.../embedded-configuration-service/.../service/IntegrationInstanceServiceImpl.java`
- Modify: `.../embedded-configuration-remote-client/src/main/java/com/bytechef/ee/embedded/configuration/remote/client/service/RemoteIntegrationInstanceServiceClient.java`
- Modify: `server/ee/libs/embedded/embedded-webhook/embedded-webhook-public-rest/src/main/java/com/bytechef/ee/embedded/webhook/public_/web/rest/RequestTriggerApiController.java:120-165`
- Test (create): `.../embedded-configuration-service/src/test/java/com/bytechef/ee/embedded/configuration/service/IntegrationInstanceServiceIntTest.java`
- Test (modify): `.../embedded-webhook-public-rest/src/test/java/com/bytechef/ee/embedded/webhook/public_/web/rest/RequestTriggerApiControllerIntTest.java`

**Interfaces:**
- Produces: `List<IntegrationInstance> IntegrationInstanceService.getIntegrationInstancesByWorkflowUuid(long connectedUserId, String workflowUuid, Environment environment)` (a distinct name: `getIntegrationInstances(long, String, Environment)` already exists and means "by component name") — instances of that user whose configuration's `(integration_id, integration_version)` has an `integration_workflow` row with that uuid, ordered by `integration_instance.id DESC`. Remote client stub throws `UnsupportedOperationException`.

- [ ] **Step 1: Write the failing service IntTest**

`IntegrationInstanceServiceIntTest` (same bootstrap as Task 1's service IntTest; it also needs `connectedUserService`, `workflowService`, `integrationWorkflowService`; build rows the way `ConnectedUserIntegrationFacadeIntTest.beforeEach` does):

```java
@Test
void testGetIntegrationInstancesByWorkflowUuidReturnsOnlyInstancesWhoseVersionHasThatWorkflow() {
    // integration with configurations v1 and v2; workflow row W1 on v1 (uuid U1), W2 on v2 (uuid U2)
    // user A: instance on v1; user B: instance on v2
    assertThat(integrationInstanceService.getIntegrationInstancesByWorkflowUuid(userAId, U1, Environment.PRODUCTION))
        .extracting(IntegrationInstance::getId).containsExactly(instanceAId);
    assertThat(integrationInstanceService.getIntegrationInstancesByWorkflowUuid(userAId, U2, Environment.PRODUCTION)).isEmpty();
    assertThat(integrationInstanceService.getIntegrationInstancesByWorkflowUuid(userBId, U1, Environment.PRODUCTION)).isEmpty();
}

@Test
void testGetIntegrationInstancesByWorkflowUuidOrdersByIdDescending() {
    // user A with two instances on v1 → ids [second, first]
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:testIntegration --tests '*IntegrationInstanceServiceIntTest' > /tmp/t3.log 2>&1; echo $?`
Expected: compile failure (method missing).

- [ ] **Step 3: Add the query, service method, remote stub**

Repository `@Query`: `SELECT DISTINCT integration_instance.* FROM integration_instance JOIN integration_instance_configuration ON ... JOIN integration_workflow ON integration_workflow.integration_id = integration_instance_configuration.integration_id AND integration_workflow.integration_version = integration_instance_configuration.integration_version WHERE integration_workflow.uuid = :uuid AND integration_instance_configuration.environment = :environment AND integration_instance.connected_user_id = :connectedUserId ORDER BY integration_instance.id DESC` as `List<IntegrationInstance> findAllByConnectedUserIdAndWorkflowUuidAndEnvironment(@Param("connectedUserId") long, @Param("uuid") UUID, @Param("environment") int)`. Service impl parses the uuid with `UUID.fromString`. Remote client: throw `UnsupportedOperationException`.

- [ ] **Step 4: Run the service IntTest to verify it passes**

Same command as Step 2. Expected: exit 0.

- [ ] **Step 5: Write the failing controller tests**

In `RequestTriggerApiControllerIntTest`: change `stubIntegrationWorkflow()` to stub `integrationInstanceService.getIntegrationInstancesByWorkflowUuid(1L, "uuid-1", Environment.PRODUCTION)` → `List.of(instance(2L, enabled))` and `integrationWorkflowService.getWorkflowId(2L, "uuid-1")` → `"integration-wf-1"`; every test that stubs `fetchLastWorkflowId(eq(x), any())` → `Optional.empty()` now stubs `getIntegrationInstancesByWorkflowUuid(anyLong(), eq(x), any())` → `List.of()`. Add:

```java
@Test
@WithMockUser(username = "user-1")
void testPinnedCustomerDispatchesAgainstItsOwnInstanceVersion() {
    stubConnectedUser("user-1", 1L);
    when(integrationInstanceService.getIntegrationInstancesByWorkflowUuid(1L, "uuid-1", Environment.PRODUCTION))
        .thenReturn(List.of(instance(7L, true)));
    when(integrationWorkflowService.getWorkflowId(7L, "uuid-1")).thenReturn("integration-wf-v1");
    when(workflowService.getWorkflow("integration-wf-v1")).thenReturn(requestTriggerWorkflow());

    expectDispatched(WORKFLOW_PATH, "uuid-1");

    assertThat(captureDispatchedWorkflowExecutionId().getJobPrincipalId()).isEqualTo(7L);
    verify(integrationWorkflowService, never()).fetchLastWorkflowId(any(), any());
}

@Test
@WithMockUser(username = "user-1")
void testSeveralMatchingInstancesPrefersTheEnabledOneWithTheHighestId() {
    // getIntegrationInstancesByWorkflowUuid → [instance(9, disabled), instance(8, enabled), instance(3, enabled)] → principal 8
}

@Test
@WithMockUser(username = "user-1")
void testNoInstanceForUuidFallsThroughToTheAutomationBridge() {
    // getIntegrationInstancesByWorkflowUuid → List.of(); reference facade returns empty → 404, no interaction with getWorkflowId
}
```

- [ ] **Step 6: Run them to verify they fail**

Run: `./gradlew :server:ee:libs:embedded:embedded-webhook:embedded-webhook-public-rest:testIntegration --tests '*RequestTriggerApiControllerIntTest' > /tmp/t3b.log 2>&1; echo $?`
Expected: non-zero.

- [ ] **Step 7: Implement in the controller**

In `executeWorkflow`: replace the `fetchLastWorkflowId` block with `List<IntegrationInstance> integrationInstances = integrationInstanceService.getIntegrationInstancesByWorkflowUuid(connectedUser.getId(), workflowUuid, environment)`; if non-empty, pick `integrationInstances.stream().filter(IntegrationInstance::isEnabled).findFirst().orElse(integrationInstances.getFirst())` (list is id-desc), resolve `integrationWorkflowService.getWorkflowId(instance.getId(), workflowUuid)` and call `executeIntegrationWorkflow(connectedUser, workflowUuid, workflowId, integrationInstance)` — change that private method to take the instance instead of re-looking it up. Otherwise keep the automation-bridge path unchanged.

- [ ] **Step 8: Run the controller IntTest + spotless**

Run: `./gradlew :server:ee:libs:embedded:embedded-webhook:embedded-webhook-public-rest:spotlessApply testIntegration --tests '*RequestTriggerApiControllerIntTest' > /tmp/t3c.log 2>&1; echo $?`
Expected: exit 0.

- [ ] **Step 9: Commit**

```bash
git add server/ee/libs/embedded/embedded-configuration/embedded-configuration-api server/ee/libs/embedded/embedded-configuration/embedded-configuration-service server/ee/libs/embedded/embedded-configuration/embedded-configuration-remote-client server/ee/libs/embedded/embedded-webhook/embedded-webhook-public-rest
git commit -m "Resolve embedded request trigger workflows from the connected user's own integration instance"
```

---

### Task 4: Provisioning operation and provisionable-integrations read (shared facade + admin facade)

**Files:**
- Create: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-api/src/main/java/com/bytechef/ee/embedded/configuration/dto/ProvisionableIntegrationDTO.java`
- Create: `.../dto/ProvisionIntegrationInstanceWorkflowDTO.java`
- Modify: `.../embedded-configuration-api/.../facade/ConnectedUserIntegrationFacade.java`, `.../facade/IntegrationInstanceAdminFacade.java`
- Modify: `.../embedded-configuration-service/.../facade/ConnectedUserIntegrationFacadeImpl.java`, `.../facade/IntegrationInstanceAdminFacadeImpl.java`
- Test (modify): `.../facade/ConnectedUserIntegrationFacadeIntTest.java`, `.../facade/IntegrationInstanceAdminFacadeIntTest.java`

**Interfaces:**
- Consumes: Task 1 `SELF_SERVE_ORDER`; `EmbeddedPermissionEvaluator.evaluate(String, ConnectedUser)`; `ConnectionFacade.create(ConnectionDTO, PlatformType)` / `delete(Long)`; `IntegrationInstanceFacade.updateIntegrationInstanceWorkflow(long, String, Map)` and `enableIntegrationInstanceWorkflow(long, String, boolean)` (admin principals pass `validateCurrentPrincipalIntegrationInstanceOwnership` because `ConnectedUserAuthentications.isConnectedUser()` is false); `OAuth2ParametersFacade.getOAuth2AuthorizationParameters(componentName, connectionVersion, connectionParameters, authorizationType)`; `OAuth2Service.getRedirectUri()`.
- Produces:
  - `record ProvisionIntegrationInstanceWorkflowDTO(String workflowUuid, Map<String, Object> inputs)`.
  - `record ProvisionableIntegrationDTO(IntegrationDTO integration, List<ProvisionableIntegrationInstanceConfiguration> integrationInstanceConfigurations)` with nested `record ProvisionableIntegrationInstanceConfiguration(IntegrationInstanceConfigurationDTO integrationInstanceConfiguration, boolean selfServe, List<String> sharedConnectionParameterNames, @Nullable OAuth2AuthorizationParameters oAuth2AuthorizationParameters, @Nullable String redirectUri)`; `integrationInstanceConfiguration.integrationInstanceConfigurationWorkflows()` already filtered to enabled + visible (reuse `filterWorkflows`).
  - On `ConnectedUserIntegrationFacade` (unguarded): `List<ProvisionableIntegrationDTO> getProvisionableIntegrations(long connectedUserId)` and `long provisionIntegrationInstance(long connectedUserId, long integrationInstanceConfigurationId, Map<String, Object> connectionParameters, List<ProvisionIntegrationInstanceWorkflowDTO> workflows)`; plus the refactor `IntegrationInstance createIntegrationInstance(ConnectedUser connectedUser, IntegrationInstanceConfiguration configuration, Map<String, Object> connectionParameters)` that the existing `createIntegrationInstance(externalUserId, integrationId, ...)` delegates to.
  - On `IntegrationInstanceAdminFacade` (`isTenantAdmin()`): the same two methods, delegating.

- [ ] **Step 1: Write the failing admin-facade IntTests**

In `IntegrationInstanceAdminFacadeIntTest` add `@MockitoBean private ConnectedUserIntegrationFacade connectedUserIntegrationFacade;` and, mirroring the three existing tests, `testProvisionIntegrationInstanceIsDeniedForConnectedUsersAndNonAdmins` (both principals → `AccessDeniedException`, `verifyNoInteractions`), `testProvisionIntegrationInstanceDelegatesForTenantAdmin` (verify `connectedUserIntegrationFacade.provisionIntegrationInstance(1L, 2L, Map.of("key","v"), List.of())`), and the same pair for `getProvisionableIntegrations(1L)`.

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:testIntegration --tests '*IntegrationInstanceAdminFacadeIntTest' > /tmp/t4.log 2>&1; echo $?`
Expected: non-zero (methods missing).

- [ ] **Step 3: Add the DTOs, interface methods, and the admin delegation**

Create the two records (EE header, `@version ee`). Add the methods to both interfaces. `IntegrationInstanceAdminFacadeImpl` gets a `ConnectedUserIntegrationFacade` constructor collaborator and delegates. Grep for `IntegrationInstanceAdminFacadeImpl` in `*IntTestConfiguration`/`@TestConfiguration` classes; add a mock `ConnectedUserIntegrationFacade` bean wherever one is hand-assembled (CLAUDE.md "adding a constructor collaborator" gotcha). Stub the shared-facade methods in `ConnectedUserIntegrationFacadeImpl` with `throw new UnsupportedOperationException()` for now.

- [ ] **Step 4: Run admin IntTest to verify it passes**

Same command as Step 2. Expected: exit 0.

- [ ] **Step 5: Write the failing shared-facade IntTests**

New `@Nested class ProvisioningTests` in `ConnectedUserIntegrationFacadeIntTest` (reuse the nested helpers; `connectionFacade` and `triggerLifecycleFacade` are already `@MockitoBean`s; stub `connectionFacade.create(any(), eq(PlatformType.EMBEDDED))` → `CONNECTION_ID`; stub `componentDefinitionService.getComponentDefinition("slack", null)` to a definition whose `getConnection()` is non-null with an `API_KEY` authorization — copy the sibling class's existing stub if one exists, otherwise build with `ComponentDsl.component("slack").connection(ComponentDsl.connection().authorizations(ComponentDsl.authorization(AuthorizationType.API_KEY)))`):

```java
@Test
void testProvisioningCreatesConnectionInstanceAndEnabledWorkflowsWithInputs() {
    // config v1 enabled with workflows A (enabled) and B (enabled); user Z
    long instanceId = connectedUserIntegrationFacade.provisionIntegrationInstance(
        connectedUserId, configurationId, Map.of("token", "t"),
        List.of(new ProvisionIntegrationInstanceWorkflowDTO(uuidA, Map.of("channel", "#ops"))));

    IntegrationInstance instance = integrationInstanceService.getIntegrationInstance(instanceId);
    assertThat(instance.getIntegrationInstanceConfigurationId()).isEqualTo(configurationId);
    assertThat(instance.getConnectionId()).isEqualTo(CONNECTION_ID);
    IntegrationInstanceWorkflow workflowA = integrationInstanceWorkflowService.getIntegrationInstanceWorkflow(instanceId, workflowIdA);
    assertThat(workflowA.isEnabled()).isTrue();
    assertThat(workflowA.getInputs()).containsEntry("channel", "#ops");
    assertThat(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(instanceId, workflowIdB)).isEmpty();
    ArgumentCaptor<ConnectionDTO> connectionCaptor = ArgumentCaptor.forClass(ConnectionDTO.class);
    verify(connectionFacade).create(connectionCaptor.capture(), eq(PlatformType.EMBEDDED));
    assertThat(connectionCaptor.getValue().parameters()).containsEntry("token", "t").containsEntry("sharedKey", "shared"); // config connectionParameters = {sharedKey: shared}
    verify(triggerLifecycleFacade).executeTriggerEnable(eq(workflowIdA), any(), any(), any(), any());
}

@Test void testProvisioningRefusesADisabledConfiguration()        // ConfigurationException with errorKey 105 (new INSTANCE_CONFIGURATION_DISABLED); no instance rows, connectionFacade never called
@Test void testProvisioningRefusesAConfigurationFromAnotherEnvironment()   // ConfigurationException errorKey 102 (INSTANCE_CONFIGURATION_NOT_FOUND)
@Test void testProvisioningRefusesAWorkflowNotInTheConfiguration() // uuid of a v2-only workflow → ConfigurationException errorKey 106 (new WORKFLOW_NOT_IN_CONFIGURATION)
@Test void testProvisioningRefusesAnIntegrationHiddenByThePermissionExpression() // integration expression "metadata['plan'] == 'pro'", user metadata {} → EmbeddedIntegrationNotVisibleException
@Test void testProvisioningRefusesAWorkflowHiddenByThePermissionExpression()   // → EmbeddedIntegrationNotVisibleException
@Test void testProvisioningAllowsASecondInstanceOnTheSameConfiguration()
@Test
void testProvisioningRollsBackInstanceConnectionAndTriggersWhenAWorkflowFailsToEnable() {
    // triggerLifecycleFacade.executeTriggerEnable(eq(workflowIdB), ...) throws IllegalStateException
    assertThatThrownBy(() -> provision(A, B)).isInstanceOf(IllegalStateException.class);
    assertThat(integrationInstanceService.getConnectedUserIntegrationInstances(connectedUserId)).isEmpty();
    verify(triggerLifecycleFacade).executeTriggerDisable(eq(workflowIdA), any(), any(), any(), any());
    verify(connectionFacade).delete(CONNECTION_ID);
}

@Test
void testProvisionableIntegrationsListsEnabledVisibleConfigurationsAndMarksTheSelfServeOne() {
    // integration with configs v1 (enabled), v2 (enabled), v3 (disabled); config v1 connectionParameters {clientId: x}
    // a second integration hidden by permission expression
    List<ProvisionableIntegrationDTO> integrations = connectedUserIntegrationFacade.getProvisionableIntegrations(connectedUserId);
    assertThat(integrations).hasSize(1);
    assertThat(integrations.getFirst().integrationInstanceConfigurations())
        .extracting(c -> c.integrationInstanceConfiguration().integrationVersion(), c -> c.selfServe())
        .containsExactly(tuple(1, false), tuple(2, true));
    assertThat(integrations.getFirst().integrationInstanceConfigurations().getFirst().sharedConnectionParameterNames()).containsExactly("clientId");
}
```

Check the exact `TriggerLifecycleFacade.executeTriggerEnable/Disable` signatures before writing the `verify` lines (the sibling delete tests in this class already verify them — copy their form).

- [ ] **Step 6: Run to verify they fail**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:testIntegration --tests '*ConnectedUserIntegrationFacadeIntTest$ProvisioningTests*' > /tmp/t4b.log 2>&1; echo $?`
Expected: non-zero (`UnsupportedOperationException`).

- [ ] **Step 7: Implement in `ConnectedUserIntegrationFacadeImpl`**

- Refactor: extract the body of `createIntegrationInstance(externalUserId, integrationId, params, env)` after the two lookups into `createIntegrationInstance(ConnectedUser, IntegrationInstanceConfiguration, Map)`; the public method resolves the self-serve configuration and delegates.
- `provisionIntegrationInstance` (`@Transactional(propagation = Propagation.NOT_SUPPORTED)` on this method — external trigger registration cannot be rolled back, so cleanup is explicit):
  1. load `ConnectedUser` by id, configuration by id; refuse when `configuration.getEnvironment() != connectedUser.getEnvironment()` with `ConfigurationException("...", IntegrationInstanceConfigurationErrorType.INSTANCE_CONFIGURATION_NOT_FOUND)`, and when `!configuration.isEnabled()` with a new constant `INSTANCE_CONFIGURATION_DISABLED = new IntegrationInstanceConfigurationErrorType(105)` appended to `IntegrationInstanceConfigurationErrorType` (`embedded-configuration-service/.../exception/`);
  2. load the `Integration` and refuse with `EmbeddedIntegrationNotVisibleException(integrationId)` when `!embeddedPermissionEvaluator.evaluate(integration.getPermissionExpression(), connectedUser)`;
  3. for each requested uuid: find the `IntegrationWorkflow` of `(integrationId, configuration.getIntegrationVersion())` with that uuid (via `integrationWorkflowService.getIntegrationWorkflows(integrationId, version)`); refuse when absent or when its `IntegrationInstanceConfigurationWorkflow` is not enabled with a new constant `WORKFLOW_NOT_IN_CONFIGURATION = new IntegrationInstanceConfigurationErrorType(106)`; refuse with `EmbeddedIntegrationNotVisibleException(integrationId)` when its permission expression evaluates false;
  4. `createIntegrationInstance(connectedUser, configuration, connectionParameters)`;
  5. for each workflow: `integrationInstanceFacade.updateIntegrationInstanceWorkflow(instanceId, workflowId, inputs)` then `enableIntegrationInstanceWorkflow(instanceId, workflowId, true)`; wrap the whole loop in try/catch: on failure disable the already-enabled ones (`enableIntegrationInstanceWorkflow(..., false)`), `integrationInstanceWorkflowService.deleteByIntegrationInstanceId`, `integrationInstanceService.delete`, `connectionFacade.delete(connectionId)`, rethrow.
- `getProvisionableIntegrations(connectedUserId)`: for every integration that has at least one enabled configuration in the user's environment (`integrationInstanceConfigurationService.getIntegrationInstanceConfigurations(environment, true)` grouped by integration) and passes the integration expression: build each configuration's DTO via `integrationInstanceConfigurationFacade.getIntegrationInstanceConfiguration(id)` → `filterWorkflows(...)`; `selfServe` = it is the max under `SELF_SERVE_ORDER`; `sharedConnectionParameterNames` = sorted keys of `configuration.getConnectionParameters()`; OAuth2 params only when `authorizationType.name().startsWith("OAUTH2")`, computed exactly as `getConnectedUserIntegration` does today; `redirectUri = oAuth2Service.getRedirectUri()`.

- [ ] **Step 8: Run the provisioning tests, then the whole class, then spotless**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:spotlessApply testIntegration --tests '*ConnectedUserIntegrationFacadeIntTest*' --tests '*IntegrationInstanceAdminFacadeIntTest' > /tmp/t4c.log 2>&1; echo $?`
Expected: exit 0.

- [ ] **Step 9: Commit**

```bash
git add server/ee/libs/embedded/embedded-configuration/embedded-configuration-api server/ee/libs/embedded/embedded-configuration/embedded-configuration-service
git commit -m "Add admin provisioning of embedded integration instances onto a chosen configuration"
```

---

### Task 5: Admin REST endpoints

**Files:**
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-rest/embedded-configuration-rest-impl/openapi.yaml`
- Modify: `.../embedded-configuration-rest-impl/src/main/java/com/bytechef/ee/embedded/configuration/web/rest/IntegrationInstanceApiController.java`
- Create: `.../embedded-configuration-rest-impl/src/main/java/com/bytechef/ee/embedded/configuration/web/rest/mapper/ProvisionableIntegrationMapper.java` (MapStruct, same `config` as the sibling mappers in that package)
- Test (create): `.../embedded-configuration-rest-impl/src/test/java/com/bytechef/ee/embedded/configuration/web/rest/IntegrationInstanceApiControllerIntTest.java`

**Interfaces:**
- Consumes: Task 4's admin facade methods and DTOs.
- Produces (spec): `POST /connected-users/{connectedUserId}/integration-instances` — tag `integration-instance`, operationId `provisionIntegrationInstance`, body `ProvisionIntegrationInstanceRequest { integrationInstanceConfigurationId: int64 (required), connectionParameters: object (additionalProperties), workflows: [ProvisionIntegrationInstanceWorkflow { workflowUuid: string (required), inputs: object }] }`, response `200` int64. `GET /connected-users/{connectedUserId}/provisionable-integrations` — operationId `getProvisionableIntegrations`, response array of `ProvisionableIntegration { integration: IntegrationBasic, integrationInstanceConfigurations: [ProvisionableIntegrationInstanceConfiguration { integrationInstanceConfiguration: IntegrationInstanceConfiguration, selfServe: boolean, sharedConnectionParameterNames: [string], oAuth2AuthorizationParameters: { authorizationUrl, clientId, extraQueryParameters: object, scopes: object } (nullable), redirectUri: string (nullable) }] }`. Generated TS lands in `client/src/ee/shared/middleware/embedded/configuration` as `IntegrationInstanceApi.provisionIntegrationInstance({connectedUserId, provisionIntegrationInstanceRequest})` and `getProvisionableIntegrations({connectedUserId})`.

- [ ] **Step 1: Write the failing WebMvc IntTest**

`IntegrationInstanceApiControllerIntTest` — copy the class-level setup of `ConnectionApiControllerIntTest` (`@ContextConfiguration(classes = EmbeddedConfigurationRestTestConfiguration.class)`, `@WebMvcTest(IntegrationInstanceApiController.class)`, `@EmbeddedConfigurationRestConfigurationSharedMocks`, the same `@MockitoBean` list plus `IntegrationInstanceAdminFacade`):

```java
@Test
@WithMockUser(roles = "ADMIN")
void testProvisionIntegrationInstancePassesBodyToTheAdminFacade() throws Exception {
    when(integrationInstanceAdminFacade.provisionIntegrationInstance(eq(5L), eq(9L), eq(Map.of("token", "t")), any())).thenReturn(123L);

    webTestClient.post().uri("/internal/connected-users/5/integration-instances") // sibling tests use the /internal prefix and a MockMvc-bound WebTestClient
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"integrationInstanceConfigurationId":9,"connectionParameters":{"token":"t"},
             "workflows":[{"workflowUuid":"u-1","inputs":{"channel":"#ops"}}]}""")
        .exchange().expectStatus().isOk().expectBody(Long.class).isEqualTo(123L);

    ArgumentCaptor<List<ProvisionIntegrationInstanceWorkflowDTO>> captor = ArgumentCaptor.captor();
    verify(integrationInstanceAdminFacade).provisionIntegrationInstance(eq(5L), eq(9L), any(), captor.capture());
    assertThat(captor.getValue()).containsExactly(new ProvisionIntegrationInstanceWorkflowDTO("u-1", Map.of("channel", "#ops")));
}

@Test
@WithMockUser(roles = "ADMIN")
void testGetProvisionableIntegrationsMapsSelfServeAndSharedParameterNames() throws Exception { /* one DTO → JSON path assertions on $[0].integrationInstanceConfigurations[0].selfServe and .sharedConnectionParameterNames[0] */ }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-rest:embedded-configuration-rest-impl:testIntegration --tests '*IntegrationInstanceApiControllerIntTest' > /tmp/t5.log 2>&1; echo $?`
Expected: non-zero (404 / compile failure).

- [ ] **Step 3: Extend the spec, regenerate, implement the controller + mapper**

Add the two paths and five schemas (see Interfaces) next to the existing `/integration-instances/...` paths. Run `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-rest:embedded-configuration-rest-impl:generateOpenAPI > /tmp/gen5.log 2>&1; echo $?` (exit 0). Implement the two generated methods in `IntegrationInstanceApiController` calling `integrationInstanceAdminFacade`; convert request workflows with a stream to `ProvisionIntegrationInstanceWorkflowDTO`; map responses through `ProvisionableIntegrationMapper` (`Converter<ProvisionableIntegrationDTO, ProvisionableIntegrationModel>`, reusing the existing `IntegrationInstanceConfigurationDTO → IntegrationInstanceConfigurationModel` converter via `ConversionService` the way sibling mappers do).

- [ ] **Step 4: Run the controller IntTest + module checks**

Run: `./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-rest:embedded-configuration-rest-impl:spotlessApply check > /tmp/t5b.log 2>&1; echo $?; grep -n "^> Task .* FAILED" /tmp/t5b.log`
Expected: exit 0, no FAILED lines.

- [ ] **Step 5: Commit (server + generated TS together)**

```bash
git add server/ee/libs/embedded/embedded-configuration/embedded-configuration-rest client/src/ee/shared/middleware/embedded/configuration
git commit -m "Add admin REST endpoints to provision integration instances for connected users"
```

---

### Task 6: Connected-user sheet — "Add integration" dialog

**Files:**
- Create: `client/src/ee/shared/queries/embedded/provisionableIntegrations.queries.ts` — `ProvisionableIntegrationKeys.provisionableIntegrations(connectedUserId)`, `useGetProvisionableIntegrationsQuery(connectedUserId, enabled)`.
- Modify: `client/src/ee/shared/mutations/embedded/integrationInstances.mutations.ts` — `useProvisionIntegrationInstanceMutation`.
- Create: `client/src/ee/pages/embedded/connected-users/components/connected-user-sheet/provision-integration-dialog/ProvisionIntegrationDialog.tsx` (dialog shell + form state), `ProvisionIntegrationDialogConfigurationSelect.tsx`, `ProvisionIntegrationDialogConnectionStep.tsx`, `ProvisionIntegrationDialogWorkflowItem.tsx`, `tests/ProvisionIntegrationDialog.test.tsx`.
- Modify: `client/src/ee/pages/embedded/connected-users/components/connected-user-sheet/ConnectedUserSheetPanelIntegrationList.tsx` (header row with `+ Add integration` button, rendered in both the populated and the empty state).

**Interfaces:**
- Consumes: generated `IntegrationInstanceApi.getProvisionableIntegrations` / `provisionIntegrationInstance`; `useGetComponentDefinitionQuery({componentName, componentVersion})` (admin platform query, as `ConnectionDialog` uses); `Properties` from `@/pages/platform/workflow-editor/components/properties/Properties`; `OAuth2Button` from `@/shared/components/connection/OAuth2Button`; `ConnectedUserKeys.connectedUser(id)` for invalidation.
- Produces: `ProvisionIntegrationDialog({connectedUserId, onClose})`.

Form state (react-hook-form): `{integrationId?: number; integrationInstanceConfigurationId?: number; connectionParameters: Record<string, unknown>; workflows: Array<{workflowUuid: string; enabled: boolean; inputs: Record<string, unknown>}>}`.

Behaviour the components implement:
- Integration select → configuration select listing `name · v{integrationVersion}` with a `Self-serve` badge when `selfServe`.
- Connection step: authorization = the component definition's connection authorization whose `type === configuration.authorizationType`; render `Properties` for `authorization.properties.filter(p => !sharedConnectionParameterNames.includes(p.name))` bound to `connectionParameters`; when `authorizationType` starts with `OAUTH2`, render `OAuth2Button` with the configuration's `oAuth2AuthorizationParameters` + `redirectUri`, `responseType="code"`, `onCodeSuccess={({code}) => setValue('connectionParameters.code', code)}`; the Provision button is disabled until `code` is set for OAuth2 types.
- Workflow item: a `Switch` per configuration workflow; when on, render that workflow's inputs (`workflow.inputs` filtered to `!internalOnly`, mapped to properties the same way `IntegrationInstanceConfigurationDialogWorkflowsStepItem` maps them) bound to `workflows.{index}.inputs`.
- Submit: `mutate({connectedUserId, provisionIntegrationInstanceRequest: {integrationInstanceConfigurationId, connectionParameters, workflows: workflows.filter(w => w.enabled).map(({workflowUuid, inputs}) => ({workflowUuid, inputs}))}})`; on success invalidate `ConnectedUserKeys.connectedUser(connectedUserId)` and close; on error show the existing toast pattern of the sheet.

- [ ] **Step 1: Write the failing component test**

`tests/ProvisionIntegrationDialog.test.tsx` (vitest + testing-library; mock `@/ee/shared/middleware/embedded/configuration` `IntegrationInstanceApi` methods and the component-definition query, following `ConnectedUserProjectWorkflowListItem.test.tsx`'s `vi.mock` style):

```ts
it('lists only the provisionable configurations and marks the self-serve one', ...)   // two configs → 2 options, one with "Self-serve"
it('hides connection fields that the configuration already provides', ...)            // properties [apiKey, clientId], shared [clientId] → only apiKey rendered
it('submits the chosen configuration, connection parameters and enabled workflows with inputs', ...)
   // expect provisionIntegrationInstance called with {connectedUserId: 7, provisionIntegrationInstanceRequest: {integrationInstanceConfigurationId: 2, connectionParameters: {apiKey: 'k'}, workflows: [{workflowUuid: 'u-1', inputs: {channel: '#ops'}}]}}
it('keeps Provision disabled for an OAuth2 configuration until a code arrives', ...)
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd client && npx vitest run src/ee/pages/embedded/connected-users/components/connected-user-sheet/provision-integration-dialog > /tmp/c6.log 2>&1; echo $?`
Expected: non-zero (module not found).

- [ ] **Step 3: Implement query, mutation, components, and the button**

Per Interfaces/behaviour above. Button copy: `Add integration`. Dialog title: `Provision integration`. Primary button: `Provision`.

- [ ] **Step 4: Run the test, then the client checks**

Run: `cd client && npx vitest run src/ee/pages/embedded/connected-users > /tmp/c6b.log 2>&1; echo $?; grep -c Unhandled /tmp/c6b.log; npm run format > /dev/null 2>&1; npm run check > /tmp/c6c.log 2>&1; echo $?`
Expected: vitest exit 0 with `0` unhandled errors; `npm run check` exit 0.

- [ ] **Step 5: Commit**

```bash
git add client/src/ee/pages/embedded/connected-users client/src/ee/shared/queries/embedded/provisionableIntegrations.queries.ts client/src/ee/shared/mutations/embedded/integrationInstances.mutations.ts
git commit -m "client - Add integration provisioning dialog to the connected user sheet"
```

---

### Task 7: Configurations page guard check and whole-feature verification

**Files:**
- Possibly modify: `client/src/ee/pages/embedded/integration-instance-configurations/**` (only if a client-side single-enabled guard exists).

- [ ] **Step 1: Look for a client-side guard against a second enabled configuration**

Run: `grep -rniE "already enabled|only one .*enabled|another .*enabled" client/src/ee/pages/embedded/integration-instance-configurations --include='*.tsx' --include='*.ts'`
Expected: no output. If something prints, remove that guard (and its test), commit as `client - Allow several enabled integration instance configurations per integration`.

- [ ] **Step 2: Full server checks on every touched module**

Run: `./gradlew --continue :server:ee:libs:embedded:embedded-configuration:embedded-configuration-api:check :server:ee:libs:embedded:embedded-configuration:embedded-configuration-service:check :server:ee:libs:embedded:embedded-configuration:embedded-configuration-public-rest:check :server:ee:libs:embedded:embedded-configuration:embedded-configuration-rest:embedded-configuration-rest-impl:check :server:ee:libs:embedded:embedded-webhook:embedded-webhook-public-rest:check :server:ee:libs:embedded:embedded-configuration:embedded-configuration-remote-client:check > /tmp/t7.log 2>&1; echo $?; grep -n "^> Task .* FAILED" /tmp/t7.log`
Expected: exit 0, no FAILED lines. Then `./gradlew compileJava compileTestJava --continue > /tmp/t7b.log 2>&1; echo $?` (exit 0) — catches any remote-client or EE-app caller of the removed repository method.

- [ ] **Step 3: Client and SDK checks**

Run: `cd client && npm run check > /tmp/t7c.log 2>&1; echo $?; cd ../sdks/frontend/embedded/library && npx vitest run > /tmp/t7d.log 2>&1; echo $?`
Expected: both exit 0.

- [ ] **Step 4: Manual smoke (monolith)**

Start infra + `bootRun`, log in as admin, create a second enabled configuration for an integration on an older version, open a connected user → Add integration → pick the older configuration → provision with an API-key component → confirm the instance row shows the older version, and that the embedded ConnectDialog (sample app or SDK test harness) for that user shows the older version's workflows. Record the outcome in the final report; do not claim this step without running it.
