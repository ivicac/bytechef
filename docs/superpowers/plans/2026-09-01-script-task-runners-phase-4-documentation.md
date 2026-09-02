# Script Task Runners Phase 4 — Documentation and Samples Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make everything the previous three phases built discoverable and correctly described — so an operator knows which flags they are turning on and what each costs, and a workflow author knows what changes when they switch runners.

**Architecture:** No production code. Two component READMEs, the generated reference pages they feed, and one sample workflow. The READMEs are the source of truth: `buildSrc/src/main/kotlin/com.bytechef.documentation-generator.gradle.kts` reads `src/main/resources/README.mdx` (`.mdx`, never `.md`) and appends it to the definition-derived page under `docs/content/docs/reference/components/`.

**Tech Stack:** Markdown/MDX, Gradle (`generateDocumentation`), JSON workflow definitions.

**Spec:** `docs/superpowers/specs/2026-08-31-script-task-runners-design.md`

## Global Constraints

- **The file must be `README.mdx`.** The generator reads only that name; a `README.md` is silently never published. This already happened once in Phase 2 and was caught in review.
- **Every claim must be verified against the code before it is written.** This documentation describes security controls. A README that overstates a guarantee is worse than one that omits it — Phase 2 shipped a `${HOME}` example into public docs that a reviewer had already disproved.
- **Do not document behaviour that does not exist.** There is no `volumes` property, no Ruby/Java/R external execution, and no orphan reaping for a backgrounded descendant. Say what is true.
- **No `TODO:`** anywhere. **CE Apache 2.0** headers on any new source file.
- Verification for docs changes: `./gradlew generateDocumentation`, then confirm the generated page under `docs/content/docs/reference/components/` actually contains your prose. Redirect Gradle output to a file, check `$?` on its own line, grep `^> Task .* FAILED`. **Never judge a Gradle run through a pipe**, and never grep for `error:` — it matches the module path `:server:libs:core:error:`.
- Two pre-existing failures in other modules are not yours: `platform-data-table-service:testIntegration` and EE `platform-user-service:testIntegration`.

---

### Task 1: The `script` component README

**Files:**
- Modify: `server/libs/modules/components/script/src/main/resources/README.mdx`
- Regenerate: `docs/content/docs/reference/components/script_v1.mdx`

The file already documents the GraalVM runner and its two modes well, including `trusted-enabled`. That part is correct — **do not rewrite it**. What is missing is everything the last two phases added. A review of Phase 2 flagged this file explicitly: it "documents only the GraalVM runner", with no mention of the process runner, of the languages that cannot run externally, or of the result shape changing.

Read these before writing, and describe what they actually do:
- `ProcessTaskRunner` and `DockerTaskRunner` in `…/platform-component-runner-impl/.../runner/external/`
- `ExternalLanguage` — which languages resolve externally and which do not
- `ScriptActionDefinition.perform` — the branch that returns a five-key map instead of a bare value
- `TaskRunnerOperatorFlag` — how the operator flags fail closed

- [ ] **Step 1: Extend the runner table**

Add rows for `process` and `docker` beside the existing `graalvm` row. For each, state what it does and the configuration key an operator must set to enable it (`bytechef.script.runners.<type>.enabled=true`). Keep the existing GraalVM row and its Strict/Trusted prose unchanged.

- [ ] **Step 2: Document the result-shape change — this is the one an author will hit**

Under GraalVM the action returns the `perform()` return value. Under any external runner it returns a map: `exitCode`, `stdout`, `stderr`, `vars` (the parsed `output.json`), `outputFiles`. A workflow reading `${myScript}` gets a different thing after switching runners.

Verify the exact keys in `TaskRunnerResult.toExternalMap()` and use those names.

- [ ] **Step 3: Document which languages run where**

`ExternalLanguage` resolves `javascript`/`js`, `python` and `shell`. Ruby, Java and R are GraalVM-only and are rejected by `validate` before anything starts. State that the editor still offers an external runner for those actions — the runner select filters by capability, not by language — so the failure is loud at run time rather than prevented in the UI. Do not describe this as a bug; it is the two-layer arrangement used throughout, and the rejection names the language.

- [ ] **Step 4: Document `inherit-environment-enabled`**

The process runner clears the child environment, re-seeding only `PATH`, `HOME` and the three `BYTECHEF_*` names, so the server's own credentials cannot be read by a script. A workflow author can ask for inheritance, but only an operator can permit it, with `bytechef.script.runners.process.properties.inherit-environment-enabled=true`. Mirror the tone of the existing `trusted-enabled` paragraph, which gets this right.

- [ ] **Step 5: Document the two limitations, honestly**

Both are real and both were deliberate decisions, not oversights:
- **A backgrounded descendant is not reaped.** A command like `sleep 600 &` that outlives its parent keeps running after the task ends. The task itself is bounded — the drain wait has a ceiling — but the orphan is not killed. Reaping it needs a process group, which is not implemented. Docker does not have this problem: container teardown takes everything.
- **stdout/stderr in the result are truncated** to a 32 KiB head and a 32 KiB tail with the elided byte count between them, and the full stream goes to the server log. Verify the exact limit in `BoundedOutputCapture` before quoting a number.

