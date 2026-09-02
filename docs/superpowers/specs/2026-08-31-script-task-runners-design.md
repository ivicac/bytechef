# Script task runners and the Commands component

Date: 2026-08-31
Status: Design, approved for planning

## Summary

Two related additions, modelled on Kestra:

1. **Task runners** — a `TaskRunner` SPI plus a `taskRunner` property on script-executing
   actions, letting one action run in the in-JVM GraalVM sandbox, in a local OS process, or in
   a Docker container. GraalVM gains two modes: `strict` (today's behaviour) and `trusted`
   (host access permitted).
2. **A `commands` component** — actions `shell`, `python` and `node` that run a list of
   commands, the Kestra `*.Commands` analogue to the existing `script` component's inline code.

The runner set is open: a new runner is one module contributing one Spring bean, with no edit
to `script`, `commands`, or `ApplicationProperties`.

## Motivation

The `script` component today executes user code in one way only: a GraalVM polyglot context
built by `PolyglotSandbox`. That covers workflow glue logic well, and nothing else:

- No way to run a real CPython or Node runtime, so libraries with native extensions
  (`pandas`, `numpy`, `sharp`) are unavailable.
- No way to run a shell command, the single most common "just do this one thing" escape hatch
  in an automation platform.
- No way to relax the sandbox for a trusted single-tenant deployment that legitimately wants a
  script to touch the local filesystem.

Kestra solves all three with the same two concepts, and users coming from Kestra expect them.

## Decisions

| Question | Decision |
|---|---|
| Do runners apply to `script` too, or only `commands`? | **Both.** The execution contract varies by runner. |
| How is a host-escaping runner governed? | **Server-config allowlist.** Ops enables runners in `application.yml`. Default: GraalVM strict only. |
| How much of Kestra's I/O contract? | **Files + env + a JSON output file.** No `::{"outputs":…}::` stdout marker protocol. |
| Shape of the `commands` component? | **One action per interpreter** — `shell`, `python`, `node` — each with its own defaults. |
| Where does runner config live? | **Inline `taskRunner` object property.** No new entity, no migration. |
| What does inline code look like under Docker/Process? | **The same `perform(input, context)` shape**, with a generated bootstrap appended to the materialised file. |
| SPI discriminator naming? | **`String getType()`**, matching `FileStorageService` and the `taskRunner.type` workflow property. |

## Architecture

### Module layout

```
server/libs/platform/platform-component/platform-component-runner/
├── platform-component-runner-api/    TaskRunner, TaskRunnerCapability, TaskRunnerConstants,
│                                     TaskRunnerRequest, TaskRunnerResult, TaskRunnerRegistry,
│                                     TaskRunnerWorkingDirectorySpec, ScriptSandboxMode
└── platform-component-runner-impl/   GraalVmTaskRunner, ProcessTaskRunner, DockerTaskRunner,
                                      TaskRunnerRegistryImpl, TaskRunnerWorkingDirectory
```

Siblings of the existing `platform-component-polyglot`. The SPI lives in `-api` so an EE module
can implement a runner without depending on the built-in implementations.

### The SPI

```java
public interface TaskRunner {

    String getType();                            // "graalvm" | "process" | "docker" | future "kubernetes"

    String getTitle();

    List<? extends Property> getProperties();    // contributed into the taskRunner object property

    Set<TaskRunnerCapability> getCapabilities();

    void validate(TaskRunnerRequest request);    // throws on config this runner cannot honour

    TaskRunnerResult run(TaskRunnerRequest request);
}
```

`TaskRunnerCapability`: `INLINE_SCRIPT`, `COMMANDS`, `COMPONENT_BRIDGE`, `INPUT_FILES`,
`OUTPUT_FILES`, `ENVIRONMENT`.

`TaskRunnerRequest` carries `languageId`, `script` **or** `commands`, `input`, `env`,
`inputFiles`, `outputFilePatterns`, `runnerParameters`, `timeout`, the `ActionContext` (for
file storage), and a component-bridge supplier consumed only by `GraalVmTaskRunner`.

`TaskRunnerResult` carries `output` (the `perform()` return value, or the external runner's
parsed `vars`), a nullable `exitCode`, `stdout`, `stderr`, and
`outputFiles` as `Map<String, FileEntry>`.

The request object is the union of every runner's needs, and each runner rejects in
`validate()` what it cannot honour — for example `inputFiles` under GraalVM. This is
deliberate: one interface, one registry, one dispatch point for the allowlist, and an explicit
error rather than silent ignoring. Splitting into `InProcessRunner` / `ExternalRunner` was
considered and rejected — the façade would `instanceof`-branch anyway, and the allowlist would
gain a second enforcement point.

### Registration and extensibility

`TaskRunnerRegistry` receives `List<TaskRunner>` by Spring injection. Any module contributing a
`@Component TaskRunner` bean registers itself. Three things keep the set genuinely open:

- **The `taskRunner` object property is registry-built.** For each registered runner the
  component takes `getProperties()` and stamps `displayCondition: taskRunner.type == '<type>'`
  onto each. The `type` select's `options()` function enumerates the registry.
- **Per-action runner filtering is derived from `getCapabilities()`**, not hardcoded. The
  `commands` actions require `COMMANDS`; `GraalVmTaskRunner` does not declare it, so it never
  appears in their select. `COMPONENT_BRIDGE` is what the UI uses to tell a user that
  `context.component.*` is unavailable on the selected runner.
- **The allowlist config is a `Map<String, RunnerConfig>` keyed by runner type**, so a new
  runner brings its own key without an `ApplicationProperties` edit.

Working-directory assembly is a shared `TaskRunnerWorkingDirectory` helper that produces a
*spec* — files, env, entrypoint, output globs. Each runner realises it its own way: a local
temp dir (Process), an archive copied into a container (Docker), an object-store upload (a
future AWS Batch or Cloud Run runner). If Process and Docker each assumed a local temp dir, no
remote runner could reuse any of it.

Adding a runner is therefore: one module, one `@Component`, one config key. No edits to
`script` or `commands`.

That extensibility is bought at a price, and three of the four costs fail silently, so each is a
binding constraint on the implementation rather than a note:

- **The `Property` objects a runner returns must be defensively copied before the
  `displayCondition` is stamped on them.** The DSL's `Modifiable*` builders are mutable, so
  stamping in place mutates objects shared across every action that includes them. A runner
  included by both `script` and `commands` would otherwise end up with whichever condition was
  applied last.
- **Runner iteration order must be deterministic** — sorted by `getType()`, not left to bean
  ordering — or the assembled property list, and therefore the definition snapshot, reorders
  between builds.
- **Definition snapshots must pin a deterministic runner set** on the test classpath. Otherwise
  they drift the way the `anthropic`/`gemini`/`mistral` snapshots do, for the same reason:
  the definition is derived from something outside the repo's control.
- **The definition is no longer readable in one file.** Unlike every other component here, you
  cannot see what `script/javascript` accepts without knowing which `TaskRunner` beans are on
  that app's classpath. The build-time component index records definitions as built, so an app
  assembling a different runner set than the one the index was generated from will disagree
  with its own index. Each action's README must therefore enumerate the built-in runners
  explicitly, since the definition no longer does.

## GraalVM: strict and trusted

`PolyglotSandbox.call(languageId, fn)` gains a mode overload; the existing signature delegates
with `STRICT`, so no existing call site changes.

```java
public static <V> V call(ScriptSandboxMode mode, String languageId, Function<Context, V> guestFunction)
```

| | STRICT (today, unchanged) | TRUSTED (new) |
|---|---|---|
| Policy | `CONSTRAINED` for `js`/`python` | `TRUSTED` |
| Host access | `NONE` (+ mutable target mappings) | `ALL` + class lookup + class loading |
| IO / env / native / process / threads | all denied | all allowed |
| Polyglot access | `NONE` | `ALL` |
| CPU + heap ceilings | 5 min / 512 MiB | **none** |
| Cancellation | resource ceilings | wall-clock timeout to `Context.close(true)` |

Three implementation notes that are easy to get silently wrong:

- **`SandboxPolicy.TRUSTED` and `ScriptSandboxMode.TRUSTED` mean opposite things.**
  `PolyglotSandbox` already builds `SandboxPolicy.TRUSTED` contexts today — for `java`, `R`,
  and when the kill switch is off — and those keep *every* restriction. The new mode is what
  lifts them. Hence the enum is named `ScriptSandboxMode`, and the distinction is stated in the
  class javadoc. Conflating them grants host access to every Java and R script.
- **`isConstrained()` must return false when the mode is `TRUSTED`**, or GraalVM rejects the
  context outright: `CONSTRAINED` forbids `HostAccess.ALL`.
- **The engine cache key needs the mode after all.** This was written as "no change needed" — an
  `Engine` carries the *policy*, host access is per-`Context`, so trusted `js` would reuse the
  existing `false:js` engine. Implementation disproved it: GraalVM additionally requires every
  context on one engine to carry the *same host access configuration*, and fails the second one
  outright with *"Found different host access configuration for a context with a shared engine"*.
  A strict context is also built on a `TRUSTED`-policy engine whenever the sandbox kill switch is
  off or the language is outside `js`/`python`, so trusted (`HostAccess.ALL`) and strict
  (`HostAccess.NONE`) collide on `false:<languages>`. The key therefore carries the
  `ScriptSandboxMode` as well. Without it, an operator who enables trusted mode *and* disables the
  sandbox loses one of the two modes entirely, whichever runs second in that JVM.

`sandbox.MaxCPUTime` and `sandbox.MaxHeapMemory` exist only under `CONSTRAINED`, so trusted
executions have no resource ceiling. They therefore take a mandatory wall-clock `timeout`
(default 5 minutes, matching the strict CPU ceiling) enforced by a watchdog calling
`Context.close(true)`.

**Trusted GraalVM is the most dangerous of the three runners, not the least.** Docker confines
to a container; Process is a separate OS process under the service account. Trusted GraalVM
runs inside the ByteChef JVM with full reflection, so a script can reach the
`ApplicationContext`, the `DataSource`, and decrypted connection credentials for every tenant.
It is disabled by default, logs a startup WARN when enabled (mirroring the existing
sandbox-disabled warning), and is documented as single-tenant, trusted-operator only.

## Component surfaces

### `script` (existing, extended)

Actions `javascript` and `python` keep their names, their `input` property and their default
`perform()` snippet. Added:

- `taskRunner` — object property. `type` select (default `graalvm`), options filtered by the
  allowlist; runner sub-properties revealed by `displayCondition`: `mode` (strict/trusted) for
  GraalVM, `image`/`pullPolicy`/`entrypoint`/`user`/`cpu`/`memory`/`networkMode`/`extraHosts`
  for Docker, `interpreter`/`inheritEnvironment` for Process.
- `env`, `inputFiles`, `outputFiles`, `timeout` — shown when the selected runner declares the
  matching capability. Capability gating is UI only; a hand-edited workflow that sets
  `inputFiles` under GraalVM is rejected by that runner's `validate()`, the same two-layer
  arrangement the allowlist uses. `timeout` bounds only executions that have no
  other ceiling. GraalVM **strict** carries the CONSTRAINED policy's own CPU and heap limits, so it
  passes no wall clock and stays exactly as it behaved before task runners existed; GraalVM
  **trusted** has no ceiling of any kind and therefore takes a 5-minute default. A caller-supplied
  timeout is honoured in either mode, and the external runners (Phase 2) always take one.
  Applying the 5-minute wall clock to strict as well looks harmless and is not: `sandbox.MaxCPUTime`
  meters CPU, not elapsed time, so a script waiting on a slow HTTP call through
  `context.component.*` burns almost no CPU and would be killed mid-request by a ceiling it never
  had before. Phase 2's `timeout` property must keep this distinction.
- Output via `output(OutputFunction)`: GraalVM yields the `perform()` return value; external
  runners yield `{exitCode, stdout, stderr, vars, outputFiles}`.

### `commands` (new)

New module `server/libs/modules/components/commands/`, component `commands`, actions `shell`,
`python`, `node`. `taskRunner` offers only runners declaring `COMMANDS` — in v1, Process and
Docker. There is no shell in a Truffle context, so GraalVM is absent by derivation, not by a
hardcoded list.

Properties: `commands` (array of string, required), `env`, `inputFiles`, `outputFiles`,
`timeout`, `warnOnStdErr`. Per-action defaults:

| Action | Docker image | Process interpreter |
|---|---|---|
| `shell` | `ubuntu:24.04` | `/bin/sh` |
| `python` | `python:3.12-slim` | `python3` |
| `node` | `node:22-alpine` | `node` |

## The external execution contract

Authoring is identical across all three runners: one inline code editor, the same
`perform(input, context)` shape. The runner changes only what happens after save. Under Docker
and Process the code is materialised to a temp file, a bootstrap is appended, and a real
interpreter runs it.

### Working directory

Created per execution, deleted in a `finally`:

```
<tmp>/bytechef-run-<uuid>/
├── script.js | script.py     script component only; user code verbatim + appended bootstrap
├── commands.sh               commands component only
├── input.json                the `input` object property, serialised
├── <inputFiles…>             from inline string content or a FileEntry
└── output/                   $BYTECHEF_OUTPUT_DIR
    └── output.json           written by the bootstrap, or by the user's own code
```

### Environment

The guest sees `BYTECHEF_WORKING_DIR`, `BYTECHEF_INPUT_FILE`, `BYTECHEF_OUTPUT_DIR`, and the
declared `env` entries — and nothing else. The host environment is **not** inherited
(`inheritEnvironment` defaults to false), so datasource passwords and cloud credentials present
in the server's environment cannot leak into a user's script.

### The bootstrap

Appended to the materialised file, never prepended: prepending shifts every line number, so a
syntax error in the user's code would be reported at the wrong line — very hard to diagnose
from a container log. The bootstrap reads `$BYTECHEF_INPUT_FILE` into `input`, calls
`perform(input, context)`, and writes the return value to `$BYTECHEF_OUTPUT_DIR/output.json`.

`context` is a stub, not `null`: a JS `Proxy` and a Python `__getattr__` that raise
*"context.component is not available under the <type> runner"*. A `null` would surface as
`TypeError: Cannot read properties of null`, which tells the user nothing about the cause.

### Outputs

- `exitCode`
- `stdout` / `stderr` — each captured up to 64 KiB, retaining the first and last 32 KiB with an
  elision marker between them when the stream is longer, so both the startup banner and the
  failing tail survive. The full stream is tee'd to the guest logger the way
  `GuestLoggingOutputStream` already does, so nothing is lost from the logs.
- `vars` — `output/output.json`, parsed if present
- `outputFiles` — glob patterns matched under `output/`, each stored via
  `context.file.storeContent` into `Map<String, FileEntry>`

A non-zero exit fails the task, with the stderr tail in the message.

### Process runner

`ProcessBuilder` with `directory(workingDir)` and a cleared environment repopulated from the
computed env. **Streams are drained concurrently with `waitFor`** on two virtual threads: a
process writing more than the OS pipe buffer (~64 KiB) blocks forever if the caller waits
first and reads after. On timeout, `destroyForcibly()` on the process *and* its descendants via
`ProcessHandle::descendants` — a `sh -c` wrapper otherwise leaves orphans behind.

### Docker runner

New `docker-java` entries in `gradle/libs.versions.toml` (`docker-java-core` +
`docker-java-transport-httpclient5`). Testcontainers already pulls docker-java transitively but
must not be used on a production path.

Lifecycle: pull per `pullPolicy` → create → copy in → start → attach logs → wait with timeout →
copy `output/` out → force-remove in a `finally`.

**Files move by `copyArchiveToContainer` / `copyArchiveFromContainer`, not bind mounts.** A
bind mount is resolved by the *daemon*: when ByteChef itself runs in a container with
`/var/run/docker.sock` mounted, the host path it asks for does not exist on the daemon's
filesystem, and Docker silently mounts an empty directory rather than failing. Archive copy is
daemon-location-agnostic and works unchanged against a remote `DOCKER_HOST`.

There is **no `volumes` property** in v1: a host bind mount chosen by a workflow author is a
direct container escape. It sits behind `allow-volume-mounts` in config and is absent from the
DSL until that is implemented.

## Governance

```yaml
bytechef:
  script:
    runners:
      graalvm:
        enabled: true
        trusted-enabled: false
      process:
        enabled: false
      docker:
        enabled: false
        host: ""
        allowed-images: []
        allow-host-network: false
        allow-volume-mounts: false
```

Enforced in two places, deliberately:

1. `TaskRunnerRegistry.getTaskRunner(type)` throws `TaskRunnerNotEnabledException` at perform
   time. **This is the security boundary.**
2. The `taskRunner.type` select's `options()` function returns only enabled runners. This is
   UX and carries no security weight — a hand-edited workflow JSON bypasses it and must still
   be rejected at (1).

Defaults are GraalVM strict only. Process, Docker and GraalVM trusted are each a deliberate
operator act.

**`trusted-enabled` needs its own gate, and it is not the registry's.** The registry answers
"may this workflow select the GraalVM runner at all"; it says nothing about which *mode* the
workflow then asks for. So `GraalVmTaskRunner.validate` rejects `mode: trusted` unless
`bytechef.script.runners.graalvm.properties.trusted-enabled` is `true`, failing closed on an
absent, unset or malformed value. Without that check the flag is documentation rather than
configuration: `PolyglotSandbox` grants `allowAllAccess(true)` to whoever asks, the editor's
mode picker is UX that a hand-edited workflow never passes through, and an operator who set the
flag false would still be one workflow edit away from a script running with full reflection
inside the server JVM. This was missed in the first draft of this spec and found in review.

### Configuration is consolidated into ApplicationProperties

`ApplicationProperties` is `@ConfigurationProperties(prefix = "bytechef", ignoreUnknownFields = false)`
and has **no `script` field**, yet `PolyglotSandboxProperties` binds `bytechef.script.sandbox.*`
as a standalone class, and neither key appears in any `application.yml`. Setting
`bytechef.script.sandbox.enabled` today may therefore trip strict binding, meaning the existing
sandbox kill switch may not be settable at all. Building a new allowlist on an unbindable prefix
would leave every runner silently disabled.

So `PolyglotSandboxProperties` is **deleted** and its fields move into a `Script` holder on
`ApplicationProperties`, alongside the new `runners` map:

```java
public static class Script {
    private Sandbox sandbox = new Sandbox();          // enabled, maxCpuTime, maxHeapMemory,
                                                      // maxConcurrentExecutions
    private Map<String, Runner> runners = new HashMap<>();
}
```

`platform-component-polyglot` gains a dependency on `server:libs:config:app-config`, which is
precedented — `platform-component-service`, `platform-component-log-service` and
`platform-component-context-service` already depend on it.

`PolyglotSandboxSettings` **stays**. It is the immutable seam that keeps the static
`PolyglotSandbox` free of Spring and lets tests swap settings; only its `@ConfigurationProperties`
source changes. `PolyglotSandboxConfiguration` reads `ApplicationProperties` instead.

The first task of the implementation plan reproduces the binding failure before the move and
confirms both `bytechef.script.sandbox.*` and `bytechef.script.runners.*` bind after it.

## Deployment

Runners execute wherever the component's task handler runs: `server-app` in the monolith and EE
`worker-app` in a distributed deployment. Nothing here touches the database or the coordinator,
so there is no monolith-only restriction.

Both new modules go on the classpath of any app that executes script tasks. The Docker runner
requires daemon access *from that host*, which in distributed EE means the workers, not the
coordinator.

**The `commands` module must be added to `server-app`'s dependencies**, not merely to
`settings.gradle.kts`. server-app's `generateComponentIndex` task writes
`META-INF/bytechef/component-index.json` at build time, and when an index is present it is
authoritative: a component absent from it is invisible in the components list and unresolvable
by name. A new component that builds and tests cleanly but never appears in the picker is
exactly this failure.

This is not a task dispatcher, so the three-registration-sites rule does not apply.

## Testing

- **Sandbox modes** — `TRUSTED` permits host class lookup and file reads; `STRICT` denies both;
  the legacy `call()` overload still defaults to `STRICT`.
- **`ProcessTaskRunnerTest`** — exit codes; a >64 KiB stdout regression test for the pipe
  deadlock; timeout kill including descendants; environment isolation, asserting that a
  variable set in the test JVM is absent from the child; working-directory cleanup.
- **`DockerTaskRunnerIntTest`** — gated on daemon availability; archive round-trip, `outputFiles`
  globs, non-zero exit, and rejection of an image outside `allowed-images`.
- **`TaskRunnerRegistryTest`** — a disabled runner throws; the options function filters.
- **Bootstrap tests per language** — a `perform()` return value reaches `output.json`;
  `context.component.*` raises the friendly error.
- **Definition snapshots** for both components. Because the definition is registry-derived, the
  snapshot varies with the runners on the test classpath, so the snapshot tests pin a
  deterministic runner set — otherwise they drift the way the `anthropic`/`gemini`/`mistral`
  snapshots do. Regenerating takes two runs, the first ending in the expected
  `NullPointerException: url`.

## Risks

1. **`bytechef.script.*` may not bind at all.** Verified before anything is built on it. See
   the governance section.
2. **`${}` collision — pre-existing, tracked separately.** `SpelEvaluator` treats every string
   in the task map as a SpEL template delimited by `${`/`}`, and JavaScript template literals
   use exactly that syntax. `INVALID_ACCESSOR_PATTERN` requires each `${…}` to be a bare
   accessor path, and the execution path calls the two-argument `evaluate`, which is
   `lenient = false`. So:

   | Script contains | Result at execution today |
   |---|---|
   | `` `Hi ${name}` `` | evaluated against the workflow context — silently substituted if a step or input is named `name` |
   | `` `Total ${a + b}` `` | `IllegalArgumentException: Invalid expression` — the whole task fails |
   | `` `${x ? y : z}` ``, `` `${fn()}` `` | same — the task fails |

   Most real template literals therefore make the task fail outright, with an error that never
   mentions the script. `expressionEnabled(false)` cannot fix this: it appears only in DTO
   `toString`s, the validator model and the client — **nothing in the evaluation path reads
   it**, so it is decorative at execution time.

   The fix is to make it load-bearing: the platform layer writes the property paths marked
   `expressionEnabled(false)` onto the workflow task under a reserved key, and the evaluator
   skips exactly those paths — atlas learns "skip these paths", never "script components
   exist". It also settles the semantics: the designed channel for workflow data reaching a
   script is the `input` property, not interpolation into the source.

   **This is out of scope here** and specified separately in [`2026-08-31-script-source-expression-evaluation-design.md`](2026-08-31-script-source-expression-evaluation-design.md). It breaks today's
   `script` component equally, independent of task runners, and it is a breaking change for
   anyone interpolating into script text deliberately. That spec also records the mechanism:
   `DeferredEvaluationParameterKeys` already holds parameters back from the evaluator for the
   conditional dispatchers, so the fix is a registration rather than new machinery. When the
   `commands` component lands it must register `commands/` → `commands` for the same reason,
   since POSIX shell uses `${VAR}` too.
3. **`displayCondition` on nested object sub-properties.** The entire `taskRunner` surface
   depends on it. Display conditions are resolved server-side by parameter path, so it should
   work, but it needs an empirical check in the editor early. Fallback: flatten to
   `taskRunnerType` + `taskRunnerDockerImage` + ….
4. **Trusted GraalVM is a full-tenant compromise primitive.** Off by default, with a startup
   WARN.
5. **Process and Docker are remote-code-execution by design.** The config allowlist is the only
   gate in v1. If the embedded multi-tenant story needs finer control, per-tenant runner policy
   is the deferred follow-up.
6. **Two runners contributing a same-named property collide on one stored value.** Every runner's
   properties are stamped with a `displayCondition` and flattened into the *one* `taskRunner`
   object property, so if Docker and Process both contribute a `timeout` — or an `image`, or a
   `user` — the two are two sub-properties of the same object with the same name. The client keys
   sub-properties by `name::displayCondition`, so both render and the editor looks correct; but
   the parameter map underneath one object is flat, so `taskRunner.timeout` is a single slot the
   two runners share. Switching runner silently inherits the other's value, and a value typed for
   one runner is submitted to the other.

   Harmless in phase 1, which has one runner. It becomes live the moment phase 2 adds Process and
   Docker, which is why it is recorded here rather than in a phase 2 plan that does not exist yet.
   Options when it lands, cheapest first: require runners to prefix their property names with
   their type (`dockerImage`, `processInterpreter`), which costs nothing but reads poorly in the
   DSL; or nest each runner's properties in a per-type object (`taskRunner.docker.image`), which
   reads well and changes the `displayCondition` paths and the stored workflow shape. Do not
   discover this by having two runners contribute `timeout`.

## Out of scope

- Kestra's `::{"outputs":…}::` and `::{"file":…}::` stdout marker protocol
- Reusable runner entities and per-project runner defaults
- Per-tenant runner policy and its admin UI
- Remote runners (AWS Batch, EC2, Cloud Run, Kubernetes) — the SPI must not preclude them
- Namespace files
- Registry credentials via a Connection
- Ruby and R (still `RUBY-DISABLED`)
- The `script` cluster elements (Script Tool, item processors), which stay GraalVM strict
- The `${}` / template-literal evaluator collision — specified in [`2026-08-31-script-source-expression-evaluation-design.md`](2026-08-31-script-source-expression-evaluation-design.md)

## Phasing

1. SPI, registry, allowlist, the `ApplicationProperties` fix, and the GraalVM runner with
   strict/trusted wired into the `script` actions. Shippable and useful on its own.
2. Working directory, bootstrap, external I/O contract, the Process runner, and the `commands`
   component.
3. The Docker runner.
4. Documentation (`README.mdx` for both components, `generateDocumentation`) and sample
   workflows.
