# Plugin Transport Tagging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `bytechef-dev` plugin's skills declare which surface performs each operation, generate the claude.ai skills and the MCP server's instructions from those declarations, and fail the build when a declaration names a tool or command that does not exist.

**Architecture:** A small Java library parses transport-fenced skill markdown. Two generators consume it — one emitting per-job claude.ai skills, one emitting MCP instruction fragments the server assembles at runtime against its actually-registered tools. An integration test in `server-app` (the one module whose classpath sees CE and EE together) checks every declaration against the live tool registry and the CLI's `@Command` literals.

**Tech Stack:** Java 25, Gradle 9.7 Kotlin DSL, JUnit 5, Spring AI MCP server, Spring Boot test with Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-11-claude-plugin-transport-routing-design.md`

**Depends on:** `docs/superpowers/plans/2026-09-11-cli-gap-fill-for-plugin-transports.md` (landed 2026-09-11) — every operation a skill names now has an MCP tool, a `@Command` path, or is deliberately untagged.

## Scope note the spec did not anticipate

An inventory of the four existing skills found that **none of them performs an MCP operation**:

| Skill | Operations it actually performs | Required transports |
|---|---|---|
| `bytechef-component` | `./gradlew` runs, files in a checkout | `local` |
| `bytechef-custom-component` | authors a file, then `bytechef component deploy` | `local`, `cli` |
| `bytechef-code-workflow` | authors a file, then `bytechef automation project deploy` / `bytechef embedded code-workflow deploy` | `local`, `cli` |
| `bytechef-mcp-setup` | edits `.mcp.json` | `local` |

`bytechef-mcp-setup` *names* MCP tool families but never instructs Claude to call one. Under the spec's own rule — a skill whose `transports.required` contains `cli` or `local` is dropped from the claude.ai output — all four are dropped and the claude.ai output generates **empty**, leaving the generator's primary purpose unexercised and the MCP instruction fragments with no source text.

Task 4 therefore authors the first genuinely MCP-transport skill. This is an addition to the spec's stated scope, made because the alternative is shipping two generators that provably produce nothing.

## Global Constraints

- Files under `server/ee/` use the ByteChef Enterprise licence header and carry an `@version ee` Javadoc tag. Everything else uses Apache 2.0. All Java files carry `@author Ivica Cardic`.
- Integration test classes end in `IntTest`; unit test classes end in `Test` with no `Impl` in the name. Test method names are camelCase with no underscores — Checkstyle enforces this on ALL methods in test sources, including private helpers.
- `check { dependsOn(test); dependsOn(testIntegration) }` in `buildSrc/src/main/kotlin/com.bytechef.java-common-conventions.gradle.kts`, so an `*IntTest` runs under `./gradlew check` but NOT under bare `./gradlew test`.
- Java style: one blank line before control statements; one blank line after a variable modification a following statement uses; no trailing blank line before a class's closing brace; no short or cryptic variable names anywhere, including lambda and catch parameters; no code comments explaining rationale.
- Never judge a Gradle run piped into `tail`/`grep` — redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`. Use `--continue`.
- Run `./gradlew spotlessApply` before committing Java.
- Commits are subject-only, one line. No `Co-Authored-By` trailer, no "Generated with Claude Code" line.
- **This checkout is shared.** Stage by explicit path, never `git add -A`. Never `git commit` with a trailing `-- <pathspec>` (it reconciles the working tree and can undo a staged `git rm --cached`). Never amend.

## The registered MCP tool inventory

These are the CE tool names the management server registers, extracted from `@Tool`-annotated methods on the seven classes `ManagementMcpServerConfiguration` passes to `ToolCallbacks.from`. Tasks 3 and 4 need them; they are the only names an `mcp:` tag may use on a CE instance.

- `ProjectTools`: listProjects, getProject, searchProjects, getProjectStatus, createProject, updateProject, deleteProject, publishProject
- `ProjectWorkflowTools`: getWorkflow, listWorkflows, searchWorkflows, createProjectWorkflow, deleteWorkflow, updateWorkflow, saveWorkflowTestConnection
- `ComponentTools`: getComponent, getTrigger, getTriggerDefinition, listActions, listComponents, listTriggers, searchComponents, searchTriggers, getAction, searchClusterElements, getClusterElement, searchActions, getActionDefinition, getOutputProperty, getProperties
- `TaskTools`: getTask, getTaskProperties, getTaskOutputProperty, getTaskDefinition, getTaskDispatcherBuildInstructions, listTasks, searchTasks, validateTask
- `TaskDispatcherTools`: listTaskDispatchers, getTaskDispatcher, getTaskDispatcherOutput, getTaskDispatcherProperties, searchTaskDispatchers, getTaskDispatcherDefinition
- `ScriptTools`: updateScriptComponentCode
- `ClusterElementTools`: updateWorkflowRootProperties, updateClusterElementTask