- [ ] **Step 6: Regenerate and verify**

```bash
./gradlew generateDocumentation > /tmp/gendocs.log 2>&1
echo "EXIT=$?"
grep '^> Task .* FAILED' /tmp/gendocs.log
grep -c 'inherit-environment-enabled' docs/content/docs/reference/components/script_v1.mdx
```

The last count must be at least 1. A zero means the prose did not reach the published page — check you edited `README.mdx` and not `README.md`.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add server/libs/modules/components/script docs/content/docs/reference/components/script_v1.mdx
git commit -m "3901 Document the external runners in the script component"
```

---

### Task 2: The `commands` component README and the operator's configuration reference

**Files:**
- Modify: `server/libs/modules/components/commands/src/main/resources/README.mdx`
- Regenerate: `docs/content/docs/reference/components/commands_v1.mdx`

This README is already substantially correct — Phase 2 and Phase 3 both extended it. Your job is the gaps their reviews named, not a rewrite.

- [ ] **Step 1: Add the complete operator configuration block**

One YAML block showing every key the three runners read, with defaults, and one sentence each on what enabling it costs:

```yaml
bytechef:
  script:
    runners:
      graalvm:
        enabled: true
        properties:
          trusted-enabled: false
      process:
        enabled: false
        properties:
          inherit-environment-enabled: false
      docker:
        enabled: false
        properties:
          host: ""
          allowed-images: ""
          allow-host-network: false
          allow-volume-mounts: false
```

**Verify this against the code before publishing it.** `ApplicationProperties.Script.Runner` has `enabled` plus a free-form `properties` map — check whether each key above really sits under `properties` or beside it, and correct the block to match. Getting this wrong sends operators to a key that does nothing.

- [ ] **Step 2: Document the allowlist's fail-closed behaviour prominently**

`allowed-images` is a comma-separated list, matched by **exact equality** — no prefixes, no wildcards. An **empty allowlist permits nothing**, which is the opposite of the usual convention and will otherwise surprise an operator who enables Docker and finds nothing runs. Say why: the alternative would let one configuration line grant arbitrary image execution.

- [ ] **Step 3: Document `networkMode` and the absent `volumes` property**

`networkMode` accepts only `bridge`, `none`, and `host` when `allow-host-network` is set — a closed set, so naming a Docker network (which would reach services on it, such as the database in the shipped compose file) is rejected. There is no `volumes` property in v1 at all, because a host bind mount chosen by a workflow author is a container escape; `allow-volume-mounts` exists in configuration and is read by nothing yet.

- [ ] **Step 4: Document the per-language default images**

`shell` → `ubuntu:24.04`, `python` → `python:3.12-slim`, `node` → `node:22-alpine`. State that a defaulted image is still checked against the allowlist, so a default is a convenience and never a bypass — verify that in `DockerTaskRunner` before writing it.

- [ ] **Step 5: Note that the published property list is configuration-dependent**

The runner select and its properties are assembled from the runners that are enabled, so the generated reference page reflects whichever runners were enabled when it was built. A reader whose deployment differs will see different fields. This is a consequence of the registry-derived design the spec chose, and the spec calls the cost out.

- [ ] **Step 6: Regenerate and verify**

```bash
./gradlew generateDocumentation > /tmp/gendocs2.log 2>&1
echo "EXIT=$?"
grep '^> Task .* FAILED' /tmp/gendocs2.log
grep -c 'allowed-images' docs/content/docs/reference/components/commands_v1.mdx
```

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add server/libs/modules/components/commands docs/content/docs/reference/components/commands_v1.mdx
git commit -m "3901 Document the runner configuration in the commands component"
```

---

### Task 3: A sample workflow

**Files:**
- Create: `server/libs/config/automation-demo-config/src/main/resources/demo/script-runners.json`

Read `demo/hello.json` in the same directory first and follow its shape exactly — `label`, `inputs`, `outputs`, `tasks`, each task with `name`, `label`, `type`, `parameters`.

The sample must run on a **default installation**, where only `graalvm` is enabled. A sample that fails out of the box is worse than none. So:

- [ ] **Step 1: Write a workflow that works on the shipped configuration**

One `script/v1/javascript` task using the GraalVM runner, taking a workflow input and returning a computed value through an output. This demonstrates the authoring shape that is identical across all three runners.

- [ ] **Step 2: Show the runner switch in a comment, not in a task that would fail**

The workflow JSON cannot carry comments, so put the explanation in the README instead: the same task runs unchanged under `process` or `docker` by setting `taskRunner.type`, with the only difference being that the result becomes the five-key map. Add that paragraph to the `script` README next to the runner table, referencing this sample by name.

- [ ] **Step 3: Verify it parses and its task types exist**

```bash
python3 -c "import json;d=json.load(open('server/libs/config/automation-demo-config/src/main/resources/demo/script-runners.json'));print([t['type'] for t in d['tasks']])"
```

Every printed type must exist — check each against the component's action names. `script/v1/javascript` is the action named `javascript` on component `script` version 1.

- [ ] **Step 4: Commit**

```bash
git add server/libs/config/automation-demo-config/src/main/resources/demo/script-runners.json server/libs/modules/components/script
git commit -m "3901 Add a script task runner sample workflow"
```
