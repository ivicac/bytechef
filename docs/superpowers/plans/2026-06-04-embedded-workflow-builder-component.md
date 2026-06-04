# Embedded Workflow Builder Component Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the CE `ConnectedUserProjectWorkflowTools` Spring-AI `@Tool` class with a new EE ByteChef component `embedded-workflow-builder` whose actions expose the same connected-user workflow operations as tools, with `externalUserId`/`environment` injected from the request context (not LLM inputs).

**Architecture:** A connection-less Spring-DI component (`@Component("embeddedWorkflowBuilder_v1_ComponentHandler")`) injects `ConnectedUserProjectFacade`; its four actions read `externalUserId`/`environment` from reserved parameter keys. Both embedded tool entry points — `ToolFacadeImpl` (REST `/tools`) and `EmbeddedMcpToolFacade` (MCP) — inject those reserved keys via a shared helper before calling the shared `clusterElementDefinitionFacade.executeTool(...)`. The MCP facade is also taught to tolerate connection-less components. The old CE module is removed.

**Tech Stack:** Java 25 / Spring Boot (EE), ByteChef component DSL (`component-api`), JUnit 5 + Mockito, `JsonFileAssert` component-definition snapshot tests.

**Spec:** `docs/superpowers/specs/2026-06-04-embedded-workflow-builder-component-design.md`

> **Conventions:** Every new file under `server/ee/**` uses the ByteChef **Enterprise** license header and a `@version ee` Javadoc tag (Spotless selects the header by `@version ee` content). Run `./gradlew spotlessApply` before each commit. Java: one blank line before control statements and after variable modification; no trailing blank line before a class close; test classes end in `Test`, methods camelCase without underscores. Commit prefix: `732 <description>`.

**EE license header (use verbatim at the top of every new EE file):**
```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */
```

---

## Task 1: Reserved-key constants + shared injection helper

**Files:**
- Create: `server/ee/libs/embedded/embedded-execution/embedded-execution-api/src/main/java/com/bytechef/ee/embedded/execution/constant/EmbeddedToolConstants.java`
- Test: `server/ee/libs/embedded/embedded-execution/embedded-execution-api/src/test/java/com/bytechef/ee/embedded/execution/constant/EmbeddedToolConstantsTest.java`

