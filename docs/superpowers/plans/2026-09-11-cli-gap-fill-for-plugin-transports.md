# CLI Gap Fill for Plugin Transports — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every ByteChef operation the `bytechef-dev` plugin skills perform a real `bytechef` CLI command, so the transport tags introduced by the plugin transport routing design have exactly two checkable inventories (MCP tool names, `@Command` paths) and no raw-REST third category.

**Architecture:** Follow the established CLI shape exactly — a generated OpenAPI client module under `cli/clients/` whose sources are committed, and a Spring Shell `@Command` class in an existing `cli/commands/` module. One endpoint the skills use (`POST /api/platform/v1/custom-components/deploy`) has no command; one skill block names an endpoint that no longer exists.

**Tech Stack:** Java 25, Gradle 9.7 Kotlin DSL, Spring Shell (`org.springframework.shell.core.command.annotation`), openapi-generator (`java` generator, `native` library), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-11-claude-plugin-transport-routing-design.md`

## Global Constraints

- Java sources under `server/ee/` and files derived from EE specs use the ByteChef Enterprise license header and an `@version ee` Javadoc tag. Files in `cli/` use the Apache 2.0 header, matching the surrounding CLI modules.
- Run `./gradlew spotlessApply` before every commit. Never judge a Gradle run piped into `tail`/`grep` — redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`.
- Test method names are camelCase with no underscores (Checkstyle enforces this on all methods in test sources, including private helpers).
- Unit test classes end in `Test`; integration test classes end in `IntTest`.
- Generated client sources are committed and deliberately NOT wired to `compileJava` — regenerate manually with that module's `generateClient` task.
- Commit message convention: `<ticket_number> <description>` for server-side, `<ticket_number> client - <description>` for client-side. This plan has no ticket; use a bare imperative description.
- Do not add code comments that explain rationale; rationale belongs in the spec.

## File Structure

| File | Responsibility |
|---|---|
| `claude-code-plugin/bytechef-dev/skills/bytechef-code-workflow/SKILL.md` | Fix the stale deploy endpoint (Task 1) |
| `settings.gradle.kts` | Register the new client module (Task 2) |
| `cli/clients/platform-custom-component/build.gradle.kts` | `generateClient` task pointing at the EE custom-component public spec (Task 2) |
| `cli/clients/platform-custom-component/generated/**` | Committed generated client (Task 2) |
| `cli/commands/component/build.gradle.kts` | Depend on the new client (Task 3) |
| `cli/commands/component/src/main/java/com/bytechef/cli/command/component/ComponentDeployCommand.java` | `bytechef component deploy` (Task 3) |
| `cli/commands/component/src/test/java/com/bytechef/cli/command/component/StubApi.java` | Per-module stub HTTP server, copied from the embedded module (Task 3) |
| `cli/commands/component/src/test/java/com/bytechef/cli/command/component/ComponentDeployCommandTest.java` | Tests for the new command (Task 3) |
| `cli/README.md` | Document the new command (Task 3) |

---

### Task 1: Fix the stale deploy endpoint in the code workflow skill

The skill documents `POST /api/platform/v1/automation-project-code-workflows/deploy`. The CLI client
(`cli/clients/embedded-configuration/generated/.../AutomationProjectCodeWorkflowApi.java`), the CLI test
(`EmbeddedCodeWorkflowCommandTest`) and the server spec
(`server/ee/libs/embedded/embedded-configuration/embedded-configuration-public-rest/openapi.yaml`) all say the
path is under `/api/embedded/v1`. The skill is wrong today.

**Files:**
- Modify: `claude-code-plugin/bytechef-dev/skills/bytechef-code-workflow/SKILL.md:174`
- Modify: `claude-code-plugin/bytechef-dev/skills/bytechef-code-workflow/SKILL.md:194`
- Modify: `claude-code-plugin/bytechef-dev/skills/bytechef-code-workflow/SKILL.md:200`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Later tasks do not depend on this; it is separated because it is a live defect worth
  landing on its own.

- [ ] **Step 1: Confirm the defect**

Run:

```bash
grep -n "automation-project-code-workflows" claude-code-plugin/bytechef-dev/skills/bytechef-code-workflow/SKILL.md
grep -rn "automation-project-code-workflows" cli/commands/embedded/src/main/java/com/bytechef/cli/command/embedded/EmbeddedCodeWorkflowCommand.java
```

Expected: the skill says `/api/platform/v1/...`, the command javadoc says `/api/embedded/v1/...`.

- [ ] **Step 2: Correct the three references**

Replace every `/api/platform/v1/automation-project-code-workflows` in that file with
`/api/embedded/v1/automation-project-code-workflows`. Leave `/api/platform/v1/custom-components/deploy` in the
sibling skill untouched — that path is correct.