EE contributors add the intelligent tools (`buildWorkflow`, `importWorkflow`, `buildCodeWorkflow`, `buildCustomComponent`, `authorSkill`, `debugWorkflowExecution`, `configureMcpServer`, and the MCP-server CRUD tools) through `McpServerToolCallbackContributor` beans under `server/ee/`. Any tag naming one of those MUST carry `edition: ee`.

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | register `claude-code-plugin:plugin-tools` |
| `claude-code-plugin/plugin-tools/build.gradle.kts` | plain Java library, JUnit |
| `.../plugintools/Transport.java`, `Edition.java` | the two enums |
| `.../plugintools/TransportFence.java` | one fenced block: transport, edition, uses, body |
| `.../plugintools/SkillDocument.java` | one parsed SKILL.md: frontmatter + fences |
| `.../plugintools/SkillDocumentParser.java` | markdown → `SkillDocument`, with validation |
| `.../plugintools/ClaudeSkillGenerator.java` | per-job claude.ai skills |
| `.../plugintools/McpInstructionFragmentGenerator.java` | the fragment resource |
| `.../plugintools/PluginToolsMain.java` | `main` for the Gradle `JavaExec` tasks |
| `buildSrc/src/main/kotlin/com.bytechef.claude-plugin-generator.gradle.kts` | the two Gradle tasks |
| `server/libs/ai/ai-mcp/ai-mcp-server/src/main/resources/bytechef/mcp-instructions.md` | generated, committed |
| `server/apps/server-app/src/test/java/com/bytechef/ai/mcp/server/config/PluginSkillTagDriftIntTest.java` | the drift check |

---

### Task 1: The skill document parser

**Files:**
- Modify: `settings.gradle.kts`
- Create: `claude-code-plugin/plugin-tools/build.gradle.kts`
- Create: `claude-code-plugin/plugin-tools/src/main/java/com/bytechef/plugintools/{Transport,Edition,TransportFence,SkillDocument,SkillDocumentParser}.java`
- Test: `claude-code-plugin/plugin-tools/src/test/java/com/bytechef/plugintools/SkillDocumentParserTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces, and every later task depends on these exact signatures:

```java
public enum Transport { MCP, CLI, LOCAL }

public enum Edition { CE, EE }

public record TransportFence(Transport transport, Edition edition, List<String> uses, String body) {}

public record SkillDocument(
    Path path, String name, String description, Set<Transport> requiredTransports,
    Set<Transport> optionalTransports, List<TransportFence> fences, String rawContent) {}

public final class SkillDocumentParser {
    public static SkillDocument parse(Path file) throws IOException;
    public static List<SkillDocument> parseAll(Path skillsDirectory) throws IOException;
}
```

`parseAll` walks `skillsDirectory` for files named `SKILL.md` plus any `.md` under a `references/` subdirectory, and returns one `SkillDocument` per file.

**Fence syntax** (the format the whole plan rests on):

```markdown
<!-- transport: mcp uses: createProjectWorkflow, buildWorkflow edition: ee -->
prose and code here
<!-- /transport -->
```

`transport:` is required and one of `mcp`, `cli`, `local`. `uses:` is a comma-separated list, REQUIRED for `mcp` and `cli`, optional for `local`. `edition:` is optional and defaults to `ce`. Fences do not nest and may not span a `#` heading.

**Frontmatter** adds one block to the existing `name`/`description`:

```yaml
transports:
  required: [local]
  optional: [cli]
```

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, after the `cli:` includes (around line 39), add:

```kotlin
include("claude-code-plugin:plugin-tools")
```

- [ ] **Step 2: Write the build file**

Create `claude-code-plugin/plugin-tools/build.gradle.kts`:

```kotlin
plugins {
    id("com.bytechef.java-library-conventions")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
}
```

- [ ] **Step 3: Write the failing tests**

Create `SkillDocumentParserTest.java` with the Apache 2.0 header and `@author Ivica Cardic`. Use `@TempDir` to write fixture files. Cover exactly these cases:

