# EE mcp-tool Tier — Copilot Subagents as MCP Agent-Tools — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce an EE tier of the `mcp-tool` modules, relocate the AI-Hub agent-tool callbacks + shared infra into it, and expose the Copilot BUILD subagents as high-level agent tools on the management MCP server via a CE contributor seam.

**Architecture:** New EE modules `mcp-tool-api` (shared helpers moved down from `platform-ai-hub-api`) and `mcp-tool-automation` (the 6 `*AgentToolCallback`s + `SkillsTools`/`ReadSkillsTools` + a self-contained `McpToolCallbackContributor`). A revived CE `mcp-tool-api` holds the contributor interface; CE `mcp-server` folds contributed callbacks into its `ToolCallbackProvider` alongside 7 directly-wired CE CRUD tools. CE→EE layering is preserved.

**Tech Stack:** Java 25, Spring Boot 4, Spring AI (`spring-ai-client-chat`/`spring-ai-model`), Gradle (Kotlin DSL), JUnit 5 + Mockito, Jackson 3 (`tools.jackson`).

**Spec:** `docs/superpowers/specs/2026-06-04-ee-mcp-tool-tier-agent-tools-design.md`

**Conventions:**
- New files under `server/ee/` use the **ByteChef Enterprise** license header and a `@version ee` Javadoc tag. New files under `server/libs/` use the **Apache 2.0** header.
- `org.jspecify:jspecify` and `com.github.spotbugs:spotbugs-annotations` are provided globally — never declared per-module.
- Build files in this repo are bare `dependencies { }` blocks (convention plugins applied at root).
- Run all gradle commands from the repo root `/Volumes/Data/bytechef/bytechef`.

---

## File Structure

**New modules**
- `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api/` — EE shared tool infra (`Agent`, `CurrentAgentContext`, `LogSanitizer`, `ToolErrors`).
- `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/` — EE agent callbacks, `SkillsTools`/`ReadSkillsTools`, contributor.

**Revived module**
- `server/libs/ai/mcp/mcp-tool/mcp-tool-api/` — add `McpToolCallbackContributor` interface; register in settings.

**Heavily modified**
- `server/libs/ai/mcp/mcp-server/.../ManagementMcpServerConfiguration.java` — pure host + 7 direct tools.
- `settings.gradle.kts` — 3 new `include(...)` lines.

**Import-only rewires (package moves)**
- `platform-ai-hub-{api,service}`, `automation-ai-hub-{service,rest}` — helper imports.
- `automation-ai-hub-service/.../AiHubConfiguration.java` — callback imports.
- `ai-copilot-service/.../CopilotConfiguration.java` — SkillsTools imports.

---

## Task 1: Scaffold EE `mcp-tool-api` module

**Files:**
- Create: `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Create the module build file**

Create `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api/build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.slf4j:slf4j-api")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
}
```

- [ ] **Step 2: Register the module in settings**

In `settings.gradle.kts`, find the line `include("server:ee:libs:platform:platform-ai-hub:platform-ai-hub-api")` and add **above the platform-ai-hub block** (keep file ordering roughly alphabetical/grouped):

```kotlin
include("server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api")
```

- [ ] **Step 3: Create the source root so Gradle resolves the project**

Run:
```bash
mkdir -p server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api/src/main/java/com/bytechef/ee/ai/mcp/tool
```

- [ ] **Step 4: Verify the module resolves**

Run: `./gradlew :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api:compileJava`
Expected: `BUILD SUCCESSFUL` (no sources yet — compiles empty).

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api/build.gradle.kts settings.gradle.kts
git commit -m "732 Scaffold EE mcp-tool-api module"
```

---

## Task 2: Revive CE `mcp-tool-api` + add `McpToolCallbackContributor`

**Files:**
- Create: `server/libs/ai/mcp/mcp-tool/mcp-tool-api/src/main/java/com/bytechef/ai/mcp/tool/McpToolCallbackContributor.java`
- Modify: `server/libs/ai/mcp/mcp-tool/mcp-tool-api/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Register the dead CE module in settings**

In `settings.gradle.kts`, next to the existing CE mcp-tool includes (`include("server:libs:ai:mcp:mcp-tool:mcp-tool-automation")` etc.), add:

```kotlin
include("server:libs:ai:mcp:mcp-tool:mcp-tool-api")
```

- [ ] **Step 2: Add spring-ai-model to the CE module build**

Edit `server/libs/ai/mcp/mcp-tool/mcp-tool-api/build.gradle.kts` to:

```kotlin
dependencies {
    implementation("org.springframework.ai:spring-ai-model")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-context")
}
```

- [ ] **Step 3: Create the contributor interface**

Create `server/libs/ai/mcp/mcp-tool/mcp-tool-api/src/main/java/com/bytechef/ai/mcp/tool/McpToolCallbackContributor.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.ai.mcp.tool;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * Extension point for contributing {@link ToolCallback}s to the management MCP server. Implementations are collected by
 * {@code ManagementMcpServerConfiguration} and folded into the server's tool catalog. This keeps the CE MCP server
 * independent of EE modules: EE deployments supply an implementation; CE-only deployments simply have none.
 *
 * @author Ivica Cardic
 */
