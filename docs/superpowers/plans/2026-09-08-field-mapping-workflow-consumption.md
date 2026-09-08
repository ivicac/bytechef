# Field Mapping Workflow Consumption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a connected user's saved field mapping consumable inside an embedded workflow, in both directions, through a new EE `field-mapping` component with `mapToIntegration` / `mapToApplication` actions.

**Architecture:** Three layered changes. (1) `IntegrationJobPrincipalAccessor.getInputMap` merges per-connected-user workflow inputs over configuration inputs so the saved descriptor reaches job runtime as `${input.<name>}`. (2) A Spring-registered EE component resolves the descriptor by object name — from the connected user's `IntegrationInstanceWorkflow` at runtime, or from a `sampleMapping` in the input's test value under the editor's Test button — and applies it with a small pure transform. (3) The editor's synthetic per-field pills on the input are removed; pills now come from the action node's test-run output.

**Tech Stack:** Java 25, Spring Boot 4, ByteChef component DSL (`ComponentDsl`), JUnit 5 + Mockito, `JsonFileAssert` definition snapshots; React 19 + Vitest on the client; Fumadocs MDX.

**Spec:** `docs/superpowers/specs/2026-09-08-field-mapping-workflow-consumption-design.md`

## Global Constraints

- **Edition:** everything server-side is EE and lives under `server/ee/`. Every new Java file there carries the ByteChef Enterprise license header (copied verbatim in Task 1) and a `@version ee` Javadoc tag.
- **Component name:** `fieldMapping`. Action names: `mapToIntegration`, `mapToApplication`. Property names: `objectName`, `inputType`, `data`, `includeUnmapped`.
- **Input type string on the server is `field_mapping`** (lower-case); the REST layer upper-cases it.
- **Transform semantics (spec §5):** unmapped source keys dropped unless `includeUnmapped`; a missing source key omits the destination key (never writes `null`); dotted paths on both sides; lists map element-wise; last declared mapping wins on collisions.
- **Failures are explicit** (spec §5 "Failure modes"): never pass a payload through unmapped.
- **Commit format** (CLAUDE.md): server `--- <description>`, client `- client - <description>`, docs `--- docs - <description>`; end every commit message with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- **Before each server commit:** `./gradlew spotlessApply` on the touched modules. **Never judge a Gradle run through a pipe** — redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`.
- **Checkstyle:** test method names are camelCase with no underscores; no `TODO:` comments; one blank line before control statements and after a variable modification that the next statement uses.
- **Client:** interfaces end in `I`/`Props`; object keys sorted; Lucide icons imported with the `Icon` suffix; no bare Zustand store calls.

---

## File structure

**Created**

| Path | Responsibility |
|---|---|
| `server/ee/libs/modules/components/field-mapping/build.gradle.kts` | Module dependencies |
| `.../fieldmapping/constant/FieldMappingConstants.java` | Component, action and property names |
| `.../fieldmapping/constant/FieldMappingInputType.java` | `OBJECT` / `ARRAY` selector values |
| `.../fieldmapping/mapper/FieldMappingDescriptor.java` | The saved-mapping shape, parsed from a `Map`; rejects empty mappings |
| `.../fieldmapping/mapper/FieldMappingDirection.java` | `TO_INTEGRATION` / `TO_APPLICATION`: which side is source, which is destination, plus action metadata |
| `.../fieldmapping/mapper/FieldMappingPaths.java` | Split-on-dot nested read/write, mirroring `ClusterElementContextImpl`'s nested implementation |
| `.../fieldmapping/mapper/FieldMappingApplier.java` | The pure transform: descriptor + payload + direction → mapped payload |
| `.../fieldmapping/resolver/FieldMappingDescriptorResolver.java` | Finds the workflow input by object name and loads the descriptor (runtime vs editor branch) |
| `.../fieldmapping/action/FieldMappingMapAction.java` | Builds one action definition per direction; `perform` wires resolver + applier |
| `.../fieldmapping/FieldMappingComponentHandler.java` | Spring-registered `ComponentHandler` |
| `.../field-mapping/src/main/resources/assets/field-mapping.svg` | Component icon |
| `.../field-mapping/src/main/resources/README.mdx` | Prose for the generated reference page |
| `.../field-mapping/src/test/...` | `FieldMappingApplierTest`, `FieldMappingPathsTest`, `FieldMappingDescriptorResolverTest`, `FieldMappingComponentHandlerTest` + `definition/field-mapping_v1.json` |
| `.agents/field-mapping.md` | Feature deep-dive |

(`...` = `server/ee/libs/modules/components/field-mapping/src/main/java/com/bytechef/ee/component/`)

**Modified**

| Path | Change |
|---|---|
| `settings.gradle.kts` | Include the new module |
| `server/ee/libs/embedded/embedded-configuration/embedded-configuration-instance-impl/src/main/java/com/bytechef/ee/embedded/configuration/instance/accessor/IntegrationJobPrincipalAccessor.java` | Merge per-user inputs in `getInputMap` |
| `.../instance/accessor/IntegrationJobPrincipalAccessorTest.java` | Cover the merge |
| `client/src/pages/platform/workflow-editor/components/datapills/DataPillPanelBodyInputsItem.tsx` (+ test) | Drop the synthetic child pills |
| `client/src/pages/platform/workflow-editor/utils/getFieldMappingPillProperties.ts` (+ test) | Deleted |
| `client/src/pages/platform/workflow-editor/components/workflow-inputs/WorkflowInputsEditDialog.tsx` | Mention `sampleMapping` in the test-value help text and placeholder |
| `docs/content/docs/platform/embedded/build/workflows/field-mapping.mdx` | Replace "apply it yourself" with the component + script usage; fix the stale Edit Input warning |
| `CLAUDE.md` | One row in the feature deep-dive table |
| `docs/superpowers/specs/2026-09-08-...-design.md` | One-sentence amendment in §5 (path helper) |

---

### Task 1: Runtime plumbing — merge per-user inputs into the job context

**Files:**
- Modify: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-instance-impl/src/main/java/com/bytechef/ee/embedded/configuration/instance/accessor/IntegrationJobPrincipalAccessor.java`
- Test: `server/ee/libs/embedded/embedded-configuration/embedded-configuration-instance-impl/src/test/java/com/bytechef/ee/embedded/configuration/instance/accessor/IntegrationJobPrincipalAccessorTest.java`

**Interfaces:**
- Consumes: `IntegrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(long integrationInstanceId, String workflowId): Optional<IntegrationInstanceWorkflow>` (exists, `embedded-configuration-api`); `MapUtils.concat(Map<K,V>, Map<K,V>): Map<K,V>` (exists, `commons-util`, second map wins).
- Produces: `IntegrationJobPrincipalAccessor` gains a fifth constructor parameter `IntegrationInstanceWorkflowService integrationInstanceWorkflowService` (after `IntegrationInstanceService`, before `IntegrationWorkflowService`). Only its own unit test constructs it by hand — verified by grep; no `*IntTestConfiguration` does.

- [ ] **Step 1: Extend the existing test class with the new collaborator and two failing tests**

Replace the `@Mock` block, `setUp`, and the `testGetInputMapUsesConfigurationIdNotInstanceId` method in `IntegrationJobPrincipalAccessorTest.java` with the following (the two `isWorkflowEnabled` tests stay as they are). Add these imports: `com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceWorkflow`, `com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService`, `java.util.Optional`.

```java
    @Mock
    private IntegrationInstanceConfigurationService integrationInstanceConfigurationService;

    @Mock
    private IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService;

    @Mock
    private IntegrationInstanceService integrationInstanceService;

    @Mock
    private IntegrationInstanceWorkflowService integrationInstanceWorkflowService;

    @Mock
    private IntegrationWorkflowService integrationWorkflowService;

    @Mock
    private IntegrationInstance integrationInstance;

    private IntegrationJobPrincipalAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = new IntegrationJobPrincipalAccessor(
            integrationInstanceConfigurationService,
            integrationInstanceConfigurationWorkflowService,
            integrationInstanceService,
            integrationInstanceWorkflowService,
            integrationWorkflowService);

        when(integrationInstanceService.getIntegrationInstance(INSTANCE_ID)).thenReturn(integrationInstance);
        when(integrationInstance.getIntegrationInstanceConfigurationId()).thenReturn(CONFIGURATION_ID);
    }

    @Test
    void testGetInputMapUsesConfigurationIdNotInstanceId() {
        when(integrationWorkflowService.getWorkflowId(INSTANCE_ID, WORKFLOW_UUID)).thenReturn(WORKFLOW_ID);

        IntegrationInstanceConfigurationWorkflow configurationWorkflow =
            new IntegrationInstanceConfigurationWorkflow();

        when(integrationInstanceConfigurationWorkflowService
            .getIntegrationInstanceConfigurationWorkflow(CONFIGURATION_ID, WORKFLOW_ID))
                .thenReturn(configurationWorkflow);
        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.empty());

        Map<String, ?> result = accessor.getInputMap(INSTANCE_ID, WORKFLOW_UUID);

        assertEquals(configurationWorkflow.getInputs(), result);

        verify(integrationInstanceConfigurationWorkflowService)
            .getIntegrationInstanceConfigurationWorkflow(CONFIGURATION_ID, WORKFLOW_ID);

        verify(integrationInstanceConfigurationWorkflowService, never())
            .getIntegrationInstanceConfigurationWorkflow(INSTANCE_ID, WORKFLOW_ID);
    }

    @Test
    void testGetInputMapMergesConnectedUserInputsOverConfigurationInputs() {
        when(integrationWorkflowService.getWorkflowId(INSTANCE_ID, WORKFLOW_UUID)).thenReturn(WORKFLOW_ID);

        IntegrationInstanceConfigurationWorkflow configurationWorkflow =
            new IntegrationInstanceConfigurationWorkflow();

        configurationWorkflow.setInputs(Map.of("apiKey", "config-key", "region", "eu"));

        when(integrationInstanceConfigurationWorkflowService
            .getIntegrationInstanceConfigurationWorkflow(CONFIGURATION_ID, WORKFLOW_ID))
                .thenReturn(configurationWorkflow);

        IntegrationInstanceWorkflow instanceWorkflow = new IntegrationInstanceWorkflow();

        instanceWorkflow.setInputs(
            Map.of("apiKey", "user-key", "contactMapping", Map.of("objectType", "contacts", "mappings", List.of())));

        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.of(instanceWorkflow));

        Map<String, ?> result = accessor.getInputMap(INSTANCE_ID, WORKFLOW_UUID);

        assertEquals("user-key", result.get("apiKey"));
        assertEquals("eu", result.get("region"));
        assertEquals(Map.of("objectType", "contacts", "mappings", List.of()), result.get("contactMapping"));
    }

    @Test
    void testGetInputMapReturnsConfigurationInputsWhenConnectedUserHasNoRow() {
        when(integrationWorkflowService.getWorkflowId(INSTANCE_ID, WORKFLOW_UUID)).thenReturn(WORKFLOW_ID);

        IntegrationInstanceConfigurationWorkflow configurationWorkflow =
            new IntegrationInstanceConfigurationWorkflow();

        configurationWorkflow.setInputs(Map.of("apiKey", "config-key"));

        when(integrationInstanceConfigurationWorkflowService
            .getIntegrationInstanceConfigurationWorkflow(CONFIGURATION_ID, WORKFLOW_ID))
                .thenReturn(configurationWorkflow);
        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.empty());

        Map<String, ?> result = accessor.getInputMap(INSTANCE_ID, WORKFLOW_UUID);

        assertEquals(Map.of("apiKey", "config-key"), result);
    }
```