```java
@Test
void testParsesFrontmatterTransports() throws IOException {
    Path skillFile = writeSkill("""
        ---
        name: Example
        description: An example.
        transports:
          required: [local]
          optional: [cli]
        ---

        Body.
        """);

    SkillDocument skillDocument = SkillDocumentParser.parse(skillFile);

    assertEquals("Example", skillDocument.name());
    assertEquals(Set.of(Transport.LOCAL), skillDocument.requiredTransports());
    assertEquals(Set.of(Transport.CLI), skillDocument.optionalTransports());
}

@Test
void testParsesFenceWithUsesAndDefaultEdition() throws IOException {
    Path skillFile = writeSkill("""
        ---
        name: Example
        description: An example.
        transports:
          required: [mcp]
        ---

        <!-- transport: mcp uses: createProjectWorkflow, getWorkflow -->
        Call createProjectWorkflow first.
        <!-- /transport -->
        """);

    SkillDocument skillDocument = SkillDocumentParser.parse(skillFile);
    TransportFence fence = skillDocument.fences()
        .getFirst();

    assertEquals(Transport.MCP, fence.transport());
    assertEquals(Edition.CE, fence.edition());
    assertEquals(List.of("createProjectWorkflow", "getWorkflow"), fence.uses());
    assertTrue(fence.body()
        .contains("Call createProjectWorkflow first."));
}

@Test
void testParsesEeEdition() throws IOException {
    // fence carrying "edition: ee" yields Edition.EE
}

@Test
void testLocalFenceMayOmitUses() throws IOException {
    // transport: local with no uses: parses, uses() is empty
}

@Test
void testMcpFenceWithoutUsesIsRejected() throws IOException {
    Path skillFile = writeSkill(/* an mcp fence with no uses: attribute */);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> SkillDocumentParser.parse(skillFile));

    assertTrue(exception.getMessage()
        .contains("uses"));
}

@Test
void testUnknownTransportIsRejected() throws IOException {
    // "transport: http" throws IllegalArgumentException naming the bad value
}

@Test
void testUnclosedFenceIsRejected() throws IOException {
    // an opening fence with no <!-- /transport --> throws
}

@Test
void testNestedFenceIsRejected() throws IOException {
    // a second opening fence before the first closes throws
}

@Test
void testFenceSpanningAHeadingIsRejected() throws IOException {
    // a fence whose body contains a line starting with "#" throws
}

@Test
void testParseAllFindsSkillAndReferenceFiles() throws IOException {
    // a directory with one/SKILL.md and one/references/detail.md yields 2 documents
}
```

Add a private helper `writeSkill(String content)` that writes to the `@TempDir` and returns the path — remember Checkstyle forbids underscores in ALL test method names, helpers included.

- [ ] **Step 4: Run the tests to verify they fail**

```bash
./gradlew :claude-code-plugin:plugin-tools:test > /tmp/t1.log 2>&1
echo $?
```

Expected: non-zero, compilation error naming `SkillDocumentParser` as not found.

- [ ] **Step 5: Implement the enums and records**

Create `Transport`, `Edition`, `TransportFence` and `SkillDocument` exactly as specified in the Interfaces block above.

- [ ] **Step 6: Implement the parser**

`SkillDocumentParser` is a final class with a private constructor and the two static methods. Parse the frontmatter between the leading `---` lines yourself with simple line handling — do NOT add a YAML dependency for four keys. Fences are found with a regex over the whole file for `<!--\s*transport:\s*(\w+)(.*?)-->` through `<!--\s*/transport\s*-->`; validate as the tests require. Validation failures throw `IllegalArgumentException` whose message names the file and the offending line number.

- [ ] **Step 7: Run the tests to verify they pass**

```bash
./gradlew :claude-code-plugin:plugin-tools:check > /tmp/t1.log 2>&1
echo $?
grep "^> Task .* FAILED" /tmp/t1.log
```

Expected: exit 0, no FAILED lines.

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo $?
git add settings.gradle.kts claude-code-plugin/plugin-tools
git commit -m "Add a parser for transport-tagged plugin skill documents"
```

---

### Task 2: The drift check

Built BEFORE any skill is tagged, so tagging lands against a working gate. With no tags present it passes vacuously; that is expected and is asserted explicitly so the test cannot silently pass forever by finding nothing.

**Files:**
- Modify: `server/apps/server-app/build.gradle.kts` (add `testImplementation(project(":claude-code-plugin:plugin-tools"))`)
- Create: `server/apps/server-app/src/test/java/com/bytechef/ai/mcp/server/config/PluginSkillTagDriftIntTest.java`

**Interfaces:**
- Consumes: `SkillDocumentParser.parseAll`, `SkillDocument`, `TransportFence`, `Transport`, `Edition` from Task 1.
- Produces: nothing later tasks call; it is a gate.

The test class lives in package `com.bytechef.ai.mcp.server.config` — deliberately the same package as `ManagementMcpServerConfiguration`, because `toolCallbackProvider()` is package-private. A split package across modules is legal here (no JPMS).

- [ ] **Step 1: Add the test dependency**

In `server/apps/server-app/build.gradle.kts`, alongside the other `testImplementation` entries:

```kotlin
testImplementation(project(":claude-code-plugin:plugin-tools"))
```

- [ ] **Step 2: Write the test**

Model the Spring setup on `server/apps/server-app/src/test/java/com/bytechef/server/ServerApplicationIntTest.java` — `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` with `@Import(PostgreSQLContainerConfiguration.class, ...)`, reusing its `ServerApplicationIntTestConfiguration` mock beans if the context will not start without them. Read that file first.

```java
@Autowired
private ManagementMcpServerConfiguration managementMcpServerConfiguration;

private static Path repositoryRoot() {
    Path directory = Path.of("")
        .toAbsolutePath();

    while (directory != null && !Files.exists(directory.resolve("settings.gradle.kts"))) {
        directory = directory.getParent();
    }

    if (directory == null) {
        throw new IllegalStateException("Could not locate the repository root from " + Path.of("").toAbsolutePath());
    }

    return directory;
}

