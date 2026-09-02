# Script Task Runners Phase 3 — The Docker Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run a script or a set of commands inside a container, reusing every collaborator Phase 2 built, so that the same `perform(input, context)` source runs unchanged under GraalVM, a local process, or Docker.

**Architecture:** Phase 2's `com.bytechef.platform.component.runner.external` package already owns the working directory, the appended bootstrap, bounded output capture and output collection. `DockerTaskRunner` is a fourth consumer of exactly those, differing only in *where* the interpreter runs and *how* files cross the boundary — by tar archive, never by bind mount. It reads its operator configuration through the same fail-closed `TaskRunnerOperatorFlag` seam the process runner uses, and it is invisible until an operator enables it.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Gradle 9.7 (Kotlin DSL), `docker-java` (new — `docker-java-core` + `docker-java-transport-httpclient5`), JUnit 5, Mockito, AssertJ, Testcontainers only for the integration test's daemon gating.

**Spec:** `docs/superpowers/specs/2026-08-31-script-task-runners-design.md`

## Global Constraints

- **CE Apache 2.0 licence header only.** Everything here is under `server/libs/`. No `@version ee` tag — Spotless selects the EE header by that tag's *content*, not by path.
- **`@author Ivica Cardic`** on every new class javadoc.
- **Blank line before control statements**; **blank line after a variable modification** a following statement consumes; **no trailing blank line** before a class's closing `}`; **no `TODO:`** (Checkstyle fails the build).
- **Test method names camelCase, no underscores.** Unit classes end `Test`, integration classes end `IntTest`.
- **Descriptive names**, no `_` prefix on private methods, no gratuitous method chaining.
- **`./gradlew spotlessApply` before every commit.**
- **Testcontainers needs the OrbStack socket on this machine.** Before any `testIntegration` run:
  ```
  export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  ```
  Without these, every integration task fails with `NoSuchFileException (/var/run/docker.sock)` while `docker info` succeeds. That is not a code failure.
- **Never judge a Gradle run through a pipe.** Redirect to a file, check `$?` on its own line, then grep `^> Task .* FAILED`.
- **For every test you write, ask what production change would make it fail.** Phase 2 had seven tests rewritten for passing the wrong reason. If the answer is "nothing", the test is worthless however good it looks.

---

### Task 1: Docker operator settings

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl/build.gradle.kts`
- Create: `.../platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/external/DockerOperatorSettings.java`
- Test: `.../src/test/java/com/bytechef/platform/component/runner/external/DockerOperatorSettingsTest.java`

**Interfaces:**
- Consumes: `ApplicationProperties.Script.Runner#getProperties()` — a `Map<String, String>`, and `TaskRunnerOperatorFlag` for booleans.
- Produces: `static DockerOperatorSettings of(ApplicationProperties applicationProperties)`, with `String getHost()`, `List<String> getAllowedImages()`, `boolean isHostNetworkAllowed()`, `boolean isVolumeMountAllowed()`, `boolean isImageAllowed(String image)`.

The spec's governance block is:

```yaml
docker:
  enabled: false
  host: ""
  allowed-images: []
  allow-host-network: false
  allow-volume-mounts: false
```

`Runner.properties` is `Map<String, String>`, so `allowed-images` arrives as one string. Parse it as a comma-separated list, trimming blanks. Document that in the class javadoc — an operator writing a YAML list under `properties` gets a string either way, and silently reading an empty allowlist as "everything permitted" would be a security hole.