Also add `import java.util.List;`.

- [ ] **Step 2: Run the test to verify it fails to compile**

```bash
./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-instance-impl:test --tests '*IntegrationJobPrincipalAccessorTest' > /tmp/fm-task1-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`; `grep -n "error:" /tmp/fm-task1-red.log` shows the five-argument constructor does not exist.

- [ ] **Step 3: Implement the merge**

In `IntegrationJobPrincipalAccessor.java`:

Add imports `com.bytechef.commons.util.MapUtils` and `com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService`.

Replace the fields and constructor:

```java
    private final IntegrationInstanceConfigurationService integrationInstanceConfigurationService;
    private final IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService;
    private final IntegrationInstanceService integrationInstanceService;
    private final IntegrationInstanceWorkflowService integrationInstanceWorkflowService;
    private final IntegrationWorkflowService integrationWorkflowService;

    @SuppressFBWarnings("EI")
    public IntegrationJobPrincipalAccessor(
        IntegrationInstanceConfigurationService integrationInstanceConfigurationService,
        IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService,
        IntegrationInstanceService integrationInstanceService,
        IntegrationInstanceWorkflowService integrationInstanceWorkflowService,
        IntegrationWorkflowService integrationWorkflowService) {

        this.integrationInstanceConfigurationService = integrationInstanceConfigurationService;
        this.integrationInstanceConfigurationWorkflowService = integrationInstanceConfigurationWorkflowService;
        this.integrationInstanceService = integrationInstanceService;
        this.integrationInstanceWorkflowService = integrationInstanceWorkflowService;
        this.integrationWorkflowService = integrationWorkflowService;
    }
```

Replace `getInputMap`:

```java
    /**
     * The job's initial context: configuration-level inputs overlaid with the connected user's own inputs, the
     * per-user value winning. This is the same precedence {@code IntegrationInstanceFacadeImpl} applies when it
     * enables and disables triggers; a connected user who has never opened the Connect Portal for this workflow has
     * no per-user row and gets the configuration inputs alone.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, ?> getInputMap(long jobPrincipalId, String workflowUuid) {
        IntegrationInstance integrationInstance = integrationInstanceService.getIntegrationInstance(jobPrincipalId);

        String workflowId = getWorkflowId(jobPrincipalId, workflowUuid);

        IntegrationInstanceConfigurationWorkflow integrationInstanceConfigurationWorkflow =
            integrationInstanceConfigurationWorkflowService.getIntegrationInstanceConfigurationWorkflow(
                integrationInstance.getIntegrationInstanceConfigurationId(), workflowId);

        Map<String, Object> connectedUserInputs = integrationInstanceWorkflowService
            .fetchIntegrationInstanceWorkflow(jobPrincipalId, workflowId)
            .map(integrationInstanceWorkflow -> (Map<String, Object>) integrationInstanceWorkflow.getInputs())
            .orElse(Map.of());

        return MapUtils.concat(
            (Map<String, Object>) integrationInstanceConfigurationWorkflow.getInputs(), connectedUserInputs);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-instance-impl:test --tests '*IntegrationJobPrincipalAccessorTest' > /tmp/fm-task1-green.log 2>&1; echo "exit=$?"
```
Expected: `exit=0`; `grep -c "FAILED" /tmp/fm-task1-green.log` prints `0`.

- [ ] **Step 5: Format and commit**

```bash
./gradlew :server:ee:libs:embedded:embedded-configuration:embedded-configuration-instance-impl:spotlessApply > /tmp/fm-task1-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs/embedded/embedded-configuration/embedded-configuration-instance-impl/src/main/java/com/bytechef/ee/embedded/configuration/instance/accessor/IntegrationJobPrincipalAccessor.java server/ee/libs/embedded/embedded-configuration/embedded-configuration-instance-impl/src/test/java/com/bytechef/ee/embedded/configuration/instance/accessor/IntegrationJobPrincipalAccessorTest.java
git commit -F - <<'MSG'
--- Merge the connected user's workflow inputs into the embedded job context

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 2: Module scaffold, descriptor, path helper and the transform

**Files:**
- Create: `server/ee/libs/modules/components/field-mapping/build.gradle.kts`
- Modify: `settings.gradle.kts` (after the `context-store` include)
- Create: `server/ee/libs/modules/components/field-mapping/src/main/java/com/bytechef/ee/component/fieldmapping/constant/FieldMappingConstants.java`
- Create: `.../fieldmapping/constant/FieldMappingInputType.java`
- Create: `.../fieldmapping/mapper/FieldMappingDescriptor.java`
- Create: `.../fieldmapping/mapper/FieldMappingDirection.java`
- Create: `.../fieldmapping/mapper/FieldMappingPaths.java`
- Create: `.../fieldmapping/mapper/FieldMappingApplier.java`
- Test: `.../field-mapping/src/test/java/com/bytechef/ee/component/fieldmapping/mapper/FieldMappingPathsTest.java`
- Test: `.../field-mapping/src/test/java/com/bytechef/ee/component/fieldmapping/mapper/FieldMappingApplierTest.java`
- Modify: `docs/superpowers/specs/2026-09-08-field-mapping-workflow-consumption-design.md` (one sentence in §5)

**Interfaces:**
- Produces:
  - `FieldMappingDescriptor(String objectType, List<FieldMappingDescriptor.Mapping> mappings)` with nested `record Mapping(String applicationField, String integrationField)` and `static FieldMappingDescriptor of(Map<String, ?> map)` — throws `IllegalArgumentException("Field mapping has no mappings")` when `mappings` is missing or empty.
  - `enum FieldMappingDirection { TO_INTEGRATION, TO_APPLICATION }` with `String actionName()`, `String title()`, `String description()`, `String sourcePath(Mapping)`, `String destinationPath(Mapping)`.
  - `FieldMappingPaths.containsPath(Map<String, ?> map, String path): boolean`, `getValue(Map<String, ?> map, String path): Object`, `setValue(Map<String, Object> map, String path, Object value): void`.
  - `FieldMappingApplier.apply(FieldMappingDescriptor descriptor, Object data, FieldMappingDirection direction, boolean includeUnmapped): Object` — returns a `Map<String, Object>` for a map payload and a `List<Map<String, Object>>` for a list payload; throws `IllegalArgumentException` for anything else.
  - `FieldMappingConstants.FIELD_MAPPING = "fieldMapping"`, `OBJECT_NAME = "objectName"`, `INPUT_TYPE = "inputType"`, `DATA = "data"`, `INCLUDE_UNMAPPED = "includeUnmapped"`.
  - `enum FieldMappingInputType { OBJECT, ARRAY }`.

The license header for **every** new Java file in this module, verbatim:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */
```

- [ ] **Step 1: Scaffold the module**

Create `server/ee/libs/modules/components/field-mapping/build.gradle.kts`:

```kotlin
version="1.0"

dependencies {
    implementation("org.springframework:spring-context")
    implementation(project(":server:libs:atlas:atlas-configuration:atlas-configuration-api"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:platform:platform-api"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))
    implementation(project(":server:libs:platform:platform-configuration:platform-configuration-api"))

    implementation(project(":server:ee:libs:embedded:embedded-configuration:embedded-configuration-api"))
}
```