private Set<String> registeredMcpToolNames() {
    ToolCallbackProvider toolCallbackProvider = managementMcpServerConfiguration.toolCallbackProvider();

    return Arrays.stream(toolCallbackProvider.getToolCallbacks())
        .map(toolCallback -> toolCallback.getToolDefinition()
            .name())
        .collect(Collectors.toSet());
}

private static Set<String> cliCommandNames() throws IOException {
    Pattern pattern = Pattern.compile("@Command\\(\\s*name\\s*=\\s*\"([^\"]+)\"");

    try (Stream<Path> paths = Files.walk(repositoryRoot().resolve("cli/commands"))) {
        return paths.filter(path -> path.toString()
            .endsWith("Command.java"))
            .filter(path -> !path.toString()
                .contains("/build/"))
            .flatMap(path -> {
                try {
                    Matcher matcher = pattern.matcher(Files.readString(path));
                    List<String> names = new ArrayList<>();

                    while (matcher.find()) {
                        names.add(matcher.group(1));
                    }

                    return names.stream();
                } catch (IOException ioException) {
                    throw new UncheckedIOException(ioException);
                }
            })
            .collect(Collectors.toSet());
    }
}
```

Four test methods:

```java
@Test
void testEveryMcpTagNamesARegisteredTool() throws IOException { }

@Test
void testEveryCliTagNamesAnExistingCommand() throws IOException { }

@Test
void testEeTaggedToolsAreNotClaimedAsCe() throws IOException { }

@Test
void testTheCliInventoryIsNotEmpty() throws IOException { }
```

The first three iterate `SkillDocumentParser.parseAll(repositoryRoot().resolve("claude-code-plugin/bytechef-dev/skills"))`, collect every failing `uses:` entry with its file name, and assert the collected list is empty — reporting ALL failures at once rather than the first, because a tagging pass fixes them in one edit.

The fourth is the anti-vacuity guard: `assertFalse(cliCommandNames().isEmpty())` and `assertTrue(cliCommandNames().contains("component deploy"))`. Without it, a broken extractor makes every other assertion pass trivially. There is deliberately no equivalent vacuity guard on the MCP side in this task — Task 4 adds it once MCP tags exist.

For `testEeTaggedToolsAreNotClaimedAsCe`: a fence with `edition: ee` whose tools ARE present in the registry is fine (the test runs on a server-app context which carries EE); the assertion is the converse — a fence with no `edition: ee` must not name a tool that only EE contributes. Determine the EE-only set as the registry's tool names minus the CE names listed in this plan's "registered MCP tool inventory" section, and hard-code that CE list in the test as a `private static final Set<String> CE_TOOL_NAMES`. Hard-coding is correct here: the test's job is to notice when reality diverges from the documented CE surface.

- [ ] **Step 3: Run it**

```bash
./gradlew :server:apps:server-app:testIntegration --tests '*PluginSkillTagDriftIntTest*' > /tmp/t2.log 2>&1
echo $?
grep "^> Task .* FAILED" /tmp/t2.log
```

Expected: exit 0. Three assertions pass vacuously (no tags exist yet); the fourth proves the CLI extractor works. Requires Docker for Testcontainers.

- [ ] **Step 4: Prove the check actually fails on drift**

Temporarily add a bogus fence to any skill file:

```markdown
<!-- transport: mcp uses: thisToolDoesNotExist -->
temporary
<!-- /transport -->
```

Re-run Step 3's command. Expected: FAILS, naming `thisToolDoesNotExist`. Then REMOVE the temporary fence and re-run to confirm green. Record both outputs in your report — a gate never observed failing is not known to be a gate.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo $?
git add server/apps/server-app/build.gradle.kts server/apps/server-app/src/test/java/com/bytechef/ai/mcp/server/config/PluginSkillTagDriftIntTest.java
git commit -m "Add a drift check for plugin skill transport tags"
```

---

### Task 3: Tag the four existing skills

**Files:**
- Modify: `claude-code-plugin/bytechef-dev/skills/bytechef-component/SKILL.md` and its two `references/*.md`
- Modify: `claude-code-plugin/bytechef-dev/skills/bytechef-custom-component/SKILL.md`
- Modify: `claude-code-plugin/bytechef-dev/skills/bytechef-code-workflow/SKILL.md`
- Modify: `claude-code-plugin/bytechef-dev/skills/bytechef-mcp-setup/SKILL.md`

**Interfaces:**
- Consumes: the fence syntax from Task 1; the gate from Task 2.
- Produces: tagged skills that Tasks 5 and 6 generate from.

Frontmatter to add to each, keeping the existing `name` and `description` untouched:

| Skill | required | optional |
|---|---|---|
| `bytechef-component` | `[local]` | none |
| `bytechef-custom-component` | `[local, cli]` | none |
| `bytechef-code-workflow` | `[local, cli]` | none |
| `bytechef-mcp-setup` | `[local]` | none |

- [ ] **Step 1: Fence the operations**

Wrap each block that instructs Claude to PERFORM an operation. Leave explanatory prose, property tables and API reference material unfenced — fences mark operations, not paragraphs.