public interface McpToolCallbackContributor {

    List<ToolCallback> getToolCallbacks();
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :server:libs:ai:mcp:mcp-tool:mcp-tool-api:compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add server/libs/ai/mcp/mcp-tool/mcp-tool-api settings.gradle.kts
git commit -m "732 Revive CE mcp-tool-api and add McpToolCallbackContributor seam"
```

---

## Task 3: Move shared helpers to EE `mcp-tool-api`

Moves `Agent`, `CurrentAgentContext`, `LogSanitizer`, `ToolErrors` from `platform-ai-hub-api` (package `com.bytechef.ee.platform.aihub.{usage,util}`) into EE `mcp-tool-api` (package `com.bytechef.ee.ai.mcp.tool.{usage,util}`). ~80 FQCN import sites + a handful of same-package consumers that need new explicit imports.

**Files:**
- Move: 4 main classes + `ToolErrorsTest`
- Modify: 4 module `build.gradle.kts` (add EE mcp-tool-api dep)
- Modify: ~80 consumer `.java` files (import rewrite)

- [ ] **Step 1: Move the 4 source files with git mv**

```bash
cd /Volumes/Data/bytechef/bytechef
API_SRC=server/ee/libs/platform/platform-ai-hub/platform-ai-hub-api/src/main/java/com/bytechef/ee/platform/aihub
NEW=server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api/src/main/java/com/bytechef/ee/ai/mcp/tool
mkdir -p "$NEW/usage" "$NEW/util"
git mv "$API_SRC/usage/Agent.java" "$NEW/usage/Agent.java"
git mv "$API_SRC/usage/CurrentAgentContext.java" "$NEW/usage/CurrentAgentContext.java"
git mv "$API_SRC/util/LogSanitizer.java" "$NEW/util/LogSanitizer.java"
git mv "$API_SRC/util/ToolErrors.java" "$NEW/util/ToolErrors.java"
```

- [ ] **Step 2: Move the ToolErrors test**

```bash
API_TEST=server/ee/libs/platform/platform-ai-hub/platform-ai-hub-api/src/test/java/com/bytechef/ee/platform/aihub
NEW_TEST=server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api/src/test/java/com/bytechef/ee/ai/mcp/tool
mkdir -p "$NEW_TEST/util"
git mv "$API_TEST/util/ToolErrorsTest.java" "$NEW_TEST/util/ToolErrorsTest.java"
```

- [ ] **Step 3: Rewrite the package declarations in the 4 moved files + the test**

```bash
NEW=server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api/src/main/java/com/bytechef/ee/ai/mcp/tool
NEW_TEST=server/ee/libs/ai/mcp/mcp-tool/mcp-tool-api/src/test/java/com/bytechef/ee/ai/mcp/tool
sed -i '' 's/^package com.bytechef.ee.platform.aihub.usage;/package com.bytechef.ee.ai.mcp.tool.usage;/' "$NEW/usage/Agent.java" "$NEW/usage/CurrentAgentContext.java"
sed -i '' 's/^package com.bytechef.ee.platform.aihub.util;/package com.bytechef.ee.ai.mcp.tool.util;/' "$NEW/util/LogSanitizer.java" "$NEW/util/ToolErrors.java"
sed -i '' 's/^package com.bytechef.ee.platform.aihub.util;/package com.bytechef.ee.ai.mcp.tool.util;/' "$NEW_TEST/util/ToolErrorsTest.java"
```

- [ ] **Step 4: Rewrite all FQCN imports across server/ (the ~80-file churn)**

```bash
grep -rl "com.bytechef.ee.platform.aihub.usage.Agent\|com.bytechef.ee.platform.aihub.usage.CurrentAgentContext\|com.bytechef.ee.platform.aihub.util.LogSanitizer\|com.bytechef.ee.platform.aihub.util.ToolErrors" server --include="*.java" \
  | xargs sed -i '' \
    -e 's/com\.bytechef\.ee\.platform\.aihub\.usage\.Agent/com.bytechef.ee.ai.mcp.tool.usage.Agent/g' \
    -e 's/com\.bytechef\.ee\.platform\.aihub\.usage\.CurrentAgentContext/com.bytechef.ee.ai.mcp.tool.usage.CurrentAgentContext/g' \
    -e 's/com\.bytechef\.ee\.platform\.aihub\.util\.LogSanitizer/com.bytechef.ee.ai.mcp.tool.util.LogSanitizer/g' \
    -e 's/com\.bytechef\.ee\.platform\.aihub\.util\.ToolErrors/com.bytechef.ee.ai.mcp.tool.util.ToolErrors/g'
```

This rewrites both `import` lines and any fully-qualified references. The moved files' own self-imports (e.g. `CurrentAgentContext.AgentBinding`) are package-local and unaffected.

- [ ] **Step 5: Add explicit imports to same-package consumers**

These files live in `com.bytechef.ee.platform.aihub.usage` and reference `Agent`/`CurrentAgentContext` by simple name (no FQCN, so Step 4 missed them). Add the needed imports just after the `package` line of each. Only add an import for a symbol the file actually uses (check with `grep -n 'Agent\|CurrentAgentContext' <file>`):

- `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/usage/DefaultCostEstimator.java`
- `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/usage/CostEstimationProperties.java`
- `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/usage/AiHubToolUsageContextResolver.java`
- `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src/main/java/com/bytechef/ee/platform/aihub/usage/UsageObservationHandler.java`

Imports to add (only those used):
```java
import com.bytechef.ee.ai.mcp.tool.usage.Agent;
import com.bytechef.ee.ai.mcp.tool.usage.CurrentAgentContext;
```

Also check `EnumOrdinalStabilityTest` (`platform-ai-hub-api` test, references `Agent`) and any `com.bytechef.ee.platform.aihub.util`-package main/test class that uses `LogSanitizer`/`ToolErrors` by simple name; add the corresponding import:
```java
import com.bytechef.ee.ai.mcp.tool.util.LogSanitizer;
import com.bytechef.ee.ai.mcp.tool.util.ToolErrors;
```

- [ ] **Step 6: Add EE mcp-tool-api dependency to the consuming modules**

Add `implementation(project(":server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api"))` to the `dependencies { }` of:
- `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-api/build.gradle.kts`
- `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/build.gradle.kts`
- `server/ee/libs/automation/automation-ai-hub/automation-ai-hub-service/build.gradle.kts`
- `server/ee/libs/automation/automation-ai-hub/automation-ai-hub-rest/build.gradle.kts`

For `platform-ai-hub-api`, also add it as `testImplementation` if the test sources reference the moved classes (e.g. `EnumOrdinalStabilityTest`). EE `mcp-tool-api` itself needs `testImplementation(project(":server:libs:test:test-support"))` only if `ToolErrorsTest` uses it — if `ToolErrorsTest` uses `JsonUtils`/`ObjectMapperSetupExtension`, add `testImplementation(project(":server:libs:core:commons:commons-util"))` and `testImplementation(project(":server:libs:test:test-support"))`; otherwise leave the Task-1 test deps.

- [ ] **Step 7: Compile each affected module; fix stragglers**

Run, in order, fixing any `cannot find symbol` by adding the matching import from Step 5:
```bash
./gradlew :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api:compileJava :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api:compileTestJava
./gradlew :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-api:compileJava :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-api:compileTestJava
./gradlew :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:compileJava
./gradlew :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service:compileJava
./gradlew :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-rest:compileJava
```
Expected: all `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run the moved + ordinal-stability tests**

Run:
```bash
./gradlew :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api:test
./gradlew :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-api:test --tests "*EnumOrdinalStabilityTest"
```
Expected: PASS. (Confirms `Agent` ordinals unchanged after the move.)

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "732 Move Agent/CurrentAgentContext/LogSanitizer/ToolErrors to EE mcp-tool-api"
```

---

## Task 4: Move the 6 agent callbacks to EE `mcp-tool-automation`

**Files:**
- Create: `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/build.gradle.kts`
- Move: 6 `*AgentToolCallback.java` + 6 `*AgentToolCallbackTest.java`
- Modify: `settings.gradle.kts`, `AiHubConfiguration.java`, `automation-ai-hub-service/build.gradle.kts`

- [ ] **Step 1: Create the EE mcp-tool-automation build file**

Create `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework:spring-context")
    implementation("org.springframework.ai:spring-ai-client-chat")
    implementation("org.springframework.ai:spring-ai-model")
    implementation("tools.jackson.core:jackson-databind")
    implementation(project(":server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api"))
    implementation(project(":server:libs:ai:mcp:mcp-tool:mcp-tool-api"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
}
```

- [ ] **Step 2: Register the module in settings**

In `settings.gradle.kts`, below the EE mcp-tool-api include from Task 1, add:
```kotlin
include("server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation")
```

- [ ] **Step 3: Move the 6 callback classes + tests**

```bash
cd /Volumes/Data/bytechef/bytechef
SVC=server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service/src
DST=server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/src
mkdir -p "$DST/main/java/com/bytechef/ee/ai/mcp/tool/automation" "$DST/test/java/com/bytechef/ee/ai/mcp/tool/automation"
for c in CodeEditor WorkflowEditor Converter ClusterElement Skills WorkflowExecution; do
  git mv "$SVC/main/java/com/bytechef/ee/platform/aihub/tool/${c}AgentToolCallback.java" \
         "$DST/main/java/com/bytechef/ee/ai/mcp/tool/automation/${c}AgentToolCallback.java"
  git mv "$SVC/test/java/com/bytechef/ee/platform/aihub/tool/${c}AgentToolCallbackTest.java" \
         "$DST/test/java/com/bytechef/ee/ai/mcp/tool/automation/${c}AgentToolCallbackTest.java"
done
```

- [ ] **Step 4: Rewrite package declarations in the moved files**

```bash
DST=server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/src
grep -rl "package com.bytechef.ee.platform.aihub.tool;" "$DST" \
  | xargs sed -i '' 's/package com.bytechef.ee.platform.aihub.tool;/package com.bytechef.ee.ai.mcp.tool.automation;/'
```

(The helper imports inside these files already point at `com.bytechef.ee.ai.mcp.tool.{usage,util}` after Task 3.)

- [ ] **Step 5: Rewrite the callback imports in AiHubConfiguration**

```bash
sed -i '' 's/com\.bytechef\.ee\.platform\.aihub\.tool\.\(CodeEditor\|WorkflowEditor\|Converter\|ClusterElement\|Skills\|WorkflowExecution\)AgentToolCallback/com.bytechef.ee.ai.mcp.tool.automation.\1AgentToolCallback/g' \
  server/ee/libs/automation/automation-ai-hub/automation-ai-hub-service/src/main/java/com/bytechef/ee/automation/aihub/config/AiHubConfiguration.java
```

(Registration logic and `ProgressReportingToolCallback` wrapping are unchanged.)

- [ ] **Step 6: Add the new module dep to automation-ai-hub-service**

Add to `server/ee/libs/automation/automation-ai-hub/automation-ai-hub-service/build.gradle.kts`:
```kotlin
implementation(project(":server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation"))
```

- [ ] **Step 7: Compile the new module, AI-Hub service, and the donor module**

```bash
./gradlew :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation:compileJava :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation:compileTestJava
./gradlew :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:compileJava
./gradlew :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service:compileJava
```
Expected: all `BUILD SUCCESSFUL` (donor no longer holds the callbacks; only `AiHubConfiguration` referenced them).

- [ ] **Step 8: Run the moved callback tests**

Run: `./gradlew :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation:test`
Expected: 6 `*AgentToolCallbackTest` PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "732 Move 6 copilot agent-tool callbacks to EE mcp-tool-automation"
```

---

## Task 5: Make `ManagementMcpServerConfiguration` a host + 7 direct tools

**Files:**
- Modify: `server/libs/ai/mcp/mcp-server/src/main/java/com/bytechef/ai/mcp/server/config/ManagementMcpServerConfiguration.java`
- Modify: `server/libs/ai/mcp/mcp-server/build.gradle.kts`
- Test: `server/libs/ai/mcp/mcp-server/src/test/java/com/bytechef/ai/mcp/server/config/ManagementMcpServerToolCallbackProviderTest.java`

- [ ] **Step 1: Write the failing test**

Create `server/libs/ai/mcp/mcp-server/src/test/java/com/bytechef/ai/mcp/server/config/ManagementMcpServerToolCallbackProviderTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.ai.mcp.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ai.mcp.tool.McpToolCallbackContributor;
import com.bytechef.ai.mcp.tool.automation.ClusterElementTools;
import com.bytechef.ai.mcp.tool.automation.ProjectTools;
import com.bytechef.ai.mcp.tool.automation.ProjectWorkflowTools;
import com.bytechef.ai.mcp.tool.automation.ScriptTools;
import com.bytechef.ai.mcp.tool.platform.ComponentTools;
import com.bytechef.ai.mcp.tool.platform.TaskDispatcherTools;
import com.bytechef.ai.mcp.tool.platform.TaskTools;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

class ManagementMcpServerToolCallbackProviderTest {

    @Test
    void includesContributedCallbacksAlongsideDirectTools() {
        ToolCallback contributed = mock(ToolCallback.class);

        when(contributed.getToolDefinition()).thenReturn(
            ToolDefinition.builder()
                .name("workflow_editor_agent")
                .description("d")
                .inputSchema("{\"type\":\"object\"}")
                .build());

        McpToolCallbackContributor contributor = () -> List.of(contributed);

        ManagementMcpServerConfiguration configuration = new ManagementMcpServerConfiguration(
            mock(ComponentTools.class), mock(ProjectTools.class), mock(ProjectWorkflowTools.class),
            mock(TaskTools.class), mock(TaskDispatcherTools.class), mock(ScriptTools.class),
            mock(ClusterElementTools.class), List.of(contributor));

        ToolCallbackProvider provider = configuration.toolCallbackProvider();

        List<String> names = java.util.Arrays.stream(provider.getToolCallbacks())
            .map(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .toList();

        assertThat(names).contains("workflow_editor_agent");
    }

    @Test
    void worksWithNoContributors() {
        ManagementMcpServerConfiguration configuration = new ManagementMcpServerConfiguration(
            mock(ComponentTools.class), mock(ProjectTools.class), mock(ProjectWorkflowTools.class),
            mock(TaskTools.class), mock(TaskDispatcherTools.class), mock(ScriptTools.class),
            mock(ClusterElementTools.class), List.of());

        ToolCallbackProvider provider = configuration.toolCallbackProvider();

        assertThat(provider.getToolCallbacks()).isNotNull();
    }
}
```

- [ ] **Step 2: Add test deps + run to confirm it fails to compile**

Add to `server/libs/ai/mcp/mcp-server/build.gradle.kts`:
```kotlin
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
```
Run: `./gradlew :server:libs:ai:mcp:mcp-server:compileTestJava`
Expected: FAIL — constructor signature does not match (old constructor still has 12 params).

- [ ] **Step 3: Rewrite `ManagementMcpServerConfiguration`**

Replace the file body with (keeping the existing transport/server/security beans unchanged — only the imports, fields, constructor, and `toolCallbackProvider()` change). The full file:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.ai.mcp.server.config;

import com.bytechef.ai.mcp.server.security.web.configurer.ManagementMcpServerSecurityConfigurer;
import com.bytechef.ai.mcp.tool.McpToolCallbackContributor;
import com.bytechef.ai.mcp.tool.automation.ClusterElementTools;
import com.bytechef.ai.mcp.tool.automation.ProjectTools;
import com.bytechef.ai.mcp.tool.automation.ProjectWorkflowTools;
import com.bytechef.ai.mcp.tool.automation.ScriptTools;
import com.bytechef.ai.mcp.tool.platform.ComponentTools;
import com.bytechef.ai.mcp.tool.platform.TaskDispatcherTools;
import com.bytechef.ai.mcp.tool.platform.TaskTools;
import com.bytechef.platform.configuration.service.PropertyService;
import com.bytechef.platform.security.service.ApiKeyService;
import com.bytechef.platform.security.web.config.SecurityConfigurerContributor;
import com.bytechef.platform.user.service.AuthorityService;
import com.bytechef.platform.user.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.HttpSecurityBuilder;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Configuration for ByteChef MCP Server using Streamable HTTP transport.
 *
 * This configuration registers a set of deterministic CE automation/platform tools directly, and folds in any
 * {@link McpToolCallbackContributor} beans (EE deployments contribute the Copilot subagent agent-tools). The server is
 * exposed via Streamable HTTP at /api/management/{secretKey}/mcp.
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnProperty(name = "bytechef.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class ManagementMcpServerConfiguration {

    private final ComponentTools componentTools;
    private final ProjectTools projectTools;
    private final ProjectWorkflowTools projectWorkflowTools;
    private final TaskTools taskTools;
    private final TaskDispatcherTools taskDispatcherTools;
    private final ScriptTools scriptTools;
    private final ClusterElementTools clusterElementTools;
    private final List<McpToolCallbackContributor> mcpToolCallbackContributors;

    @SuppressFBWarnings("EI")
    public ManagementMcpServerConfiguration(
        ComponentTools componentTools, ProjectTools projectTools, ProjectWorkflowTools projectWorkflowTools,
        TaskTools taskTools, TaskDispatcherTools taskDispatcherTools, ScriptTools scriptTools,
        ClusterElementTools clusterElementTools, List<McpToolCallbackContributor> mcpToolCallbackContributors) {

        this.componentTools = componentTools;
        this.projectTools = projectTools;
        this.projectWorkflowTools = projectWorkflowTools;
        this.taskTools = taskTools;
        this.taskDispatcherTools = taskDispatcherTools;
        this.scriptTools = scriptTools;
        this.clusterElementTools = clusterElementTools;
        this.mcpToolCallbackContributors = mcpToolCallbackContributors;
    }

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableHttpServerTransportProvider() {
        return WebMvcStreamableServerTransportProvider.builder()
            .mcpEndpoint("/api/management/{secretKey}/mcp")
            .build();
    }

    @Bean
    RouterFunction<ServerResponse> mcpRouterFunction() {
        return webMvcStreamableHttpServerTransportProvider().getRouterFunction();
    }

    @Bean
    McpAsyncServer mcpAsyncServer(ToolCallbackProvider toolCallbackProvider) {
        return McpServer.async(webMvcStreamableHttpServerTransportProvider())
            .serverInfo("mcp-server", "1.0.0")
            .capabilities(
                McpSchema.ServerCapabilities.builder()
                    .resources(false, true)
                    .tools(true)
                    .prompts(true)
                    .logging()
                    .build())
            .tools(McpToolUtils.toAsyncToolSpecifications(toolCallbackProvider.getToolCallbacks()))
            .build();
    }

    /**
     * Direct CE CRUD tools plus every contributed callback. EE deployments contribute the Copilot subagent agent-tools
     * (and SkillsTools) via {@link McpToolCallbackContributor}; CE-only deployments expose just the direct tools.
     */
    @Bean
    @Primary
    ToolCallbackProvider toolCallbackProvider() {
        List<Object> tools = List.of(
            projectTools, projectWorkflowTools, componentTools, taskTools, taskDispatcherTools, scriptTools,
            clusterElementTools);

        List<ToolCallback> toolCallbacks = new ArrayList<>(List.of(ToolCallbacks.from(tools.toArray())));

        for (McpToolCallbackContributor contributor : mcpToolCallbackContributors) {
            toolCallbacks.addAll(contributor.getToolCallbacks());
        }

        return ToolCallbackProvider.from(toolCallbacks);
    }

    @Bean
    SecurityConfigurerContributor mcpServerSecurityConfigurerContributor(
        ApiKeyService apiKeyService, AuthorityService authorityService, PropertyService propertyService,
        UserService userService) {

        return new SecurityConfigurerContributor() {

            @Override
            @SuppressWarnings("unchecked")
            public <T extends AbstractHttpConfigurer<T, B>, B extends HttpSecurityBuilder<B>> T
                getSecurityConfigurerAdapter() {

                return (T) new ManagementMcpServerSecurityConfigurer(
                    apiKeyService, authorityService, propertyService, userService);
            }
        };
    }
}
```

- [ ] **Step 4: Update mcp-server build deps**

Edit `server/libs/ai/mcp/mcp-server/build.gradle.kts`: **remove** the `mcp-tool-integration` line, **add** the CE `mcp-tool-api` line. Resulting project deps:
```kotlin
    implementation(project(":server:libs:ai:mcp:mcp-tool:mcp-tool-api"))
    implementation(project(":server:libs:ai:mcp:mcp-tool:mcp-tool-automation"))
    implementation(project(":server:libs:ai:mcp:mcp-tool:mcp-tool-platform"))
```
(Keep all the non-mcp-tool deps as-is, plus the test deps from Step 2.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :server:libs:ai:mcp:mcp-server:test --tests "*ManagementMcpServerToolCallbackProviderTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add server/libs/ai/mcp/mcp-server
git commit -m "732 Make ManagementMcpServer a host with 7 direct tools + contributor seam"
```

---

## Task 6: Move `SkillsTools`/`ReadSkillsTools` to EE `mcp-tool-automation`

**Files:**
- Move: `SkillsTools.java`, `ReadSkillsTools.java`, `exception/SkillToolErrorType.java`
- Modify: EE `mcp-tool-automation/build.gradle.kts`, CE `mcp-tool-automation/build.gradle.kts`, `CopilotConfiguration.java`, `ai-copilot-service/build.gradle.kts`

- [ ] **Step 1: Move the three files**

```bash
cd /Volumes/Data/bytechef/bytechef
CE=server/libs/ai/mcp/mcp-tool/mcp-tool-automation/src/main/java/com/bytechef/ai/mcp/tool/automation
DST=server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/src/main/java/com/bytechef/ee/ai/mcp/tool/automation
mkdir -p "$DST/exception"
git mv "$CE/SkillsTools.java" "$DST/SkillsTools.java"
git mv "$CE/ReadSkillsTools.java" "$DST/ReadSkillsTools.java"
git mv "$CE/exception/SkillToolErrorType.java" "$DST/exception/SkillToolErrorType.java"
```

- [ ] **Step 2: Rewrite package declarations + the SkillToolErrorType import**

```bash
DST=server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/src/main/java/com/bytechef/ee/ai/mcp/tool/automation
sed -i '' 's/^package com.bytechef.ai.mcp.tool.automation;/package com.bytechef.ee.ai.mcp.tool.automation;/' "$DST/SkillsTools.java" "$DST/ReadSkillsTools.java"
sed -i '' 's/^package com.bytechef.ai.mcp.tool.automation.exception;/package com.bytechef.ee.ai.mcp.tool.automation.exception;/' "$DST/exception/SkillToolErrorType.java"
sed -i '' 's/com.bytechef.ai.mcp.tool.automation.exception.SkillToolErrorType/com.bytechef.ee.ai.mcp.tool.automation.exception.SkillToolErrorType/' "$DST/SkillsTools.java"
```

Add the EE license header + `@version ee` Javadoc tag to all three moved files (they currently carry the Apache header). Replace the Apache header block with the Enterprise header:
```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */
```
and add a `@version ee` line to each class Javadoc. (Spotless keys the header off `@version ee`, so this is required for the files to pass formatting under `server/ee/`.)

- [ ] **Step 3: Add SkillsTools deps to EE mcp-tool-automation**

Add to `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/build.gradle.kts`:
```kotlin
    implementation(project(":server:ee:libs:platform:platform-ai:platform-ai-skill:platform-ai-skill-api"))
    implementation(project(":server:libs:core:exception:exception-api"))
    implementation(project(":server:libs:core:commons:commons-util"))
```

- [ ] **Step 4: Rewrite SkillsTools/ReadSkillsTools imports in CopilotConfiguration**

```bash
sed -i '' \
  -e 's/com.bytechef.ai.mcp.tool.automation.SkillsTools/com.bytechef.ee.ai.mcp.tool.automation.SkillsTools/g' \
  -e 's/com.bytechef.ai.mcp.tool.automation.ReadSkillsTools/com.bytechef.ee.ai.mcp.tool.automation.ReadSkillsTools/g' \
  server/ee/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ee/ai/copilot/config/CopilotConfiguration.java
```

- [ ] **Step 5: Add the EE module dep to ai-copilot-service**

Add to `server/ee/libs/ai/ai-copilot/ai-copilot-service/build.gradle.kts`:
```kotlin
    implementation(project(":server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation"))
```

- [ ] **Step 6: Remove the now-unused platform-ai-skill-api dep from CE mcp-tool-automation**

In `server/libs/ai/mcp/mcp-tool/mcp-tool-automation/build.gradle.kts`, confirm no remaining file imports `com.bytechef.ee.platform.ai.skill` (run the grep below); if none, delete the line:
```kotlin
implementation(project(":server:ee:libs:platform:platform-ai:platform-ai-skill:platform-ai-skill-api"))
```
Check: `grep -rl "com.bytechef.ee.platform.ai.skill" server/libs/ai/mcp/mcp-tool/mcp-tool-automation/src` → expect no output.

- [ ] **Step 7: Compile affected modules**

```bash
./gradlew :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation:compileJava
./gradlew :server:libs:ai:mcp:mcp-tool:mcp-tool-automation:compileJava
./gradlew :server:ee:libs:ai:ai-copilot:ai-copilot-service:compileJava
./gradlew :server:libs:ai:mcp:mcp-server:compileJava
```
Expected: all `BUILD SUCCESSFUL`. (mcp-server no longer references SkillsTools after Task 5, so the move doesn't break it.)

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "732 Move SkillsTools/ReadSkillsTools to EE mcp-tool-automation"
```

---

## Task 7: Add the EE `McpToolCallbackContributor` implementation

**Files:**
- Create: `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/.../McpToolCallbackContributorConfiguration.java`
- Test: `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/.../McpToolCallbackContributorConfigurationTest.java`

- [ ] **Step 1: Write the failing test**

Create `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/src/test/java/com/bytechef/ee/ai/mcp/tool/automation/config/McpToolCallbackContributorConfigurationTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.mcp.tool.automation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.bytechef.ai.mcp.tool.McpToolCallbackContributor;
import com.bytechef.ee.ai.mcp.tool.automation.SkillsTools;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * @version ee
 */
class McpToolCallbackContributorConfigurationTest {

    private final McpToolCallbackContributorConfiguration configuration =
        new McpToolCallbackContributorConfiguration();

    @Test
    void contributesAgentCallbacksWhenChatClientsPresent() {
        McpToolCallbackContributor contributor = configuration.copilotAgentMcpToolCallbackContributor(
            emptyProvider(), present(mock(ChatClient.class)), present(mock(ChatClient.class)),
            present(mock(ChatClient.class)), present(mock(ChatClient.class)), present(mock(ChatClient.class)),
            present(mock(ChatClient.class)));

        // 6 agent callbacks, no SkillsTools
        assertThat(contributor.getToolCallbacks()).hasSize(6);
    }

    @Test
    void contributesNothingWhenAllAbsent() {
        McpToolCallbackContributor contributor = configuration.copilotAgentMcpToolCallbackContributor(
            emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
            emptyProvider());

        assertThat(contributor.getToolCallbacks()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> present(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);

        doAnswer(invocation -> {
            Consumer<T> consumer = invocation.getArgument(0);
            consumer.accept(value);

            return null;
        }).when(provider)
            .ifAvailable(org.mockito.ArgumentMatchers.any());

        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);

        // ifAvailable is a no-op when absent (default mock behavior does nothing)
        return provider;
    }
}
```

- [ ] **Step 2: Run to confirm it fails to compile**

Run: `./gradlew :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation:compileTestJava`
Expected: FAIL — `McpToolCallbackContributorConfiguration` does not exist.

- [ ] **Step 3: Create the contributor configuration**

Create `server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation/src/main/java/com/bytechef/ee/ai/mcp/tool/automation/config/McpToolCallbackContributorConfiguration.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.mcp.tool.automation.config;

import com.bytechef.ai.mcp.tool.McpToolCallbackContributor;
import com.bytechef.ee.ai.mcp.tool.automation.ClusterElementAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.CodeEditorAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.ConverterAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.SkillsAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.SkillsTools;
import com.bytechef.ee.ai.mcp.tool.automation.WorkflowEditorAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.WorkflowExecutionAgentToolCallback;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes the Copilot BUILD subagents (and {@link SkillsTools}) to the management MCP server as high-level
 * agent-tools. Each contribution is gated on bean presence via {@link ObjectProvider#ifAvailable}: the BUILD subagent
 * {@link ChatClient}s exist only when {@code bytechef.ai.copilot.enabled=true}, and {@link SkillsTools} only when the
 * skill stack is present. The {@link ChatClient}s are referenced by their {@link CopilotConfiguration} bean names via
 * {@link Qualifier}, so this module stays decoupled from {@code ai-copilot-service} at compile time.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
class McpToolCallbackContributorConfiguration {

    @Bean
    McpToolCallbackContributor copilotAgentMcpToolCallbackContributor(
        ObjectProvider<SkillsTools> skillsToolsProvider,
        @Qualifier("workflowEditorBuildSubAgentChatClient") ObjectProvider<ChatClient> workflowEditorProvider,
        @Qualifier("codeEditorBuildSubAgentChatClient") ObjectProvider<ChatClient> codeEditorProvider,
        @Qualifier("clusterElementBuildSubAgentChatClient") ObjectProvider<ChatClient> clusterElementProvider,
        @Qualifier("skillsBuildSubAgentChatClient") ObjectProvider<ChatClient> skillsProvider,
        @Qualifier("workflowExecutionBuildSubAgentChatClient") ObjectProvider<ChatClient> workflowExecutionProvider,
        @Qualifier("converterBuildSubAgentChatClient") ObjectProvider<ChatClient> converterProvider) {

        return () -> {
            List<ToolCallback> toolCallbacks = new ArrayList<>();

            skillsToolsProvider.ifAvailable(
                skillsTools -> toolCallbacks.addAll(List.of(ToolCallbacks.from(skillsTools))));

            workflowEditorProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new WorkflowEditorAgentToolCallback(chatClient)));
            codeEditorProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new CodeEditorAgentToolCallback(chatClient)));
            clusterElementProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new ClusterElementAgentToolCallback(chatClient)));
            skillsProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new SkillsAgentToolCallback(chatClient)));
            workflowExecutionProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new WorkflowExecutionAgentToolCallback(chatClient)));
            converterProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new ConverterAgentToolCallback(chatClient)));

            return toolCallbacks;
        };
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation:test --tests "*McpToolCallbackContributorConfigurationTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/ai/mcp/mcp-tool/mcp-tool-automation
git commit -m "732 Contribute copilot BUILD subagents + SkillsTools to MCP server"
```

---

## Task 8: Full verification

- [ ] **Step 1: Format**

Run: `./gradlew spotlessApply`
Expected: `BUILD SUCCESSFUL`; EE files retain the Enterprise header (driven by `@version ee`).

- [ ] **Step 2: Compile the whole server**

Run: `./gradlew compileJava compileTestJava`
Expected: `BUILD SUCCESSFUL`. (Catches any straggler import from the helper move across all modules.)

- [ ] **Step 3: Run the directly-affected module test suites**

Run:
```bash
./gradlew :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api:test \
          :server:ee:libs:ai:mcp:mcp-tool:mcp-tool-automation:test \
          :server:libs:ai:mcp:mcp-server:test \
          :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-api:test \
          :server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service:test \
          :server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service:test