**The allowlist is fail-closed and its empty state is CLOSED, not open.** An operator who enables Docker without naming images gets no runnable image and a message telling them why. This is the opposite of the usual "empty means unrestricted" convention and is deliberate: the alternative lets one config line grant arbitrary image execution.

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.platform.component.runner.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.config.ApplicationProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerOperatorSettingsTest {

    @Test
    void testAbsentRunnerEntryYieldsAClosedConfiguration() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(applicationProperties(null));

        assertThat(settings.getAllowedImages()).isEmpty();
        assertThat(settings.isHostNetworkAllowed()).isFalse();
        assertThat(settings.isVolumeMountAllowed()).isFalse();
        assertThat(settings.isImageAllowed("ubuntu:24.04")).isFalse();
    }

    @Test
    void testAnEmptyAllowlistPermitsNothing() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(applicationProperties(Map.of()));

        assertThat(settings.isImageAllowed("ubuntu:24.04")).isFalse();
    }

    @Test
    void testAllowedImagesAreParsedAsACommaSeparatedList() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", " ubuntu:24.04 , python:3.12-slim ")));

        assertThat(settings.getAllowedImages()).containsExactly("ubuntu:24.04", "python:3.12-slim");
        assertThat(settings.isImageAllowed("ubuntu:24.04")).isTrue();
        assertThat(settings.isImageAllowed("python:3.12-slim")).isTrue();
    }

    @Test
    void testAnImageOutsideTheAllowlistIsRejected() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", "ubuntu:24.04")));

        assertThat(settings.isImageAllowed("alpine:latest")).isFalse();
    }

    @Test
    void testAnUntaggedImageDoesNotMatchATaggedAllowlistEntry() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", "ubuntu:24.04")));

        assertThat(settings.isImageAllowed("ubuntu")).isFalse();
        assertThat(settings.isImageAllowed("ubuntu:latest")).isFalse();
    }

    @Test
    void testAPrefixOfAnAllowedImageIsRejected() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", "ubuntu:24.04")));

        assertThat(settings.isImageAllowed("ubuntu:24.04-evil")).isFalse();
        assertThat(settings.isImageAllowed("evil/ubuntu:24.04")).isFalse();
    }

    @Test
    void testBlankEntriesAreDropped() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", "ubuntu:24.04, ,,python:3.12-slim")));

        assertThat(settings.getAllowedImages()).containsExactly("ubuntu:24.04", "python:3.12-slim");
    }

    @Test
    void testHostDefaultsToEmptyMeaningTheAmbientDaemon() {
        assertThat(DockerOperatorSettings.of(applicationProperties(Map.of()))
            .getHost()).isEmpty();
    }

    @Test
    void testMalformedBooleansFailClosed() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allow-host-network", "yes", "allow-volume-mounts", "")));

        assertThat(settings.isHostNetworkAllowed()).isFalse();
        assertThat(settings.isVolumeMountAllowed()).isFalse();
    }

    private static ApplicationProperties applicationProperties(Map<String, String> properties) {
        ApplicationProperties applicationProperties = new ApplicationProperties();

        ApplicationProperties.Script script = applicationProperties.getScript();

        if (properties == null) {
            script.setRunners(new HashMap<>());

            return applicationProperties;
        }

        ApplicationProperties.Script.Runner runner = new ApplicationProperties.Script.Runner();

        runner.setEnabled(true);
        runner.setProperties(new HashMap<>(properties));

        Map<String, ApplicationProperties.Script.Runner> runners = new HashMap<>();

        runners.put("docker", runner);

        script.setRunners(runners);

        return applicationProperties;
    }
}
```

`testAPrefixOfAnAllowedImageIsRejected` and `testAnUntaggedImageDoesNotMatchATaggedAllowlistEntry` are the two that matter: an allowlist compared with `startsWith` or `contains` instead of equality is a bypass, and that is exactly the mistake this shape of code invites.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*DockerOperatorSettingsTest'`
Expected: FAIL — `DockerOperatorSettings` does not exist.

- [ ] **Step 3: Add the docker-java dependencies**

In `gradle/libs.versions.toml`, add a version and two libraries for `docker-java-core` and `docker-java-transport-httpclient5`. Pin the version explicitly — do NOT rely on whatever Testcontainers drags in transitively; the spec is explicit that the test-only path must not become a production dependency. Check the latest stable release and record which you chose and why in your report.

Add both to `platform-component-runner-impl/build.gradle.kts` as `implementation`.

- [ ] **Step 4: Write the implementation**

Read `TaskRunnerOperatorFlag` in the sibling package first and use it for the two booleans rather than re-parsing them — that class exists precisely so every operator escape hatch fails the same way on half-written configuration.

Implement image matching as **exact string equality against a trimmed, blank-filtered list**. Nothing clever: no prefix matching, no wildcard, no registry normalisation. If an operator wants two tags they list two tags.

- [ ] **Step 5: Run the tests to verify they pass**