- `./gradlew` invocations and file creation in a checkout → `<!-- transport: local -->` (no `uses:` needed).
- `bytechef ...` invocations → `<!-- transport: cli uses: <the command path> -->`. The command paths in use are `component deploy`, `automation project deploy`, `embedded code-workflow deploy`, `embedded code-workflow list` and `configure`. Each must match a `@Command(name = "...")` literal exactly — Task 2's check verifies this.
- Nothing here is `mcp`. If you find yourself wanting an `mcp` fence in these four files, stop and report it.

- [ ] **Step 2: Handle the untaggable block**

`bytechef-code-workflow/SKILL.md` around line 157 documents `POST /api/embedded/internal/integrations/deploy`. Per the spec's subsection "Embedded code integration deploy: demoted to a statement of fact", this block must be rewritten from an instruction into a statement: the operation is reachable only from the admin console and has no automatable route. Remove its `curl` invocation. It carries NO fence, because it instructs no operation. Read that spec subsection before editing.

The `curl` blocks that remain elsewhere — the documented fallbacks beside a CLI command — stay unfenced too, for the same reason: they are the "no CLI available?" alternative, not the instructed path. Do not fence them and do not delete them.

- [ ] **Step 3: Run the drift check**

```bash
./gradlew :server:apps:server-app:testIntegration --tests '*PluginSkillTagDriftIntTest*' > /tmp/t3.log 2>&1
echo $?
grep "^> Task .* FAILED" /tmp/t3.log
```

Expected: exit 0. This run is no longer vacuous — it verifies every `cli:` tag you just wrote.

- [ ] **Step 4: Commit**

```bash
git add claude-code-plugin/bytechef-dev/skills
git commit -m "Tag the plugin skills with the transport that performs each operation"
```

---

### Task 4: Author the first MCP-transport skill

Without this the claude.ai generator and the instruction fragments both have no input. See "Scope note" above.

**Files:**
- Create: `claude-code-plugin/bytechef-dev/skills/bytechef-workflow-builder/SKILL.md`
- Modify: `claude-code-plugin/bytechef-dev/README.md` (the capability table at the top)
- Modify: `server/apps/server-app/src/test/java/com/bytechef/ai/mcp/server/config/PluginSkillTagDriftIntTest.java` (add the MCP anti-vacuity guard)

**Interfaces:**
- Consumes: the fence syntax from Task 1, the registered tool inventory in this plan, the gate from Task 2.
- Produces: the only skill with `transports.required: [mcp]`, and therefore the only one that reaches the claude.ai output.

- [ ] **Step 1: Write the skill**

Frontmatter:

```yaml
---
name: ByteChef Workflow Builder
description: This skill should be used when the user asks to "build a workflow in ByteChef", "create a project and workflow", "add a step to my workflow", "wire up a trigger", "import an n8n/Make/Zapier workflow", or wants Claude to operate a connected ByteChef instance over the Management MCP server rather than author code locally.
transports:
  required: [mcp]
---
```

The body is the sequencing knowledge currently hard-coded in `ManagementMcpServerConfiguration.INSTRUCTIONS` (read that constant — it is the source text, and Task 6 will delete it in favour of what you write here), reorganised into fences. At minimum:

- A `<!-- transport: mcp uses: listProjects, createProject, createProjectWorkflow -->` fence covering project and workflow creation.
- A `<!-- transport: mcp uses: buildWorkflow edition: ee -->` fence covering the build loop — each intelligent-tool call is independent and re-reads state, so iterate by calling again with the next instruction and restate context.
- A `<!-- transport: mcp uses: importWorkflow edition: ee -->` fence for importing from n8n/Make/Zapier/Workato.
- A `<!-- transport: mcp uses: listComponents, searchActions, getActionDefinition, getProperties -->` fence for discovering what a step can do.
- A `<!-- transport: mcp uses: getWorkflow, updateWorkflow -->` fence for reading and editing a definition.
- A `<!-- transport: mcp uses: publishProject -->` fence for publishing.

Every `edition: ee` fence must state in its prose that the tool is EE-only and what a CE user should do instead, because the claude.ai output ships to users whose instance edition is unknowable at generation time.

Do NOT include the MCP-server-configuration sequence (`createMcpServer` → `createMcpProject` → `configureMcpServer` → `updateMcpServer`) in this skill. It is a separate job and belongs in its own skill; note it as a follow-up rather than building it.

- [ ] **Step 2: Add the anti-vacuity guard**

In `PluginSkillTagDriftIntTest`, add:

```java
@Test
void testTheMcpInventoryIsNotEmpty() {
    Set<String> toolNames = registeredMcpToolNames();

    assertFalse(toolNames.isEmpty());
    assertTrue(toolNames.contains("createProjectWorkflow"));
}
```

and extend the tag-collecting tests' assertion so that at least one `mcp` fence was seen across all skills — otherwise a future edit that deletes every MCP tag makes the MCP assertions vacuous again.

- [ ] **Step 3: Update the plugin README**

Add a row to the capability table at the top of `claude-code-plugin/bytechef-dev/README.md` and a short section matching the shape of the four existing ones.