(`component-api`, `commons-lang3`, `test-support` and `component-test` come from `server/ee/libs/modules/components/build.gradle.kts`'s `subprojects` block.)

In `settings.gradle.kts`, after `include("server:ee:libs:modules:components:context-store")` add:

```kotlin
include("server:ee:libs:modules:components:field-mapping")
```

Create `constant/FieldMappingConstants.java`:

```java
package com.bytechef.ee.component.fieldmapping.constant;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class FieldMappingConstants {

    public static final String DATA = "data";
    public static final String FIELD_MAPPING = "fieldMapping";
    public static final String INCLUDE_UNMAPPED = "includeUnmapped";
    public static final String INPUT_TYPE = "inputType";
    public static final String OBJECT_NAME = "objectName";

    private FieldMappingConstants() {
    }
}
```

Create `constant/FieldMappingInputType.java`:

```java
package com.bytechef.ee.component.fieldmapping.constant;

/**
 * Whether the {@code data} property carries one object or a list of objects. Mirrors the {@code inputType} selector
 * the data-mapper component uses for the same purpose.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum FieldMappingInputType {

    OBJECT, ARRAY
}
```

- [ ] **Step 2: Write the failing path-helper test**

Create `src/test/java/com/bytechef/ee/component/fieldmapping/mapper/FieldMappingPathsTest.java`:

```java
package com.bytechef.ee.component.fieldmapping.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class FieldMappingPathsTest {

    @Test
    void testContainsPathAndGetValueOnTopLevelKey() {
        Map<String, Object> map = Map.of("title", "Dr");

        assertTrue(FieldMappingPaths.containsPath(map, "title"));
        assertEquals("Dr", FieldMappingPaths.getValue(map, "title"));
    }

    @Test
    void testContainsPathIsTrueForPresentNullValue() {
        Map<String, Object> map = new HashMap<>();

        map.put("title", null);

        assertTrue(FieldMappingPaths.containsPath(map, "title"));
        assertNull(FieldMappingPaths.getValue(map, "title"));
    }

    @Test
    void testContainsPathIsFalseForMissingKey() {
        assertFalse(FieldMappingPaths.containsPath(Map.of("title", "Dr"), "email"));
    }

    @Test
    void testNestedReadThroughDottedPath() {
        Map<String, Object> map = Map.of("properties", Map.of("firstname", "Ada"));

        assertTrue(FieldMappingPaths.containsPath(map, "properties.firstname"));
        assertEquals("Ada", FieldMappingPaths.getValue(map, "properties.firstname"));
        assertFalse(FieldMappingPaths.containsPath(map, "properties.lastname"));
        assertFalse(FieldMappingPaths.containsPath(map, "title.firstname"));
    }

    @Test
    void testSetValueCreatesIntermediateMaps() {
        Map<String, Object> map = new LinkedHashMap<>();

        FieldMappingPaths.setValue(map, "properties.firstname", "Ada");
        FieldMappingPaths.setValue(map, "properties.lastname", "Lovelace");
        FieldMappingPaths.setValue(map, "email", "ada@example.com");

        assertEquals(
            Map.of("properties", Map.of("firstname", "Ada", "lastname", "Lovelace"), "email", "ada@example.com"),
            map);
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingPathsTest' > /tmp/fm-task2-paths-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`, compilation error `cannot find symbol ... FieldMappingPaths`.

- [ ] **Step 4: Implement the path helper**

Create `mapper/FieldMappingPaths.java`:

```java
package com.bytechef.ee.component.fieldmapping.mapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Split-on-dot nested access for the transform. The same approach {@code ClusterElementContextImpl}'s nested
 * implementation takes; that one is reachable only from a cluster-element context, so the component carries its own.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
final class FieldMappingPaths {

    private FieldMappingPaths() {
    }

    static boolean containsPath(Map<String, ?> map, String path) {
        String[] segments = path.split("\\.");
        Map<String, ?> current = map;

        for (int index = 0; index < segments.length; index++) {
            if (!current.containsKey(segments[index])) {
                return false;
            }

            if (index == segments.length - 1) {
                return true;
            }

            if (!(current.get(segments[index]) instanceof Map<?, ?> nested)) {
                return false;
            }

            current = castMap(nested);
        }

        return false;
    }

    static Object getValue(Map<String, ?> map, String path) {
        String[] segments = path.split("\\.");
        Map<String, ?> current = map;

        for (int index = 0; index < segments.length - 1; index++) {
            if (!(current.get(segments[index]) instanceof Map<?, ?> nested)) {
                return null;
            }

            current = castMap(nested);
        }

        return current.get(segments[segments.length - 1]);
    }

    static void setValue(Map<String, Object> map, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = map;

        for (int index = 0; index < segments.length - 1; index++) {
            Object existing = current.get(segments[index]);

            if (existing instanceof Map<?, ?> nested) {
                current = castMutableMap(nested);
            } else {
                Map<String, Object> created = new LinkedHashMap<>();

                current.put(segments[index], created);

                current = created;
            }
        }

        current.put(segments[segments.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castMap(Map<?, ?> map) {
        return (Map<String, ?>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMutableMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
```

- [ ] **Step 5: Run the path test to verify it passes**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingPathsTest' > /tmp/fm-task2-paths-green.log 2>&1; echo "exit=$?"
```
Expected: `exit=0`.

- [ ] **Step 6: Write the failing descriptor + applier test**

Create `src/test/java/com/bytechef/ee/component/fieldmapping/mapper/FieldMappingApplierTest.java`:

```java
package com.bytechef.ee.component.fieldmapping.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class FieldMappingApplierTest {

    private static final FieldMappingDescriptor DESCRIPTOR = new FieldMappingDescriptor(
        "contacts",
        List.of(
            new FieldMappingDescriptor.Mapping("title", "first_name"),
            new FieldMappingDescriptor.Mapping("priority", "hs_priority")));

    @Test
    void testDescriptorParsesTheSavedShape() {
        FieldMappingDescriptor descriptor = FieldMappingDescriptor.of(
            Map.of(
                "objectType", "contacts",
                "mappings", List.of(
                    Map.of(
                        "applicationField", Map.of("label", "Title", "value", "title", "custom", false),
                        "integrationField", "first_name"),
                    Map.of(
                        "applicationField", Map.of("label", "Priority", "value", "priority", "custom", true),
                        "integrationField", "hs_priority"))));

        assertEquals(DESCRIPTOR, descriptor);
    }

    @Test
    void testDescriptorRejectsEmptyMappings() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> FieldMappingDescriptor.of(Map.of("objectType", "contacts", "mappings", List.of())));

        assertEquals("Field mapping has no mappings", exception.getMessage());
    }

    @Test
    void testDescriptorRejectsMissingMappings() {
        assertThrows(
            IllegalArgumentException.class, () -> FieldMappingDescriptor.of(Map.of("objectType", "contacts")));
    }

    @Test
    void testMapToIntegrationRenamesApplicationKeysToIntegrationKeys() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("title", "Dr", "priority", "high"), FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(Map.of("first_name", "Dr", "hs_priority", "high"), result);
    }

    @Test
    void testMapToApplicationRenamesIntegrationKeysToApplicationKeys() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("first_name", "Dr", "hs_priority", "high"), FieldMappingDirection.TO_APPLICATION,
            false);

        assertEquals(Map.of("title", "Dr", "priority", "high"), result);
    }

    @Test
    void testUnmappedSourceKeysAreDroppedByDefault() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("first_name", "Dr", "lifecyclestage", "lead"), FieldMappingDirection.TO_APPLICATION,
            false);

        assertEquals(Map.of("title", "Dr"), result);
    }

    @Test
    void testUnmappedSourceKeysPassThroughWhenIncludeUnmapped() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("first_name", "Dr", "lifecyclestage", "lead"), FieldMappingDirection.TO_APPLICATION,
            true);

        assertEquals(Map.of("title", "Dr", "lifecyclestage", "lead"), result);
    }

    @Test
    void testMissingSourceKeyOmitsDestinationKeyRatherThanWritingNull() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("title", "Dr"), FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(Map.of("first_name", "Dr"), result);
        assertFalse(result.containsKey("hs_priority"));
    }

    @Test
    void testPresentNullSourceValueIsCarriedAcross() {
        Map<String, Object> source = new HashMap<>();

        source.put("title", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldMappingApplier.apply(
            DESCRIPTOR, source, FieldMappingDirection.TO_INTEGRATION, false);

        assertTrue(result.containsKey("first_name"));
        assertNull(result.get("first_name"));
    }

    @Test
    void testDottedPathsResolveNestedStructuresOnBothSides() {
        FieldMappingDescriptor descriptor = new FieldMappingDescriptor(
            "contacts", List.of(new FieldMappingDescriptor.Mapping("contact.title", "properties.firstname")));

        Object toIntegration = FieldMappingApplier.apply(
            descriptor, Map.of("contact", Map.of("title", "Dr")), FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(Map.of("properties", Map.of("firstname", "Dr")), toIntegration);

        Object toApplication = FieldMappingApplier.apply(
            descriptor, Map.of("properties", Map.of("firstname", "Dr")), FieldMappingDirection.TO_APPLICATION,
            false);

        assertEquals(Map.of("contact", Map.of("title", "Dr")), toApplication);
    }

    @Test
    void testListPayloadMapsElementWise() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR,
            List.of(Map.of("title", "Dr"), Map.of("title", "Prof", "priority", "low")),
            FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(
            List.of(Map.of("first_name", "Dr"), Map.of("first_name", "Prof", "hs_priority", "low")), result);
    }

    @Test
    void testTwoApplicationFieldsOnOneIntegrationFieldLastDeclaredWinsOnTheWayBack() {
        FieldMappingDescriptor descriptor = new FieldMappingDescriptor(
            "contacts",
            List.of(
                new FieldMappingDescriptor.Mapping("title", "first_name"),
                new FieldMappingDescriptor.Mapping("salutation", "first_name")));

        Object toIntegration = FieldMappingApplier.apply(
            descriptor, Map.of("title", "Dr", "salutation", "Mr"), FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(Map.of("first_name", "Mr"), toIntegration);

        Object toApplication = FieldMappingApplier.apply(
            descriptor, Map.of("first_name", "Dr"), FieldMappingDirection.TO_APPLICATION, false);

        assertEquals(Map.of("title", "Dr", "salutation", "Dr"), toApplication);
    }

    @Test
    void testUnsupportedPayloadIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> FieldMappingApplier.apply(DESCRIPTOR, "not an object", FieldMappingDirection.TO_INTEGRATION, false));
    }

    @Test
    void testDirectionMetadata() {
        assertEquals("mapToIntegration", FieldMappingDirection.TO_INTEGRATION.actionName());
        assertEquals("mapToApplication", FieldMappingDirection.TO_APPLICATION.actionName());

        FieldMappingDescriptor.Mapping mapping = new FieldMappingDescriptor.Mapping("title", "first_name");

        assertEquals("title", FieldMappingDirection.TO_INTEGRATION.sourcePath(mapping));
        assertEquals("first_name", FieldMappingDirection.TO_INTEGRATION.destinationPath(mapping));
        assertEquals("first_name", FieldMappingDirection.TO_APPLICATION.sourcePath(mapping));
        assertEquals("title", FieldMappingDirection.TO_APPLICATION.destinationPath(mapping));
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingApplierTest' > /tmp/fm-task2-applier-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`, `cannot find symbol` for `FieldMappingDescriptor`, `FieldMappingDirection`, `FieldMappingApplier`.

- [ ] **Step 8: Implement descriptor, direction and applier**

Create `mapper/FieldMappingDescriptor.java`:

```java
package com.bytechef.ee.component.fieldmapping.mapper;

import com.bytechef.commons.util.MapUtils;
import java.util.List;
import java.util.Map;

/**
 * The connected user's saved mapping, as {@code FieldMappingField} emits it and the Connect Portal stores it:
 * {@code {objectType, mappings: [{applicationField: {label, value, custom}, integrationField}]}}. Only the two
 * field names matter to the transform; labels and the {@code custom} flag are dropped.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public record FieldMappingDescriptor(String objectType, List<Mapping> mappings) {

    private static final String APPLICATION_FIELD = "applicationField";
    private static final String INTEGRATION_FIELD = "integrationField";
    private static final String MAPPINGS = "mappings";
    private static final String OBJECT_TYPE = "objectType";
    private static final String VALUE = "value";

    public static FieldMappingDescriptor of(Map<String, ?> map) {
        List<Object> rawMappings = MapUtils.getList(map, MAPPINGS, Object.class, List.of());

        if (rawMappings.isEmpty()) {
            throw new IllegalArgumentException("Field mapping has no mappings");
        }

        List<Mapping> mappings = rawMappings.stream()
            .map(FieldMappingDescriptor::toMapping)
            .toList();

        return new FieldMappingDescriptor(MapUtils.getString(map, OBJECT_TYPE), mappings);
    }

    private static Mapping toMapping(Object rawMapping) {
        if (!(rawMapping instanceof Map<?, ?> mappingMap)) {
            throw new IllegalArgumentException("Each field mapping entry must be an object, got: " + rawMapping);
        }

        Map<String, ?> mapping = castMap(mappingMap);

        Map<String, ?> applicationField = MapUtils.getRequiredMap(mapping, APPLICATION_FIELD);

        return new Mapping(
            MapUtils.getRequiredString(applicationField, VALUE), MapUtils.getRequiredString(mapping, INTEGRATION_FIELD));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castMap(Map<?, ?> map) {
        return (Map<String, ?>) map;
    }

    public record Mapping(String applicationField, String integrationField) {
    }
}
```

The overloads used exist on `MapUtils` with exactly these shapes: `getList(Map<K, ?>, K, Class<T>, List<T>)`, `getRequiredMap(Map<K1, ?>, K1): Map<K2, ?>`, `getRequiredString(Map<K, ?>, K)`, `getString(Map<K, ?>, K)`.

Create `mapper/FieldMappingDirection.java`:

```java
package com.bytechef.ee.component.fieldmapping.mapper;

/**
 * Which way a mapping is applied. Named for the <em>output</em>, matching Paragon's {@code mappedIntegrationObject}
 * and {@code mappedApplicationObject}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum FieldMappingDirection {

    TO_INTEGRATION(
        "mapToIntegration", "Map to Integration Object",
        "Renames an application object's keys to the connected user's integration fields, ready to write into " +
            "their integration."),
    TO_APPLICATION(
        "mapToApplication", "Map to Application Object",
        "Renames an integration record's keys to your application's fields, ready to hand back to your " +
            "application.");

    private final String actionName;
    private final String title;
    private final String description;

    FieldMappingDirection(String actionName, String title, String description) {
        this.actionName = actionName;
        this.title = title;
        this.description = description;
    }

    public String actionName() {
        return actionName;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String sourcePath(FieldMappingDescriptor.Mapping mapping) {
        return this == TO_INTEGRATION ? mapping.applicationField() : mapping.integrationField();
    }

    public String destinationPath(FieldMappingDescriptor.Mapping mapping) {
        return this == TO_INTEGRATION ? mapping.integrationField() : mapping.applicationField();
    }
}
```

Create `mapper/FieldMappingApplier.java`:

```java
package com.bytechef.ee.component.fieldmapping.mapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a {@link FieldMappingDescriptor} to a payload. Unmapped source keys are dropped unless
 * {@code includeUnmapped}; a missing source key omits its destination key rather than writing {@code null}; a list
 * maps element-wise; when several mappings share a destination the last declared one wins.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class FieldMappingApplier {

    private FieldMappingApplier() {
    }

    public static Object apply(
        FieldMappingDescriptor descriptor, Object data, FieldMappingDirection direction, boolean includeUnmapped) {

        if (data instanceof Map<?, ?> map) {
            return applyToObject(descriptor, castMap(map), direction, includeUnmapped);
        }

        if (data instanceof List<?> list) {
            return list.stream()
                .map(item -> apply(descriptor, item, direction, includeUnmapped))
                .toList();
        }

        throw new IllegalArgumentException(
            "Field mapping data must be an object or an array of objects, got: " +
                (data == null ? "null" : data.getClass()
                    .getSimpleName()));
    }

    private static Map<String, Object> applyToObject(
        FieldMappingDescriptor descriptor, Map<String, ?> source, FieldMappingDirection direction,
        boolean includeUnmapped) {

        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> consumedTopLevelKeys = new HashSet<>();

        for (FieldMappingDescriptor.Mapping mapping : descriptor.mappings()) {
            String sourcePath = direction.sourcePath(mapping);

            consumedTopLevelKeys.add(sourcePath.split("\\.")[0]);

            if (FieldMappingPaths.containsPath(source, sourcePath)) {
                FieldMappingPaths.setValue(
                    result, direction.destinationPath(mapping), FieldMappingPaths.getValue(source, sourcePath));
            }
        }

        if (includeUnmapped) {
            for (Map.Entry<String, ?> entry : source.entrySet()) {
                if (!consumedTopLevelKeys.contains(entry.getKey())) {
                    result.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castMap(Map<?, ?> map) {
        return (Map<String, ?>) map;
    }
}
```

- [ ] **Step 9: Run the applier test to verify it passes**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingApplierTest' > /tmp/fm-task2-applier-green.log 2>&1; echo "exit=$?"
```
Expected: `exit=0`. If `testPresentNullSourceValueIsCarriedAcross` fails on `Map.of` rejecting nulls, the assertion is written against a `HashMap` source and a `LinkedHashMap` result, so that is not it — check `FieldMappingPaths.containsPath` uses `containsKey`, not a null check.

- [ ] **Step 10: Amend the spec sentence about path reads**

In `docs/superpowers/specs/2026-09-08-field-mapping-workflow-consumption-design.md`, §5 "Transform semantics", replace the sentence beginning "Reads use `MapUtils.containsPath` / `MapUtils.getFromPath`" through "is not available to an action.)" with:

```
The component carries a small split-on-dot path helper (`FieldMappingPaths`) for both sides, mirroring
  `ClusterElementContextImpl`'s nested implementation. (`Context.nested(...)` is declared only on
  `ClusterElementContext`, so it is not available to an action, and `MapUtils`' JsonPath-backed readers
  would add a second path dialect.)