Run the same command as Step 2. Expected: PASS, 9 tests.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add gradle/libs.versions.toml server/libs/platform/platform-component/platform-component-runner
git commit -m "3901 Add the Docker runner's operator settings"
```

---

### Task 2: Tar archive transfer

**Files:**
- Create: `.../external/ContainerArchive.java`
- Test: `.../src/test/java/com/bytechef/platform/component/runner/external/ContainerArchiveTest.java`

**Interfaces:**
- Produces: `static InputStream toTar(Path directory) throws IOException` and
  `static void extractTar(InputStream tarStream, Path targetDirectory) throws IOException`.
- Consumed by: Task 3's `DockerTaskRunner`, for `copyArchiveToContainer` / `copyArchiveFromContainer`.

**Files move by archive, not by bind mount.** A bind mount is resolved by the *daemon*: when ByteChef itself runs in a container with the Docker socket mounted, the host path it names does not exist on the daemon's filesystem, and Docker silently mounts an empty directory rather than failing. Archive copy is daemon-location-agnostic and works unchanged against a remote `DOCKER_HOST`.

**THE SECURITY PROPERTY OF THIS TASK IS EXTRACTION SAFETY.** `copyArchiveFromContainer` returns a tar stream produced *inside the container*, which the guest controls completely. An entry named `../../etc/cron.d/x`, an absolute path, or a symlink pointing at a host file are all trivially constructed by the guest. This is Zip Slip, and here the attacker is the workflow author. Phase 2 already found the same class of bug twice in `TaskRunnerOutputs` — do not let it in a third time by a different door.

- [ ] **Step 1: Write the failing test**

Cover, at minimum:
- a round trip: `toTar` a directory containing a nested file, `extractTar` it elsewhere, assert content and relative layout survive;
- an entry named `../escape.txt` is rejected with a message naming the entry, and nothing is written outside the target;
- an entry with an absolute path (`/etc/passwd`) is rejected the same way;
- a symlink entry is not materialised as a link that escapes the target;
- an entry whose name normalises back inside the target (`a/../b.txt`) is accepted, since it does not escape;
- extraction creates missing intermediate directories;
- an empty directory round-trips.

For each rejection test, assert BOTH that it throws AND that the target directory contains no file created by the attempt. A test that only asserts the throw would pass against an implementation that writes the file and then throws.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*ContainerArchiveTest'`
Expected: FAIL — `ContainerArchive` does not exist.

- [ ] **Step 3: Write the implementation**

Use whichever tar implementation docker-java already brings (it depends on Apache Commons Compress) rather than adding a new dependency; confirm what is on the classpath before choosing, and say so in your report.

For every extracted entry, resolve it against the target and then verify with `Path#normalize` and `Path#startsWith` that the result is inside — the same guard `TaskRunnerWorkingDirectory.resolveInside` already uses. Read that method and follow its shape so both places behave identically. Skip symlink and hard-link entries entirely rather than trying to make them safe; record that in the javadoc.

- [ ] **Step 4: Run the tests to verify they pass**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Prove the guard discriminates**

Temporarily remove the containment check, re-run, and confirm the traversal tests fail. Restore. Report what you saw — a guard nobody has watched fail is a guard nobody has tested.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-component/platform-component-runner
git commit -m "3901 Add safe tar transfer for the Docker runner"
```

---

### Task 3: The Docker task runner

**Files:**
- Create: `.../external/DockerTaskRunner.java`
- Modify: `.../platform-component-runner-api/.../TaskRunnerConstants.java` — add the Docker property names
- Test: `.../src/test/java/com/bytechef/platform/component/runner/external/DockerTaskRunnerTest.java`

**Interfaces:**
- Consumes: `TaskRunnerWorkingDirectory`, `TaskRunnerBootstrap`, `ExternalLanguage`, `BoundedOutputCapture`, `TaskRunnerOutputs`, `ContainerArchive` (Task 2), `DockerOperatorSettings` (Task 1).
- Produces: a Spring `@Component` implementing `TaskRunner` with `getType()` returning `TaskRunnerConstants.DOCKER`; `TaskRunnerRegistryImpl` picks it up through its `List<TaskRunner>` injection with no registration anywhere.

Read `ProcessTaskRunner` completely before starting. This class is its sibling: same collaborators, same capability set, same result shape. Differences are confined to *how* the interpreter is reached.

Properties per the spec: `image`, `pullPolicy`, `entrypoint`, `user`, `cpu`, `memory`, `networkMode`, `extraHosts`.

**There is no `volumes` property in v1.** A host bind mount chosen by a workflow author is a direct container escape. It sits behind `allow-volume-mounts` in configuration and is absent from the DSL until that is implemented — so `DockerOperatorSettings.isVolumeMountAllowed()` exists and is read by nothing yet. Say so in the javadoc rather than deleting the setting.

`validate` must reject, before any work starts:
- an `image` outside the operator allowlist, naming the configuration key;
- `networkMode: host` unless `allow-host-network` is set;
- a language `ExternalLanguage` cannot resolve.

The allowlist check in `validate` is the **security boundary**. The image select's options are UX only and a hand-edited workflow bypasses them — the same two-layer arrangement the runner allowlist already uses.

- [ ] **Step 1: Write the failing test**

Unit-testable without a daemon: `getType`, `getTitle`, `getCapabilities` (INLINE_SCRIPT, COMMANDS, INPUT_FILES, OUTPUT_FILES, ENVIRONMENT — no COMPONENT_BRIDGE), `getProperties` returning fresh instances on every call, and every `validate` rejection above. Mock `DockerClient`; do not start a container in a unit test.

Include a test that `getProperties()` omits nothing an enabled operator should see, and one asserting the image property is present even when the allowlist is empty — an operator needs to see the field to learn why nothing runs.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*DockerTaskRunnerTest'`
Expected: FAIL — `DockerTaskRunner` does not exist.

