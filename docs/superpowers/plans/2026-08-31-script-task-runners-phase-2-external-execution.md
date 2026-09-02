# Script Task Runners Phase 2 — External Execution and the Commands Component Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute script and command tasks outside the JVM through a real interpreter, by materialising the source into a per-execution working directory, appending a bootstrap that bridges the `perform(input, context)` shape to files, and running it under a new `process` task runner — then expose that shape as a new `commands` component.

**Architecture:** Phase 1 delivered the `TaskRunner` SPI, the config-gated registry and the in-JVM GraalVM runner. Phase 2 adds the *external* half. Four collaborators in a new `com.bytechef.platform.component.runner.external` package carry everything that is common to any out-of-process runner — working directory, bootstrap, bounded output capture — so Phase 3's Docker runner reuses them rather than reimplementing them. `ProcessTaskRunner` is the first consumer. The `script` actions gain the capability-gated `env`/`inputFiles`/`outputFiles`/`timeout` properties, and a new `commands` component offers the same contract for `shell`, `python` and `node`.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Gradle 9.7 (Kotlin DSL), JUnit 5, Mockito, AssertJ. No new third-party dependency — the process runner is pure JDK (`ProcessBuilder`, virtual threads, `ProcessHandle`).

**Spec:** `docs/superpowers/specs/2026-08-31-script-task-runners-design.md` (see also the dependency spec `docs/superpowers/specs/2026-08-31-script-source-expression-evaluation-design.md`)

## Global Constraints

- **CE licence header only.** Every file in this plan is under `server/libs/`, so it takes the Apache 2.0 header. No `@version ee` tag anywhere — Spotless picks the EE header by that tag's *content*, not by path, so adding it to a CE file rewrites the header wrongly.
- **`@author Ivica Cardic`** on every new class javadoc, matching the surrounding modules.
- **Blank line before control statements** (`if`, `for`, `while`, `switch`, `try`) — except immediately after an opening `{`, after `} else {` / `} catch {`, and for short top-of-method guard clauses.
- **Blank line after a variable modification** that a following statement consumes.
- **No trailing blank line** between a class's last member and its closing `}`.
- **No `TODO:` comments** — Checkstyle's `TodoComment` rule fails the build.
- **Test method names are camelCase with no underscores** (`testRunCapturesExitCode`, never `testRun_ExitCode`). The rule covers private helpers in test sources too.
- **Unit test classes end in `Test`; integration test classes end in `IntTest`.** Drop `Impl` from test class names.
- **Descriptive variable names** — no single letters, no `_` prefix on private methods.
- **No method chaining** outside the sanctioned idioms (builders, Streams, `Optional`, assertion DSLs).
- **`./gradlew spotlessApply` before every commit.**
- Verification command for the modules this plan touches:
  `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:check :server:libs:modules:components:script:check --continue`
  Redirect to a file, check `$?` on its own line, then grep `^> Task .* FAILED`. **Never judge a Gradle run through a pipe** — the pipeline's exit code is the filter's, not Gradle's.
- **Two pre-existing failures on `0_732` are not yours.** `:server:ee:libs:platform:platform-user:platform-user-api:compileJava` and `:server:libs:platform:platform-user:platform-user-service:compileTestJava` fail on the base branch, in files this branch does not touch. Do not fix them, and do not treat them as a regression.

---

### Task 1: The per-execution working directory

**Files:**
- Create: `server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/external/TaskRunnerWorkingDirectory.java`
- Test: `server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl/src/test/java/com/bytechef/platform/component/runner/external/TaskRunnerWorkingDirectoryTest.java`

**Interfaces:**
- Consumes: `TaskRunnerRequest` (Phase 1, `com.bytechef.platform.component.runner`) — reads `input()`, `inputFiles()`, `actionContext()`.
- Produces:
  - `static TaskRunnerWorkingDirectory create(TaskRunnerRequest request) throws IOException`
  - `Path getPath()`, `Path getOutputPath()`, `Path getInputFilePath()`
  - `Path writeSourceFile(String fileName, String content) throws IOException`
  - `Map<String, String> getEnvironment()` — the three `BYTECHEF_*` variables
  - `void close()` — recursive delete, best effort; implements `AutoCloseable` with no checked exception

The layout, exactly as the spec fixes it:

```
<tmp>/bytechef-run-<uuid>/
├── script.js | script.py | commands.sh    written later by writeSourceFile
├── input.json
├── <inputFiles…>
└── output/
```

- [ ] **Step 1: Write the failing test**

Create `TaskRunnerWorkingDirectoryTest.java`:

```java
package com.bytechef.platform.component.runner.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRunnerWorkingDirectoryTest {

    @Test
    void testCreateLaysOutTheDirectory() throws IOException {
        try (TaskRunnerWorkingDirectory workingDirectory =
            TaskRunnerWorkingDirectory.create(request(Map.of("name", "world"), Map.of()))) {

            Path path = workingDirectory.getPath();

            assertThat(path).isDirectory();
            assertThat(path.getFileName())
                .asString()
                .startsWith("bytechef-run-");
            assertThat(workingDirectory.getOutputPath()).isDirectory();
            assertThat(workingDirectory.getInputFilePath()).isRegularFile();
            assertThat(Files.readString(workingDirectory.getInputFilePath())).contains("world");
        }
    }

    @Test
    void testEnvironmentNamesTheThreePaths() throws IOException {
        try (TaskRunnerWorkingDirectory workingDirectory =
            TaskRunnerWorkingDirectory.create(request(Map.of(), Map.of()))) {

            Map<String, String> environment = workingDirectory.getEnvironment();

            assertThat(environment).containsOnlyKeys(
                "BYTECHEF_WORKING_DIR", "BYTECHEF_INPUT_FILE", "BYTECHEF_OUTPUT_DIR");
            assertThat(environment).containsEntry(
                "BYTECHEF_WORKING_DIR", workingDirectory.getPath()
                    .toString());
        }
    }

    @Test
    void testInlineInputFileIsMaterialised() throws IOException {
        try (TaskRunnerWorkingDirectory workingDirectory =
            TaskRunnerWorkingDirectory.create(request(Map.of(), Map.of("data.csv", "a,b\n1,2\n")))) {

            Path dataPath = workingDirectory.getPath()
                .resolve("data.csv");

            assertThat(Files.readString(dataPath)).isEqualTo("a,b\n1,2\n");
        }
    }

    @Test
    void testWriteSourceFileReturnsThePathItWrote() throws IOException {
        try (TaskRunnerWorkingDirectory workingDirectory =
            TaskRunnerWorkingDirectory.create(request(Map.of(), Map.of()))) {

            Path sourcePath = workingDirectory.writeSourceFile("script.js", "console.log(1);");

            assertThat(sourcePath).hasFileName("script.js");
            assertThat(Files.readString(sourcePath)).isEqualTo("console.log(1);");
        }
    }

    @Test
    void testCloseRemovesTheDirectoryAndItsContents() throws IOException {
        Path path;

        try (TaskRunnerWorkingDirectory workingDirectory =
            TaskRunnerWorkingDirectory.create(request(Map.of(), Map.of("data.csv", "x")))) {

            path = workingDirectory.getPath();

            workingDirectory.writeSourceFile("script.js", "1");
        }

        assertThat(path).doesNotExist();
    }

    @Test
    void testInputFileNameEscapingTheDirectoryIsRejected() {
        assertThatThrownBy(
            () -> TaskRunnerWorkingDirectory.create(request(Map.of(), Map.of("../escape.txt", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("../escape.txt");
    }

    @Test
    void testAbsoluteInputFileNameIsRejected() {
        assertThatThrownBy(
            () -> TaskRunnerWorkingDirectory.create(request(Map.of(), Map.of("/etc/passwd", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/etc/passwd");
    }

    private static TaskRunnerRequest request(Map<String, ?> input, Map<String, ?> inputFiles) {
        return new TaskRunnerRequest(
            "javascript", "console.log(1);", List.of(), input, Map.of(), inputFiles, List.of(), null, null,
            Duration.ofMinutes(1), Map.of(), mock(ActionContext.class));
    }
}
```

Note the two `null`s: `TaskRunnerRequest`'s `inputParameters` and `runnerParameters` are `Parameters`, and this class reads neither.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*TaskRunnerWorkingDirectoryTest'`
Expected: FAIL — compilation error, `package com.bytechef.platform.component.runner.external does not exist`.

- [ ] **Step 3: Write the implementation**

Create `TaskRunnerWorkingDirectory.java`:

```java
package com.bytechef.platform.component.runner.external;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The directory one external execution runs in, created before it starts and removed after it ends.
 *
 * <p>
 * Every path the guest is told about is inside this directory, and the guest is told about them through the
 * environment rather than through arguments, so the same three names work identically for a process and for a
 * container whose paths differ from the host's.
 *
 * <p>
 * Input file names are validated rather than sanitised. A name is accepted only if it is a single path segment: a
 * workflow author who writes {@code ../../etc/cron.d/x} is asking to write outside the directory, and silently
 * rewriting that to something safe would hide an attack rather than report it.
 *
 * @author Ivica Cardic
 */