```

- [ ] **Step 11: Format, run the module's full check, commit**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:spotlessApply > /tmp/fm-task2-spotless.log 2>&1; echo "exit=$?"
./gradlew :server:ee:libs:modules:components:field-mapping:check --continue > /tmp/fm-task2-check.log 2>&1; echo "exit=$?"
grep -n "^> Task .* FAILED" /tmp/fm-task2-check.log
```
Expected: both `exit=0`; the grep prints nothing. If Checkstyle/PMD/SpotBugs flag anything, fix it before committing (SpotBugs report is HTML: `server/ee/libs/modules/components/field-mapping/build/reports/spotbugs/main.html`).

```bash
git add settings.gradle.kts server/ee/libs/modules/components/field-mapping docs/superpowers/specs/2026-09-08-field-mapping-workflow-consumption-design.md
git commit -F - <<'MSG'
--- Add the field-mapping component's descriptor and transform

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 3: Descriptor resolver — runtime and editor branches

**Files:**
- Create: `server/ee/libs/modules/components/field-mapping/src/main/java/com/bytechef/ee/component/fieldmapping/resolver/FieldMappingDescriptorResolver.java`
- Test: `server/ee/libs/modules/components/field-mapping/src/test/java/com/bytechef/ee/component/fieldmapping/resolver/FieldMappingDescriptorResolverTest.java`

**Interfaces:**
- Consumes: `FieldMappingDescriptor.of(Map)` (Task 2); `ActionContextAware` (`platform-component-api`) — `getWorkflowId()`, `isEditorEnvironment()`, `getPlatformType()`, `getJobPrincipalId()`, `getEnvironmentId()`; `WorkflowService.getWorkflow(String)`; `WorkflowInput.of(Workflow)` and `WorkflowInput.getObjectName()` / `getName()` / `getLabel()` (`platform-configuration-api`); `WorkflowTestConfigurationService.getWorkflowTestConfigurationInputs(String workflowId, long environmentId): Map<String, ?>`; `IntegrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(long, String): Optional<IntegrationInstanceWorkflow>`; `JsonUtils.readMap(String)` (`commons-util`).
- Produces: `FieldMappingDescriptorResolver(IntegrationInstanceWorkflowService, WorkflowService, WorkflowTestConfigurationService)` with `FieldMappingDescriptor resolve(String objectName, ActionContext context)`.

- [ ] **Step 1: Write the failing resolver test**

Create `src/test/java/com/bytechef/ee/component/fieldmapping/resolver/FieldMappingDescriptorResolverTest.java`:

```java
package com.bytechef.ee.component.fieldmapping.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDescriptor;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceWorkflow;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class FieldMappingDescriptorResolverTest {

    private static final long ENVIRONMENT_ID = 1L;
    private static final long INSTANCE_ID = 42L;
    private static final String WORKFLOW_ID = "wf-1";

    private static final String DEFINITION = """
        {
          "label": "Sync contacts",
          "inputs": [
            {"name": "contactMapping", "label": "Contact Mapping", "type": "field_mapping", "objectName": "Contacts"},
            {"name": "apiKey", "label": "API Key", "type": "string"}
          ],
          "tasks": []
        }
        """;

    private static final Map<String, Object> SAVED_MAPPING = Map.of(
        "objectType", "contacts",
        "mappings", List.of(
            Map.of(
                "applicationField", Map.of("label", "Title", "value", "title", "custom", false),
                "integrationField", "first_name")));

    private static final FieldMappingDescriptor EXPECTED = new FieldMappingDescriptor(
        "contacts", List.of(new FieldMappingDescriptor.Mapping("title", "first_name")));

    private final IntegrationInstanceWorkflowService integrationInstanceWorkflowService =
        mock(IntegrationInstanceWorkflowService.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkflowTestConfigurationService workflowTestConfigurationService =
        mock(WorkflowTestConfigurationService.class);

    private FieldMappingDescriptorResolver resolver;

    @BeforeEach
    void setUp() {
        when(workflowService.getWorkflow(WORKFLOW_ID))
            .thenReturn(new Workflow(WORKFLOW_ID, DEFINITION, Workflow.Format.JSON));

        resolver = new FieldMappingDescriptorResolver(
            integrationInstanceWorkflowService, workflowService, workflowTestConfigurationService);
    }

    @Test
    void testRuntimeResolvesTheConnectedUsersSavedMapping() {
        IntegrationInstanceWorkflow integrationInstanceWorkflow = new IntegrationInstanceWorkflow();

        integrationInstanceWorkflow.setInputs(Map.of("contactMapping", SAVED_MAPPING, "apiKey", "k"));

        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.of(integrationInstanceWorkflow));

        FieldMappingDescriptor descriptor = resolver.resolve("Contacts", runtimeContext(PlatformType.EMBEDDED));

        assertEquals(EXPECTED, descriptor);
    }

    @Test
    void testRuntimeFailsWhenPlatformTypeIsNotEmbedded() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> resolver.resolve("Contacts", runtimeContext(PlatformType.AUTOMATION)));

        assertTrue(exception.getMessage()
            .contains("embedded"), exception.getMessage());
    }

    @Test
    void testRuntimeFailsWhenConnectedUserHasNoRow() {
        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve("Contacts", runtimeContext(PlatformType.EMBEDDED)));

        assertTrue(exception.getMessage()
            .contains("Contact Mapping"), exception.getMessage());
    }

    @Test
    void testRuntimeFailsWhenSavedMappingIsEmpty() {
        IntegrationInstanceWorkflow integrationInstanceWorkflow = new IntegrationInstanceWorkflow();

        integrationInstanceWorkflow.setInputs(
            Map.of("contactMapping", Map.of("objectType", "contacts", "mappings", List.of())));

        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.of(integrationInstanceWorkflow));

        assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve("Contacts", runtimeContext(PlatformType.EMBEDDED)));
    }

    @Test
    void testRuntimeNeverReadsAnotherConnectedUsersMapping() {
        long otherInstanceId = 43L;

        IntegrationInstanceWorkflow integrationInstanceWorkflow = new IntegrationInstanceWorkflow();

        integrationInstanceWorkflow.setInputs(Map.of("contactMapping", SAVED_MAPPING));

        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.of(integrationInstanceWorkflow));
        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(otherInstanceId, WORKFLOW_ID))
            .thenReturn(Optional.empty());

        ActionContextAware otherUsersContext = runtimeContext(PlatformType.EMBEDDED);

        when(otherUsersContext.getJobPrincipalId()).thenReturn(otherInstanceId);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("Contacts", otherUsersContext));
    }

    @Test
    void testUnknownObjectNameListsTheDeclaredOnes() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> resolver.resolve("Deals", runtimeContext(PlatformType.EMBEDDED)));

        assertTrue(exception.getMessage()
            .contains("Deals"), exception.getMessage());
        assertTrue(exception.getMessage()
            .contains("Contacts"), exception.getMessage());
    }

    @Test
    void testMissingWorkflowIdFails() {
        ActionContextAware context = mock(ActionContextAware.class);

        when(context.getWorkflowId()).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> resolver.resolve("Contacts", context));
    }

    @Test
    void testEditorResolvesSampleMappingFromTheInputTestValue() {
        String testValue = """
            {"Contacts": {
               "objectTypes": [{"label": "Contacts", "value": "contacts"}],
               "integrationFields": [{"label": "First Name", "value": "first_name"}],
               "applicationFields": {"fields": [{"label": "Title", "value": "title"}]},
               "sampleMapping": {
                 "objectType": "contacts",
                 "mappings": [
                   {"applicationField": {"label": "Title", "value": "title", "custom": false},
                    "integrationField": "first_name"}
                 ]
               }
            }}
            """;

        when(workflowTestConfigurationService.getWorkflowTestConfigurationInputs(WORKFLOW_ID, ENVIRONMENT_ID))
            .thenReturn(Map.of("contactMapping", testValue));

        FieldMappingDescriptor descriptor = resolver.resolve("Contacts", editorContext());

        assertEquals(EXPECTED, descriptor);
    }

    @Test
    void testEditorAcceptsTheMapObjectFieldsEnvelope() {
        String testValue = """
            {"mapObjectFields": {"Contacts": {
               "sampleMapping": {"objectType": "contacts", "mappings": [
                 {"applicationField": {"value": "title"}, "integrationField": "first_name"}]}
            }}}
            """;

        when(workflowTestConfigurationService.getWorkflowTestConfigurationInputs(WORKFLOW_ID, ENVIRONMENT_ID))
            .thenReturn(Map.of("contactMapping", testValue));

        assertEquals(EXPECTED, resolver.resolve("Contacts", editorContext()));
    }

    @Test
    void testEditorFailsWithGuidanceWhenSampleMappingIsAbsent() {
        when(workflowTestConfigurationService.getWorkflowTestConfigurationInputs(WORKFLOW_ID, ENVIRONMENT_ID))
            .thenReturn(Map.of("contactMapping", "{\"Contacts\": {\"applicationFields\": {\"fields\": []}}}"));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> resolver.resolve("Contacts", editorContext()));

        assertTrue(exception.getMessage()
            .contains("sampleMapping"), exception.getMessage());
    }

    @Test
    void testEditorFailsWithGuidanceWhenThereIsNoTestValue() {
        when(workflowTestConfigurationService.getWorkflowTestConfigurationInputs(WORKFLOW_ID, ENVIRONMENT_ID))
            .thenReturn(Map.of());

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> resolver.resolve("Contacts", editorContext()));

        assertTrue(exception.getMessage()
            .contains("sampleMapping"), exception.getMessage());
    }

    private static ActionContextAware runtimeContext(PlatformType platformType) {
        ActionContextAware context = mock(ActionContextAware.class);

        when(context.getWorkflowId()).thenReturn(WORKFLOW_ID);
        when(context.isEditorEnvironment()).thenReturn(false);
        when(context.getPlatformType()).thenReturn(platformType);
        when(context.getJobPrincipalId()).thenReturn(INSTANCE_ID);
        when(context.getEnvironmentId()).thenReturn(ENVIRONMENT_ID);

        return context;
    }

    private static ActionContextAware editorContext() {
        ActionContextAware context = mock(ActionContextAware.class);

        when(context.getWorkflowId()).thenReturn(WORKFLOW_ID);
        when(context.isEditorEnvironment()).thenReturn(true);
        when(context.getPlatformType()).thenReturn(PlatformType.EMBEDDED);
        when(context.getJobPrincipalId()).thenReturn(null);
        when(context.getEnvironmentId()).thenReturn(ENVIRONMENT_ID);

        return context;
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingDescriptorResolverTest' > /tmp/fm-task3-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`, `cannot find symbol ... FieldMappingDescriptorResolver`.

- [ ] **Step 3: Implement the resolver**

Create `resolver/FieldMappingDescriptorResolver.java`:

```java
package com.bytechef.ee.component.fieldmapping.resolver;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDescriptor;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.configuration.domain.WorkflowInput;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.constant.PlatformType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finds the {@code field_mapping} workflow input declared with a given object name and loads its descriptor. At
 * runtime that is the connected user's saved value on {@code IntegrationInstanceWorkflow.inputs}; under the editor's
 * Test button there is no connected user, so the input's design-time test value must carry a {@code sampleMapping}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class FieldMappingDescriptorResolver {

    private static final int DEVELOPMENT_ENVIRONMENT_ID = 0;
    private static final String MAP_OBJECT_FIELDS = "mapObjectFields";
    private static final String SAMPLE_MAPPING = "sampleMapping";

    private final IntegrationInstanceWorkflowService integrationInstanceWorkflowService;
    private final WorkflowService workflowService;
    private final WorkflowTestConfigurationService workflowTestConfigurationService;

    @SuppressFBWarnings("EI2")
    public FieldMappingDescriptorResolver(
        IntegrationInstanceWorkflowService integrationInstanceWorkflowService, WorkflowService workflowService,
        WorkflowTestConfigurationService workflowTestConfigurationService) {

        this.integrationInstanceWorkflowService = integrationInstanceWorkflowService;
        this.workflowService = workflowService;
        this.workflowTestConfigurationService = workflowTestConfigurationService;
    }

    public FieldMappingDescriptor resolve(String objectName, ActionContext context) {
        ActionContextAware contextAware = (ActionContextAware) context;

        String workflowId = contextAware.getWorkflowId();

        if (workflowId == null) {
            throw new IllegalStateException("Field mapping actions require a workflow execution context");
        }

        Workflow workflow = workflowService.getWorkflow(workflowId);

        WorkflowInput workflowInput = findInput(objectName, WorkflowInput.of(workflow));

        Object rawDescriptor = contextAware.isEditorEnvironment()
            ? readSampleMapping(objectName, workflowInput, workflowId, contextAware)
            : readSavedMapping(workflowInput, workflowId, contextAware);

        return FieldMappingDescriptor.of(toMap(rawDescriptor));
    }

    private static WorkflowInput findInput(String objectName, List<WorkflowInput> workflowInputs) {
        return workflowInputs.stream()
            .filter(workflowInput -> Objects.equals(objectName, workflowInput.getObjectName()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No field mapping input declares object name '%s'; declared object names: %s".formatted(
                    objectName,
                    workflowInputs.stream()
                        .map(WorkflowInput::getObjectName)
                        .filter(Objects::nonNull)
                        .toList())));
    }

    private Object readSavedMapping(WorkflowInput workflowInput, String workflowId, ActionContextAware contextAware) {
        if (contextAware.getPlatformType() != PlatformType.EMBEDDED) {
            throw new IllegalStateException(
                "The field mapping component is available in embedded workflows only");
        }

        Long integrationInstanceId = contextAware.getJobPrincipalId();

        if (integrationInstanceId == null) {
            throw new IllegalStateException("Field mapping actions require an integration instance");
        }

        Object saved = integrationInstanceWorkflowService
            .fetchIntegrationInstanceWorkflow(integrationInstanceId, workflowId)
            .map(integrationInstanceWorkflow -> integrationInstanceWorkflow.getInputs()
                .get(workflowInput.getName()))
            .orElse(null);

        if (saved == null) {
            throw new IllegalArgumentException(
                "The connected user has not completed the '%s' field mapping".formatted(inputLabel(workflowInput)));
        }

        return saved;
    }

    private Object readSampleMapping(
        String objectName, WorkflowInput workflowInput, String workflowId, ActionContextAware contextAware) {

        Long environmentId = contextAware.getEnvironmentId();

        Map<String, ?> testInputs = workflowTestConfigurationService.getWorkflowTestConfigurationInputs(
            workflowId, environmentId == null ? DEVELOPMENT_ENVIRONMENT_ID : environmentId);

        Object testValue = testInputs.get(workflowInput.getName());

        Object sampleMapping = null;

        if (testValue != null) {
            Map<String, ?> root = toMap(testValue);

            if (root.get(MAP_OBJECT_FIELDS) instanceof Map<?, ?> envelope) {
                root = castMap(envelope);
            }

            Object entry = root.get(objectName);

            if (entry == null && !root.isEmpty()) {
                entry = root.values()
                    .iterator()
                    .next();
            }

            if (entry instanceof Map<?, ?> entryMap) {
                sampleMapping = entryMap.get(SAMPLE_MAPPING);
            }
        }

        if (sampleMapping == null) {
            throw new IllegalArgumentException(
                "Add a sampleMapping to the '%s' input's test value to run this action from the editor".formatted(
                    inputLabel(workflowInput)));
        }

        return sampleMapping;
    }

    private static String inputLabel(WorkflowInput workflowInput) {
        return workflowInput.getLabel() == null ? workflowInput.getName() : workflowInput.getLabel();
    }

    private static Map<String, ?> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return castMap(map);
        }

        if (value instanceof String string) {
            return JsonUtils.readMap(string);
        }

        throw new IllegalArgumentException(
            "Expected a field mapping object, got: " + value.getClass()
                .getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castMap(Map<?, ?> map) {
        return (Map<String, ?>) map;
    }
}
```

- [ ] **Step 4: Run the resolver test to verify it passes**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingDescriptorResolverTest' > /tmp/fm-task3-green.log 2>&1; echo "exit=$?"
```
Expected: `exit=0`. If `new Workflow(id, definition, Format.JSON)` complains that `tasks` must be non-empty or a `trigger` is required, add `"triggers": []` or a trivial task `{"name": "noop", "type": "logger/v1/info"}` to `DEFINITION` — only `inputs` is under test.

- [ ] **Step 5: Format, check, commit**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:spotlessApply > /tmp/fm-task3-spotless.log 2>&1; echo "exit=$?"
./gradlew :server:ee:libs:modules:components:field-mapping:check --continue > /tmp/fm-task3-check.log 2>&1; echo "exit=$?"
grep -n "^> Task .* FAILED" /tmp/fm-task3-check.log
git add server/ee/libs/modules/components/field-mapping
git commit -F - <<'MSG'
--- Resolve a field mapping descriptor by object name at runtime and in the editor

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 4: Actions, component handler, icon and definition snapshot

**Files:**
- Create: `.../fieldmapping/action/FieldMappingMapAction.java`
- Create: `.../fieldmapping/FieldMappingComponentHandler.java`
- Create: `server/ee/libs/modules/components/field-mapping/src/main/resources/assets/field-mapping.svg`
- Test: `server/ee/libs/modules/components/field-mapping/src/test/java/com/bytechef/ee/component/fieldmapping/FieldMappingComponentHandlerTest.java`
- Test: `server/ee/libs/modules/components/field-mapping/src/test/java/com/bytechef/ee/component/fieldmapping/action/FieldMappingMapActionTest.java`
- Generated by the test: `server/ee/libs/modules/components/field-mapping/src/test/resources/definition/field-mapping_v1.json`

**Interfaces:**
- Consumes: `FieldMappingDescriptorResolver.resolve(String, ActionContext)` (Task 3), `FieldMappingApplier.apply(...)` and `FieldMappingDirection` (Task 2), `FieldMappingConstants`, `FieldMappingInputType`.
- Produces: `FieldMappingMapAction.of(FieldMappingDirection, FieldMappingDescriptorResolver): ModifiableActionDefinition`; `FieldMappingMapAction.perform(FieldMappingDirection, FieldMappingDescriptorResolver, Parameters, ActionContext): Object`; `FieldMappingComponentHandler(IntegrationInstanceWorkflowService, WorkflowService, WorkflowTestConfigurationService)`, a Spring bean named `fieldMapping_v1_ComponentHandler`.

- [ ] **Step 1: Write the failing action test**

Create `src/test/java/com/bytechef/ee/component/fieldmapping/action/FieldMappingMapActionTest.java`:

```java
package com.bytechef.ee.component.fieldmapping.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDescriptor;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDirection;
import com.bytechef.ee.component.fieldmapping.resolver.FieldMappingDescriptorResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class FieldMappingMapActionTest {

    private static final FieldMappingDescriptor DESCRIPTOR = new FieldMappingDescriptor(
        "contacts", List.of(new FieldMappingDescriptor.Mapping("title", "first_name")));

    private final FieldMappingDescriptorResolver resolver = mock(FieldMappingDescriptorResolver.class);
    private final ActionContext context = mock(ActionContext.class);

    @Test
    void testMapToIntegrationResolvesByObjectNameAndAppliesToAnObject() {
        when(resolver.resolve(eq("Contacts"), eq(context))).thenReturn(DESCRIPTOR);

        Parameters inputParameters = MockParametersFactory.create(
            Map.of("objectName", "Contacts", "inputType", "OBJECT", "data", Map.of("title", "Dr", "x", 1)));

        Object result = FieldMappingMapAction.perform(
            FieldMappingDirection.TO_INTEGRATION, resolver, inputParameters, context);

        assertEquals(Map.of("first_name", "Dr"), result);
    }

    @Test
    void testMapToApplicationAppliesToAnArrayAndHonoursIncludeUnmapped() {
        when(resolver.resolve(eq("Contacts"), eq(context))).thenReturn(DESCRIPTOR);

        Parameters inputParameters = MockParametersFactory.create(
            Map.of(
                "objectName", "Contacts", "inputType", "ARRAY", "includeUnmapped", true,
                "data", List.of(Map.of("first_name", "Dr", "stage", "lead"))));

        Object result = FieldMappingMapAction.perform(
            FieldMappingDirection.TO_APPLICATION, resolver, inputParameters, context);

        assertEquals(List.of(Map.of("title", "Dr", "stage", "lead")), result);
    }

    @Test
    void testDefinitionsCarryTheDirectionsNames() {
        assertEquals(
            "mapToIntegration",
            FieldMappingMapAction.of(FieldMappingDirection.TO_INTEGRATION, resolver)
                .getName());
        assertEquals(
            "mapToApplication",
            FieldMappingMapAction.of(FieldMappingDirection.TO_APPLICATION, resolver)
                .getName());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingMapActionTest' > /tmp/fm-task4-action-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`, `cannot find symbol ... FieldMappingMapAction`.

- [ ] **Step 3: Implement the action factory**

Create `action/FieldMappingMapAction.java`:

```java
package com.bytechef.ee.component.fieldmapping.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.ee.component.fieldmapping.constant.FieldMappingConstants.DATA;
import static com.bytechef.ee.component.fieldmapping.constant.FieldMappingConstants.INCLUDE_UNMAPPED;
import static com.bytechef.ee.component.fieldmapping.constant.FieldMappingConstants.INPUT_TYPE;
import static com.bytechef.ee.component.fieldmapping.constant.FieldMappingConstants.OBJECT_NAME;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.ee.component.fieldmapping.constant.FieldMappingInputType;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingApplier;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDescriptor;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDirection;
import com.bytechef.ee.component.fieldmapping.resolver.FieldMappingDescriptorResolver;

/**
 * One action definition per {@link FieldMappingDirection}. Both take an object name (not the descriptor itself) so
 * the author never needs to know which workflow input holds the connected user's mapping.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class FieldMappingMapAction {

    private FieldMappingMapAction() {
    }

    public static ModifiableActionDefinition of(
        FieldMappingDirection direction, FieldMappingDescriptorResolver resolver) {

        return action(direction.actionName())
            .title(direction.title())
            .description(direction.description())
            .properties(
                string(OBJECT_NAME)
                    .label("Object Name")
                    .description(
                        "The object name declared on the workflow's field mapping input, e.g. \"Contacts\".")
                    .required(true),
                string(INPUT_TYPE)
                    .label("Input Type")
                    .description("Whether Data is a single object or an array of objects.")
                    .options(
                        option("Object", FieldMappingInputType.OBJECT.name()),
                        option("Array", FieldMappingInputType.ARRAY.name()))
                    .required(true),
                object(DATA)
                    .label("Data")
                    .description("The object whose keys are renamed through the mapping.")
                    .displayCondition("%s == '%s'".formatted(INPUT_TYPE, FieldMappingInputType.OBJECT.name()))
                    .required(true),
                array(DATA)
                    .label("Data")
                    .description("The objects whose keys are renamed through the mapping, one by one.")
                    .displayCondition("%s == '%s'".formatted(INPUT_TYPE, FieldMappingInputType.ARRAY.name()))
                    .items(object())
                    .required(true),
                bool(INCLUDE_UNMAPPED)
                    .label("Include Unmapped")
                    .description("Copy source keys that no mapping covers through unchanged. Off by default.")
                    .defaultValue(false))
            .output()
            .perform(
                (inputParameters, connectionParameters, context) -> perform(
                    direction, resolver, inputParameters, context));
    }

    public static Object perform(
        FieldMappingDirection direction, FieldMappingDescriptorResolver resolver, Parameters inputParameters,
        ActionContext context) {

        FieldMappingDescriptor descriptor = resolver.resolve(inputParameters.getRequiredString(OBJECT_NAME), context);

        return FieldMappingApplier.apply(
            descriptor, inputParameters.getRequired(DATA), direction,
            inputParameters.getBoolean(INCLUDE_UNMAPPED, false));
    }
}
```

- [ ] **Step 4: Run the action test to verify it passes**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingMapActionTest' > /tmp/fm-task4-action-green.log 2>&1; echo "exit=$?"
```
Expected: `exit=0`. If `MockParametersFactory.create` hands `data` back as something other than a `Map`/`List` (it converts through Jackson, so nested `Map.of` survives as `LinkedHashMap`), `assertEquals` on map contents still holds.

- [ ] **Step 5: Write the failing snapshot test and add the handler + icon**

Create `src/test/java/com/bytechef/ee/component/fieldmapping/FieldMappingComponentHandlerTest.java`:

```java
package com.bytechef.ee.component.fieldmapping;

import com.bytechef.test.jsonasssert.JsonFileAssert;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class FieldMappingComponentHandlerTest {

    @Test
    void testGetComponentDefinition() {
        JsonFileAssert.assertEquals(
            "definition/field-mapping_v1.json", new FieldMappingComponentHandler(null, null, null).getDefinition());
    }
}
```

Create `src/main/resources/assets/field-mapping.svg`:

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <rect x="3" y="4" width="6" height="4" rx="1"/>
  <rect x="3" y="10" width="6" height="4" rx="1"/>
  <rect x="3" y="16" width="6" height="4" rx="1"/>
  <rect x="15" y="4" width="6" height="4" rx="1"/>
  <rect x="15" y="10" width="6" height="4" rx="1"/>
  <rect x="15" y="16" width="6" height="4" rx="1"/>
  <path d="M9 6h6"/>
  <path d="M9 12l6 6"/>
  <path d="M9 18l6-6"/>
</svg>
```

Create `FieldMappingComponentHandler.java`:

```java
package com.bytechef.ee.component.fieldmapping;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.ee.component.fieldmapping.constant.FieldMappingConstants.FIELD_MAPPING;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.ee.component.fieldmapping.action.FieldMappingMapAction;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDirection;
import com.bytechef.ee.component.fieldmapping.resolver.FieldMappingDescriptorResolver;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import org.springframework.stereotype.Component;

/**
 * Spring-registered rather than {@code @AutoService}, so the actions can reach the embedded configuration services.
 * Spring-registered handlers are resolved by {@code ComponentDefinitionRegistry} ahead of the build-time component
 * index, so the component is visible by name — including as {@code context.component.fieldMapping} from a Script
 * step — whether or not an index is present.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component(FIELD_MAPPING + "_v1_ComponentHandler")
@ConditionalOnEEVersion
public class FieldMappingComponentHandler implements ComponentHandler {

    private final ComponentDefinition componentDefinition;

    public FieldMappingComponentHandler(
        IntegrationInstanceWorkflowService integrationInstanceWorkflowService, WorkflowService workflowService,
        WorkflowTestConfigurationService workflowTestConfigurationService) {

        FieldMappingDescriptorResolver resolver = new FieldMappingDescriptorResolver(
            integrationInstanceWorkflowService, workflowService, workflowTestConfigurationService);

        this.componentDefinition = component(FIELD_MAPPING)
            .title("Field Mapping")
            .description(
                "Applies the connected user's field mapping to a payload, renaming keys between your application's " +
                    "fields and the integration's fields in either direction.")
            .icon("path:assets/field-mapping.svg")
            .categories(ComponentCategory.HELPERS)
            .actions(
                FieldMappingMapAction.of(FieldMappingDirection.TO_INTEGRATION, resolver),
                FieldMappingMapAction.of(FieldMappingDirection.TO_APPLICATION, resolver));
    }

    @Override
    public ComponentDefinition getDefinition() {
        return componentDefinition;
    }
}
```

- [ ] **Step 6: Run the snapshot test twice**

`JsonFileAssert` writes the snapshot to `src/test/resources/definition/` on a miss but reads it back from the classpath, so the first run writes the file and fails with `NullPointerException: url`; the second run passes. That NPE is the expected midpoint.

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingComponentHandlerTest' > /tmp/fm-task4-snap-1.log 2>&1; echo "exit=$?"
ls server/ee/libs/modules/components/field-mapping/src/test/resources/definition/
./gradlew :server:ee:libs:modules:components:field-mapping:test --tests '*FieldMappingComponentHandlerTest' > /tmp/fm-task4-snap-2.log 2>&1; echo "exit=$?"
```
Expected: first `exit=1` and the `ls` shows `field-mapping_v1.json`; second `exit=0`.

Open the generated JSON and confirm it lists two actions, `mapToIntegration` and `mapToApplication`, each with `objectName`, `inputType`, two `data` properties and `includeUnmapped`.

- [ ] **Step 7: Confirm the monolith compiles with the new module and the bean wires**

```bash
./gradlew :server:apps:server-app:compileJava > /tmp/fm-task4-app-compile.log 2>&1; echo "exit=$?"
grep -n "^> Task .* FAILED" /tmp/fm-task4-app-compile.log
```
Expected: `exit=0`, empty grep. (`server-app` includes every `:server:ee:libs:modules:components:*` project by path prefix, so no wiring is added by hand.)

- [ ] **Step 8: Format, check, commit**

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:spotlessApply > /tmp/fm-task4-spotless.log 2>&1; echo "exit=$?"
./gradlew :server:ee:libs:modules:components:field-mapping:check --continue > /tmp/fm-task4-check.log 2>&1; echo "exit=$?"
grep -n "^> Task .* FAILED" /tmp/fm-task4-check.log
git add server/ee/libs/modules/components/field-mapping
git commit -F - <<'MSG'
--- Add the field-mapping component with mapToIntegration and mapToApplication actions

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 5: Client — one pill per field-mapping input, and the `sampleMapping` hint

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/components/datapills/DataPillPanelBodyInputsItem.tsx`
- Modify: `client/src/pages/platform/workflow-editor/components/datapills/DataPillPanelBodyInputsItem.test.tsx`
- Delete: `client/src/pages/platform/workflow-editor/utils/getFieldMappingPillProperties.ts`
- Delete: `client/src/pages/platform/workflow-editor/utils/getFieldMappingPillProperties.test.ts`
- Modify: `client/src/pages/platform/workflow-editor/components/workflow-inputs/WorkflowInputsEditDialog.tsx` (the field-mapping `Test Value` block, around lines 420–437)

**Interfaces:**
- Consumes: nothing new; `DataPill` props unchanged.
- Produces: `DataPillPanelBodyInputsItem` renders every input, including `field_mapping`, as a single root pill.

- [ ] **Step 1: Rewrite the first test case to assert a single pill**

In `DataPillPanelBodyInputsItem.test.tsx`, replace the leading comment block above `vi.mock('./DataPill', ...)` and the first `it(...)` with:

```tsx
// Minimal DataPill mock: renders ONLY property.name, so a pill appears here only if the component itself renders it.
vi.mock('./DataPill', () => ({
    default: ({property}: {property?: {name?: string}}) => <div data-testid="data-pill">{property?.name}</div>,
}));
```

and

```tsx
    it('renders a single root pill for a field_mapping input and no synthetic child pills', () => {
        render(
            <Accordion collapsible defaultValue="inputs" type="single">
                <AccordionItem value="inputs">
                    <DataPillPanelBodyInputsItem dataPillFilterQuery="" />
                </AccordionItem>
            </Accordion>
        );

        expect(screen.getByText('contactMapping')).toBeInTheDocument();

        // The runtime value of a field_mapping input is the mapping descriptor, not a mapped object, so the
        // per-application-field pills the panel used to synthesize from the test value would advertise paths that
        // never exist. Mapped-object pills now come from the field-mapping action node's test-run output instead.
        expect(screen.queryByText('title')).not.toBeInTheDocument();

        // Two pills total: contactMapping root + apiKey root.
        expect(screen.getAllByTestId('data-pill')).toHaveLength(2);
    });
```

Leave the `useGetWorkflowTestConfigurationQuery` mock as it is — it proves the test value is present and still ignored for child pills.

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/components/datapills/DataPillPanelBodyInputsItem.test.tsx 2>&1 | tail -20
```
Expected: the new case fails — `title` is found and there are 3 pills.

- [ ] **Step 3: Remove the synthetic-children branch and the utility**

In `DataPillPanelBodyInputsItem.tsx`, delete the import line `import getFieldMappingPillProperties from '../../utils/getFieldMappingPillProperties';` and delete the entire `if (input.type === 'field_mapping') { ... }` block inside `filteredInputs.map`, so every input falls through to the existing `<li className="flex w-full items-center space-x-3" ...>` return.

Delete the two files:

```bash
git rm client/src/pages/platform/workflow-editor/utils/getFieldMappingPillProperties.ts client/src/pages/platform/workflow-editor/utils/getFieldMappingPillProperties.test.ts
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/components/datapills/DataPillPanelBodyInputsItem.test.tsx 2>&1 | tail -20
```
Expected: 2 passed.

- [ ] **Step 5: Mention `sampleMapping` in the input editor**

In `WorkflowInputsEditDialog.tsx`, in the field-mapping `Test Value` block, change the `<textarea>` `placeholder` to:

```tsx
placeholder='{"Contacts": {"applicationFields": {"fields": []}, "integrationFields": [], "objectTypes": [], "sampleMapping": {"objectType": "", "mappings": []}}}'
```

and the help paragraph to:

```tsx
<p className="text-sm text-content-neutral-secondary">
    Static mapObjectFields-shaped sample; the top-level key is the object name. Add a sampleMapping
    (a saved-mapping value) so Field Mapping actions can run from the Test button.
</p>
```

Confirm `WorkflowInputsEditDialog.test.tsx` still passes — it asserts the `field-mapping-json-editor` test id, not the copy:

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/components/workflow-inputs/WorkflowInputsEditDialog.test.tsx 2>&1 | tail -8
```

- [ ] **Step 6: Lint, typecheck, format, commit**

```bash
cd client && npm run format && npx eslint src/pages/platform/workflow-editor/components/datapills/DataPillPanelBodyInputsItem.tsx src/pages/platform/workflow-editor/components/datapills/DataPillPanelBodyInputsItem.test.tsx src/pages/platform/workflow-editor/components/workflow-inputs/WorkflowInputsEditDialog.tsx && npm run typecheck
```
Expected: no lint errors, typecheck clean (a stale reference to the deleted utility would surface here).

```bash
cd /Volumes/Data/bytechef/bytechef/.claude/worktrees/field-mapping-workflow-consumption-93e978
git add client/src/pages/platform/workflow-editor/components/datapills/DataPillPanelBodyInputsItem.tsx client/src/pages/platform/workflow-editor/components/datapills/DataPillPanelBodyInputsItem.test.tsx client/src/pages/platform/workflow-editor/components/workflow-inputs/WorkflowInputsEditDialog.tsx
git commit -F - <<'MSG'
- client - Render a field mapping input as one pill and hint at sampleMapping in the input editor

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```
(The two `git rm`s from Step 3 are already staged and land in this commit.)

---

### Task 6: Documentation — reference page, embedded guide, deep-dive

**Files:**
- Create: `server/ee/libs/modules/components/field-mapping/src/main/resources/README.mdx`
- Generated: `docs/content/docs/reference/components/field-mapping_v1.mdx` (by `./gradlew generateDocumentation`)
- Modify: `docs/content/docs/platform/embedded/build/workflows/field-mapping.mdx`
- Create: `.agents/field-mapping.md`
- Modify: `CLAUDE.md` (feature deep-dive table)

**Interfaces:** none — prose only.

- [ ] **Step 1: Write the component README**

Create `server/ee/libs/modules/components/field-mapping/src/main/resources/README.mdx` (four-backtick outer fence; it contains a ```js block):

````mdx
# Overview

Field Mapping applies a connected user's saved field mapping — the one they built in the embedded Connect dialog — to a payload, in either direction. It is available in embedded workflows only, because the mapping is supplied by the end user at connect time, not by the workflow author.

Both actions take an **Object Name** rather than the mapping itself: the component finds the workflow's `field_mapping` input declared with that object name and loads the connected user's saved value for it. The author never needs to know which input holds the mapping.
<hr/>

## Map to Integration Object

### Overview

Renames an application object's keys to the integration fields the user chose, ready to write into their integration. Reads the mapping's application-field values and writes its integration-field values — Paragon's `mappedIntegrationObject`.

### Property Description

1. **Object Name**: The object name declared on the workflow's field mapping input, e.g. `Contacts`.
2. **Input Type**: `Object` for one object, `Array` for a list of objects mapped one by one.
3. **Data**: The object or array whose keys are renamed.
4. **Include Unmapped**: Off by default, so keys no mapping covers are dropped. On, they are copied through unchanged.

Missing source keys are omitted from the output rather than written as `null`, so an absent field never blanks a field in the target system. Dotted paths (`properties.firstname`) resolve nested structures on both sides.

## Map to Application Object

### Overview

The reverse: renames an integration record's keys to your application's fields, ready to hand back to your application — Paragon's `mappedApplicationObject`. Same properties as Map to Integration Object.

If the user mapped two application fields to one integration field, mapping back gives both application fields that value.

## From a Script step

Both actions are callable from custom code through the component proxy, the same way `context.component.httpClient` is:

```js
function perform(input, context) {
    return context.component.fieldMapping.mapToApplication({
        objectName: "Contacts",
        inputType: "ARRAY",
        data: input.records
    });
}
```

## Testing in the editor

The editor's Test button runs without a connected user. Add a `sampleMapping` — a saved-mapping value — to the field mapping input's test value so the actions can run and produce sample output for data pills.
````

- [ ] **Step 2: Generate the reference page**

```bash
./gradlew generateDocumentation > /tmp/fm-task6-docs.log 2>&1; echo "exit=$?"
grep -n "^> Task .* FAILED" /tmp/fm-task6-docs.log
ls docs/content/docs/reference/components/field-mapping_v1.mdx
git status --short docs/content/docs/reference/components/ | head
```
Expected: `exit=0`, the page exists. If the generation run rewrote *other* component pages (a snapshot drift unrelated to this work), stage only `field-mapping_v1.mdx` and leave the rest untouched.

- [ ] **Step 3: Update the embedded guide**

In `docs/content/docs/platform/embedded/build/workflows/field-mapping.mdx`:

(a) Replace the `<Callout title="Scope">` block with:

```mdx
<Callout title="Scope">
    This is a deliberately reduced subset of full field mapping. **Included:** dynamic application fields, the object-type selector, flat (non-paginated) option lists, configurable/creatable mappings, and a built-in transform — the **Field Mapping** component's `Map to Integration Object` / `Map to Application Object` actions. **Not included:** paginated / search-as-you-type dropdowns, and an automatic "apply field mapping" toggle on triggers.
</Callout>
```

(b) Replace the `<Callout type="warn" title="Declared in the definition, not in the input dialog">` block with:

```mdx
<Callout title="Declare it in the editor or in the definition">
    The workflow editor's **Edit Input** dialog offers a **Field Mapping** type whose test value is the static `mapObjectFields`-shaped sample shown below; the object name is derived from the sample's top-level key. You can equally author the input in a `.json`/`.yaml` definition and bring it in with **Import Workflow**.
</Callout>
```

(c) In "Design time - declare a field-mapping input", after the JSON example and its explanatory paragraph, add (the outer fence below is four backticks only so the inner ```json block survives in this plan; paste the content, not the outer fence):

````mdx
### The test value and `sampleMapping`

The input's **test value** is a static sample of what the SDK will fetch live: the object types, integration fields and application fields, keyed by the object name. Add a `sampleMapping` — a value of the same shape the Connect dialog saves — so the Field Mapping actions can run from the editor's **Test** button, which has no connected user to read a real mapping from:

```json
{
  "Contacts": {
    "objectTypes": [{"label": "Contacts", "value": "contacts"}],
    "integrationFields": [{"label": "First Name", "value": "first_name"}],
    "applicationFields": {"fields": [{"label": "Title", "value": "title"}]},
    "sampleMapping": {
      "objectType": "contacts",
      "mappings": [
        {"applicationField": {"label": "Title", "value": "title", "custom": false}, "integrationField": "first_name"}
      ]
    }
  }
}
```
````

(d) Replace the `<Callout title="You apply the mapping yourself">` block with a new section (again a four-backtick outer fence, because the section contains a ```js block):

````mdx
## Applying the mapping in a workflow

Add the **Field Mapping** component to the workflow. Its two actions take the input's **object name** and a payload, and resolve the connected user's saved mapping themselves — you never wire the mapping value into the action:

| Action | Turns | Into | Use it when |
|---|---|---|---|
| **Map to Integration Object** | your application's object (`{title, email}`) | the integration's fields (`{first_name, hs_email}`) | writing your data into the user's CRM |
| **Map to Application Object** | an integration record (`{first_name, hs_email}`) | your application's fields (`{title, email}`) | handing a CRM record back to your app |

Both accept a single object or an array (set **Input Type**). Keys no mapping covers are dropped unless **Include Unmapped** is on; a missing source key is omitted rather than written as `null`.

From a **Script** step, call the same actions through the component proxy — this is ByteChef's equivalent of Paragon's `paragonUtils.mapIntegrationObjects()`, and it works in JavaScript, Python, Ruby and Java:

```js
function perform(input, context) {
    const mapped = context.component.fieldMapping.mapToApplication({
        objectName: "Contacts",
        inputType: "ARRAY",
        data: input.records
    });

    return mapped.filter((record) => record.email);
}
```

The saved mapping is also readable as a plain data pill — `${input.contactMapping}` — for a step that wants to inspect it rather than apply it.
````

(e) In "Example use case", change the last clause "and your sync workflow reads the stored mapping to build each upsert." to "and your sync workflow's **Map to Integration Object** step turns each of your contacts into the CRM's shape before the upsert."

- [ ] **Step 4: Write the deep-dive and index it**

Create `.agents/field-mapping.md`:

```markdown
# Field mapping (embedded)

Two halves joined by an **object name**. Authoring (spec `docs/superpowers/specs/2026-06-04-embedded-field-mapping-design.md`): a `field_mapping` workflow input whose `objectName` extension is derived from its test value's top-level key; the embedded SDK's `ConnectDialog` renders `FieldMappingField` and saves the descriptor `{objectType, mappings: [{applicationField: {label, value, custom}, integrationField}]}` onto `IntegrationInstanceWorkflow.inputs[<name>]` — per connected user. Consumption (spec `2026-09-08-field-mapping-workflow-consumption-design.md`): the EE `field-mapping` component.

## Invariants

- **Per-user inputs reach the job through `IntegrationJobPrincipalAccessor.getInputMap`**, which overlays `IntegrationInstanceWorkflow.inputs` on `IntegrationInstanceConfigurationWorkflow.inputs`, per-user winning — the same precedence `IntegrationInstanceFacadeImpl` uses to enable/disable triggers. Before this merge no Connect-Portal-collected input ever reached a running job. A user who never opened the portal has no row and gets configuration inputs alone (`fetchIntegrationInstanceWorkflow`, not the throwing getter).
- **The component is Spring-registered** (`fieldMapping_v1_ComponentHandler`, `@ConditionalOnEEVersion`) so it can inject `IntegrationInstanceWorkflowService`, `WorkflowService`, `WorkflowTestConfigurationService`. Spring handlers are resolved by `ComponentDefinitionRegistry` *before* the build-time component index is consulted, so the index cannot hide it and `context.component.fieldMapping` resolves from scripts.
- **Actions take an object name, not the descriptor.** `FieldMappingDescriptorResolver` finds the input whose `WorkflowInput.getObjectName()` matches, then branches on `ActionContextAware.isEditorEnvironment()`: runtime reads the connected user's row via `getJobPrincipalId()` (the integration instance id under EMBEDDED); the editor Test button has no principal and reads `sampleMapping` from the input's test value via `WorkflowTestConfigurationService`. Both branches fail loudly — never pass a payload through unmapped, that writes raw keys into a third-party system.
- **Transform semantics** (`FieldMappingApplier`): unmapped keys dropped unless `includeUnmapped`; a missing source key omits the destination key (never `null`); dotted paths on both sides through `FieldMappingPaths` (split-on-dot, like `ClusterElementContextImpl`'s nested impl — `Context.nested` is cluster-element-only); lists map element-wise; last declared mapping wins on a shared destination.
- **Data pills:** a `field_mapping` input is one opaque pill. The per-application-field pills that used to be synthesized from the test value were removed because the runtime value is the descriptor, not a mapped object. Mapped-object pills come from the action node's test-run output, which is why `sampleMapping` exists.
- **No dynamic output function**: `ActionDefinitionServiceImpl.executeOutput` builds its context with every identity field null, so an output function cannot find the workflow input.

## Deliberately not built

CE evaluator functions (CE cannot produce a descriptor; EE-only would need `EvaluatorConfiguration` turned into a contributor-collecting bean, and `runtime-job-app`/`WorkflowUtils` build evaluators that skip contributed functions anyway); trigger-level auto-apply (own spec); anything for automation (the author is the configurer there — `data-mapper` already renames keys).
```

In `CLAUDE.md`, add a row to the "Feature deep-dives" table after the `.agents/embedded-bridge.md` row:

```markdown
| `.agents/field-mapping.md` | Embedded field mapping: per-user input merge, the `field-mapping` component, `sampleMapping`, what was deliberately not built |
```

- [ ] **Step 5: Commit the docs**

```bash
git add server/ee/libs/modules/components/field-mapping/src/main/resources/README.mdx docs/content/docs/reference/components/field-mapping_v1.mdx docs/content/docs/platform/embedded/build/workflows/field-mapping.mdx .agents/field-mapping.md CLAUDE.md
git commit -F - <<'MSG'
--- docs - Document consuming a field mapping through the field-mapping component

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 7: Whole-branch verification

**Files:** none new.

- [ ] **Step 1: Server-side checks on every touched module, plus the monolith compile**

```bash
./gradlew spotlessApply > /tmp/fm-task7-spotless.log 2>&1; echo "exit=$?"
git status --short
```
Expected: `exit=0`, and `git status` shows nothing — formatting was already applied per task. If it shows changes, commit them as `--- Apply spotless` before continuing.

```bash
./gradlew :server:ee:libs:modules:components:field-mapping:check :server:ee:libs:embedded:embedded-configuration:embedded-configuration-instance-impl:check :server:apps:server-app:compileJava :server:apps:server-app:compileTestJava --continue > /tmp/fm-task7-check.log 2>&1; echo "exit=$?"
grep -n "^> Task .* FAILED" /tmp/fm-task7-check.log
```
Expected: `exit=0`, empty grep.

- [ ] **Step 2: Client checks**

```bash
cd client && npm run check 2>&1 | tail -30
```
Expected: lint, typecheck and the full Vitest suite pass. Use Node 22 or 24 (`node --version`); Node 26 fake-fails on jsdom.

- [ ] **Step 3: Boot the monolith and confirm the bean wires**

The three injected services all exist in `server-app`, but a mis-wired constructor only shows at boot.

```bash
cd server && docker compose -f docker-compose.dev.infra.yml up -d && cd ..
./gradlew -p server/apps/server-app bootRun > /tmp/fm-task7-boot.log 2>&1 &
```
Then poll until it starts (up to a few minutes) and check:

```bash
grep -n "Started ServerApplication\|BeanCreationException\|UnsatisfiedDependencyException\|fieldMapping_v1_ComponentHandler" /tmp/fm-task7-boot.log | head
```
Expected: a `Started ServerApplication` line and no `BeanCreationException` / `UnsatisfiedDependencyException`. Stop the server afterwards (`kill %1` or `pkill -f 'server-app.*bootRun'`).

If the boot log shows `bytechef.edition` is not `ee` in the dev profile, `@ConditionalOnEEVersion` will keep the bean out — that is expected in a CE-configured dev run, not a wiring error; confirm the edition setting before reading a missing bean as a failure.

- [ ] **Step 4: Spec §10 pre-landing checks**

*Distributed EE.* Confirm which apps carry the module the merge lives in, so the change is known to reach every deployment shape that creates embedded jobs:

```bash
grep -rn "embedded-configuration-instance-impl" server/apps/*/build.gradle.kts server/ee/apps/*/build.gradle.kts
```
Expected: `server-app` at least. Record the list in the final report; if an EE app that runs `TriggerCompletionHandler` or the webhook executors for embedded lacks it, say so — that app keeps today's configuration-only behavior.

*Name collisions.* The merge makes a per-user value win over a configuration value of the same input name. Against the local dev database (started in Step 3), list any workflow where both tiers carry the same key:

```bash
docker compose -f server/docker-compose.dev.infra.yml exec -T postgres psql -U postgres -d bytechef -c "
SELECT iiw.id AS instance_workflow_id, k.key
FROM integration_instance_workflow iiw
JOIN integration_instance_configuration_workflow iicw ON iicw.id = iiw.integration_instance_configuration_workflow_id
JOIN LATERAL jsonb_object_keys(iiw.inputs::jsonb) AS k(key) ON TRUE
WHERE iicw.inputs::jsonb ? k.key;"
```
Expected on a dev database: zero rows. If the column names differ, check `\d integration_instance_workflow` first; the point is the overlap query, not its exact spelling. Report the row count; a non-empty result on a real tenant's database is the signal to review those workflows before deploying.

- [ ] **Step 5: Report**

State plainly which of Steps 1–4 ran and their outcomes, with the log paths. If a step was skipped (e.g. no Docker for Step 3), say so explicitly rather than reporting the branch verified.