- [ ] **Step 3: Write the implementation**

Lifecycle, in order: pull per `pullPolicy` → create → `copyArchiveToContainer` → start → attach logs → wait with timeout → `copyArchiveFromContainer` for `output/` → **force-remove in a `finally`**.

Requirements carried forward from Phase 2, each of which was a real bug there:
- The working directory is closed in a try-with-resources.
- The container is force-removed in a `finally` that runs on every path including timeout and interruption. A leaked container is this runner's equivalent of Phase 2's leaked process tree.
- The timeout covers the whole lifecycle, not just the wait — an image pull can hang.
- stdout and stderr are captured through `BoundedOutputCapture` and tee'd to the guest logger, exactly as `ProcessTaskRunner` does.
- The result is `TaskRunnerResult.toExternalMap()`'s five keys via the shared constants; do not assemble a map here.
- A non-zero exit fails the task with both stream tails in the message.

- [ ] **Step 4: Run the tests to verify they pass**

Same command as Step 2.

- [ ] **Step 5: Run the module check**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:check > /tmp/docker-check.log 2>&1
echo "EXIT=$?"
grep '^> Task .* FAILED' /tmp/docker-check.log
```

SpotBugs reports go to `build/reports/spotbugs/*.html` — read the HTML; the XML report is disabled in this repo.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-component/platform-component-runner
git commit -m "3901 Add the Docker task runner"
```

---

### Task 4: Per-action image defaults and the integration test

**Files:**
- Modify: `server/libs/modules/components/commands/.../action/CommandsShellAction.java`, `CommandsPythonAction.java`, `CommandsNodeAction.java`
- Create: `.../src/test/java/com/bytechef/platform/component/runner/external/DockerTaskRunnerIntTest.java`
- Modify: `server/libs/modules/components/commands/src/main/resources/README.mdx`

The spec's per-action defaults:

| Action | Docker image | Process interpreter |
|---|---|---|
| `shell` | `ubuntu:24.04` | `/bin/sh` |
| `python` | `python:3.12-slim` | `python3` |
| `node` | `node:22-alpine` | `node` |

These are *defaults for a property the Docker runner owns*, contributed per action. Decide how an action supplies a default for another runner's property and say why in your report — if the SPI has no clean seam for it, report that rather than bolting one on, and default the image in `DockerTaskRunner` by language instead. **Do not invent a component-to-runner coupling to make the table fit**; the derivation-not-exclusion principle that has governed this feature throughout applies here too.

- [ ] **Step 1: Write the integration test**

`DockerTaskRunnerIntTest`, gated on daemon availability with `assumeTrue` so it skips rather than fails where Docker is absent. Cover, per the spec's Testing section:
- an archive round trip: an input file reaches the container and an output file comes back;
- `outputFiles` glob matching;
- a non-zero exit fails the task with the stderr tail;
- an image outside `allowed-images` is rejected;
- a `perform()` return value reaches `output.json` and comes back as the result's `vars`.

Use a small image already common in this repo's tests. Set the two Testcontainers environment variables from the Global Constraints before running.

- [ ] **Step 2: Run it**

```bash
export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:testIntegration > /tmp/docker-int.log 2>&1
echo "EXIT=$?"
grep '^> Task .* FAILED' /tmp/docker-int.log
```

Report whether the tests actually RAN or were skipped by the assumption — a skipped suite is not evidence.

- [ ] **Step 3: Document the runner**

Extend the `commands` README with the Docker runner: its properties, that the image allowlist is fail-closed and empty means nothing runs, that there is no `volumes` property in v1 and why, and the per-action image defaults.

- [ ] **Step 4: Format, verify, commit**

```bash
./gradlew spotlessApply
./gradlew :server:libs:modules:components:commands:check > /tmp/commands-check.log 2>&1
echo "EXIT=$?"
grep '^> Task .* FAILED' /tmp/commands-check.log
git add server/libs/platform/platform-component/platform-component-runner server/libs/modules/components/commands
git commit -m "3901 Add Docker image defaults and the Docker runner integration test"
```