- [ ] **Step 3: Verify no stale reference remains**

Run:

```bash
grep -rn "platform/v1/automation-project-code-workflows" claude-code-plugin/
```

Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add claude-code-plugin/bytechef-dev/skills/bytechef-code-workflow/SKILL.md
git commit -m "Correct the embedded code workflow deploy endpoint in the plugin skill"
```

---

### Task 2: Generated client for the platform custom component API

**Files:**
- Modify: `settings.gradle.kts` (add the include next to the other `cli:clients:` entries, around line 31-34)
- Create: `cli/clients/platform-custom-component/build.gradle.kts`
- Create (generated, committed): `cli/clients/platform-custom-component/generated/**`

**Interfaces:**
- Consumes: the spec at
  `server/ee/libs/platform/platform-custom-component/platform-custom-component-configuration/platform-custom-component-configuration-rest/openapi.yaml`
  (`servers: /api/platform/v1`, path `/custom-components/deploy`, `operationId: deployCustomComponent`,
  tag `custom-component`, multipart field `componentFile`, response 204 no content).
- Produces: with `useTags: true` and `modelNameSuffix: Model`, the generator emits
  `com.bytechef.cli.client.platformcustomcomponent.api.CustomComponentApi` with
  `void deployCustomComponent(java.io.File componentFile) throws ApiException`, plus
  `com.bytechef.cli.client.platformcustomcomponent.ApiClient` and
  `com.bytechef.cli.client.platformcustomcomponent.ApiException`. Task 3 consumes exactly these.

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, immediately after `include("cli:clients:embedded-execution")`, add:

```kotlin
include("cli:clients:platform-custom-component")
```

- [ ] **Step 2: Write the client build file**

Create `cli/clients/platform-custom-component/build.gradle.kts`:

```kotlin
plugins {
    id("com.bytechef.java-library-conventions")
    alias(libs.plugins.org.openapi.generator)
}

val generateClient by tasks.registering(org.openapitools.generator.gradle.plugin.tasks.GenerateTask::class) {
    generatorName.set("java")
    library.set("native")
    inputSpec.set(
        "${rootDir}/server/ee/libs/platform/platform-custom-component/" +
            "platform-custom-component-configuration/platform-custom-component-configuration-rest/openapi.yaml"
    )
    outputDir.set("$projectDir/generated")
    apiPackage.set("com.bytechef.cli.client.platformcustomcomponent.api")
    modelPackage.set("com.bytechef.cli.client.platformcustomcomponent.model")
    invokerPackage.set("com.bytechef.cli.client.platformcustomcomponent")
    modelNameSuffix.set("Model")
    configOptions.set(
        mapOf(
            "useJakartaEe" to "true",
            "useTags" to "true",
            "hideGenerationTimestamp" to "true",
            "openApiNullable" to "false"
        )
    )
}

sourceSets.main.get().java.srcDir("$projectDir/generated/src/main/java")

listOf("checkstyleMain", "checkstyleTest", "spotbugsMain", "spotbugsTest").forEach { taskName ->
    tasks.matching { it.name == taskName }
        .configureEach { enabled = false }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("jakarta.annotation:jakarta.annotation-api")

    implementation("org.apache.httpcomponents:httpmime:4.5.14")
}
```

The `httpmime` dependency is not optional — the `native` library uses Apache HttpMime to encode multipart
uploads, and this endpoint is multipart.

- [ ] **Step 3: Generate the client**

Run (never `openApiGenerate`, which is unconfigured and fails with "generator name must be specified"):

```bash
./gradlew :cli:clients:platform-custom-component:generateClient > /tmp/gen.log 2>&1
echo $?
```

Expected: exit 0. Then confirm the API class exists:

```bash
ls cli/clients/platform-custom-component/generated/src/main/java/com/bytechef/cli/client/platformcustomcomponent/api/
```

Expected: `CustomComponentApi.java`.

- [ ] **Step 4: Verify it compiles**

```bash
./gradlew :cli:clients:platform-custom-component:compileJava > /tmp/compile.log 2>&1
echo $?
grep "^> Task .* FAILED" /tmp/compile.log
```

Expected: exit 0, no FAILED lines.

- [ ] **Step 5: Confirm the generated signature matches what Task 3 expects**

```bash
grep -n "public void deployCustomComponent" cli/clients/platform-custom-component/generated/src/main/java/com/bytechef/cli/client/platformcustomcomponent/api/CustomComponentApi.java
```

Expected: a `deployCustomComponent(File componentFile)` overload. If the generator produced a different name or
arity, STOP and record the actual signature — Task 3's code must be adjusted to match rather than the reverse.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo $?
git add settings.gradle.kts cli/clients/platform-custom-component
git commit -m "Add the platform custom component CLI client"
```

---

### Task 3: `bytechef component deploy`

**Files:**
- Modify: `cli/commands/component/build.gradle.kts`
- Create: `cli/commands/component/src/main/java/com/bytechef/cli/command/component/ComponentDeployCommand.java`
- Create: `cli/commands/component/src/test/java/com/bytechef/cli/command/component/StubApi.java`
- Create: `cli/commands/component/src/test/java/com/bytechef/cli/command/component/ComponentDeployCommandTest.java`
- Modify: `cli/README.md` (under "Component scaffolding")

**Interfaces:**
- Consumes: `CustomComponentApi.deployCustomComponent(File)`, `ApiClient`, `ApiException` from Task 2;
  `com.bytechef.cli.core.config.CliConfig`, `com.bytechef.cli.core.error.CliException` and
  `com.bytechef.cli.core.output.OutputRenderer` from `cli:cli-core`; `com.bytechef.cli.CliApplication.execute(String[])`
  returning an `int` exit code, used by the tests.
- Produces: the command path `component deploy`, which the plugin skills will later tag as
  `cli: component deploy`. No other task consumes this.

Read `cli/commands/embedded/src/main/java/com/bytechef/cli/command/embedded/EmbeddedCodeWorkflowCommand.java`
before writing the command — it is the closest existing analogue (a multipart deploy with a file guard) and
defines the `setConfigPath` / `setEnvironmentVariables` test seams and the profile-resolution order that this
command must reproduce.

- [ ] **Step 1: Add the client dependency**

In `cli/commands/component/build.gradle.kts`, add to `dependencies`:

```kotlin
implementation(project(":cli:clients:platform-custom-component"))
```

- [ ] **Step 2: Copy the stub server into this module's test package**

`StubApi` is package-private and already duplicated between `cli/commands/automation` and `cli/commands/embedded`.
Follow that existing duplication rather than extracting a shared module — extraction is out of scope here.

```bash
sed 's/package com.bytechef.cli.command.embedded;/package com.bytechef.cli.command.component;/' \
  cli/commands/embedded/src/test/java/com/bytechef/cli/command/embedded/StubApi.java \
  > cli/commands/component/src/test/java/com/bytechef/cli/command/component/StubApi.java
```

- [ ] **Step 3: Write the failing tests**

Create `cli/commands/component/src/test/java/com/bytechef/cli/command/component/ComponentDeployCommandTest.java`.
Copy the Apache 2.0 license header from `EmbeddedCodeWorkflowCommandTest`.

```java
package com.bytechef.cli.command.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bytechef.cli.CliApplication;
import com.bytechef.cli.core.error.CliException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
@SuppressFBWarnings(
    value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
    justification = "Deliberately nonexistent paths used only to exercise the missing-file guard.")
class ComponentDeployCommandTest {

    @Test
    void testDeployRejectsAMissingFile() {
        ComponentDeployCommand command = new ComponentDeployCommand();

        command.setConfigPath(Path.of("/nonexistent/config"));
        command.setEnvironmentVariables(Map.of());

        CliException exception = assertThrows(
            CliException.class,
            () -> command.componentDeploy(
                "/nonexistent/component.js", "default", "http://localhost:8080", "token", "PRODUCTION"));

        assertEquals(1, exception.exitCode());
    }

    @Test
    void testDeployPostsToThePlatformCustomComponentEndpoint() throws Exception {
        Path componentFile = Files.createTempFile("component", ".js");

        Files.writeString(componentFile, "export default {};");

        try (StubApi stub = StubApi.start(204, "")) {
            int code = CliApplication.execute(new String[] {
                "component", "deploy", "--file", componentFile.toString(), "--host", stub.host(), "--token",
                "btc_x", "--environment", "PRODUCTION"
            });

            assertEquals(0, code);
            assertTrue(
                stub.lastPath()
                    .startsWith("/api/platform/v1/custom-components/deploy"),
                "expected path /api/platform/v1/custom-components/deploy but was " + stub.lastPath());
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

```bash
./gradlew :cli:commands:component:test --tests '*ComponentDeployCommandTest*' > /tmp/t.log 2>&1
echo $?
```

Expected: non-zero exit, with a compilation error naming `ComponentDeployCommand` as not found.

- [ ] **Step 5: Write the command**

Create `cli/commands/component/src/main/java/com/bytechef/cli/command/component/ComponentDeployCommand.java`
with the Apache 2.0 header. Mirror `EmbeddedCodeWorkflowCommand`'s structure: the same `@Option` set, the same
`CliConfig` resolution order (per-command flag, then environment variable, then profile), and the same
missing-file guard that throws `CliException` with exit code 1 *before* any request is sent.

```java
@Command(name = "component deploy", description = "Deploy a custom component to a ByteChef instance.")
public void componentDeploy(
    @Option(longName = "file", required = true) String file,
    @Option(longName = "profile") String profile,
    @Option(longName = "host") String host,
    @Option(longName = "token") String token,
    @Option(longName = "environment") String environment) {

    Path path = Path.of(file);

    if (!Files.exists(path)) {
        throw new CliException("File does not exist: " + file, 1);
    }

    CliConfig cliConfig = CliConfig.resolve(configPath, environmentVariables, profile, host, token, environment);

    CustomComponentApi customComponentApi = new CustomComponentApi(createApiClient(cliConfig));

    try {
        customComponentApi.deployCustomComponent(path.toFile());
    } catch (ApiException apiException) {
        throw CliException.of(apiException);
    }

    OutputRenderer.renderMessage("Custom component deployed: " + path.getFileName());
}
```

Adapt `CliConfig.resolve`, `createApiClient` and `CliException.of` to the exact helper names used by
`EmbeddedCodeWorkflowCommand` — read that file and reuse its idiom verbatim rather than the names above if they
differ. Note the blank line required before each control statement and after each variable modification.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew :cli:commands:component:test --tests '*ComponentDeployCommandTest*' > /tmp/t.log 2>&1
echo $?
grep "^> Task .* FAILED" /tmp/t.log
```

Expected: exit 0, no FAILED lines.

- [ ] **Step 7: Document the command**

In `cli/README.md`, under the "Component scaffolding" section, add below the existing `component init` block:

```bash
# Deploy a custom component (single-file JS/Python/Ruby, or a Java .jar) to a running instance
bytechef component deploy --file ./my-component.js
```

with a following note: the profile token must be an admin platform API key, `.jar` requires `java-enabled` on
the server, and the file extension selects the language server-side.

- [ ] **Step 8: Run the full module check**

```bash
./gradlew :cli:commands:component:check > /tmp/check.log 2>&1
echo $?
grep "^> Task .* FAILED" /tmp/check.log
```

Expected: exit 0, no FAILED lines.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo $?
git add cli/commands/component cli/README.md
git commit -m "Add a CLI command for deploying custom components"
```

---

## Blocked: embedded code integration deploy

`bytechef-code-workflow` also names `POST /api/embedded/internal/integrations/deploy`. This one **cannot** get a
CLI command as the surface stands. `EmbeddedCodeWorkflowCommand`'s own javadoc records why:
`/api/embedded/internal/**` is matched by `EmbeddedApiKeySecurityConfigurer`'s connected-user auth, which
requires a `/v<n>/{externalUserId}/` path segment and grants zero authorities, so a profile bearer token can
never satisfy the facade's `ROLE_ADMIN` guard through it.

The precedent for fixing it exists: `EmbeddedPlatformUserApiKeySecurityConfigurer` already carves
`/automation-project-code-workflows/**` out of that configurer and authenticates the profile token as its own
ByteChef user with real authorities. Extending that carve-out to an integrations-deploy path would make a CLI
command possible.

**This is an authentication-surface widening and is deliberately NOT planned here.** It needs an explicit
decision before any implementation. Until it is taken, that one skill block has no CLI route, and the transport
routing design cannot tag it.

---

## Self-Review

**Spec coverage.** This plan covers only the prerequisite the spec's transport tagging depends on — that every
operation named in a skill resolves to an MCP tool or a `@Command` path. Tasks 2 and 3 close the
custom-component gap; Task 1 removes a stale path that would fail the drift check. The generator, the claude.ai
output, the MCP instruction fragments and `PluginSkillTagDriftIntTest` are NOT in this plan; they belong to a
second plan written against the same spec, which depends on this one.

**Known gap.** The embedded code integration deploy endpoint is unresolved and is called out above rather than
planned around. A second gap is smaller and deliberately left alone: the skills reference
`POST /api/embedded/v1/{externalUserId}/automation/workflow-templates/{workflowUuid}/provision` and the runtime
endpoints `POST /api/embedded/v1/workflows/{workflowUuid}` and `POST /api/embedded/v1/app-events`. The latter
two are the customer application's own runtime surface, not operations Claude performs, so they stay as
reference prose and need no tag.

**Type consistency.** Task 3 consumes exactly the three types Task 2's Interfaces block produces
(`CustomComponentApi`, `ApiClient`, `ApiException` in `com.bytechef.cli.client.platformcustomcomponent`).
Task 2 Step 5 exists specifically to catch a generated signature that differs from the one Task 3 assumes,
before Task 3 is written against it.