- [ ] **Step 4: Run the drift check**

```bash
./gradlew :server:apps:server-app:testIntegration --tests '*PluginSkillTagDriftIntTest*' > /tmp/t4.log 2>&1
echo $?
grep "^> Task .* FAILED" /tmp/t4.log
```

Expected: exit 0, with the MCP assertions now non-vacuous. If an `edition: ee` tag fails, the EE tool is genuinely absent from the registry — report it rather than deleting the tag.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo $?
git add claude-code-plugin/bytechef-dev server/apps/server-app/src/test/java/com/bytechef/ai/mcp/server/config/PluginSkillTagDriftIntTest.java
git commit -m "Add a workflow builder skill driven by the management MCP server"
```

---

### Task 5: The claude.ai skill generator

**Files:**
- Create: `claude-code-plugin/plugin-tools/src/main/java/com/bytechef/plugintools/ClaudeSkillGenerator.java`
- Create: `claude-code-plugin/plugin-tools/src/main/java/com/bytechef/plugintools/PluginToolsMain.java`
- Test: `claude-code-plugin/plugin-tools/src/test/java/com/bytechef/plugintools/ClaudeSkillGeneratorTest.java`
- Create: `buildSrc/src/main/kotlin/com.bytechef.claude-plugin-generator.gradle.kts`
- Modify: root `build.gradle.kts` (apply the convention plugin)

**Interfaces:**
- Consumes: `SkillDocumentParser.parseAll`, `SkillDocument`, `TransportFence`, `Transport` from Task 1.
- Produces:

```java
public final class ClaudeSkillGenerator {
    public static void generate(Path skillsDirectory, Path outputDirectory) throws IOException;
}

public final class PluginToolsMain {
    public static void main(String[] args) throws IOException;
}
```

`PluginToolsMain` dispatches on `args[0]`: `"claude-skills"` takes `args[1]` (skills dir) and `args[2]` (output dir); `"mcp-instructions"` (added in Task 6) takes `args[1]` and `args[2]` (output file).

**Generation rules**, from the spec's "The claude.ai / Desktop skills — one per job":

1. One output directory per input skill: `<outputDirectory>/<skill-directory-name>/SKILL.md`.
2. Drop a skill entirely when its `requiredTransports` contains `CLI` or `LOCAL`.
3. In surviving skills, delete every `CLI` and `LOCAL` fence including its body.
4. Rewrite the `description`: prefix `ByteChef: ` and append ` (requires the ByteChef Management MCP connector)`. The `name` is unchanged.
5. Strip the `transports:` frontmatter block from the output — it is build metadata, not something a claude.ai reader needs.
6. Leave `edition: ee` fences in place, but strip the fence comments themselves from the output so the shipped file is clean markdown.

- [ ] **Step 1: Write the failing tests**

`ClaudeSkillGeneratorTest` builds a fixture skills directory under `@TempDir` with three skills — one `required: [mcp]`, one `required: [local]`, one `required: [mcp]` containing a `cli` fence — and asserts:

```java
@Test
void testDropsSkillsRequiringAShell() throws IOException {
    // the required: [local] skill produces no output directory
}

@Test
void testKeepsMcpOnlySkills() throws IOException {
    // the required: [mcp] skill produces <out>/<name>/SKILL.md
}

@Test
void testStripsCliFencesAndTheirBodies() throws IOException {
    String generated = Files.readString(outputDirectory.resolve("mixed/SKILL.md"));

    assertFalse(generated.contains("bytechef component deploy"));
    assertTrue(generated.contains("createProjectWorkflow"));
}

@Test
void testRewritesTheDescription() throws IOException {
    // description starts with "ByteChef: " and ends with the connector clause
}

@Test
void testStripsTransportsFrontmatterAndFenceComments() throws IOException {
    String generated = Files.readString(outputDirectory.resolve("mcponly/SKILL.md"));

    assertFalse(generated.contains("transports:"));
    assertFalse(generated.contains("<!-- transport:"));
    assertFalse(generated.contains("<!-- /transport -->"));
}