```
Expected: all PASS.

- [ ] **Step 4: Sanity grep — no dangling old FQCNs**

Run:
```bash
grep -rn "com.bytechef.ee.platform.aihub.usage.Agent\|com.bytechef.ee.platform.aihub.usage.CurrentAgentContext\|com.bytechef.ee.platform.aihub.util.LogSanitizer\|com.bytechef.ee.platform.aihub.util.ToolErrors\|com.bytechef.ee.platform.aihub.tool.CodeEditorAgentToolCallback\|com.bytechef.ai.mcp.tool.automation.SkillsTools" server --include="*.java"
```
Expected: **no output** (all references migrated).

- [ ] **Step 5: Commit any spotless changes**

```bash
git add -A
git commit -m "732 Apply spotless formatting after mcp-tool tier refactor" || echo "nothing to commit"
```

---

## Notes for the implementer

- **Build green at every task boundary.** Task 5 (mcp-server) intentionally precedes Task 6 (SkillsTools move) so the CE server stops referencing `SkillsTools` before it leaves CE. Between Task 5 and Task 7 the MCP server has no agent/skills tools yet — that's expected; full functionality lands at Task 7.
- **`@Qualifier` bean names** in Task 7 must exactly match the BUILD subagent bean method names in `CopilotConfiguration`: `workflowEditorBuildSubAgentChatClient`, `codeEditorBuildSubAgentChatClient`, `clusterElementBuildSubAgentChatClient`, `skillsBuildSubAgentChatClient`, `workflowExecutionBuildSubAgentChatClient`, `converterBuildSubAgentChatClient`.
- **`macOS sed`** uses `sed -i ''`. On Linux use `sed -i`.
- If `compileJava` in Task 3 surfaces a same-package straggler not in the Step-5 list, add the matching `com.bytechef.ee.ai.mcp.tool.{usage,util}` import and continue.
```