public final class TaskRunnerWorkingDirectory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TaskRunnerWorkingDirectory.class);

    private static final String INPUT_FILE_NAME = "input.json";
    private static final String OUTPUT_DIRECTORY_NAME = "output";

    private final Path path;
    private final Path inputFilePath;
    private final Path outputPath;

    private TaskRunnerWorkingDirectory(Path path, Path inputFilePath, Path outputPath) {
        this.path = path;
        this.inputFilePath = inputFilePath;
        this.outputPath = outputPath;
    }

    /**
     * Creates the directory, serialises the request's input and materialises its input files.
     */
    public static TaskRunnerWorkingDirectory create(TaskRunnerRequest request) throws IOException {
        Path path = Files.createTempDirectory("bytechef-run-" + UUID.randomUUID() + "-");

        try {
            Path outputPath = Files.createDirectory(path.resolve(OUTPUT_DIRECTORY_NAME));

            Path inputFilePath = path.resolve(INPUT_FILE_NAME);

            Files.writeString(inputFilePath, JsonUtils.write(request.input()), StandardCharsets.UTF_8);

            writeInputFiles(request, path);

            return new TaskRunnerWorkingDirectory(path, inputFilePath, outputPath);
        } catch (IOException | RuntimeException exception) {
            deleteRecursively(path);

            throw exception;
        }
    }

    @Override
    public void close() {
        deleteRecursively(path);
    }

    public Map<String, String> getEnvironment() {
        return Map.of(
            "BYTECHEF_WORKING_DIR", path.toString(),
            "BYTECHEF_INPUT_FILE", inputFilePath.toString(),
            "BYTECHEF_OUTPUT_DIR", outputPath.toString());
    }

    public Path getInputFilePath() {
        return inputFilePath;
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public Path getPath() {
        return path;
    }

    /**
     * Writes the executable source into the directory and returns the path it was written to.
     */
    public Path writeSourceFile(String fileName, String content) throws IOException {
        Path sourcePath = resolveInside(fileName);

        Files.writeString(sourcePath, content, StandardCharsets.UTF_8);

        return sourcePath;
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(TaskRunnerWorkingDirectory::deleteQuietly);
        } catch (IOException exception) {
            log.warn("Could not remove the task runner working directory {}", path, exception);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Could not remove {}", path, exception);
        }
    }

    private Path resolveInside(String fileName) {
        Path candidate = path.resolve(fileName)
            .normalize();

        if (!candidate.startsWith(path) || candidate.equals(path)) {
            throw new IllegalArgumentException(
                "File name '%s' resolves outside the working directory".formatted(fileName));
        }

        return candidate;
    }

    private static void writeInputFiles(TaskRunnerRequest request, Path path) throws IOException {
        Map<String, ?> inputFiles = request.inputFiles();

        for (Map.Entry<String, ?> entry : inputFiles.entrySet()) {
            String fileName = entry.getKey();

            Path filePath = path.resolve(fileName)
                .normalize();

            if (!filePath.startsWith(path) || filePath.equals(path)) {
                throw new IllegalArgumentException(
                    "File name '%s' resolves outside the working directory".formatted(fileName));
            }

            Files.createDirectories(filePath.getParent());

            Object value = entry.getValue();

            if (value instanceof FileEntry fileEntry) {
                copyFileEntry(request, fileEntry, filePath);
            } else {
                Files.writeString(filePath, String.valueOf(value), StandardCharsets.UTF_8);
            }
        }
    }

    private static void copyFileEntry(TaskRunnerRequest request, FileEntry fileEntry, Path filePath)
        throws IOException {

        Context.File file = request.actionContext()
            .getFile();

        try (InputStream inputStream = file.getInputStream(fileEntry)) {
            Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
```

Two details worth naming. `resolveInside` rejects a candidate equal to the directory itself, so an empty name cannot overwrite the directory entry. And `create` deletes the directory if anything fails partway, so a rejected input file leaves nothing behind.

Before writing this, verify the two APIs it leans on actually have these shapes, and adjust to what the repo has rather than to this text: `com.bytechef.commons.util.JsonUtils.write(Object)`, and how `ActionContext` exposes file storage (`getFile()` versus a `file(...)` accessor). Read `ActionContext`/`Context` in `sdks/backend/java/component-api` and follow it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*TaskRunnerWorkingDirectoryTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl
git commit -m "3901 Add the external execution working directory"
```

---

### Task 2: The appended bootstrap

**Files:**
- Create: `.../platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/external/TaskRunnerBootstrap.java`
- Test: `.../platform-component-runner-impl/src/test/java/com/bytechef/platform/component/runner/external/TaskRunnerBootstrapTest.java`

**Interfaces:**
- Produces: `static String append(String languageId, String source, String runnerType)` and `static String sourceFileName(String languageId)`.
- Consumed by: Task 4's `ProcessTaskRunner`, and Phase 3's Docker runner unchanged.

The bootstrap is **appended, never prepended.** Prepending shifts every line of the user's code, so a syntax error on their line 3 is reported at line 34 of a file they never saw. That is the whole reason this is a separate collaborator with its own test.

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.platform.component.runner.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TaskRunnerBootstrapTest {

    @Test
    void testUserSourceKeepsItsLineNumbers() {
        String source = "function perform(input, context) {\n    return input;\n}\n";

        String appended = TaskRunnerBootstrap.append("javascript", source, "process");

        assertThat(appended).startsWith(source);
    }

    @Test
    void testJavaScriptBootstrapReadsInputAndWritesOutput() {
        String appended = TaskRunnerBootstrap.append("javascript", "function perform() {}\n", "process");

        assertThat(appended).contains("BYTECHEF_INPUT_FILE");
        assertThat(appended).contains("BYTECHEF_OUTPUT_DIR");
        assertThat(appended).contains("output.json");
        assertThat(appended).contains("perform(");
    }

    @Test
    void testPythonBootstrapReadsInputAndWritesOutput() {
        String appended = TaskRunnerBootstrap.append("python", "def perform(input, context):\n    return 1\n", "process");

        assertThat(appended).contains("BYTECHEF_INPUT_FILE");
        assertThat(appended).contains("BYTECHEF_OUTPUT_DIR");
        assertThat(appended).contains("output.json");
        assertThat(appended).contains("perform(");
    }

    @Test
    void testContextStubNamesTheRunnerInItsError() {
        assertThat(TaskRunnerBootstrap.append("javascript", "function perform() {}\n", "docker"))
            .contains("is not available under the docker runner");
        assertThat(TaskRunnerBootstrap.append("python", "def perform(a, b):\n    pass\n", "process"))
            .contains("is not available under the process runner");
    }

    @Test
    void testSourceIsSeparatedFromTheBootstrapByANewline() {
        String appended = TaskRunnerBootstrap.append("javascript", "function perform() {}", "process");

        assertThat(appended).startsWith("function perform() {}\n");
    }

    @Test
    void testSourceFileNamePerLanguage() {
        assertThat(TaskRunnerBootstrap.sourceFileName("javascript")).isEqualTo("script.js");
        assertThat(TaskRunnerBootstrap.sourceFileName("python")).isEqualTo("script.py");
        assertThat(TaskRunnerBootstrap.sourceFileName("shell")).isEqualTo("commands.sh");
    }

    @Test
    void testUnsupportedLanguageIsRejected() {
        assertThatThrownBy(() -> TaskRunnerBootstrap.append("cobol", "x", "process"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cobol");
    }

    @Test
    void testShellNeedsNoBootstrap() {
        assertThat(TaskRunnerBootstrap.append("shell", "echo hi\n", "process")).isEqualTo("echo hi\n");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*TaskRunnerBootstrapTest'`
Expected: FAIL — `TaskRunnerBootstrap` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.bytechef.platform.component.runner.external;

/**
 * Bridges the {@code perform(input, context)} authoring shape to the file-based contract an external interpreter can
 * honour.
 *
 * <p>
 * The generated code is <strong>appended</strong> to the user's source, never prepended. Prepending shifts every line,
 * so a syntax error on the author's third line is reported at the thirtieth line of a file they never wrote - close to
 * undiagnosable from a container log. Appending costs nothing, because both languages resolve {@code perform} at call
 * time from a definition that appears above.
 *
 * <p>
 * {@code context} is a stub rather than {@code null}. Passing null would surface as
 * {@code TypeError: Cannot read properties of null}, which says nothing about why; the stub raises a sentence naming
 * the runner and the fact that the component bridge is in-process only.
 *
 * @author Ivica Cardic
 */
public final class TaskRunnerBootstrap {

    private static final String JAVASCRIPT = "javascript";
    private static final String PYTHON = "python";
    private static final String SHELL = "shell";

    private TaskRunnerBootstrap() {
    }

    /**
     * Returns the user's source with the language's bootstrap appended, or the source unchanged for a language that
     * needs none.
     */
    public static String append(String languageId, String source, String runnerType) {
        String separated = source.endsWith("\n") ? source : source + "\n";

        return switch (languageId) {
            case JAVASCRIPT -> separated + javaScriptBootstrap(runnerType);
            case PYTHON -> separated + pythonBootstrap(runnerType);
            case SHELL -> separated;
            default -> throw new IllegalArgumentException(
                "Language '%s' cannot be executed by an external task runner".formatted(languageId));
        };
    }

    /**
     * The name the source is written under inside the working directory.
     */
    public static String sourceFileName(String languageId) {
        return switch (languageId) {
            case JAVASCRIPT -> "script.js";
            case PYTHON -> "script.py";
            case SHELL -> "commands.sh";
            default -> throw new IllegalArgumentException(
                "Language '%s' cannot be executed by an external task runner".formatted(languageId));
        };
    }

    private static String javaScriptBootstrap(String runnerType) {
        return """

            /* ByteChef bootstrap - appended, so the lines above keep their numbers. */
            (async function bytechefMain() {
                const bytechefFs = require('fs');
                const bytechefPath = require('path');
                const bytechefInput = JSON.parse(
                    bytechefFs.readFileSync(process.env.BYTECHEF_INPUT_FILE, 'utf8'));
                const bytechefContext = new Proxy({}, {
                    get(target, property) {
                        throw new Error(
                            'context.' + String(property) + ' is not available under the %s runner');
                    }
                });
                const bytechefResult = await perform(bytechefInput, bytechefContext);

                bytechefFs.writeFileSync(
                    bytechefPath.join(process.env.BYTECHEF_OUTPUT_DIR, 'output.json'),
                    JSON.stringify(bytechefResult === undefined ? null : bytechefResult));
            })().catch(function (error) {
                console.error(error && error.stack ? error.stack : String(error));
                process.exit(1);
            });
            """.formatted(runnerType);
    }

    private static String pythonBootstrap(String runnerType) {
        return """

            # ByteChef bootstrap - appended, so the lines above keep their numbers.
            def _bytechef_main():
                import json
                import os

                class _BytechefContext:
                    def __getattr__(self, name):
                        raise RuntimeError(
                            "context." + name + " is not available under the %s runner")

                with open(os.environ["BYTECHEF_INPUT_FILE"], "r") as bytechef_input_file:
                    bytechef_input = json.load(bytechef_input_file)

                bytechef_result = perform(bytechef_input, _BytechefContext())

                bytechef_output_path = os.path.join(os.environ["BYTECHEF_OUTPUT_DIR"], "output.json")

                with open(bytechef_output_path, "w") as bytechef_output_file:
                    json.dump(bytechef_result, bytechef_output_file)


            _bytechef_main()
            """.formatted(runnerType);
    }
}
```

`_bytechef_main` and `_BytechefContext` keep their underscore prefixes: these are generated Python, where a leading underscore is the language's own convention for "not part of the public surface", and the project's no-underscore rule governs Java method names. Java's own naming here (`javaScriptBootstrap`, `pythonBootstrap`) follows the project rule.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*TaskRunnerBootstrapTest'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl
git commit -m "3901 Add the appended external execution bootstrap"
```

---

### Task 3: Bounded output capture

**Files:**
- Create: `.../platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/external/BoundedOutputCapture.java`
- Test: `.../platform-component-runner-impl/src/test/java/com/bytechef/platform/component/runner/external/BoundedOutputCaptureTest.java`

**Interfaces:**
- Produces: `new BoundedOutputCapture()`, `void append(byte[] buffer, int length)`, `String get()`, `long getTotalLength()`.
- Consumed by: Task 4 and Phase 3.

A process that writes megabytes must not put megabytes into a task's output. The spec fixes the shape: keep the first 32 KiB and the last 32 KiB, elide the middle, name how much was elided. Both halves matter — the head carries the startup banner that says what ran, the tail carries the failure.

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.platform.component.runner.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedOutputCaptureTest {

    @Test
    void testShortOutputIsReturnedVerbatim() {
        BoundedOutputCapture capture = new BoundedOutputCapture();

        append(capture, "hello world");

        assertThat(capture.get()).isEqualTo("hello world");
        assertThat(capture.getTotalLength()).isEqualTo(11);
    }

    @Test
    void testOutputExactlyAtTheLimitIsReturnedVerbatim() {
        BoundedOutputCapture capture = new BoundedOutputCapture();

        String text = "a".repeat(64 * 1024);

        append(capture, text);

        assertThat(capture.get()).isEqualTo(text);
    }

    @Test
    void testLongOutputKeepsBothEndsAndElidesTheMiddle() {
        BoundedOutputCapture capture = new BoundedOutputCapture();

        append(capture, "HEAD" + "x".repeat(200 * 1024) + "TAIL");

        String captured = capture.get();

        assertThat(captured).startsWith("HEAD");
        assertThat(captured).endsWith("TAIL");
        assertThat(captured).contains("elided");
        assertThat(captured.length()).isLessThan(70 * 1024);
        assertThat(capture.getTotalLength()).isEqualTo(200 * 1024 + 8);
    }

    @Test
    void testManySmallAppendsBehaveLikeOneLargeOne() {
        BoundedOutputCapture chunked = new BoundedOutputCapture();
        BoundedOutputCapture whole = new BoundedOutputCapture();

        String text = "HEAD" + "y".repeat(200 * 1024) + "TAIL";

        for (int index = 0; index < text.length(); index += 997) {
            append(chunked, text.substring(index, Math.min(index + 997, text.length())));
        }

        append(whole, text);

        assertThat(chunked.get()).isEqualTo(whole.get());
    }

    @Test
    void testEmptyCaptureIsAnEmptyString() {
        assertThat(new BoundedOutputCapture().get()).isEmpty();
    }

    private static void append(BoundedOutputCapture capture, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        capture.append(bytes, bytes.length);
    }
}
```

The chunked-versus-whole test is the one that matters: it is the only one that fails if the ring buffer's wrap-around is wrong, and stream draining always arrives in arbitrary chunk sizes.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*BoundedOutputCaptureTest'`
Expected: FAIL — `BoundedOutputCapture` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.bytechef.platform.component.runner.external;

import java.io.ByteArrayOutputStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/**
 * Keeps the head and the tail of a stream that may be arbitrarily long.
 *
 * <p>
 * A runaway process can emit gigabytes, and a task's stored output cannot. Truncating the tail loses the failure;
 * truncating the head loses the banner that says what actually ran. So both ends are kept and the middle is elided,
 * with a marker stating how much went.
 *
 * <p>
 * The kept bytes are decoded as UTF-8 with malformed input replaced, because an elision boundary can fall inside a
 * multi-byte character. Losing one character to a replacement marker at the cut is the correct trade against refusing
 * to decode.
 *
 * @author Ivica Cardic
 */
public final class BoundedOutputCapture {

    private static final int HALF_LIMIT = 32 * 1024;

    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private final byte[] tail = new byte[HALF_LIMIT];

    private int tailLength;
    private int tailPosition;
    private long totalLength;

    /**
     * Appends the first {@code length} bytes of {@code buffer}.
     */
    public synchronized void append(byte[] buffer, int length) {
        for (int index = 0; index < length; index++) {
            byte value = buffer[index];

            if (head.size() < HALF_LIMIT) {
                head.write(value);
            } else {
                tail[tailPosition] = value;
                tailPosition = (tailPosition + 1) % HALF_LIMIT;

                if (tailLength < HALF_LIMIT) {
                    tailLength++;
                }
            }
        }

        totalLength += length;
    }

    /**
     * Returns the captured text, with the elided middle marked when the stream exceeded the limit.
     */
    public synchronized String get() {
        byte[] headBytes = head.toByteArray();

        if (tailLength == 0) {
            return decode(headBytes);
        }

        byte[] tailBytes = orderedTail();

        long elided = totalLength - headBytes.length - tailLength;

        if (elided <= 0) {
            return decode(headBytes) + decode(tailBytes);
        }

        return decode(headBytes)
            + "%n... %d bytes elided ...%n".formatted(elided)
            + decode(tailBytes);
    }

    public synchronized long getTotalLength() {
        return totalLength;
    }

    private byte[] orderedTail() {
        byte[] ordered = new byte[tailLength];

        int start = tailLength < HALF_LIMIT ? 0 : tailPosition;

        for (int index = 0; index < tailLength; index++) {
            ordered[index] = tail[(start + index) % HALF_LIMIT];
        }

        return ordered;
    }

    private static String decode(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);

        try {
            CharBuffer charBuffer = decoder.decode(ByteBuffer.wrap(bytes));

            return charBuffer.toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
```

`"%n"` in the marker renders as the platform line separator through `formatted`; the test asserts only that `elided` appears, so either separator satisfies it.

The byte-at-a-time loop is deliberate: it is obviously correct across chunk boundaries, and it runs at stream-drain speed on output that is by construction bounded by what a process can emit through a pipe. If profiling ever shows it matters, `System.arraycopy` over the two ring segments is the replacement — but write it only against the chunked-equals-whole test.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*BoundedOutputCaptureTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl
git commit -m "3901 Add bounded output capture for external executions"
```

---

### Task 4: The process task runner

**Files:**
- Create: `.../platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/external/ProcessTaskRunner.java`
- Modify: `server/libs/platform/platform-component/platform-component-runner/platform-component-runner-api/src/main/java/com/bytechef/platform/component/runner/TaskRunnerConstants.java` — add `INTERPRETER`, `INHERIT_ENVIRONMENT`
- Test: `.../platform-component-runner-impl/src/test/java/com/bytechef/platform/component/runner/external/ProcessTaskRunnerTest.java`

**Interfaces:**
- Consumes: `TaskRunnerWorkingDirectory` (Task 1), `TaskRunnerBootstrap` (Task 2), `BoundedOutputCapture` (Task 3), the `TaskRunner` SPI (Phase 1).
- Produces: a Spring `@Component` bean implementing `TaskRunner` with `getType()` returning `TaskRunnerConstants.PROCESS`; picked up automatically by `TaskRunnerRegistryImpl`'s `List<TaskRunner>` injection.

Four things this task must get right, each of which is a real bug if missed:

1. **Streams are drained concurrently with `waitFor`.** A process writing past the OS pipe buffer (~64 KiB) blocks forever if the caller waits first and reads after. Drain on two virtual threads started before the wait.
2. **`descendants()` is collected before the parent is destroyed.** Once the parent dies the handle's descendant list is empty, so `sh -c` children survive as orphans. Snapshot first, then kill children, then the parent.
3. **The environment is cleared, not inherited** — the whole point is that datasource passwords in the server's environment cannot reach a user's script. `PATH` and `HOME` are re-seeded deliberately (see below).
4. **The working directory is closed in a `finally`.**

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.platform.component.runner.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessTaskRunnerTest {

    private final ProcessTaskRunner processTaskRunner = new ProcessTaskRunner();

    @Test
    void testGetTypeIsProcess() {
        assertThat(processTaskRunner.getType()).isEqualTo("process");
    }

    @Test
    void testShellCommandsRunAndCaptureStdout() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerResult result = processTaskRunner.run(commandsRequest(List.of("echo hello-from-shell")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello-from-shell");
    }

    @Test
    void testNonZeroExitFailsWithTheStderrTailInTheMessage() {
        assumeTrue(isExecutable("/bin/sh"));

        assertThatThrownBy(
            () -> processTaskRunner.run(commandsRequest(List.of("echo boom 1>&2", "exit 3"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("boom");
    }

    @Test
    void testLargeStdoutDoesNotDeadlock() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerResult result = processTaskRunner.run(
            commandsRequest(List.of("i=0; while [ $i -lt 4000 ]; do echo 0123456789012345678901234567890123456789; i=$((i+1)); done")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isNotEmpty();
    }

    @Test
    void testHostEnvironmentIsNotInherited() {
        assumeTrue(isExecutable("/bin/sh"));

        String hostHome = System.getenv("HOME");

        assumeTrue(hostHome != null && !hostHome.isBlank());

        TaskRunnerResult result = processTaskRunner.run(commandsRequest(List.of("echo \"[$HOME]\"")));

        assertThat(result.stdout()).doesNotContain("[" + hostHome + "]");
    }

    @Test
    void testDeclaredEnvironmentReachesTheProcess() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("echo \"[$GREETING]\""), Map.of(), Map.of("GREETING", "hi"), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), Duration.ofSeconds(30), Map.of(),
            mock(ActionContext.class));

        assertThat(processTaskRunner.run(request)
            .stdout()).contains("[hi]");
    }

    @Test
    void testTimeoutKillsTheProcess() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("sleep 30"), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), Duration.ofSeconds(2), Map.of(),
            mock(ActionContext.class));

        assertThatThrownBy(() -> processTaskRunner.run(request))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("timed out");
    }

    @Test
    void testValidateRejectsAnEmptyInterpreterOverride() {
        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("echo hi"), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of("interpreter", "   ")),
            Duration.ofSeconds(5), Map.of(), mock(ActionContext.class));

        assertThatThrownBy(() -> processTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testCapabilitiesExcludeTheComponentBridge() {
        assertThat(processTaskRunner.getCapabilities())
            .doesNotContain(com.bytechef.platform.component.runner.TaskRunnerCapability.COMPONENT_BRIDGE);
    }

    @Test
    void testGetPropertiesReturnsFreshInstances() {
        assertThat(processTaskRunner.getProperties()
            .getFirst())
                .isNotSameAs(
                    processTaskRunner.getProperties()
                        .getFirst());
    }

    private static TaskRunnerRequest commandsRequest(List<String> commands) {
        return new TaskRunnerRequest(
            "shell", null, commands, Map.of(), Map.of(), Map.of(), List.of(), ParametersFactory.create(Map.of()),
            ParametersFactory.create(Map.of()), Duration.ofSeconds(30), Map.of(), mock(ActionContext.class));
    }

    private static boolean isExecutable(String path) {
        return java.nio.file.Files.isExecutable(java.nio.file.Path.of(path));
    }
}
```

`testGetPropertiesReturnsFreshInstances` exists because `TaskRunnerPropertyFactory` throws at definition-assembly time for a runner that caches its properties — this asserts the contract at the runner rather than discovering it through a component's definition test.

`testHostEnvironmentIsNotInherited` asserts against the host's real `HOME` rather than against an
unset variable. A test that checks some invented name is absent passes whether or not the environment
was cleared, because that name was never set anywhere; asserting the child's `HOME` differs from the
host user's is the version that actually fails when the clear is removed. `PATH` cannot serve here — it
is re-seeded on purpose.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*ProcessTaskRunnerTest'`
Expected: FAIL — `ProcessTaskRunner` does not exist.

- [ ] **Step 3: Add the two constants**

In `TaskRunnerConstants.java`, beside the existing `MODE`/`STRICT`/`TRUSTED`:

```java
    public static final String INHERIT_ENVIRONMENT = "inheritEnvironment";
    public static final String INTERPRETER = "interpreter";
```

- [ ] **Step 4: Write the implementation**

```java
package com.bytechef.platform.component.runner.external;

import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.INHERIT_ENVIRONMENT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.INTERPRETER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.PROCESS;

import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.platform.component.runner.TaskRunner;
import com.bytechef.platform.component.runner.TaskRunnerCapability;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Runs the task in a child process on the same host.
 *
 * <p>
 * The environment the child sees is built from nothing: the inherited environment is cleared, and only the working
 * directory's three {@code BYTECHEF_*} names, the declared {@code env} entries, and a deliberately re-seeded
 * {@code PATH} and {@code HOME} are put back. Clearing is the point - the server process holds datasource passwords
 * and cloud credentials, and a workflow author's script must not be able to read them by name.
 *
 * <p>
 * {@code PATH} is re-seeded because a relative interpreter name such as {@code node} cannot be resolved without it,
 * and {@code HOME} because several interpreters write caches relative to it and fall over when it is unset; it points
 * at the working directory, so those caches die with the execution. Neither is a credential.
 *
 * @author Ivica Cardic
 */
@Component
@SuppressFBWarnings("EI")
public class ProcessTaskRunner implements TaskRunner {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final int DRAIN_BUFFER_SIZE = 8 * 1024;

    private static final Map<String, String> DEFAULT_INTERPRETERS = Map.of(
        "javascript", "node",
        "python", "python3",
        "shell", "/bin/sh");

    @Override
    public String getType() {
        return PROCESS;
    }

    @Override
    public String getTitle() {
        return "Process";
    }

    @Override
    public List<? extends ModifiableValueProperty<?, ?>> getProperties() {
        return List.of(
            string(INTERPRETER)
                .label("Interpreter")
                .description(
                    "The executable the source is handed to. Defaults to the interpreter for the action's language.")
                .required(false),
            bool(INHERIT_ENVIRONMENT)
                .label("Inherit Environment")
                .description(
                    "Passes the server process's own environment to the execution. Off by default, because that " +
                        "environment holds the server's credentials.")
                .defaultValue(false)
                .required(false));
    }

    @Override
    public Set<TaskRunnerCapability> getCapabilities() {
        return Set.of(
            TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.COMMANDS, TaskRunnerCapability.INPUT_FILES,
            TaskRunnerCapability.OUTPUT_FILES, TaskRunnerCapability.ENVIRONMENT);
    }

    @Override
    public void validate(TaskRunnerRequest request) {
        String interpreter = request.runnerParameters()
            .getString(INTERPRETER);

        if (interpreter != null && interpreter.isBlank()) {
            throw new IllegalArgumentException("The process runner's interpreter must not be blank");
        }

        resolveInterpreter(request);
    }

    @Override
    public TaskRunnerResult run(TaskRunnerRequest request) {
        validate(request);

        try (TaskRunnerWorkingDirectory workingDirectory = TaskRunnerWorkingDirectory.create(request)) {
            return run(request, workingDirectory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();

            throw new IllegalStateException("Interrupted while running the process task runner", exception);
        }
    }

    private TaskRunnerResult run(TaskRunnerRequest request, TaskRunnerWorkingDirectory workingDirectory)
        throws IOException, InterruptedException {

        String languageId = request.languageId();

        String source = request.script() != null
            ? TaskRunnerBootstrap.append(languageId, request.script(), PROCESS)
            : String.join(System.lineSeparator(), request.commands()) + System.lineSeparator();

        Path sourcePath = workingDirectory.writeSourceFile(
            TaskRunnerBootstrap.sourceFileName(languageId), source);

        ProcessBuilder processBuilder = new ProcessBuilder(
            resolveInterpreter(request), sourcePath.toString());

        processBuilder.directory(
            workingDirectory.getPath()
                .toFile());

        applyEnvironment(processBuilder, request, workingDirectory);

        Process process = processBuilder.start();

        BoundedOutputCapture stdout = new BoundedOutputCapture();
        BoundedOutputCapture stderr = new BoundedOutputCapture();

        Thread stdoutThread = drain(process.getInputStream(), stdout);
        Thread stderrThread = drain(process.getErrorStream(), stderr);

        Duration timeout = request.timeout() == null ? DEFAULT_TIMEOUT : request.timeout();

        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

        if (!exited) {
            destroyTree(process);

            stdoutThread.join();
            stderrThread.join();

            throw new IllegalStateException(
                "The process task runner timed out after %s; stderr tail: %s".formatted(timeout, stderr.get()));
        }

        stdoutThread.join();
        stderrThread.join();

        int exitCode = process.exitValue();

        if (exitCode != 0) {
            throw new IllegalStateException(
                "The process exited with code %d; stderr tail: %s".formatted(exitCode, stderr.get()));
        }

        return new TaskRunnerResult(
            TaskRunnerOutputs.readOutputJson(workingDirectory), exitCode, stdout.get(), stderr.get(),
            TaskRunnerOutputs.collectOutputFiles(request, workingDirectory));
    }

    private static void applyEnvironment(
        ProcessBuilder processBuilder, TaskRunnerRequest request, TaskRunnerWorkingDirectory workingDirectory) {

        Map<String, String> environment = processBuilder.environment();

        boolean inherit = request.runnerParameters()
            .getBoolean(INHERIT_ENVIRONMENT, false);

        if (!inherit) {
            String path = environment.get("PATH");

            environment.clear();

            if (path != null) {
                environment.put("PATH", path);
            }
        }

        environment.put(
            "HOME", workingDirectory.getPath()
                .toString());
        environment.putAll(workingDirectory.getEnvironment());
        environment.putAll(request.env());
    }

    private static Thread drain(InputStream inputStream, BoundedOutputCapture capture) {
        return Thread.ofVirtual()
            .start(() -> {
                byte[] buffer = new byte[DRAIN_BUFFER_SIZE];

                try (InputStream stream = inputStream) {
                    int read = stream.read(buffer);

                    while (read != -1) {
                        capture.append(buffer, read);

                        read = stream.read(buffer);
                    }
                } catch (IOException exception) {
                    // the process died mid-stream; whatever was captured before that is what the task reports
                    capture.append(new byte[0], 0);
                }
            });
    }

    /**
     * Kills the process and everything it started.
     *
     * <p>
     * The descendant handles are snapshotted <strong>before</strong> the parent is destroyed. A dead parent reports no
     * descendants, so destroying it first leaves a {@code sh -c} wrapper's children running as orphans holding the
     * working directory open.
     */
    private static void destroyTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(
            process.descendants()
                .toList());

        for (ProcessHandle descendant : descendants) {
            descendant.destroyForcibly();
        }

        process.destroyForcibly();
    }

    private static String resolveInterpreter(TaskRunnerRequest request) {
        String interpreter = request.runnerParameters()
            .getString(INTERPRETER);

        if (interpreter != null && !interpreter.isBlank()) {
            return interpreter;
        }

        String languageId = request.languageId();

        String defaultInterpreter = DEFAULT_INTERPRETERS.get(languageId);

        if (defaultInterpreter == null) {
            throw new IllegalArgumentException(
                "The process runner has no default interpreter for language '%s'; set one on the task runner"
                    .formatted(languageId));
        }

        return defaultInterpreter;
    }
}
```

The empty `capture.append(new byte[0], 0)` in the drain's catch block exists because Checkstyle's `EmptyBlock` rule is not satisfied by a comment alone; it needs an executable statement. Keep the comment too.

- [ ] **Step 5: Write the output collector this runner calls**

`TaskRunnerOutputs` is referenced above and does not exist yet. Create
`.../external/TaskRunnerOutputs.java`:

```java
package com.bytechef.platform.component.runner.external;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Reads what an external execution left behind in its output directory.
 *
 * @author Ivica Cardic
 */
final class TaskRunnerOutputs {

    private static final String OUTPUT_JSON = "output.json";

    private TaskRunnerOutputs() {
    }

    /**
     * Returns the parsed {@code output.json}, or null when the execution wrote none.
     */
    @Nullable
    static Object readOutputJson(TaskRunnerWorkingDirectory workingDirectory) throws IOException {
        Path outputJsonPath = workingDirectory.getOutputPath()
            .resolve(OUTPUT_JSON);

        if (!Files.isRegularFile(outputJsonPath)) {
            return null;
        }

        String json = Files.readString(outputJsonPath, StandardCharsets.UTF_8);

        if (json.isBlank()) {
            return null;
        }

        return JsonUtils.read(json);
    }

    /**
     * Stores every file under the output directory matching one of the request's glob patterns, keyed by its path
     * relative to that directory.
     */
    static Map<String, FileEntry> collectOutputFiles(
        TaskRunnerRequest request, TaskRunnerWorkingDirectory workingDirectory) throws IOException {

        List<String> patterns = request.outputFilePatterns();

        if (patterns.isEmpty()) {
            return Map.of();
        }

        List<PathMatcher> matchers = patterns.stream()
            .map(pattern -> FileSystems.getDefault()
                .getPathMatcher("glob:" + pattern))
            .toList();

        Path outputPath = workingDirectory.getOutputPath();

        Map<String, FileEntry> outputFiles = new LinkedHashMap<>();

        Context.File file = request.actionContext()
            .getFile();

        try (Stream<Path> paths = Files.walk(outputPath)) {
            List<Path> candidates = paths.filter(Files::isRegularFile)
                .sorted()
                .toList();

            for (Path candidate : candidates) {
                Path relative = outputPath.relativize(candidate);

                if (OUTPUT_JSON.equals(relative.toString())) {
                    continue;
                }

                boolean matched = matchers.stream()
                    .anyMatch(matcher -> matcher.matches(relative));

                if (!matched) {
                    continue;
                }

                try (InputStream inputStream = Files.newInputStream(candidate)) {
                    outputFiles.put(relative.toString(), file.storeContent(relative.toString(), inputStream));
                }
            }
        }

        return outputFiles;
    }
}
```

`output.json` is excluded from `outputFiles` on purpose: it is already surfaced as the result's `output`, and a `**` pattern would otherwise store it twice.

Confirm `JsonUtils.read(String)` exists with that shape before using it; if the repo's accessor differs, follow the repo.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*ProcessTaskRunnerTest'`
Expected: PASS. Tests that need `/bin/sh` skip rather than fail where it is absent.

- [ ] **Step 7: Run the module's whole check**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:check > /tmp/runner-check.log 2>&1
echo "EXIT=$?"
grep '^> Task .* FAILED' /tmp/runner-check.log
```

Expected: EXIT=0, no FAILED lines. SpotBugs reports go to `build/reports/spotbugs/*.html` — read the HTML, not the XML, which is disabled in this repo.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-component/platform-component-runner
git commit -m "3901 Add the process task runner"
```

---

### Task 5: The external properties on the script actions

**Files:**
- Modify: `.../platform-component-runner-api/src/main/java/com/bytechef/platform/component/runner/TaskRunnerPropertyFactory.java`
- Modify: `.../platform-component-runner-api/src/main/java/com/bytechef/platform/component/runner/TaskRunnerConstants.java`
- Modify: `server/libs/modules/components/script/src/main/java/com/bytechef/component/script/action/ScriptJavaScriptAction.java`
- Modify: `server/libs/modules/components/script/src/main/java/com/bytechef/component/script/action/ScriptPythonAction.java`
- Modify: `server/libs/modules/components/script/src/main/java/com/bytechef/component/script/action/definition/ScriptActionDefinition.java`
- Test: `.../platform-component-runner-api/src/test/java/com/bytechef/platform/component/runner/TaskRunnerPropertyFactoryTest.java` (extend)
- Regenerate: `server/libs/modules/components/script/src/test/resources/definition/script_v1.json`

**Interfaces:**
- Produces: `static List<ModifiableValueProperty<?, ?>> externalProperties(TaskRunnerRegistry taskRunnerRegistry)` on `TaskRunnerPropertyFactory`.
- Consumed by: the `script` actions here, and Task 6's `commands` actions.

Capability gating is **UI only**. A hand-edited workflow setting `inputFiles` under GraalVM must still be rejected by `GraalVmTaskRunner.validate`, which Phase 1 already does — that is the same two-layer arrangement the allowlist uses, and this task must not weaken it.

- [ ] **Step 1: Add the constants**

In `TaskRunnerConstants.java`:

```java
    public static final String ENV = "env";
    public static final String INPUT_FILES = "inputFiles";
    public static final String OUTPUT_FILES = "outputFiles";
    public static final String TIMEOUT = "timeout";
```

- [ ] **Step 2: Write the failing test**

Append to `TaskRunnerPropertyFactoryTest`:

```java
    @Test
    void testExternalPropertiesAreOmittedWhenNoRunnerDeclaresTheCapability() {
        TaskRunnerRegistry taskRunnerRegistry = registryOf(inProcessRunner());

        List<ModifiableValueProperty<?, ?>> properties =
            TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry);

        assertThat(properties).isEmpty();
    }

    @Test
    void testExternalPropertiesAppearWhenARunnerDeclaresTheCapability() {
        TaskRunnerRegistry taskRunnerRegistry = registryOf(externalRunner());

        List<String> names = TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry)
            .stream()
            .map(property -> property.getName())
            .toList();

        assertThat(names).containsExactly("env", "inputFiles", "outputFiles", "timeout");
    }

    @Test
    void testEachExternalPropertyIsConditionedOnTheRunnersThatSupportIt() {
        TaskRunnerRegistry taskRunnerRegistry = registryOf(externalRunner());

        ModifiableValueProperty<?, ?> envProperty = TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry)
            .getFirst();

        assertThat(envProperty.getDisplayCondition())
            .hasValueSatisfying(condition -> assertThat(condition).contains("taskRunner.type == 'external'"));
    }
```

Build the two fake runners with Mockito, declaring capability sets that differ: `inProcessRunner()` returns `Set.of(INLINE_SCRIPT, COMPONENT_BRIDGE)`, `externalRunner()` returns the five capabilities `ProcessTaskRunner` declares and `getType()` returning `"external"`. Reuse whatever helper the existing tests in this file already have rather than adding a second one; read the file first.

`getDisplayCondition()` returns `Optional<String>` on the property domain object — confirm the accessor's name and return type against `ComponentDsl` before writing the assertion, and follow the repo.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api:test --tests '*TaskRunnerPropertyFactoryTest'`
Expected: FAIL — `externalProperties` does not exist.

- [ ] **Step 4: Implement `externalProperties`**

Add to `TaskRunnerPropertyFactory`:

```java
    /**
     * Builds the properties that only an out-of-process runner can honour, each shown for exactly the runners that
     * declare its capability.
     *
     * <p>
     * A property no enabled runner supports is omitted rather than shown disabled: an always-hidden field is
     * indistinguishable from a broken one, and the runner's own {@code validate} rejects the value anyway if a
     * hand-edited workflow supplies it.
     */
    public static List<ModifiableValueProperty<?, ?>> externalProperties(TaskRunnerRegistry taskRunnerRegistry) {
        List<ModifiableValueProperty<?, ?>> properties = new ArrayList<>();

        addIfSupported(
            properties, taskRunnerRegistry, TaskRunnerCapability.ENVIRONMENT,
            object(ENV)
                .label("Environment")
                .description("Environment variables the execution sees. The host's own environment is not inherited.")
                .additionalProperties(string())
                .required(false));

        addIfSupported(
            properties, taskRunnerRegistry, TaskRunnerCapability.INPUT_FILES,
            object(INPUT_FILES)
                .label("Input Files")
                .description(
                    "Files written into the working directory before the execution, keyed by file name. A value may " +
                        "be inline text or a file entry.")
                .additionalProperties(string(), fileEntry())
                .required(false));

        addIfSupported(
            properties, taskRunnerRegistry, TaskRunnerCapability.OUTPUT_FILES,
            array(OUTPUT_FILES)
                .label("Output Files")
                .description("Glob patterns matched against the output directory after the execution.")
                .items(string())
                .required(false));

        addForExternalRunners(
            properties, taskRunnerRegistry,
            integer(TIMEOUT)
                .label("Timeout (seconds)")
                .description("How long the execution may run before it is killed.")
                .required(false));

        return properties;
    }

    private static void addIfSupported(
        List<ModifiableValueProperty<?, ?>> properties, TaskRunnerRegistry taskRunnerRegistry,
        TaskRunnerCapability capability, ModifiableValueProperty<?, ?> property) {

        List<TaskRunner> taskRunners = taskRunnerRegistry.getTaskRunners(Set.of(capability));

        if (taskRunners.isEmpty()) {
            return;
        }

        String condition = taskRunners.stream()
            .map(taskRunner -> "%s.%s == '%s'".formatted(TASK_RUNNER, TYPE, taskRunner.getType()))
            .collect(Collectors.joining(" || "));

        property.displayCondition(condition);

        properties.add(property);
    }

    /**
     * Adds a property shown for every runner that executes outside this JVM.
     *
     * <p>
     * "External" is derived, not listed: a runner is in-process exactly when it can offer the component bridge, which
     * is a live host object and therefore cannot cross a process boundary. So the filter is the absence of
     * {@link TaskRunnerCapability#COMPONENT_BRIDGE}, and a runner contributed by another module lands on the correct
     * side of it without an edit here.
     */
    private static void addForExternalRunners(
        List<ModifiableValueProperty<?, ?>> properties, TaskRunnerRegistry taskRunnerRegistry,
        ModifiableValueProperty<?, ?> property) {

        List<TaskRunner> taskRunners = taskRunnerRegistry.getTaskRunners(Set.of())
            .stream()
            .filter(taskRunner -> {
                Set<TaskRunnerCapability> capabilities = taskRunner.getCapabilities();

                return !capabilities.contains(TaskRunnerCapability.COMPONENT_BRIDGE);
            })
            .toList();

        if (taskRunners.isEmpty()) {
            return;
        }

        String condition = taskRunners.stream()
            .map(taskRunner -> "%s.%s == '%s'".formatted(TASK_RUNNER, TYPE, taskRunner.getType()))
            .collect(Collectors.joining(" || "));

        property.displayCondition(condition);

        properties.add(property);
    }
```

`timeout` is deliberately not gated on a capability. Every runner can be given a wall clock; what varies is whether
one is *wanted*, and for GraalVM strict the answer is no — `sandbox.MaxCPUTime` meters CPU rather than elapsed time, so
a script waiting on a slow HTTP call through `context.component.*` burns almost no CPU and a wall clock would kill it
mid-request. That is why the property is offered only for runners that execute outside this JVM.

Add one further test asserting `timeout`'s condition names the external runner and does **not** name GraalVM — without
it, gating on the wrong capability is indistinguishable from gating on the right one.

Confirm `additionalProperties(...)` accepts a varargs of properties and that `array(...).items(...)` exists in this repo's DSL before writing; adjust to what is there.

- [ ] **Step 5: Wire the properties into the two script actions**

In `ScriptJavaScriptAction` and `ScriptPythonAction`, the `of(taskRunnerRegistry)` factory already appends `TaskRunnerPropertyFactory.taskRunnerProperty(...)`. Append `TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry)` to the same property list. Read both files first — they share a shape, and the change must be identical in each.

- [ ] **Step 6: Read the new properties in `ScriptActionDefinition.perform`**

Replace the three `Map.of()`/`List.of()`/`null` placeholders in the `TaskRunnerRequest` construction:

```java
        Integer timeoutSeconds = inputParameters.getInteger(TIMEOUT);

        TaskRunnerRequest taskRunnerRequest = new TaskRunnerRequest(
            languageId, inputParameters.getRequiredString(SCRIPT), List.of(),
            inputParameters.getMap(INPUT, Object.class, Map.of()),
            inputParameters.getMap(ENV, String.class, Map.of()),
            inputParameters.getMap(INPUT_FILES, Object.class, Map.of()),
            inputParameters.getList(OUTPUT_FILES, String.class, List.of()), inputParameters, runnerParameters,
            timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds), connectionParameters, context);
```

Update the class javadoc: the paragraph stating "The request carries no timeout" is now false. Replace it with a paragraph saying the action carries an optional `timeout`, that a null one leaves the ceiling to the runner, and that GraalVM strict deliberately applies none.

- [ ] **Step 6a: Return the full result for an external runner**

The spec fixes the action's output contract and Phase 1 does not yet honour it:

> Output via `output(OutputFunction)`: GraalVM yields the `perform()` return value; external runners yield
> `{exitCode, stdout, stderr, vars, outputFiles}`.

`ScriptActionDefinition.perform` currently ends with `return taskRunnerResult.output();` for every runner, which
throws away the exit code, both streams and the collected output files the whole of Phase 2 exists to produce. A
user running under the process runner would get back only whatever `output.json` held, with no way to see a
diagnostic the script printed.

Decide "external" the same way the `timeout` property does — by the ABSENCE of
`TaskRunnerCapability.COMPONENT_BRIDGE`, since an in-JVM bridge is a live host object that cannot cross a process
boundary. Do not test the runner's type string; a runner contributed by another module must land on the right side
of this without an edit here. The resolved `TaskRunner` is already in scope in `perform`, so its `getCapabilities()`
is available.

```java
        TaskRunnerResult taskRunnerResult = taskRunner.run(taskRunnerRequest);

        Set<TaskRunnerCapability> capabilities = taskRunner.getCapabilities();

        if (capabilities.contains(TaskRunnerCapability.COMPONENT_BRIDGE)) {
            return taskRunnerResult.output();
        }

        Map<String, Object> result = new HashMap<>();

        result.put("exitCode", taskRunnerResult.exitCode());
        result.put("stdout", taskRunnerResult.stdout());
        result.put("stderr", taskRunnerResult.stderr());
        result.put("vars", taskRunnerResult.output());
        result.put("outputFiles", taskRunnerResult.outputFiles());

        return result;
```

Note `vars` carries `TaskRunnerResult.output()` — the record's component is named `output` but the spec names this
key `vars`, and the spec's name is what a workflow author sees. Use a `HashMap` rather than `Map.of`, because
`exitCode` is `@Nullable` and `Map.of` rejects null values with an NPE.

Add two tests to `ScriptActionDefinitionTest`: one asserting a GraalVM run returns the bare value, and one asserting
a run through a stub runner WITHOUT `COMPONENT_BRIDGE` returns a map carrying all five keys. The second must fail if
the capability check is inverted — check that it does.

Do NOT declare a static `output(...)` schema for these actions. The shape genuinely differs per runner, and a fixed
schema would advertise `exitCode`/`stdout` fields to an editor session using GraalVM, where they never appear. If a
bare no-argument `.output()` exists in this repo's DSL and means "determined at run time", using it is fine; verify
what it does before adding it, and leave the declaration off rather than guess.

- [ ] **Step 6b: Prove the wiring with a registry that actually has an external runner**

`ScriptComponentHandlerTest.createTaskRunnerRegistry()` hand-builds a registry holding **only**
`GraalVmTaskRunner`, with only `graalvm` enabled. GraalVM declares none of the three external capabilities and does
declare `COMPONENT_BRIDGE`, so `externalProperties` returns an **empty list** for that registry — the existing snapshot
does not move, and nothing in the module proves the four properties are wired at all. A test that cannot fail is worse
than no test, so add one that can.

Create `server/libs/modules/components/script/src/test/java/com/bytechef/component/script/ScriptExternalRunnerPropertiesTest.java`:

```java
package com.bytechef.component.script;

import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.PROCESS;
import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.definition.Property;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.runner.GraalVmTaskRunner;
import com.bytechef.platform.component.runner.TaskRunnerRegistry;
import com.bytechef.platform.component.runner.TaskRunnerRegistryImpl;
import com.bytechef.platform.component.runner.external.ProcessTaskRunner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptExternalRunnerPropertiesTest {

    @Test
    void testExternalPropertiesAppearWhenAnExternalRunnerIsEnabled() {
        List<String> names = actionPropertyNames(registryWith(GRAALVM, PROCESS));

        assertThat(names).contains("env", "inputFiles", "outputFiles", "timeout");
    }

    @Test
    void testExternalPropertiesAreAbsentWhenOnlyGraalVmIsEnabled() {
        List<String> names = actionPropertyNames(registryWith(GRAALVM));

        assertThat(names).doesNotContain("env", "inputFiles", "outputFiles", "timeout");
    }

    private static List<String> actionPropertyNames(TaskRunnerRegistry taskRunnerRegistry) {
        ComponentDefinition componentDefinition = new ScriptComponentHandler(null, taskRunnerRegistry).getDefinition();

        List<? extends ActionDefinition> actionDefinitions = componentDefinition.getActions()
            .orElseThrow();

        ActionDefinition actionDefinition = actionDefinitions.getFirst();

        List<? extends Property> properties = actionDefinition.getProperties()
            .orElseThrow();

        return properties.stream()
            .map(Property::getName)
            .toList();
    }

    private static TaskRunnerRegistry registryWith(String... enabledTypes) {
        ApplicationProperties applicationProperties = new ApplicationProperties();

        ApplicationProperties.Script script = applicationProperties.getScript();

        Map<String, ApplicationProperties.Script.Runner> runners = new java.util.HashMap<>();

        for (String enabledType : enabledTypes) {
            ApplicationProperties.Script.Runner runner = new ApplicationProperties.Script.Runner();

            runner.setEnabled(true);

            runners.put(enabledType, runner);
        }

        script.setRunners(runners);

        return new TaskRunnerRegistryImpl(
            List.of(new GraalVmTaskRunner(null, applicationProperties), new ProcessTaskRunner()),
            applicationProperties);
    }
}
```

The second test is what gives the first one meaning: together they show the properties track the enabled runner set
rather than appearing unconditionally. Check `ActionDefinition.getProperties()` and `ComponentDefinition.getActions()`
return types against the SDK before writing — `ScriptComponentHandlerTest` already navigates both and is the reference.

Run: `./gradlew :server:libs:modules:components:script:test --tests '*ScriptExternalRunnerPropertiesTest'`
Expected: PASS, 2 tests.

- [ ] **Step 7: Regenerate the definition snapshot**

```bash
rm -f server/libs/modules/components/script/src/test/resources/definition/script_v1.json
rm -rf server/libs/modules/components/script/build/resources/test/definition
./gradlew :server:libs:modules:components:script:test --tests '*ComponentDefinitionTest' || true
./gradlew :server:libs:modules:components:script:test --tests '*ComponentDefinitionTest'
```

The first run writes the snapshot to `src` and then fails with `NullPointerException: url` because the classpath copy does not exist yet. That is the expected midpoint, not a bug. The second run passes once `processTestResources` copies it across.

Expect the regenerated file to be **identical** to the one you deleted: that test's registry enables only GraalVM, so no external property enters the snapshot. `git diff` showing nothing here is the correct outcome, and Step 6b is what actually covers the new properties. A snapshot that *does* change means the properties leaked past their display conditions — investigate before committing.

- [ ] **Step 8: Run both modules' checks**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api:check :server:libs:modules:components:script:check --continue > /tmp/task5-check.log 2>&1
echo "EXIT=$?"
grep '^> Task .* FAILED' /tmp/task5-check.log
```

Expected: EXIT=0, no FAILED lines.

- [ ] **Step 9: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-component/platform-component-runner server/libs/modules/components/script
git commit -m "3901 Add env, input files, output files and timeout to the script actions"
```

---

### Task 6: The commands component

**Files:**
- Create: `server/libs/modules/components/commands/build.gradle.kts`
- Create: `server/libs/modules/components/commands/src/main/java/com/bytechef/component/commands/CommandsComponentHandler.java`
- Create: `.../commands/constant/CommandsConstants.java`
- Create: `.../commands/action/CommandsShellAction.java`, `CommandsPythonAction.java`, `CommandsNodeAction.java`
- Create: `.../commands/action/definition/CommandsActionDefinition.java`
- Create: `.../commands/config/CommandsDeferredEvaluationConfiguration.java`
- Create: `server/libs/modules/components/commands/src/main/resources/README.md`
- Modify: `settings.gradle.kts` — add `include("server:libs:modules:components:commands")`
- Test: `.../commands/src/test/java/com/bytechef/component/commands/CommandsComponentHandlerTest.java`, `.../commands/config/CommandsDeferredEvaluationConfigurationTest.java`

**Interfaces:**
- Consumes: `TaskRunnerRegistry`, `TaskRunnerPropertyFactory.taskRunnerProperty(registry, Set.of(COMMANDS))`, `TaskRunnerPropertyFactory.externalProperties(registry)`.
- Produces: component `commands` with actions `shell`, `python`, `node`.

Three rulings this task implements, recorded here so the implementer does not re-litigate them:

1. **`settings.gradle.kts` is sufficient to reach `server-app`.** The spec warns that the module must be added to server-app's dependencies too. It does not: `server/apps/server-app/build.gradle.kts` enumerates `rootProject.subprojects` filtered by the path prefix `:server:libs:modules:components`, so a module registered in settings is picked up automatically. Verify this after the build by confirming `commands` appears in the generated `META-INF/bytechef/component-index.json`.
2. **The action's `commands` are lines for that action's interpreter**, not shell lines that happen to run in an interpreter-bearing image. `shell` feeds `/bin/sh`, `python` feeds `python3`, `node` feeds `node`. This is the literal reading of the spec's per-action table, it is what makes the overridable `interpreter` property coherent, and it keeps the three actions genuinely distinct under both external runners.
3. **GraalVM is absent from the runner select by derivation, not by exclusion.** It declares no `COMMANDS` capability, so `taskRunnerProperty(registry, Set.of(COMMANDS))` omits it with no list of runner names anywhere in this module.

- [ ] **Step 1: Register the module**

Add to `settings.gradle.kts`, in alphabetical position among the component includes:

```kotlin
include("server:libs:modules:components:commands")
```

- [ ] **Step 2: Write the module's build file**

`server/libs/modules/components/commands/build.gradle.kts`:

```kotlin
version="1.0"

dependencies {
    implementation("org.springframework:spring-context")
    implementation(project(":server:libs:atlas:atlas-configuration:atlas-configuration-api"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))
    implementation(project(":server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api"))

    testImplementation(project(":server:libs:config:app-config"))
    testImplementation(project(":server:libs:platform:platform-component:platform-component-test-int-support"))
    testImplementation(project(":server:libs:test:test-support"))
}
```

Model it on `server/libs/modules/components/script/build.gradle.kts` and keep only what compiles — an unused dependency is a review finding.

- [ ] **Step 3: Write the failing test for the eager deferred-evaluation registration**

```java
package com.bytechef.component.commands.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.atlas.configuration.domain.DeferredEvaluationParameterKeys;
import org.junit.jupiter.api.Test;

class CommandsDeferredEvaluationConfigurationTest {

    @Test
    void testCommandsAreDeferredFromExpressionEvaluation() {
        new CommandsDeferredEvaluationConfiguration();

        assertThat(DeferredEvaluationParameterKeys.forTaskType("commands/v1")).containsExactly("commands");
    }

    @Test
    void testAnUnrelatedTaskTypeIsUnaffected() {
        new CommandsDeferredEvaluationConfiguration();

        assertThat(DeferredEvaluationParameterKeys.forTaskType("httpClient/v1")).isEmpty();
    }
}
```

POSIX shell writes `${VAR}`, which is exactly the workflow evaluator's accessor syntax. Without this registration a command as ordinary as `echo ${HOME}` fails the whole task with `Invalid expression`, naming neither the component nor the property.

The registration must live in an **eagerly instantiated** `@Configuration`, never in the component handler: `ComponentDefinitionRegistry` loads no component at startup, so a static initialiser in the handler may not have run when a task is evaluated, and the deferral would silently not apply.

- [ ] **Step 4: Write the configuration**

```java
package com.bytechef.component.commands.config;

import static com.bytechef.component.commands.constant.CommandsConstants.COMMANDS;

import com.bytechef.atlas.configuration.domain.DeferredEvaluationParameterKeys;
import org.springframework.context.annotation.Configuration;

/**
 * Holds the {@code commands} parameter back from the workflow expression evaluator.
 *
 * <p>
 * POSIX shell parameter expansion is spelled {@code ${VAR}}, which is character-for-character the evaluator's accessor
 * syntax. Left evaluated, {@code echo ${HOME}} fails the task with {@code Invalid expression} - a message naming
 * neither this component nor this property. Workflow data reaches a command through the {@code env} property instead,
 * whose values are evaluated normally.
 *
 * <p>
 * This registration is eager on purpose. Components load lazily, so a static initialiser on the handler may not have
 * run by the time a task is evaluated, and the deferral would apply only after something else happened to load the
 * component first.
 *
 * @author Ivica Cardic
 */
@Configuration
public class CommandsDeferredEvaluationConfiguration {

    static {
        DeferredEvaluationParameterKeys.register(COMMANDS + "/", COMMANDS);
    }
}
```

- [ ] **Step 5: Write the constants and the action definition**

`CommandsConstants`:

```java
package com.bytechef.component.commands.constant;

/**
 * @author Ivica Cardic
 */
public class CommandsConstants {

    public static final String COMMANDS = "commands";
    public static final String NODE = "node";
    public static final String PYTHON = "python";
    public static final String SHELL = "shell";
    public static final String WARN_ON_STD_ERR = "warnOnStdErr";

    private CommandsConstants() {
    }
}
```

`CommandsActionDefinition` mirrors `ScriptActionDefinition`: it resolves the runner from `taskRunner.type`, builds a `TaskRunnerRequest` with `script` null and `commands` populated from `getRequiredList(COMMANDS, String.class)`, and returns a map of `exitCode`, `stdout`, `stderr`, `vars` and `outputFiles` from the `TaskRunnerResult`. Read `ScriptActionDefinition` and follow its shape, including its fallback and its javadoc discipline.

There is one difference: `commands` has no in-process runner to fall back to, so a missing `taskRunner.type` must fail with a message naming the configuration key rather than defaulting to `graalvm`. Resolve the default from `TaskRunnerPropertyFactory`'s own rule instead — the first runner declaring `COMMANDS` — and if there is none, let `getTaskRunner` throw `TaskRunnerNotEnabledException`.

`warnOnStdErr`, when true and stderr is non-empty, logs a warning through `context.getLog()`; it never fails the task. A non-zero exit fails it regardless, which the runner already does.

- [ ] **Step 6: Write the three actions and the handler**

Each action is a small factory in the shape of `ScriptJavaScriptAction`:

```java
    public static ModifiableActionDefinition of(TaskRunnerRegistry taskRunnerRegistry) {
        List<ModifiableValueProperty<?, ?>> properties = new ArrayList<>();

        properties.add(
            array(COMMANDS)
                .label("Commands")
                .description("The lines handed to the interpreter, in order.")
                .items(string())
                .required(true)
                .expressionEnabled(false));

        properties.add(TaskRunnerPropertyFactory.taskRunnerProperty(
            taskRunnerRegistry, Set.of(TaskRunnerCapability.COMMANDS)));
        properties.addAll(TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry));
        properties.add(
            bool(WARN_ON_STD_ERR)
                .label("Warn On Standard Error")
                .description("Logs a warning when the execution writes to standard error.")
                .defaultValue(true)
                .required(false));

        return new CommandsActionDefinition(
            action(SHELL)
                .title("Shell")
                .description("Runs shell commands in the selected task runner.")
                .properties(properties)
                .output(),
            SHELL, taskRunnerRegistry);
    }
```

`expressionEnabled(false)` on `commands` has no runtime effect today — nothing in the evaluation path reads it — but it is the honest declaration and the signal the editor uses. The mechanism is Step 4's registration.

`CommandsComponentHandler` follows `ScriptComponentHandler`: a Spring `@Component("commands_v1_ComponentHandler")` taking `TaskRunnerRegistry` by constructor, because it needs a bean and `@AutoService` gives no dependency injection.

- [ ] **Step 7: Write the README**

`src/main/resources/README.md`, following the format of another component's README. It must state the three built-in runners by name, because the definition is registry-derived and therefore no longer readable from one file, and it must say that workflow data reaches commands through `env` rather than through `${…}` interpolation.

- [ ] **Step 8: Generate the definition snapshot**

```bash
./gradlew :server:libs:modules:components:commands:test || true
./gradlew :server:libs:modules:components:commands:test
```

Same two-run dance as Task 5. The snapshot varies with the runners on the test classpath, so the test must pin a deterministic runner set through its own `ApplicationProperties` rather than inheriting whatever the classpath supplies — `script`'s definition test already does this; copy its arrangement.

- [ ] **Step 9: Verify the component reaches the index**

```bash
./gradlew :server:apps:server-app:generateComponentIndex
grep -c '"commands"' server/apps/server-app/build/resources/main/META-INF/bytechef/component-index.json
```

Expected: at least 1. A zero here means the component builds and tests cleanly but is invisible in the picker — the exact failure the spec's deployment section warns about. Locate the index file by reading the `generateComponentIndex` task in `server/apps/server-app/build.gradle.kts` if the path above is wrong.

- [ ] **Step 10: Run the full check for the new module**

```bash
./gradlew :server:libs:modules:components:commands:check > /tmp/commands-check.log 2>&1
echo "EXIT=$?"
grep '^> Task .* FAILED' /tmp/commands-check.log
```

- [ ] **Step 11: Format and commit**

```bash
./gradlew spotlessApply
git add settings.gradle.kts server/libs/modules/components/commands
git commit -m "3901 Add the commands component"
```