@Test
void testGeneratesNothingFromAnEmptyDirectory() throws IOException {
    // an empty skills dir yields an empty output dir rather than throwing
}
```

- [ ] **Step 2: Run to verify RED**

```bash
./gradlew :claude-code-plugin:plugin-tools:test --tests '*ClaudeSkillGeneratorTest*' > /tmp/t5.log 2>&1
echo $?
```

Expected: non-zero, `ClaudeSkillGenerator` not found.

- [ ] **Step 3: Implement the generator and the main**

- [ ] **Step 4: Run to verify GREEN**

```bash
./gradlew :claude-code-plugin:plugin-tools:check > /tmp/t5.log 2>&1
echo $?
grep "^> Task .* FAILED" /tmp/t5.log
```

- [ ] **Step 5: Write the Gradle convention plugin**

Create `buildSrc/src/main/kotlin/com.bytechef.claude-plugin-generator.gradle.kts` following the shape of `buildSrc/src/main/kotlin/com.bytechef.documentation-generator.gradle.kts` (read it first — it registers its task at the bottom of the file):

```kotlin
val generateClaudeSkillBundle by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Generates the claude.ai skill set from the bytechef-dev plugin skills."
    mainClass.set("com.bytechef.plugintools.PluginToolsMain")
    classpath = project(":claude-code-plugin:plugin-tools").the<SourceSetContainer>()["main"].runtimeClasspath
    args("claude-skills", "$rootDir/claude-code-plugin/bytechef-dev/skills", "$rootDir/build/claude-skills")
}
```

Apply it in the root `build.gradle.kts` `plugins` block.

- [ ] **Step 6: Run the generator for real**

```bash
./gradlew generateClaudeSkillBundle > /tmp/gen.log 2>&1
echo $?
find build/claude-skills -name SKILL.md
```

Expected: exit 0 and EXACTLY ONE output — `build/claude-skills/bytechef-workflow-builder/SKILL.md`. The other four skills require a shell and are correctly dropped. If the output is empty, Task 4's skill is not reaching the generator; stop and report rather than loosening the drop rule.

Read the generated file and confirm it contains no `bytechef ` command invocations and no `./gradlew` lines.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo $?
git add claude-code-plugin/plugin-tools buildSrc/src/main/kotlin/com.bytechef.claude-plugin-generator.gradle.kts build.gradle.kts
git commit -m "Generate the claude.ai skill set from the plugin skills"
```

`build/` is gitignored — do not commit the generated output.

---

### Task 6: MCP instruction fragments and runtime assembly

This is the task that fixes a live defect: `ManagementMcpServerConfiguration` applies a static `INSTRUCTIONS` constant naming `buildWorkflow`, `buildCodeWorkflow`, `authorSkill` and others, while every one of those tools comes from `McpServerToolCallbackContributor` beans that exist only under `server/ee/`. A CE instance advertises tools it does not have.

**Files:**
- Create: `claude-code-plugin/plugin-tools/src/main/java/com/bytechef/plugintools/McpInstructionFragmentGenerator.java`
- Test: `claude-code-plugin/plugin-tools/src/test/java/com/bytechef/plugintools/McpInstructionFragmentGeneratorTest.java`
- Modify: `claude-code-plugin/plugin-tools/src/main/java/com/bytechef/plugintools/PluginToolsMain.java`
- Modify: `buildSrc/src/main/kotlin/com.bytechef.claude-plugin-generator.gradle.kts`
- Create (generated, committed): `server/libs/ai/ai-mcp/ai-mcp-server/src/main/resources/bytechef/mcp-instructions.md`
- Modify: `server/libs/ai/ai-mcp/ai-mcp-server/src/main/java/com/bytechef/ai/mcp/server/config/ManagementMcpServerConfiguration.java`
- Test: `server/libs/ai/ai-mcp/ai-mcp-server/src/test/java/com/bytechef/ai/mcp/server/config/McpInstructionAssemblyTest.java`

**Interfaces:**
- Consumes: `SkillDocumentParser.parseAll` and `TransportFence` from Task 1; the MCP fences authored in Task 4.
- Produces:

```java
public final class McpInstructionFragmentGenerator {
    public static void generate(Path skillsDirectory, Path outputFile) throws IOException;
}
```

and, in `ManagementMcpServerConfiguration`, a package-private static method replacing the constant:

```java
static String buildInstructions(Set<String> registeredToolNames);
```

**Fragment file format** — one `always` section plus one section per tool:

```markdown
## always
ByteChef management server. Ordinary tools are deterministic CRUD; intelligent tools run an inner
AI agent and may take minutes.

## tool: buildWorkflow
To build a workflow: createProject (if needed), then createProjectWorkflow, then buildWorkflow with
the workflowId and a plain-language instruction.
```

The generator emits `## always` from any `mcp` fence whose `uses:` is empty or whose body the skill marks as general, and one `## tool: <name>` section per distinct name in every `mcp` fence's `uses:`, carrying that fence's body. A tool appearing in several fences gets its sections concatenated in file order.

- [ ] **Step 1: Write the failing generator test**

```java
@Test
void testEmitsOneSectionPerToolNamed() throws IOException {
    // a fixture skill with "uses: createProject, buildWorkflow" yields
    // "## tool: createProject" and "## tool: buildWorkflow"
}

@Test
void testIgnoresCliAndLocalFences() throws IOException {
    // a cli fence's body must not appear anywhere in the output
}

@Test
void testConcatenatesRepeatedTools() throws IOException {
    // a tool named by two fences gets both bodies under one section
}
```

- [ ] **Step 2: Verify RED, then implement the generator and wire `PluginToolsMain`'s `"mcp-instructions"` branch and a `generateMcpInstructions` Gradle task writing to the resource path above.**

- [ ] **Step 3: Write the failing assembly test FIRST, against the current constant**

Create `McpInstructionAssemblyTest` in `server/libs/ai/ai-mcp/ai-mcp-server/src/test/java/com/bytechef/ai/mcp/server/config/`. This test must FAIL against today's code — that is the point; it is the regression test for the live defect.