`embedded-execution-api` already depends on `platform-configuration-api` (for `Environment`) and is already a dependency of both `embedded-execution-service` (`ToolFacadeImpl`) and `embedded-ai-mcp-server` (`EmbeddedMcpToolFacade`). The new component will depend on it too.

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.execution.constant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bytechef.platform.configuration.domain.Environment;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class EmbeddedToolConstantsTest {

    @Test
    void testWithConnectedUserContextAddsReservedKeysAndPreservesInputs() {
        Map<String, Object> result = EmbeddedToolConstants.withConnectedUserContext(
            Map.of("prompt", "build a thing"), "user-1", Environment.PRODUCTION);

        assertEquals("user-1", result.get(EmbeddedToolConstants.EXTERNAL_USER_ID));
        assertEquals("PRODUCTION", result.get(EmbeddedToolConstants.ENVIRONMENT));
        assertEquals("build a thing", result.get("prompt"));
    }

    @Test
    void testWithConnectedUserContextDoesNotMutateInput() {
        Map<String, Object> input = Map.of("prompt", "x");

        EmbeddedToolConstants.withConnectedUserContext(input, "user-1", Environment.STAGING);

        assertEquals(1, input.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:embedded:embedded-execution:embedded-execution-api:test --tests "*EmbeddedToolConstantsTest"`
Expected: FAIL — class does not exist / compile error.

- [ ] **Step 3: Implement**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.execution.constant;

import com.bytechef.platform.configuration.domain.Environment;
import java.util.HashMap;
import java.util.Map;

/**
 * Reserved tool-execution parameter keys that the embedded tool facades inject from the connected-user request context
 * so component actions can read the connected user's identity without it being an LLM-supplied input. The {@code __}
 * prefix marks these as server-injected and prevents collision with any declared tool property.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class EmbeddedToolConstants {

    public static final String ENVIRONMENT = "__environment";
    public static final String EXTERNAL_USER_ID = "__externalUserId";

    private EmbeddedToolConstants() {
    }

    public static Map<String, Object> withConnectedUserContext(
        Map<String, ?> inputParameters, String externalUserId, Environment environment) {

        Map<String, Object> parameters = new HashMap<>(inputParameters);

        parameters.put(EXTERNAL_USER_ID, externalUserId);
        parameters.put(ENVIRONMENT, environment.name());

        return parameters;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:ee:libs:embedded:embedded-execution:embedded-execution-api:test --tests "*EmbeddedToolConstantsTest"`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/embedded/embedded-execution/embedded-execution-api
git commit -m "732 Add embedded reserved tool-context parameter keys and injection helper"
```

---

## Task 2: New component module scaffold + environment util

**Files:**
- Create: `server/ee/libs/modules/components/embedded-workflow-builder/build.gradle.kts`
- Modify: `settings.gradle.kts` (add the `include(...)`)
- Create: `server/ee/libs/modules/components/embedded-workflow-builder/src/main/resources/assets/embedded-workflow-builder.svg`
- Create: `server/ee/libs/modules/components/embedded-workflow-builder/src/main/java/com/bytechef/ee/component/embeddedworkflowbuilder/util/EmbeddedWorkflowBuilderUtils.java`
- Test: `server/ee/libs/modules/components/embedded-workflow-builder/src/test/java/com/bytechef/ee/component/embeddedworkflowbuilder/util/EmbeddedWorkflowBuilderUtilsTest.java`

- [ ] **Step 1: Create the module `build.gradle.kts`**

`server/ee/libs/modules/components/embedded-workflow-builder/build.gradle.kts`:
```gradle
version="1.0"

dependencies {
    implementation("org.springframework:spring-context")
    implementation(project(":sdks:backend:java:component-api"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:platform:platform-annotation"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))
    implementation(project(":server:ee:libs:embedded:embedded-configuration:embedded-configuration-api"))
    implementation(project(":server:ee:libs:embedded:embedded-execution:embedded-execution-api"))
}
```
(`component-api`, `commons-lang3`, `auto-service`, and `test-support` are inherited from `server/ee/libs/modules/components/build.gradle.kts`'s `subprojects` block.)

- [ ] **Step 2: Register the module in `settings.gradle.kts`**

In the EE-components include block (after `include("server:ee:libs:modules:components:context-store")`), add:
```gradle
include("server:ee:libs:modules:components:embedded-workflow-builder")
```

- [ ] **Step 3: Add the icon asset**

`server/ee/libs/modules/components/embedded-workflow-builder/src/main/resources/assets/embedded-workflow-builder.svg`:
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/><path d="M6.5 10v4a1 1 0 0 0 1 1h6"/><path d="M17.5 10v4"/></svg>
```

- [ ] **Step 4: Write the failing util test**

`EmbeddedWorkflowBuilderUtilsTest.java`:
```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bytechef.platform.configuration.domain.Environment;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class EmbeddedWorkflowBuilderUtilsTest {

    @Test
    void testResolveEnvironmentParsesName() {
        assertEquals(Environment.STAGING, EmbeddedWorkflowBuilderUtils.resolveEnvironment("STAGING"));
    }

    @Test
    void testResolveEnvironmentDefaultsToProductionWhenBlank() {
        assertEquals(Environment.PRODUCTION, EmbeddedWorkflowBuilderUtils.resolveEnvironment(null));
        assertEquals(Environment.PRODUCTION, EmbeddedWorkflowBuilderUtils.resolveEnvironment(" "));
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*EmbeddedWorkflowBuilderUtilsTest"`
Expected: FAIL — module/class missing. (If Gradle does not recognize the project, ensure Step 2's `include` was added.)

- [ ] **Step 6: Implement the util**

`EmbeddedWorkflowBuilderUtils.java`:
```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.util;

import com.bytechef.platform.configuration.domain.Environment;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class EmbeddedWorkflowBuilderUtils {

    private EmbeddedWorkflowBuilderUtils() {
    }

    public static Environment resolveEnvironment(@Nullable String environment) {
        if (StringUtils.isBlank(environment)) {
            return Environment.PRODUCTION;
        }

        return Environment.valueOf(StringUtils.upperCase(environment));
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*EmbeddedWorkflowBuilderUtilsTest"`
Expected: PASS.

> If `org.jspecify.annotations.Nullable` does not resolve, use `edu.umd.cs.findbugs.annotations.Nullable` (used elsewhere in this codebase) instead.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/modules/components/embedded-workflow-builder settings.gradle.kts
git commit -m "732 Scaffold embedded-workflow-builder EE component module"
```

---

## Task 3: `createConnectedUserWorkflowFromPrompt` action

**Files:**
- Create: `.../embeddedworkflowbuilder/action/CreateConnectedUserWorkflowFromPromptAction.java`
- Test: `.../embeddedworkflowbuilder/action/CreateConnectedUserWorkflowFromPromptActionTest.java`

(Base path: `server/ee/libs/modules/components/embedded-workflow-builder/src/main/java/com/bytechef/ee/component/embeddedworkflowbuilder/` and the mirror under `src/test/java/...`.)

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.platform.configuration.domain.Environment;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class CreateConnectedUserWorkflowFromPromptActionTest {

    @Test
    void testPerformCreatesWorkflowFromPrompt() {
        ConnectedUserProjectFacade facade = mock(ConnectedUserProjectFacade.class);

        when(facade.createProjectWorkflow("user-1", "build a thing", Environment.PRODUCTION, true))
            .thenReturn("wf-uuid");

        Parameters inputParameters = mock(Parameters.class);

        when(inputParameters.getRequiredString(EmbeddedToolConstants.EXTERNAL_USER_ID)).thenReturn("user-1");
        when(inputParameters.getString(EmbeddedToolConstants.ENVIRONMENT)).thenReturn("PRODUCTION");
        when(inputParameters.getRequiredString("prompt")).thenReturn("build a thing");

        Object result = new CreateConnectedUserWorkflowFromPromptAction(facade)
            .perform(inputParameters, mock(Parameters.class), mock(ActionContext.class));

        assertEquals("wf-uuid", result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*CreateConnectedUserWorkflowFromPromptActionTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.ee.component.embeddedworkflowbuilder.util.EmbeddedWorkflowBuilderUtils;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public class CreateConnectedUserWorkflowFromPromptAction {

    public static final String PROMPT = "prompt";

    private final ConnectedUserProjectFacade connectedUserProjectFacade;

    @SuppressFBWarnings("EI")
    public static ModifiableActionDefinition of(ConnectedUserProjectFacade connectedUserProjectFacade) {
        return new CreateConnectedUserWorkflowFromPromptAction(connectedUserProjectFacade).build();
    }

    CreateConnectedUserWorkflowFromPromptAction(ConnectedUserProjectFacade connectedUserProjectFacade) {
        this.connectedUserProjectFacade = connectedUserProjectFacade;
    }

    private ModifiableActionDefinition build() {
        return action("createConnectedUserWorkflowFromPrompt")
            .title("Create Workflow From Prompt")
            .description(
                "Generate a new workflow for the connected user from a natural language prompt. "
                    + "Returns the new workflow uuid.")
            .properties(
                string(PROMPT)
                    .label("Prompt")
                    .description("Natural language description of the workflow to build.")
                    .required(true))
            .perform(this::perform);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    String perform(Parameters inputParameters, Parameters connectionParameters, ActionContext actionContext) {
        String externalUserId = inputParameters.getRequiredString(EmbeddedToolConstants.EXTERNAL_USER_ID);
        Environment environment = EmbeddedWorkflowBuilderUtils.resolveEnvironment(
            inputParameters.getString(EmbeddedToolConstants.ENVIRONMENT));
        String prompt = inputParameters.getRequiredString(PROMPT);

        return connectedUserProjectFacade.createProjectWorkflow(externalUserId, prompt, environment, true);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*CreateConnectedUserWorkflowFromPromptActionTest"`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/modules/components/embedded-workflow-builder
git commit -m "732 Add createConnectedUserWorkflowFromPrompt action to embedded-workflow-builder"
```

---

## Task 4: `updateConnectedUserWorkflowFromPrompt` action

**Files:**
- Create: `.../action/UpdateConnectedUserWorkflowFromPromptAction.java`
- Test: `.../action/UpdateConnectedUserWorkflowFromPromptActionTest.java`

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.platform.configuration.domain.Environment;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class UpdateConnectedUserWorkflowFromPromptActionTest {

    @Test
    void testPerformUpdatesWorkflowFromPrompt() {
        ConnectedUserProjectFacade facade = mock(ConnectedUserProjectFacade.class);

        when(facade.updateProjectWorkflow("user-1", "wf-1", "add a step", Environment.PRODUCTION, true))
            .thenReturn("wf-1");

        Parameters inputParameters = mock(Parameters.class);

        when(inputParameters.getRequiredString(EmbeddedToolConstants.EXTERNAL_USER_ID)).thenReturn("user-1");
        when(inputParameters.getString(EmbeddedToolConstants.ENVIRONMENT)).thenReturn("PRODUCTION");
        when(inputParameters.getRequiredString("workflowUuid")).thenReturn("wf-1");
        when(inputParameters.getRequiredString("prompt")).thenReturn("add a step");

        Object result = new UpdateConnectedUserWorkflowFromPromptAction(facade)
            .perform(inputParameters, mock(Parameters.class), mock(ActionContext.class));

        assertEquals("wf-1", result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*UpdateConnectedUserWorkflowFromPromptActionTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.ee.component.embeddedworkflowbuilder.util.EmbeddedWorkflowBuilderUtils;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public class UpdateConnectedUserWorkflowFromPromptAction {

    public static final String PROMPT = "prompt";
    public static final String WORKFLOW_UUID = "workflowUuid";

    private final ConnectedUserProjectFacade connectedUserProjectFacade;

    @SuppressFBWarnings("EI")
    public static ModifiableActionDefinition of(ConnectedUserProjectFacade connectedUserProjectFacade) {
        return new UpdateConnectedUserWorkflowFromPromptAction(connectedUserProjectFacade).build();
    }

    UpdateConnectedUserWorkflowFromPromptAction(ConnectedUserProjectFacade connectedUserProjectFacade) {
        this.connectedUserProjectFacade = connectedUserProjectFacade;
    }

    private ModifiableActionDefinition build() {
        return action("updateConnectedUserWorkflowFromPrompt")
            .title("Update Workflow From Prompt")
            .description(
                "Update an existing connected user workflow from a natural language prompt. "
                    + "Returns the workflow uuid.")
            .properties(
                string(WORKFLOW_UUID)
                    .label("Workflow UUID")
                    .description("The uuid of the workflow to update.")
                    .required(true),
                string(PROMPT)
                    .label("Prompt")
                    .description("Natural language description of the changes to apply.")
                    .required(true))
            .perform(this::perform);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    String perform(Parameters inputParameters, Parameters connectionParameters, ActionContext actionContext) {
        String externalUserId = inputParameters.getRequiredString(EmbeddedToolConstants.EXTERNAL_USER_ID);
        Environment environment = EmbeddedWorkflowBuilderUtils.resolveEnvironment(
            inputParameters.getString(EmbeddedToolConstants.ENVIRONMENT));
        String workflowUuid = inputParameters.getRequiredString(WORKFLOW_UUID);
        String prompt = inputParameters.getRequiredString(PROMPT);

        return connectedUserProjectFacade.updateProjectWorkflow(
            externalUserId, workflowUuid, prompt, environment, true);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*UpdateConnectedUserWorkflowFromPromptActionTest"`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/modules/components/embedded-workflow-builder
git commit -m "732 Add updateConnectedUserWorkflowFromPrompt action to embedded-workflow-builder"
```

---

## Task 5: `updateConnectedUserWorkflow` action

**Files:**
- Create: `.../action/UpdateConnectedUserWorkflowAction.java`
- Test: `.../action/UpdateConnectedUserWorkflowActionTest.java`

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.platform.configuration.domain.Environment;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class UpdateConnectedUserWorkflowActionTest {

    @Test
    void testPerformUpdatesWorkflowDefinition() {
        ConnectedUserProjectFacade facade = mock(ConnectedUserProjectFacade.class);

        Parameters inputParameters = mock(Parameters.class);

        when(inputParameters.getRequiredString(EmbeddedToolConstants.EXTERNAL_USER_ID)).thenReturn("user-1");
        when(inputParameters.getString(EmbeddedToolConstants.ENVIRONMENT)).thenReturn("PRODUCTION");
        when(inputParameters.getRequiredString("workflowUuid")).thenReturn("wf-1");
        when(inputParameters.getRequiredString("definition")).thenReturn("{\"tasks\":[]}");

        Object result = new UpdateConnectedUserWorkflowAction(facade)
            .perform(inputParameters, mock(Parameters.class), mock(ActionContext.class));

        verify(facade).updateProjectWorkflow("user-1", "wf-1", "{\"tasks\":[]}", Environment.PRODUCTION);
        assertEquals("Workflow 'wf-1' has been successfully updated.", result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*UpdateConnectedUserWorkflowActionTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.ee.component.embeddedworkflowbuilder.util.EmbeddedWorkflowBuilderUtils;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public class UpdateConnectedUserWorkflowAction {

    public static final String DEFINITION = "definition";
    public static final String WORKFLOW_UUID = "workflowUuid";

    private final ConnectedUserProjectFacade connectedUserProjectFacade;

    @SuppressFBWarnings("EI")
    public static ModifiableActionDefinition of(ConnectedUserProjectFacade connectedUserProjectFacade) {
        return new UpdateConnectedUserWorkflowAction(connectedUserProjectFacade).build();
    }

    UpdateConnectedUserWorkflowAction(ConnectedUserProjectFacade connectedUserProjectFacade) {
        this.connectedUserProjectFacade = connectedUserProjectFacade;
    }

    private ModifiableActionDefinition build() {
        return action("updateConnectedUserWorkflow")
            .title("Update Workflow Definition")
            .description("Replace the JSON definition of a connected user's workflow. Returns a confirmation message.")
            .properties(
                string(WORKFLOW_UUID)
                    .label("Workflow UUID")
                    .description("The uuid of the workflow to update.")
                    .required(true),
                string(DEFINITION)
                    .label("Definition")
                    .description("The new workflow definition in JSON format.")
                    .required(true))
            .perform(this::perform);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    String perform(Parameters inputParameters, Parameters connectionParameters, ActionContext actionContext) {
        String externalUserId = inputParameters.getRequiredString(EmbeddedToolConstants.EXTERNAL_USER_ID);
        Environment environment = EmbeddedWorkflowBuilderUtils.resolveEnvironment(
            inputParameters.getString(EmbeddedToolConstants.ENVIRONMENT));
        String workflowUuid = inputParameters.getRequiredString(WORKFLOW_UUID);
        String definition = inputParameters.getRequiredString(DEFINITION);

        connectedUserProjectFacade.updateProjectWorkflow(externalUserId, workflowUuid, definition, environment);

        return "Workflow '" + workflowUuid + "' has been successfully updated.";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*UpdateConnectedUserWorkflowActionTest"`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/modules/components/embedded-workflow-builder
git commit -m "732 Add updateConnectedUserWorkflow action to embedded-workflow-builder"
```

---

## Task 6: `deleteConnectedUserWorkflow` action

**Files:**
- Create: `.../action/DeleteConnectedUserWorkflowAction.java`
- Test: `.../action/DeleteConnectedUserWorkflowActionTest.java`

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.platform.configuration.domain.Environment;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class DeleteConnectedUserWorkflowActionTest {

    @Test
    void testPerformDeletesWorkflow() {
        ConnectedUserProjectFacade facade = mock(ConnectedUserProjectFacade.class);

        Parameters inputParameters = mock(Parameters.class);

        when(inputParameters.getRequiredString(EmbeddedToolConstants.EXTERNAL_USER_ID)).thenReturn("user-1");
        when(inputParameters.getString(EmbeddedToolConstants.ENVIRONMENT)).thenReturn("PRODUCTION");
        when(inputParameters.getRequiredString("workflowUuid")).thenReturn("wf-1");

        Object result = new DeleteConnectedUserWorkflowAction(facade)
            .perform(inputParameters, mock(Parameters.class), mock(ActionContext.class));

        verify(facade).deleteProjectWorkflow("user-1", "wf-1", Environment.PRODUCTION);
        assertEquals("Workflow 'wf-1' has been successfully deleted.", result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*DeleteConnectedUserWorkflowActionTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.ee.component.embeddedworkflowbuilder.util.EmbeddedWorkflowBuilderUtils;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public class DeleteConnectedUserWorkflowAction {

    public static final String WORKFLOW_UUID = "workflowUuid";

    private final ConnectedUserProjectFacade connectedUserProjectFacade;

    @SuppressFBWarnings("EI")
    public static ModifiableActionDefinition of(ConnectedUserProjectFacade connectedUserProjectFacade) {
        return new DeleteConnectedUserWorkflowAction(connectedUserProjectFacade).build();
    }

    DeleteConnectedUserWorkflowAction(ConnectedUserProjectFacade connectedUserProjectFacade) {
        this.connectedUserProjectFacade = connectedUserProjectFacade;
    }

    private ModifiableActionDefinition build() {
        return action("deleteConnectedUserWorkflow")
            .title("Delete Workflow")
            .description("Delete a connected user's workflow. Returns a confirmation message.")
            .properties(
                string(WORKFLOW_UUID)
                    .label("Workflow UUID")
                    .description("The uuid of the workflow to delete.")
                    .required(true))
            .perform(this::perform);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    String perform(Parameters inputParameters, Parameters connectionParameters, ActionContext actionContext) {
        String externalUserId = inputParameters.getRequiredString(EmbeddedToolConstants.EXTERNAL_USER_ID);
        Environment environment = EmbeddedWorkflowBuilderUtils.resolveEnvironment(
            inputParameters.getString(EmbeddedToolConstants.ENVIRONMENT));
        String workflowUuid = inputParameters.getRequiredString(WORKFLOW_UUID);

        connectedUserProjectFacade.deleteProjectWorkflow(externalUserId, workflowUuid, environment);

        return "Workflow '" + workflowUuid + "' has been successfully deleted.";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*DeleteConnectedUserWorkflowActionTest"`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/modules/components/embedded-workflow-builder
git commit -m "732 Add deleteConnectedUserWorkflow action to embedded-workflow-builder"
```

---

## Task 7: Component handler + definition snapshot test

**Files:**
- Create: `.../embeddedworkflowbuilder/EmbeddedWorkflowBuilderComponentHandler.java`
- Test: `.../embeddedworkflowbuilder/EmbeddedWorkflowBuilderComponentHandlerTest.java`
- Generated on first run: `.../src/test/resources/definition/embeddedWorkflowBuilder_v1.json`

- [ ] **Step 1: Write the snapshot test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder;

import static org.mockito.Mockito.mock;

import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.test.jsonasssert.JsonFileAssert;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
public class EmbeddedWorkflowBuilderComponentHandlerTest {

    @Test
    public void testGetComponentDefinition() {
        ConnectedUserProjectFacade facade = mock(ConnectedUserProjectFacade.class);

        JsonFileAssert.assertEquals(
            "definition/embeddedWorkflowBuilder_v1.json",
            new EmbeddedWorkflowBuilderComponentHandler(facade).getDefinition());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*EmbeddedWorkflowBuilderComponentHandlerTest"`
Expected: FAIL — handler class does not exist.

- [ ] **Step 3: Implement the handler**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.embeddedworkflowbuilder;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.ee.component.embeddedworkflowbuilder.action.CreateConnectedUserWorkflowFromPromptAction;
import com.bytechef.ee.component.embeddedworkflowbuilder.action.DeleteConnectedUserWorkflowAction;
import com.bytechef.ee.component.embeddedworkflowbuilder.action.UpdateConnectedUserWorkflowAction;
import com.bytechef.ee.component.embeddedworkflowbuilder.action.UpdateConnectedUserWorkflowFromPromptAction;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Component;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component("embeddedWorkflowBuilder_v1_ComponentHandler")
@ConditionalOnEEVersion
public class EmbeddedWorkflowBuilderComponentHandler implements ComponentHandler {

    public static final String EMBEDDED_WORKFLOW_BUILDER = "embeddedWorkflowBuilder";

    private final ComponentDefinition componentDefinition;

    @SuppressFBWarnings("EI2")
    public EmbeddedWorkflowBuilderComponentHandler(ConnectedUserProjectFacade connectedUserProjectFacade) {
        ActionDefinition createAction =
            CreateConnectedUserWorkflowFromPromptAction.of(connectedUserProjectFacade);
        ActionDefinition updateFromPromptAction =
            UpdateConnectedUserWorkflowFromPromptAction.of(connectedUserProjectFacade);
        ActionDefinition updateAction = UpdateConnectedUserWorkflowAction.of(connectedUserProjectFacade);
        ActionDefinition deleteAction = DeleteConnectedUserWorkflowAction.of(connectedUserProjectFacade);

        this.componentDefinition = component(EMBEDDED_WORKFLOW_BUILDER)
            .title("Embedded Workflow Builder")
            .description("Create, update, and delete an embedded connected user's workflows via AI Copilot.")
            .icon("path:assets/embedded-workflow-builder.svg")
            .categories(ComponentCategory.HELPERS)
            .actions(createAction, updateFromPromptAction, updateAction, deleteAction)
            .clusterElements(
                tool(createAction), tool(updateFromPromptAction), tool(updateAction), tool(deleteAction))
            .version(1);
    }

    @Override
    public ComponentDefinition getDefinition() {
        return componentDefinition;
    }
}
```

- [ ] **Step 4: Run the test (it generates the snapshot, then passes)**

Before running, ensure no stale snapshot exists: delete `server/ee/libs/modules/components/embedded-workflow-builder/build/resources/test/definition/embeddedWorkflowBuilder_v1.json` if present.

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:test --tests "*EmbeddedWorkflowBuilderComponentHandlerTest"`
Expected: PASS — `JsonFileAssert` auto-generates `src/test/resources/definition/embeddedWorkflowBuilder_v1.json` on the first run, then the assertion succeeds. Confirm the JSON file was created and contains the four actions and four tool cluster elements.

- [ ] **Step 5: Compile the whole module to confirm Spring wiring types resolve**

Run: `./gradlew :server:ee:libs:modules:components:embedded-workflow-builder:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Format and commit (include the generated definition JSON)**

```bash
./gradlew spotlessApply
git add server/ee/libs/modules/components/embedded-workflow-builder
git commit -m "732 Add EmbeddedWorkflowBuilderComponentHandler exposing workflow tools"
```

---

## Task 8: Inject reserved keys in `ToolFacadeImpl` (REST `/tools` path)

**Files:**
- Modify: `server/ee/libs/embedded/embedded-execution/embedded-execution-service/src/main/java/com/bytechef/ee/embedded/execution/facade/ToolFacadeImpl.java:117-129`
- Test: `server/ee/libs/embedded/embedded-execution/embedded-execution-service/src/test/java/com/bytechef/ee/embedded/execution/facade/ToolFacadeImplTest.java` (create)

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.execution.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.ee.embedded.configuration.service.IntegrationService;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.ee.embedded.execution.util.ConnectionIdHelper;
import com.bytechef.platform.component.facade.ClusterElementDefinitionFacade;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @version ee
 */
class ToolFacadeImplTest {

    private final ClusterElementDefinitionFacade clusterElementDefinitionFacade =
        mock(ClusterElementDefinitionFacade.class);
    private final ConnectionIdHelper connectionIdHelper = mock(ConnectionIdHelper.class);

    private final ToolFacadeImpl toolFacade = new ToolFacadeImpl(
        clusterElementDefinitionFacade, mock(ClusterElementDefinitionService.class),
        mock(ComponentDefinitionService.class), mock(ConnectedUserService.class), connectionIdHelper,
        mock(IntegrationInstanceConfigurationService.class), mock(IntegrationInstanceService.class),
        mock(IntegrationService.class));

    @Test
    @SuppressWarnings("unchecked")
    void testExecuteToolInjectsReservedContextParameters() {
        when(connectionIdHelper.getConnectionId("user-1", "slack", null, Environment.PRODUCTION)).thenReturn(7L);

        ArgumentCaptor<Map<String, ?>> captor = ArgumentCaptor.forClass(Map.class);

        when(clusterElementDefinitionFacade.executeTool(eq("slack"), eq("send"), captor.capture(), eq(7L)))
            .thenReturn("ok");

        Map<String, Object> input = new HashMap<>();

        input.put("text", "hi");

        Object result = toolFacade.executeTool("user-1", "slack_send", input, null, Environment.PRODUCTION);

        assertEquals("ok", result);

        Map<String, ?> passed = captor.getValue();

        assertEquals("user-1", passed.get(EmbeddedToolConstants.EXTERNAL_USER_ID));
        assertEquals("PRODUCTION", passed.get(EmbeddedToolConstants.ENVIRONMENT));
        assertEquals("hi", passed.get("text"));
    }
}
```

> The constructor arg order must match `ToolFacadeImpl`'s actual constructor (clusterElementDefinitionFacade, clusterElementDefinitionService, componentDefinitionService, connectedUserService, connectionIdHelper, integrationInstanceConfigurationService, integrationInstanceService, integrationService). Verify against the source and reorder the mocks if needed.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:embedded:embedded-execution:embedded-execution-service:test --tests "*ToolFacadeImplTest"`
Expected: FAIL — reserved keys absent from the captured map.

- [ ] **Step 3: Modify `executeTool`**

Replace the `executeTool` method body (lines 117-129) with:

```java
    @Override
    public Object executeTool(
        String externalUserId, String toolName, Map<String, Object> inputParameters, @Nullable Long instanceId,
        Environment environment) {

        ComponentClusterElementNameResult result = getComponentClusterElementNames(toolName);

        Long connectionId = connectionIdHelper.getConnectionId(
            externalUserId, result.componentName(), instanceId, environment);

        Map<String, Object> parameters = EmbeddedToolConstants.withConnectedUserContext(
            inputParameters, externalUserId, environment);

        return clusterElementDefinitionFacade.executeTool(
            result.componentName(), result.clusterElementName(), parameters, connectionId);
    }
```

Add the import:
```java
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:ee:libs:embedded:embedded-execution:embedded-execution-service:test --tests "*ToolFacadeImplTest"`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/embedded/embedded-execution/embedded-execution-service
git commit -m "732 Inject connected-user context params in ToolFacadeImpl tool execution"
```

---

## Task 9: Inject reserved keys + tolerate connection-less components in `EmbeddedMcpToolFacade`

**Files:**
- Modify: `server/ee/libs/embedded/embedded-ai/embedded-ai-mcp-server/src/main/java/com/bytechef/ee/embedded/ai/mcp/server/facade/EmbeddedMcpToolFacade.java`
- Modify: the `@Bean` that constructs `EmbeddedMcpToolFacade` (in `.../config/EmbeddedMcpServerConfiguration.java`) to pass the new `ComponentDefinitionService` arg
- Test: `.../embedded-ai-mcp-server/src/test/java/com/bytechef/ee/embedded/ai/mcp/server/facade/EmbeddedMcpToolFacadeConnectionTest.java` (create)

This facade currently returns a "connection required" response whenever `fetchConnectionId(...)` is null — which wrongly blocks connection-less components. We add a static, unit-testable connection-requirement check and gate the connection-required response on it, and inject the reserved keys.

- [ ] **Step 1: Write the failing test for the connection-requirement helper**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.ai.mcp.server.facade;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.domain.ConnectionDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class EmbeddedMcpToolFacadeConnectionTest {

    private final ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);

    @Test
    void testConnectionRequiredWhenComponentDeclaresConnection() {
        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getConnection()).thenReturn(mock(ConnectionDefinition.class));
        when(componentDefinitionService.getComponentDefinition("slack", 1)).thenReturn(componentDefinition);

        assertTrue(EmbeddedMcpToolFacade.isConnectionRequired(componentDefinitionService, "slack", 1));
    }

    @Test
    void testConnectionNotRequiredForConnectionlessComponent() {
        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getConnection()).thenReturn(null);
        when(componentDefinitionService.getComponentDefinition("embeddedWorkflowBuilder", 1))
            .thenReturn(componentDefinition);

        assertFalse(
            EmbeddedMcpToolFacade.isConnectionRequired(componentDefinitionService, "embeddedWorkflowBuilder", 1));
    }
}
```

> Verify the connection accessor on `com.bytechef.platform.component.domain.ComponentDefinition`. If it is not `getConnection()` returning a `@Nullable ConnectionDefinition` (e.g. it returns `Optional<ConnectionDefinition>` or exposes `isConnectionRequired()`), adapt both the test stubbing and the helper in Step 3 to the real accessor. The contract — "true when the component declares a connection, false otherwise" — is what matters.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:ee:libs:embedded:embedded-ai:embedded-ai-mcp-server:test --tests "*EmbeddedMcpToolFacadeConnectionTest"`
Expected: FAIL — `isConnectionRequired` does not exist.

- [ ] **Step 3: Add the helper, inject `ComponentDefinitionService`, gate the connection-required response, and inject reserved keys**

In `EmbeddedMcpToolFacade.java`:

(a) Add imports:
```java
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
```

(b) Add a field and constructor parameter `ComponentDefinitionService componentDefinitionService` (add it to the existing constructor parameter list and assign the field; keep the `@SuppressFBWarnings("EI")`/"EI2" annotation already on the constructor if present).

(c) Add the static helper (package-private so the test can call it):
```java
    static boolean isConnectionRequired(
        ComponentDefinitionService componentDefinitionService, String componentName, int componentVersion) {

        ComponentDefinition componentDefinition =
            componentDefinitionService.getComponentDefinition(componentName, componentVersion);

        return componentDefinition.getConnection() != null;
    }
```

(d) In `getClusterElementToolCallbackFunction`, change the connection handling and inject reserved keys. Replace the body of the returned `request -> { ... }` lambda (lines 291-316) with:

```java
        return request -> {
            McpServer mcpServer = mcpServerService.getMcpServer(mcpServerId);

            if (!mcpServer.isEnabled()) {
                throw new IllegalStateException("MCP server is disabled");
            }

            Long connectionId = fetchConnectionId(externalUserId, componentName, environment);

            if (connectionId == null && isConnectionRequired(componentDefinitionService, componentName, componentVersion)) {
                long integrationId = getIntegrationId(componentName);

                return getConnectionRequiredResponse(
                    componentName, environment, externalUserId, integrationId, tenantId);
            }

            Map<String, Object> resolvedParameters = new HashMap<>();

            for (Map.Entry<String, ?> entry : parameters.entrySet()) {
                resolvedParameters.put(entry.getKey(), resolveParameterValue(entry.getValue(), request));
            }

            Map<String, Object> mergedParameters = EmbeddedToolConstants.withConnectedUserContext(
                MapUtils.concat(request, resolvedParameters), externalUserId, environment);

            return clusterElementDefinitionFacade.executeTool(
                componentName, componentVersion, clusterElementName, mergedParameters, connectionId);
        };
```

(e) Update the `EmbeddedMcpToolFacade` `@Bean` definition in `EmbeddedMcpServerConfiguration.java` to pass the `ComponentDefinitionService` bean as the new constructor argument (Spring injects it; add the parameter to the `@Bean` method signature and forward it). Find the `@Bean` that does `new EmbeddedMcpToolFacade(...)` and add `componentDefinitionService` in the correct position matching the constructor.

- [ ] **Step 4: Run the helper test to verify it passes**

Run: `./gradlew :server:ee:libs:embedded:embedded-ai:embedded-ai-mcp-server:test --tests "*EmbeddedMcpToolFacadeConnectionTest"`
Expected: PASS.

- [ ] **Step 5: Compile the module to confirm the constructor/bean wiring**

Run: `./gradlew :server:ee:libs:embedded:embedded-ai:embedded-ai-mcp-server:compileJava`
Expected: BUILD SUCCESSFUL. If `ComponentDefinitionService` isn't resolvable, confirm the module already depends on `platform-component-api` (it does) and that the import is correct.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/embedded/embedded-ai/embedded-ai-mcp-server
git commit -m "732 Inject connected-user context and support connection-less tools in EmbeddedMcpToolFacade"
```

---

## Task 10: Remove the old CE `mcp-tool-integration` module

**Files:**
- Delete: `server/libs/ai/mcp/mcp-tool/mcp-tool-integration/` (entire directory)
- Modify: `settings.gradle.kts` (remove the `include("server:libs:ai:mcp:mcp-tool:mcp-tool-integration")` line)

(A repo-wide search found **no** other module depends on `:server:libs:ai:mcp:mcp-tool:mcp-tool-integration` and **no** code imports `com.bytechef.ai.mcp.tool.integration` outside the module itself. Re-verify in Step 1 before deleting.)

- [ ] **Step 1: Verify there are no dependents**

Run:
```bash
grep -rn "mcp-tool-integration" --include="*.kts" /Volumes/Data/bytechef/bytechef
grep -rn "com.bytechef.ai.mcp.tool.integration" --include="*.java" /Volumes/Data/bytechef/bytechef | grep -v "/mcp-tool-integration/"
```
Expected: the first prints only the `settings.gradle.kts` include line (and the module's own `build.gradle.kts` if matched); the second prints nothing. If the second prints any file, STOP and report — there is an unexpected dependent to migrate first.

- [ ] **Step 2: Remove the `include` from `settings.gradle.kts`**

Delete the line:
```gradle
include("server:libs:ai:mcp:mcp-tool:mcp-tool-integration")
```

- [ ] **Step 3: Delete the module directory**

Run: `git rm -r server/libs/ai/mcp/mcp-tool/mcp-tool-integration`
Expected: removes `ConnectedUserProjectWorkflowTools.java`, `exception/ConnectedUserProjectWorkflowToolErrorType.java`, and `build.gradle.kts`.

- [ ] **Step 4: Confirm the project still configures and the embedded modules compile**

Run: `./gradlew :server:ee:libs:embedded:embedded-ai:embedded-ai-mcp-server:compileJava :server:ee:libs:modules:components:embedded-workflow-builder:compileJava`
Expected: BUILD SUCCESSFUL (Gradle settings no longer reference the deleted module).

- [ ] **Step 5: Commit**

```bash
git add server/libs/ai/mcp/mcp-tool/mcp-tool-integration settings.gradle.kts
git commit -m "732 Remove CE mcp-tool-integration module replaced by embedded-workflow-builder component"
```

---

## Final verification

- [ ] Run the new/changed module test suites:
  `./gradlew :server:ee:libs:embedded:embedded-execution:embedded-execution-api:test :server:ee:libs:modules:components:embedded-workflow-builder:test :server:ee:libs:embedded:embedded-execution:embedded-execution-service:test :server:ee:libs:embedded:embedded-ai:embedded-ai-mcp-server:test`
  Expected: all green.
- [ ] `./gradlew spotlessApply check` on the touched modules (or at least `spotlessCheck`) — formatting and static analysis pass; EE headers and `@version ee` present on every new file.
- [ ] **Manual exposure check (operational, no code):** an admin registers the `embeddedWorkflowBuilder` component (version 1) and its tool actions on an embedded MCP server via the existing `createMcpComponentWithTools` GraphQL mutation; confirm a connected-user agent can call `createConnectedUserWorkflowFromPrompt` and the workflow is created for the authenticated connected user (no `externalUserId` argument supplied by the model).

---

## Notes for the executor

- **Docs are out of scope for this plan** — updating the embedded docs is a separate post-implementation step (tracked in the spec §7).
- **Deliberate deviation from spec §5 (error typing):** the spec mentioned a minimal `EmbeddedWorkflowBuilderErrorType` enum for wrapping facade failures. This plan omits it (YAGNI): the action `perform` methods let exceptions propagate, and `ClusterElementDefinitionService.doExecuteTool` already wraps any thrown exception in an `ExecutionException` surfaced to the tool caller. If a component-specific error type is later wanted, add the enum and wrap in each `perform`.
- **`ComponentDefinition.getConnection()` accessor (Task 9):** verify the exact method on `com.bytechef.platform.component.domain.ComponentDefinition`; adapt the helper + test if it differs (the behavioral contract is fixed).
- **`EmbeddedMcpToolFacade` constructor order (Task 9):** insert `ComponentDefinitionService` consistently in the constructor, the field assignment, and the `@Bean` call; keep alphabetical field ordering if the class already follows it.
- **EE headers:** Spotless selects the Enterprise header by `@version ee` content — every new file under `server/ee/**` must carry both the Enterprise license header and a `@version ee` Javadoc tag, or Spotless will rewrite the header.
- **Definition snapshot (Task 7):** if the snapshot JSON drifts after later edits, delete it from BOTH `src/test/resources/definition/` and `build/resources/test/definition/` and rerun to regenerate.