```java
@Test
void testCeInstructionsDoNotNameEeOnlyTools() {
    Set<String> ceToolNames = Set.of("listProjects", "createProject", "createProjectWorkflow", "getWorkflow");

    String instructions = ManagementMcpServerConfiguration.buildInstructions(ceToolNames);

    assertFalse(instructions.contains("buildWorkflow"));
    assertFalse(instructions.contains("authorSkill"));
    assertFalse(instructions.contains("configureMcpServer"));
}

@Test
void testEeInstructionsNameEeTools() {
    Set<String> eeToolNames = Set.of("listProjects", "createProjectWorkflow", "buildWorkflow");

    String instructions = ManagementMcpServerConfiguration.buildInstructions(eeToolNames);

    assertTrue(instructions.contains("buildWorkflow"));
}

@Test
void testTheAlwaysSectionIsAlwaysPresent() {
    String instructions = ManagementMcpServerConfiguration.buildInstructions(Set.of());

    assertTrue(instructions.contains("ByteChef management server"));
}
```

Run it and record the failure. Expected RED: `buildInstructions` does not exist.

- [ ] **Step 4: Implement the assembly**

Replace the `INSTRUCTIONS` constant with `buildInstructions(Set<String>)`, which reads `bytechef/mcp-instructions.md` from the classpath, always includes the `## always` section, and includes a `## tool: X` section only when `X` is in the passed set. Then change the `mcpAsyncServer` bean to call it with the names from `toolCallbackProvider().getToolCallbacks()`:

```java
@Bean
McpAsyncServer mcpAsyncServer(ApplicationProperties applicationProperties) {
    ToolCallbackProvider toolCallbackProvider = toolCallbackProvider();

    Set<String> registeredToolNames = Arrays.stream(toolCallbackProvider.getToolCallbacks())
        .map(toolCallback -> toolCallback.getToolDefinition()
            .name())
        .collect(Collectors.toSet());

    return McpServer.async(webMvcStreamableHttpServerTransportProvider())
        .serverInfo("mcp-server", "1.0.0")
        .instructions(buildInstructions(registeredToolNames))
        // ... the rest unchanged
}
```

Note `toolCallbackProvider()` is currently called inline in the `.tools(...)` argument; reuse the local variable for both rather than calling it twice.

- [ ] **Step 5: Verify GREEN and check the existing tests still pass**

```bash
./gradlew :server:libs:ai:ai-mcp:ai-mcp-server:check > /tmp/t6.log 2>&1
echo $?
grep "^> Task .* FAILED" /tmp/t6.log
```

`ManagementMcpServerToolCallbackProviderTest` and `ManagementMcpServerWorkflowEditorTest` already exist in that package and must still pass.

- [ ] **Step 6: Generate and commit the resource**

```bash
./gradlew generateMcpInstructions > /tmp/gen.log 2>&1
echo $?
cat server/libs/ai/ai-mcp/ai-mcp-server/src/main/resources/bytechef/mcp-instructions.md
```

Confirm it contains an `## always` section and a `## tool:` section for each tool Task 4's skill names.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo $?
git add claude-code-plugin/plugin-tools buildSrc/src/main/kotlin/com.bytechef.claude-plugin-generator.gradle.kts server/libs/ai/ai-mcp/ai-mcp-server
git commit -m "Assemble the management MCP instructions from the registered tools"
```

---

## Self-Review

**Spec coverage.** Transport taxonomy and fence syntax → Task 1. Canonical-source format → Tasks 1 and 3. Output 1 (the plugin as identity) → no task needed; it is the source. Output 2 (per-job claude.ai skills, rewritten descriptions, drop rules) → Task 5. Output 3 (fragments filtered by registered tools, and the CE/EE defect it fixes) → Task 6. Drift check with its four assertions and its `server-app` placement → Tasks 2 and 4. The spec's "Naming" section (never bare "skill" where ByteChef's AI Skills could be meant) is a writing rule the review applies, not a task.

**Gap the spec did not cover**, added here with reasons stated: Task 4, authoring the first MCP-transport skill, without which Outputs 2 and 3 are both empty.

**Not covered, deliberately:** the spec's open question — where the generated claude.ai skills get published and on what cadence. Task 5 writes to `build/claude-skills` and stops. Publishing is a release decision, not an implementation one.

**Type consistency.** `SkillDocument`, `TransportFence`, `Transport` and `Edition` are defined once in Task 1's Interfaces block and consumed unchanged by Tasks 2, 5 and 6. `PluginToolsMain` is created in Task 5 and extended in Task 6; both name the same `args[0]` dispatch values. `buildInstructions(Set<String>)` is declared in Task 6's Interfaces block and used by both its test and its bean.

**Ordering.** The gate (Task 2) precedes the content it gates (Tasks 3 and 4), and it carries an explicit anti-vacuity assertion because a gate built before its inputs exist otherwise passes by finding nothing. Task 6's regression test is written to fail against today's constant before the fix, per the plan's TDD constraint.
