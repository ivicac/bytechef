# Script Task Runners — Phase 1: Foundation and the GraalVM Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a `TaskRunner` SPI with a config-gated registry, add `strict`/`trusted` sandbox modes to the GraalVM polyglot context, and route the `script` component's actions through the SPI — so a later phase can add Process and Docker runners without touching the components.

**Architecture:** A new `platform-component-runner-api` / `-impl` module pair, siblings of the existing `platform-component-polyglot`. `TaskRunner` implementations register as Spring beans and are resolved by a `String` type through `TaskRunnerRegistry`, which enforces an operator allowlist read from `ApplicationProperties`. The `script` actions gain a registry-derived `taskRunner` object property and delegate `perform` to the resolved runner. `GraalVmTaskRunner` wraps the existing `PolyglotEngine` and is the only runner in this phase.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Gradle 9.7 (Kotlin DSL), GraalVM Polyglot 25.2.4, JUnit 5, AssertJ, Mockito.

**Spec:** [`docs/superpowers/specs/2026-08-31-script-task-runners-design.md`](../specs/2026-08-31-script-task-runners-design.md)

## Global Constraints

- **Apache 2.0 licence header** on every new file under `server/libs/` (copy the header verbatim from any neighbouring file). EE headers and `@version ee` apply only under `server/ee/`, which this phase does not touch.
- **Run `./gradlew spotlessApply` before every commit.** Formatting is enforced; Spotless output wins over any layout in this plan.
- **Never judge a Gradle run piped into `tail`/`grep`.** Redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`.
- **Blank line before control statements** (`if`, `for`, `while`, `try`, `switch`), except immediately after an opening `{` or after another blank line. **Blank line after a variable modification** that precedes a statement using that variable. **No blank line** before a class's closing `}`.
- **No short or cryptic names**, including lambda parameters and loop variables. **No `_` prefix** on private methods.
- **No `TODO:` comments** — Checkstyle's `TodoComment` rule forbids them.
- **Test method names are camelCase without underscores** — `testExecuteSuccess`, never `testExecute_Success`. This applies to private helpers in test sources too.
- **Unit test classes end in `Test`; integration test classes end in `IntTest`.** Drop `Impl` from test class names.
- **Enum ordinals persist as INT** — append new values at the end, never reorder. (No enum in this phase is persisted, but the rule stands.)
- **Commit message convention:** `<ticket_number> <description>` for server-side changes. This plan uses `3901` as the ticket number throughout; substitute the real one if it differs.
- Every commit in this plan is a **new** commit. Never `git commit --amend` — the user commits to shared branches in parallel.

---

## File Structure

**New module: `server/libs/platform/platform-component/platform-component-runner/platform-component-runner-api`**

| File | Responsibility |
|---|---|
| `TaskRunner.java` | The SPI. One implementation per execution environment. |
| `TaskRunnerCapability.java` | Enum of optional features a runner may support. Drives per-action filtering and property visibility. |
| `TaskRunnerConstants.java` | Type strings and parameter names shared by the SPI, the components and `displayCondition` expressions. |
| `TaskRunnerRequest.java` | Record. Everything a runner needs to execute one task. |
| `TaskRunnerResult.java` | Record. Everything an execution produced. |
| `TaskRunnerRegistry.java` | Interface. Resolves a type to a runner, enforcing the allowlist. |
| `TaskRunnerNotEnabledException.java` | Thrown when a workflow selects a runner the operator has not enabled. |
| `TaskRunnerPropertyFactory.java` | Builds the `taskRunner` object property from the registry. Used by every component that offers runners. |

**New module: `server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl`**

| File | Responsibility |
|---|---|
| `TaskRunnerRegistryImpl.java` | Indexes the `TaskRunner` beans, applies the allowlist. |
| `GraalVmTaskRunner.java` | Runs the script in-process through `PolyglotEngine`, in strict or trusted mode. |

**Modified**

| File | Change |
|---|---|
| `settings.gradle.kts` | Two `include(...)` lines. |
| `ApplicationProperties.java` | New `Script` holder with `sandbox` and `runners`. |
| `PolyglotSandboxProperties.java` | **Deleted** — fields move to `ApplicationProperties.Script.Sandbox`. |
| `PolyglotSandboxConfiguration.java` | Reads `ApplicationProperties` instead. |
| `PolyglotSandbox.java` | `ScriptSandboxMode` overload; trusted context construction. |
| `ScriptSandboxMode.java` | **New**, in `platform-component-polyglot`. |
| `PolyglotEngine.java` | Accepts a `ScriptSandboxMode`. |
| `ScriptActionDefinition.java` | Delegates `perform` to the registry. |
| `ScriptJavaScriptAction.java`, `ScriptPythonAction.java` | Add the `taskRunner` property. |
| `ScriptComponentHandler.java` | Takes the registry, passes it to the actions. |
| `script_v1.json` | Regenerated snapshot. |
| Two `build.gradle.kts` files | New module wiring. |

---

## Task 1: Consolidate script configuration into ApplicationProperties

The spec's governance section depends on `bytechef.script.*` binding at all. `ApplicationProperties` is `@ConfigurationProperties(prefix = "bytechef", ignoreUnknownFields = false)` and has no `script` field, while `PolyglotSandboxProperties` binds `bytechef.script.sandbox.*` as a standalone class. This task proves the failure, then fixes it by moving the properties in.

**Files:**
- Create: `server/libs/config/app-config/src/test/java/com/bytechef/config/ApplicationPropertiesScriptBindingTest.java`
- Modify: `server/libs/config/app-config/src/main/java/com/bytechef/config/ApplicationProperties.java`
- Modify: `server/libs/platform/platform-component/platform-component-polyglot/src/main/java/com/bytechef/platform/component/polyglot/PolyglotSandboxConfiguration.java`
- Modify: `server/libs/platform/platform-component/platform-component-polyglot/build.gradle.kts`
- Delete: `server/libs/platform/platform-component/platform-component-polyglot/src/main/java/com/bytechef/platform/component/polyglot/PolyglotSandboxProperties.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ApplicationProperties.getScript()` returning `ApplicationProperties.Script`, with `getSandbox()` → `Script.Sandbox` (`isEnabled()`, `getMaxCpuTime()`, `getMaxHeapMemory()`, `getMaxConcurrentExecutions()`) and `getRunners()` → `Map<String, Script.Runner>` where `Runner` has `isEnabled()` and `getProperties()` returning `Map<String, String>`. Task 4 reads `getRunners()`; Task 2 reads `getSandbox()`.

- [ ] **Step 1: Write the failing binding test**

Create `server/libs/config/app-config/src/test/java/com/bytechef/config/ApplicationPropertiesScriptBindingTest.java`:

```java
package com.bytechef.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * @author Ivica Cardic
 */
public class ApplicationPropertiesScriptBindingTest {

    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    public void testSandboxPropertiesBind() {
        applicationContextRunner
            .withPropertyValues(
                "bytechef.script.sandbox.enabled=false",
                "bytechef.script.sandbox.max-cpu-time=30s",
                "bytechef.script.sandbox.max-heap-memory=64MB",
                "bytechef.script.sandbox.max-concurrent-executions=4")
            .run(context -> {
                assertThat(context).hasNotFailed();

                ApplicationProperties applicationProperties = context.getBean(ApplicationProperties.class);
                ApplicationProperties.Script.Sandbox sandbox = applicationProperties.getScript()
                    .getSandbox();

                assertThat(sandbox.isEnabled()).isFalse();
                assertThat(sandbox.getMaxCpuTime()).isEqualTo(Duration.ofSeconds(30));
                assertThat(sandbox.getMaxHeapMemory()
                    .toBytes()).isEqualTo(64L * 1024 * 1024);
                assertThat(sandbox.getMaxConcurrentExecutions()).isEqualTo(4);
            });
    }

    @Test
    public void testRunnerPropertiesBind() {
        applicationContextRunner
            .withPropertyValues(
                "bytechef.script.runners.graalvm.enabled=true",
                "bytechef.script.runners.graalvm.properties.trusted-enabled=true",
                "bytechef.script.runners.docker.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();

                ApplicationProperties applicationProperties = context.getBean(ApplicationProperties.class);

                assertThat(applicationProperties.getScript()
                    .getRunners()).containsOnlyKeys("graalvm", "docker");
                assertThat(applicationProperties.getScript()
                    .getRunners()
                    .get("graalvm")
                    .isEnabled()).isTrue();
                assertThat(applicationProperties.getScript()
                    .getRunners()
                    .get("graalvm")
                    .getProperties()).containsEntry("trusted-enabled", "true");
            });
    }

    @EnableConfigurationProperties(ApplicationProperties.class)
    static class TestConfiguration {
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
./gradlew :server:libs:config:app-config:test --tests '*ApplicationPropertiesScriptBindingTest*' > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|ApplicationPropertiesScriptBindingTest' /tmp/t1.log
```

Expected: FAIL. Compilation fails on `applicationProperties.getScript()` — the method does not exist. That absence *is* the bug: nothing models `bytechef.script.*`, so with `ignoreUnknownFields = false` those keys have nowhere to bind.

- [ ] **Step 3: Add the Script holder to ApplicationProperties**

In `ApplicationProperties.java`, add the field in alphabetical position among the other private fields (after `private Resources resources` / before `private Security security` — match the existing alphabetical ordering in the file):

```java
    /**
     * Script execution configuration: the guest polyglot sandbox and the task runner allowlist.
     */
    private Script script = new Script();
```

Add the getter and setter in their alphabetical positions among the existing getters and setters:

```java
    public Script getScript() {
        return script;
    }
```

```java
    public void setScript(Script script) {
        this.script = script;
    }
```

Add the nested class alongside the other `public static class` declarations:

```java
    /**
     * Script execution configuration.
     */
    public static class Script {

        /**
         * Guest polyglot sandbox configuration
         */
        private Sandbox sandbox = new Sandbox();

        /**
         * Task runner allowlist, keyed by runner type. A runner absent from this map is disabled.
         */
        private Map<String, Runner> runners = new HashMap<>();

        public Map<String, Runner> getRunners() {
            return runners;
        }

        public Sandbox getSandbox() {
            return sandbox;
        }

        public void setRunners(Map<String, Runner> runners) {
            this.runners = runners;
        }

        public void setSandbox(Sandbox sandbox) {
            this.sandbox = sandbox;
        }

        /**
         * Guest polyglot sandbox configuration. A null ceiling means unlimited and is left off the context builder
         * entirely, rather than passed as zero, which GraalVM reads as "deny everything".
         */
        public static class Sandbox {

            /**
             * Whether guest contexts are built under a sandbox policy above TRUSTED at all; the kill switch
             */
            private boolean enabled = true;

            /**
             * The CPU time a single guest execution may consume, or null for unlimited
             */
            private Duration maxCpuTime = Duration.ofMinutes(5);

            /**
             * The heap a single guest execution may allocate, or null for unlimited
             */
            private DataSize maxHeapMemory = DataSize.ofBytes(512L * 1024 * 1024);

            /**
             * How many guest executions may hold a platform thread at once; further executions queue
             */
            private int maxConcurrentExecutions = Math.max(
                16, Runtime.getRuntime()
                    .availableProcessors() * 4);

            public int getMaxConcurrentExecutions() {
                return maxConcurrentExecutions;
            }

            public Duration getMaxCpuTime() {
                return maxCpuTime;
            }

            public DataSize getMaxHeapMemory() {
                return maxHeapMemory;
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public void setMaxConcurrentExecutions(int maxConcurrentExecutions) {
                this.maxConcurrentExecutions = maxConcurrentExecutions;
            }

            public void setMaxCpuTime(Duration maxCpuTime) {
                this.maxCpuTime = maxCpuTime;
            }

            public void setMaxHeapMemory(DataSize maxHeapMemory) {
                this.maxHeapMemory = maxHeapMemory;
            }
        }

        /**
         * One task runner's operator configuration. Runner-specific settings live in the free-form properties map, so
         * a runner defined outside this module needs no change here.
         */
        public static class Runner {

            /**
             * Whether workflows may select this runner
             */
            private boolean enabled;

            /**
             * Runner-specific operator settings, interpreted by that runner
             */
            private Map<String, String> properties = new HashMap<>();

            public Map<String, String> getProperties() {
                return properties;
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public void setProperties(Map<String, String> properties) {
                this.properties = properties;
            }
        }
    }
```

Add the imports `java.time.Duration`, `java.util.HashMap`, `java.util.Map` and `org.springframework.util.unit.DataSize` if not already present.

Note the `runners` map keeps runner-specific settings as free-form `Map<String, String>` rather than typed fields. That is what keeps the allowlist open: a runner shipped from another module brings its own keys without an edit here, exactly as the spec's extensibility section requires.

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew :server:libs:config:app-config:test --tests '*ApplicationPropertiesScriptBindingTest*' > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t1.log
```

Expected: PASS, `exit=0`, no `FAILED` lines.

- [ ] **Step 5: Point PolyglotSandboxConfiguration at ApplicationProperties and delete PolyglotSandboxProperties**

Add to `server/libs/platform/platform-component/platform-component-polyglot/build.gradle.kts`, in the `dependencies` block among the other `implementation(project(...))` lines:

```kotlin
    implementation(project(":server:libs:config:app-config"))
```

This introduces no cycle: `app-config` depends on `platform-configuration-api`, which depends on `platform-component-api` — not on `platform-component-polyglot`.

Replace the body of `PolyglotSandboxConfiguration.java`:

```java
package com.bytechef.platform.component.polyglot;

import com.bytechef.config.ApplicationProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Pushes the configured sandbox settings into {@link PolyglotSandbox}, which the guest engines and code workflow
 * loaders reach statically rather than through the container.
 *
 * @author Ivica Cardic
 */
@Configuration
public class PolyglotSandboxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PolyglotSandboxConfiguration.class);

    private final PolyglotSandboxSettings polyglotSandboxSettings;

    public PolyglotSandboxConfiguration(ApplicationProperties applicationProperties) {
        ApplicationProperties.Script.Sandbox sandbox = applicationProperties.getScript()
            .getSandbox();

        DataSize maxHeapMemory = sandbox.getMaxHeapMemory();

        this.polyglotSandboxSettings = new PolyglotSandboxSettings(
            sandbox.isEnabled(), sandbox.getMaxCpuTime(), maxHeapMemory == null ? null : maxHeapMemory.toBytes(),
            sandbox.getMaxConcurrentExecutions());
    }

    @PostConstruct
    public void applyPolyglotSandboxSettings() {
        PolyglotSandbox.setSettings(polyglotSandboxSettings);

        if (!polyglotSandboxSettings.enabled()) {
            log.warn("Guest polyglot sandboxing is disabled; scripts run without resource limits");
        } else if (log.isInfoEnabled()) {
            log.info(
                "Guest polyglot sandbox enabled: maxCpuTime={}, maxHeapMemory={}",
                polyglotSandboxSettings.maxCpuTime(), polyglotSandboxSettings.maxHeapMemory());
        }
    }
}
```

Delete `PolyglotSandboxProperties.java`. `PolyglotSandboxSettings` stays untouched — it is the immutable seam that keeps the static `PolyglotSandbox` free of Spring and lets tests swap settings.

- [ ] **Step 6: Confirm nothing else referenced the deleted class**

```bash
grep -rn "PolyglotSandboxProperties" server client 2>/dev/null; echo "matches above should be empty"
```

Expected: no output. If anything matches, update it to read `ApplicationProperties` before continuing.

- [ ] **Step 7: Compile and run the polyglot module's tests**

```bash
./gradlew :server:libs:config:app-config:test :server:libs:platform:platform-component:platform-component-polyglot:test --continue > /tmp/t1b.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t1b.log
```

Expected: `exit=0`, no `FAILED` lines.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
git add server/libs/config/app-config server/libs/platform/platform-component/platform-component-polyglot
git commit -m "3901 Move script sandbox configuration into ApplicationProperties"
```

---

## Task 2: ScriptSandboxMode and the trusted context

**Files:**
- Create: `server/libs/platform/platform-component/platform-component-polyglot/src/main/java/com/bytechef/platform/component/polyglot/ScriptSandboxMode.java`
- Modify: `server/libs/platform/platform-component/platform-component-polyglot/src/main/java/com/bytechef/platform/component/polyglot/PolyglotSandbox.java`
- Test: `server/libs/platform/platform-component/platform-component-polyglot/src/test/java/com/bytechef/platform/component/polyglot/PolyglotSandboxTest.java` (existing file, add tests)

**Interfaces:**
- Consumes: `ApplicationProperties.Script.Sandbox` from Task 1 (indirectly, through the already-wired `PolyglotSandboxSettings`).
- Produces: `enum ScriptSandboxMode { STRICT, TRUSTED }` and `PolyglotSandbox.call(ScriptSandboxMode mode, String languageId, Function<Context, V> guestFunction)`. Task 5 calls this overload.

- [ ] **Step 1: Write the failing tests**

Append to `PolyglotSandboxTest.java`:

```java
    @Test
    public void testStrictModeDeniesHostClassLookup() {
        assertThatThrownBy(
            () -> PolyglotSandbox.call(
                ScriptSandboxMode.STRICT, "js", context -> context.eval("js", "Java.type('java.lang.System')")))
                    .isInstanceOf(PolyglotException.class);
    }

    @Test
    public void testTrustedModeAllowsHostClassLookup() {
        Value value = PolyglotSandbox.call(
            ScriptSandboxMode.TRUSTED, "js", null,
            context -> context.eval("js", "Java.type('java.lang.System').getProperty('java.version')"));

        assertThat(value.asString()).isNotBlank();
    }

    @Test
    public void testLegacyCallDefaultsToStrictMode() {
        assertThatThrownBy(
            () -> PolyglotSandbox.call("js", context -> context.eval("js", "Java.type('java.lang.System')")))
                .isInstanceOf(PolyglotException.class);
    }

    @Test
    public void testTrustedModeTimeoutCancelsRunawayScript() {
        // Trusted contexts carry no resource ceiling at all - the CONSTRAINED policy is what provides one - so the
        // wall-clock timeout is the ONLY thing that can stop a runaway trusted script. Without it an infinite loop
        // pins a platform thread until the JVM dies.
        assertThatThrownBy(
            () -> PolyglotSandbox.call(
                ScriptSandboxMode.TRUSTED, "js", Duration.ofSeconds(2),
                context -> context.eval("js", "while (true) {}")))
                    .isInstanceOf(PolyglotException.class);
    }

    @Test
    public void testTrustedModeIgnoresResourceCeilings() {
        PolyglotSandbox.setSettings(
            new PolyglotSandboxSettings(
                true, Duration.ofSeconds(1), PolyglotSandboxSettings.DEFAULT_MAX_HEAP_MEMORY,
                PolyglotSandboxSettings.DEFAULT_MAX_CONCURRENT_EXECUTIONS));

        // The CONSTRAINED policy is what carries sandbox.MaxCPUTime, so a trusted context has no ceiling at all.
        // This asserts the absence deliberately: the caller-side timeout, not the policy, is what bounds a trusted
        // execution.
        Value value = PolyglotSandbox.call(
            ScriptSandboxMode.TRUSTED, "js", null,
            context -> context.eval("js", "let total = 0; for (let i = 0; i < 20000000; i++) { total += i; } total"));

        assertThat(value.asDouble()).isGreaterThan(0);
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-polyglot:test --tests '*PolyglotSandboxTest*' > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|cannot find symbol' /tmp/t2.log | head
```

Expected: FAIL — `cannot find symbol: ScriptSandboxMode`.

- [ ] **Step 3: Create ScriptSandboxMode**

```java
package com.bytechef.platform.component.polyglot;

/**
 * How much the guest context is allowed to reach outside itself.
 *
 * <p>
 * Not to be confused with {@link org.graalvm.polyglot.SandboxPolicy}, whose {@code TRUSTED} constant this enum's
 * {@code TRUSTED} does <strong>not</strong> mirror. {@link PolyglotSandbox} already builds
 * {@code SandboxPolicy.TRUSTED} contexts under {@link #STRICT} - for languages outside the constrained set, and when
 * the sandbox kill switch is off - and those keep every restriction: no host access, no IO, no process creation. The
 * policy governs which resource ceilings GraalVM can meter; this enum governs whether the guest may touch the host at
 * all. They are separate axes that happen to share a word.
 *
 * @author Ivica Cardic
 */
public enum ScriptSandboxMode {

    /**
     * No host access, no IO, no environment, no process or thread creation, and the configured CPU and heap ceilings
     * where the language supports them. The default for every caller that does not ask otherwise.
     */
    STRICT,

    /**
     * Full host access: class lookup and loading, native access, IO, the host environment, process and thread
     * creation. The guest runs inside this JVM with reflection, so it can reach the Spring context, the datasource and
     * decrypted credentials for every tenant. Single-tenant, trusted-operator deployments only.
     */
    TRUSTED
}
```

- [ ] **Step 4: Add the mode overload to PolyglotSandbox**

In `PolyglotSandbox.java`, change `call` and the methods it threads the mode through. Replace the existing `call`, `callInline`, `newContext`, `requiresGuestThread` and `isConstrained` with these versions, leaving every other method as it is:

```java
    /**
     * Runs the given function against a strictly sandboxed {@link Context}. Equivalent to
     * {@link #call(ScriptSandboxMode, String, Function)} with {@link ScriptSandboxMode#STRICT}.
     */
    public static <V> V call(String languageId, Function<Context, V> guestFunction) {
        return call(ScriptSandboxMode.STRICT, languageId, guestFunction);
    }

    /**
     * Runs the given function against a {@link Context} built for the given mode, on a thread the sandbox's resource
     * ceilings can be measured on, and closes the context before returning.
     *
     * @param mode          how much the guest may reach outside itself
     * @param languageId    the language id the context is permitted to evaluate
     * @param guestFunction the guest execution
     * @return whatever the function returned
     */
    public static <V> V call(ScriptSandboxMode mode, String languageId, Function<Context, V> guestFunction) {
        return call(mode, languageId, null, guestFunction);
    }

    /**
     * Runs the given function against a {@link Context} built for the given mode, cancelling it if it outlives the
     * timeout.
     *
     * <p>
     * The timeout matters most under {@link ScriptSandboxMode#TRUSTED}, which carries no resource ceilings at all -
     * {@code sandbox.*} options exist only under {@code CONSTRAINED} - so it is the only thing that can stop a
     * runaway trusted script.
     *
     * @param mode          how much the guest may reach outside itself
     * @param languageId    the language id the context is permitted to evaluate
     * @param timeout       the wall-clock ceiling, or null for none
     * @param guestFunction the guest execution
     * @return whatever the function returned
     */
    public static <V> V call(
        ScriptSandboxMode mode, String languageId, @Nullable Duration timeout, Function<Context, V> guestFunction) {

        PolyglotSandboxSettings currentSettings = settings;

        if (requiresGuestThread(currentSettings, mode, languageId)) {
            PolyglotGuestExecutor polyglotGuestExecutor = getGuestExecutor(currentSettings);

            return polyglotGuestExecutor.call(
                () -> callInline(currentSettings, mode, timeout, guestFunction, languageId));
        }

        return callInline(currentSettings, mode, timeout, guestFunction, languageId);
    }

    private static <V> V callInline(
        PolyglotSandboxSettings currentSettings, ScriptSandboxMode mode, @Nullable Duration timeout,
        Function<Context, V> guestFunction, String... permittedLanguages) {

        try (Context context = newContext(currentSettings, mode, permittedLanguages)) {
            if (timeout == null) {
                return guestFunction.apply(context);
            }

            // close(true) cancels whatever the context is executing. Scheduled rather than joined, so the guest keeps
            // running on this thread and the watchdog fires only if it overstays.
            ScheduledFuture<?> watchdogFuture = WATCHDOG_EXECUTOR.schedule(
                () -> context.close(true), timeout.toMillis(), TimeUnit.MILLISECONDS);

            try {
                return guestFunction.apply(context);
            } finally {
                watchdogFuture.cancel(false);
            }
        }
    }

    private static Context newContext(
        PolyglotSandboxSettings currentSettings, ScriptSandboxMode mode, String... permittedLanguages) {

        if (mode == ScriptSandboxMode.TRUSTED) {
            return newTrustedContext(permittedLanguages);
        }

        boolean constrained = isConstrained(currentSettings, mode, permittedLanguages);

        Context.Builder builder = Context.newBuilder(permittedLanguages)
            .engine(getEngine(constrained, permittedLanguages))
            .allowHostAccess(constrained ? CONSTRAINED_HOST_ACCESS : HostAccess.NONE)
            .allowHostClassLoading(false)
            .allowHostClassLookup(className -> false)
            .allowNativeAccess(false)
            .allowCreateThread(false)
            .allowCreateProcess(false)
            .allowIO(IOAccess.NONE)
            .allowEnvironmentAccess(EnvironmentAccess.NONE)
            .allowPolyglotAccess(PolyglotAccess.NONE);

        if (constrained) {
            applySandboxPolicy(builder, currentSettings);
        }

        return builder.build();
    }

    /**
     * A context with every restriction lifted. Reachable only when the operator has enabled the trusted GraalVM runner
     * and the workflow selected it. Nothing here is metered: {@code sandbox.*} options exist only under
     * {@code CONSTRAINED}, so a trusted execution is bounded by the caller's wall-clock timeout instead.
     */
    private static Context newTrustedContext(String... permittedLanguages) {
        return Context.newBuilder(permittedLanguages)
            .engine(getEngine(false, permittedLanguages))
            .allowAllAccess(true)
            .build();
    }

    private static boolean requiresGuestThread(
        PolyglotSandboxSettings currentSettings, ScriptSandboxMode mode, String... permittedLanguages) {

        Thread currentThread = Thread.currentThread();

        if (!currentThread.isVirtual()) {
            return false;
        }

        return isConstrained(currentSettings, mode, permittedLanguages) && isThreadMetered(currentSettings);
    }

    private static boolean isConstrained(
        PolyglotSandboxSettings currentSettings, ScriptSandboxMode mode, String... permittedLanguages) {

        // A trusted context carries HostAccess.ALL, which the CONSTRAINED policy rejects outright, so the mode
        // decides this before the settings get a say.
        if (mode == ScriptSandboxMode.TRUSTED) {
            return false;
        }

        if (!currentSettings.enabled() || permittedLanguages.length == 0) {
            return false;
        }

        for (String permittedLanguage : permittedLanguages) {
            if (!CONSTRAINED_LANGUAGE_IDS.contains(permittedLanguage)) {
                return false;
            }
        }

        return true;
    }
```

The engine cache needs no change. An `Engine` carries the sandbox *policy*; host access is set per-`Context`. A trusted context is not constrained, so it correctly reuses the existing `false:<language>` engine.

Add the watchdog executor as a private static field, next to the existing `GUEST_EXECUTOR_LOCK` field:

```java
    /**
     * Cancels guest executions that outlive their wall-clock timeout. One daemon thread: the work is a single
     * {@code close(true)} call per expiry, and it must not hold JVM shutdown open.
     */
    private static final ScheduledExecutorService WATCHDOG_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
        runnable -> {
            Thread thread = new Thread(runnable, "polyglot-sandbox-watchdog");

            thread.setDaemon(true);

            return thread;
        });
```

Add the imports `java.time.Duration`, `java.util.concurrent.Executors`, `java.util.concurrent.ScheduledExecutorService`, `java.util.concurrent.ScheduledFuture` and `java.util.concurrent.TimeUnit`. `java.time.Duration` and `org.jspecify.annotations.Nullable` are already imported.

Keep the existing `RUBY-DISABLED` comment block above `allowCreateThread(false)` where it is.

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-polyglot:test --tests '*PolyglotSandboxTest*' > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t2.log
```

Expected: PASS, `exit=0`.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-component/platform-component-polyglot
git commit -m "3901 Add strict and trusted script sandbox modes"
```

---

## Task 3: The TaskRunner SPI module

**Files:**
- Create: `server/libs/platform/platform-component/platform-component-runner/platform-component-runner-api/build.gradle.kts`
- Create: `.../platform-component-runner-api/src/main/java/com/bytechef/platform/component/runner/TaskRunner.java`
- Create: `.../TaskRunnerCapability.java`, `.../TaskRunnerConstants.java`, `.../TaskRunnerRequest.java`, `.../TaskRunnerResult.java`, `.../TaskRunnerRegistry.java`, `.../TaskRunnerNotEnabledException.java`
- Test: `.../platform-component-runner-api/src/test/java/com/bytechef/platform/component/runner/TaskRunnerRequestTest.java`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `ScriptSandboxMode` from Task 2, `ActionContext` and `Property` from the component SDK.
- Produces: the whole SPI. Task 4 implements `TaskRunnerRegistry`, Task 5 implements `TaskRunner`, Task 6 consumes `getProperties()`/`getCapabilities()`, Task 7 calls `TaskRunnerRegistry.getTaskRunner`.

- [ ] **Step 1: Declare the module**

Add to `settings.gradle.kts`, immediately after the `platform-component-polyglot` line so the list stays alphabetical:

```kotlin
include("server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api")
include("server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl")
```

Create `platform-component-runner-api/build.gradle.kts`:

```kotlin
dependencies {
    api(project(":server:libs:platform:platform-component:platform-component-api"))
    api(project(":sdks:backend:java:component-api"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
}
```

Create an empty placeholder so Gradle can configure the impl module now and Task 4 fills it in — `platform-component-runner-impl/build.gradle.kts`:

```kotlin
dependencies {
    api(project(":server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api"))

    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework:spring-context")
    implementation(project(":server:libs:config:app-config"))
    implementation(project(":server:libs:platform:platform-component:platform-component-polyglot"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
}
```

- [ ] **Step 2: Write the SPI types**

`TaskRunnerCapability.java`:

```java
package com.bytechef.platform.component.runner;

/**
 * An optional feature a {@link TaskRunner} may support.
 *
 * <p>
 * Capabilities are what keep the runner set open: a component asks the registry which runners declare the capability
 * it needs, rather than naming runners it knows about. They also drive property visibility, so a property a runner
 * cannot honour is never shown for it.
 *
 * @author Ivica Cardic
 */
public enum TaskRunnerCapability {

    /** Runs inline source written in the action's language. */
    INLINE_SCRIPT,

    /** Runs a list of shell commands. */
    COMMANDS,

    /** Exposes the in-JVM bridge letting a script invoke other components through {@code context.component}. */
    COMPONENT_BRIDGE,

    /** Materialises caller-supplied files into the execution's working directory. */
    INPUT_FILES,

    /** Collects files produced by the execution and stores them as file entries. */
    OUTPUT_FILES,

    /** Passes caller-supplied environment variables to the execution. */
    ENVIRONMENT
}
```

`TaskRunnerConstants.java`:

```java
package com.bytechef.platform.component.runner;

/**
 * Names shared by the SPI, the components that offer runners, and the {@code displayCondition} expressions that reveal
 * a runner's own properties.
 *
 * <p>
 * The built-in type strings are constants rather than an enum on purpose. A runner defined outside this module must be
 * able to declare its own type without an edit here.
 *
 * @author Ivica Cardic
 */
public class TaskRunnerConstants {

    public static final String TASK_RUNNER = "taskRunner";
    public static final String TYPE = "type";

    public static final String DOCKER = "docker";
    public static final String GRAALVM = "graalvm";
    public static final String PROCESS = "process";

    public static final String MODE = "mode";
    public static final String STRICT = "strict";
    public static final String TRUSTED = "trusted";

    private TaskRunnerConstants() {
    }
}
```

`TaskRunnerRequest.java`:

```java
package com.bytechef.platform.component.runner;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.ComponentConnection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything a {@link TaskRunner} needs to execute one task.
 *
 * <p>
 * The record carries the union of what every runner may need, and each runner rejects in {@link TaskRunner#validate}
 * what it cannot honour. That is deliberate: one request type keeps one registry, one dispatch point for the
 * allowlist, and an explicit error instead of a silently ignored setting.
 *
 * @param languageId         the language the script is written in, or the interpreter for commands
 * @param script             inline source, or null when commands are given
 * @param commands           the commands to run, or an empty list when a script is given
 * @param input              values the script reads as its input argument
 * @param env                environment variables the execution should see
 * @param inputFiles         files to materialise into the working directory, keyed by file name
 * @param outputFilePatterns glob patterns matched against the output directory after the execution
 * @param inputParameters    the action's own parameters, which an in-process runner reads the source from
 * @param runnerParameters   the selected runner's own configuration, from the taskRunner property
 * @param timeout            the wall-clock ceiling for the execution
 * @param componentConnections connections the script may reach through the component bridge
 * @param actionContext      the action context, used for file storage and logging
 * @author Ivica Cardic
 */
public record TaskRunnerRequest(
    String languageId, @Nullable String script, List<String> commands, Map<String, ?> input, Map<String, String> env,
    Map<String, ?> inputFiles, List<String> outputFilePatterns, Parameters inputParameters,
    Parameters runnerParameters, Duration timeout, Map<String, ComponentConnection> componentConnections,
    ActionContext actionContext) {

    public TaskRunnerRequest {
        if (script == null && commands.isEmpty()) {
            throw new IllegalArgumentException("either script or commands must be given");
        }

        if (script != null && !commands.isEmpty()) {
            throw new IllegalArgumentException("script and commands are mutually exclusive");
        }
    }
}
```

`TaskRunnerResult.java`:

```java
package com.bytechef.platform.component.runner;

import com.bytechef.component.definition.FileEntry;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything one execution produced.
 *
 * @param output      the value the script returned, or the structured variables an external execution wrote
 * @param exitCode    the process exit code, or null for an in-process runner that has none
 * @param stdout      captured standard output, empty for an in-process runner
 * @param stderr      captured standard error, empty for an in-process runner
 * @param outputFiles files the execution produced, keyed by file name
 * @author Ivica Cardic
 */
public record TaskRunnerResult(
    @Nullable Object output, @Nullable Integer exitCode, String stdout, String stderr,
    Map<String, FileEntry> outputFiles) {

    public static TaskRunnerResult ofOutput(@Nullable Object output) {
        return new TaskRunnerResult(output, null, "", "", Map.of());
    }
}
```

`TaskRunner.java`:

```java
package com.bytechef.platform.component.runner;

import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import java.util.List;
import java.util.Set;

/**
 * An environment a script or a set of commands can be executed in.
 *
 * <p>
 * Implementations register as Spring beans and are resolved by {@link #getType()} through {@link TaskRunnerRegistry}.
 * Adding a runner is one module contributing one bean plus one configuration key; no component changes.
 *
 * @author Ivica Cardic
 */
public interface TaskRunner {

    /**
     * The stable identifier this runner is selected by, in workflow JSON, configuration keys and
     * {@code displayCondition} expressions. Lower case, no spaces.
     */
    String getType();

    /**
     * The human-readable name shown in the runner select.
     */
    String getTitle();

    /**
     * This runner's own configuration properties, contributed into the {@code taskRunner} object property.
     *
     * <p>
     * <strong>Must return freshly constructed properties on every call.</strong> The caller stamps a
     * {@code displayCondition} onto each, and the DSL's property builders are mutable - a shared list would end up
     * carrying whichever condition was applied last, across every action that included it.
     */
    List<? extends ModifiableValueProperty<?, ?>> getProperties();

    /**
     * What this runner supports. A component offers this runner only if the capabilities it needs are present, and
     * hides properties whose capability is absent.
     */
    Set<TaskRunnerCapability> getCapabilities();

    /**
     * Rejects a request carrying configuration this runner cannot honour, before any work starts.
     *
     * @throws IllegalArgumentException if the request cannot be executed as specified
     */
    void validate(TaskRunnerRequest request);

    /**
     * Executes the request.
     */
    TaskRunnerResult run(TaskRunnerRequest request);
}
```

`TaskRunnerRegistry.java`:

```java
package com.bytechef.platform.component.runner;

import java.util.List;
import java.util.Set;

/**
 * Resolves a runner type to its {@link TaskRunner}, enforcing the operator allowlist.
 *
 * @author Ivica Cardic
 */
public interface TaskRunnerRegistry {

    /**
     * Returns the runner registered under the given type.
     *
     * @throws TaskRunnerNotEnabledException if no such runner is registered, or the operator has not enabled it
     */
    TaskRunner getTaskRunner(String type);

    /**
     * Returns the enabled runners declaring every one of the given capabilities, ordered by type so the assembled
     * property list is stable between builds.
     */
    List<TaskRunner> getTaskRunners(Set<TaskRunnerCapability> requiredCapabilities);
}
```

`TaskRunnerNotEnabledException.java`:

```java
package com.bytechef.platform.component.runner;

/**
 * Thrown when a workflow selects a runner that is not registered, or that the operator has not enabled.
 *
 * @author Ivica Cardic
 */
public class TaskRunnerNotEnabledException extends RuntimeException {

    private final String type;

    public TaskRunnerNotEnabledException(String type) {
        super(
            "Task runner '%s' is not enabled. An operator must enable it with bytechef.script.runners.%s.enabled=true."
                .formatted(type, type));

        this.type = type;
    }

    public String getType() {
        return type;
    }
}
```

- [ ] **Step 3: Write the request-validation test**

`TaskRunnerRequestTest.java`:

```java
package com.bytechef.platform.component.runner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
public class TaskRunnerRequestTest {

    @Test
    public void testRejectsNeitherScriptNorCommands() {
        assertThatThrownBy(() -> newRequest(null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("either script or commands");
    }

    @Test
    public void testRejectsBothScriptAndCommands() {
        assertThatThrownBy(() -> newRequest("return null;", List.of("echo hi")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mutually exclusive");
    }

    private static TaskRunnerRequest newRequest(String script, List<String> commands) {
        return new TaskRunnerRequest(
            "js", script, commands, Map.of(), Map.of(), Map.of(), List.of(), null, null, Duration.ofMinutes(5),
            Map.of(), null);
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api:test > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t3.log
```

Expected: PASS, `exit=0`. If `ModifiableValueProperty` cannot be imported, confirm the SDK dependency resolved — `component-api` is at `sdks/backend/java/component-api`.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
git add settings.gradle.kts server/libs/platform/platform-component/platform-component-runner
git commit -m "3901 Add the task runner SPI"
```

---

## Task 4: TaskRunnerRegistry and the allowlist

**Files:**
- Create: `.../platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/TaskRunnerRegistryImpl.java`
- Test: `.../platform-component-runner-impl/src/test/java/com/bytechef/platform/component/runner/TaskRunnerRegistryTest.java`

**Interfaces:**
- Consumes: `TaskRunner`, `TaskRunnerRegistry`, `TaskRunnerNotEnabledException`, `TaskRunnerCapability` (Task 3); `ApplicationProperties.Script.Runner` (Task 1).
- Produces: `TaskRunnerRegistryImpl` as a `@Component`. Task 6 and Task 7 inject `TaskRunnerRegistry`.

- [ ] **Step 1: Write the failing tests**

```java
package com.bytechef.platform.component.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.config.ApplicationProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
public class TaskRunnerRegistryTest {

    @Test
    public void testReturnsEnabledRunner() {
        TaskRunner taskRunner = newTaskRunner("graalvm", Set.of(TaskRunnerCapability.INLINE_SCRIPT));

        TaskRunnerRegistry taskRunnerRegistry = newRegistry(List.of(taskRunner), Map.of("graalvm", true));

        assertThat(taskRunnerRegistry.getTaskRunner("graalvm")).isSameAs(taskRunner);
    }

    @Test
    public void testThrowsForDisabledRunner() {
        TaskRunner taskRunner = newTaskRunner("process", Set.of(TaskRunnerCapability.COMMANDS));

        TaskRunnerRegistry taskRunnerRegistry = newRegistry(List.of(taskRunner), Map.of("process", false));

        assertThatThrownBy(() -> taskRunnerRegistry.getTaskRunner("process"))
            .isInstanceOf(TaskRunnerNotEnabledException.class)
            .hasMessageContaining("bytechef.script.runners.process.enabled=true");
    }

    @Test
    public void testThrowsForRunnerAbsentFromConfiguration() {
        TaskRunner taskRunner = newTaskRunner("docker", Set.of(TaskRunnerCapability.COMMANDS));

        TaskRunnerRegistry taskRunnerRegistry = newRegistry(List.of(taskRunner), Map.of());

        assertThatThrownBy(() -> taskRunnerRegistry.getTaskRunner("docker"))
            .isInstanceOf(TaskRunnerNotEnabledException.class);
    }

    @Test
    public void testThrowsForUnregisteredRunner() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry(List.of(), Map.of("kubernetes", true));

        assertThatThrownBy(() -> taskRunnerRegistry.getTaskRunner("kubernetes"))
            .isInstanceOf(TaskRunnerNotEnabledException.class);
    }

    @Test
    public void testFiltersByCapabilityAndOrdersByType() {
        TaskRunner dockerTaskRunner = newTaskRunner("docker", Set.of(TaskRunnerCapability.COMMANDS));
        TaskRunner graalVmTaskRunner = newTaskRunner("graalvm", Set.of(TaskRunnerCapability.INLINE_SCRIPT));
        TaskRunner processTaskRunner = newTaskRunner("process", Set.of(TaskRunnerCapability.COMMANDS));

        TaskRunnerRegistry taskRunnerRegistry = newRegistry(
            List.of(processTaskRunner, graalVmTaskRunner, dockerTaskRunner),
            Map.of("docker", true, "graalvm", true, "process", true));

        List<TaskRunner> taskRunners = taskRunnerRegistry.getTaskRunners(Set.of(TaskRunnerCapability.COMMANDS));

        assertThat(taskRunners).containsExactly(dockerTaskRunner, processTaskRunner);
    }

    @Test
    public void testExcludesDisabledRunnersFromCapabilityLookup() {
        TaskRunner dockerTaskRunner = newTaskRunner("docker", Set.of(TaskRunnerCapability.COMMANDS));
        TaskRunner processTaskRunner = newTaskRunner("process", Set.of(TaskRunnerCapability.COMMANDS));

        TaskRunnerRegistry taskRunnerRegistry = newRegistry(
            List.of(dockerTaskRunner, processTaskRunner), Map.of("process", true));

        assertThat(taskRunnerRegistry.getTaskRunners(Set.of(TaskRunnerCapability.COMMANDS)))
            .containsExactly(processTaskRunner);
    }

    private static TaskRunner newTaskRunner(String type, Set<TaskRunnerCapability> capabilities) {
        TaskRunner taskRunner = mock(TaskRunner.class);

        when(taskRunner.getType()).thenReturn(type);
        when(taskRunner.getCapabilities()).thenReturn(capabilities);

        return taskRunner;
    }

    private static TaskRunnerRegistry newRegistry(List<TaskRunner> taskRunners, Map<String, Boolean> enabledByType) {
        ApplicationProperties applicationProperties = new ApplicationProperties();

        for (Map.Entry<String, Boolean> entry : enabledByType.entrySet()) {
            ApplicationProperties.Script.Runner runner = new ApplicationProperties.Script.Runner();

            runner.setEnabled(entry.getValue());

            applicationProperties.getScript()
                .getRunners()
                .put(entry.getKey(), runner);
        }

        return new TaskRunnerRegistryImpl(taskRunners, applicationProperties);
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|cannot find symbol' /tmp/t4.log | head
```

Expected: FAIL — `cannot find symbol: TaskRunnerRegistryImpl`.

- [ ] **Step 3: Implement the registry**

```java
package com.bytechef.platform.component.runner;

import com.bytechef.config.ApplicationProperties;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Indexes the registered {@link TaskRunner} beans and applies the operator allowlist.
 *
 * <p>
 * This is the security boundary for runner selection. The runner select in the editor also filters to enabled runners,
 * but that is UX only - a hand-edited workflow reaches this class, and it must reject there.
 *
 * @author Ivica Cardic
 */
@Component
public class TaskRunnerRegistryImpl implements TaskRunnerRegistry {

    private final Map<String, TaskRunner> taskRunnerMap;
    private final ApplicationProperties applicationProperties;

    public TaskRunnerRegistryImpl(List<TaskRunner> taskRunners, ApplicationProperties applicationProperties) {
        Map<String, TaskRunner> map = new LinkedHashMap<>();

        List<TaskRunner> sortedTaskRunners = taskRunners.stream()
            .sorted(Comparator.comparing(TaskRunner::getType))
            .toList();

        for (TaskRunner taskRunner : sortedTaskRunners) {
            map.put(taskRunner.getType(), taskRunner);
        }

        this.taskRunnerMap = map;
        this.applicationProperties = applicationProperties;
    }

    @Override
    public TaskRunner getTaskRunner(String type) {
        TaskRunner taskRunner = taskRunnerMap.get(type);

        if (taskRunner == null || !isEnabled(type)) {
            throw new TaskRunnerNotEnabledException(type);
        }

        return taskRunner;
    }

    @Override
    public List<TaskRunner> getTaskRunners(Set<TaskRunnerCapability> requiredCapabilities) {
        return taskRunnerMap.values()
            .stream()
            .filter(taskRunner -> isEnabled(taskRunner.getType()))
            .filter(taskRunner -> {
                Set<TaskRunnerCapability> capabilities = taskRunner.getCapabilities();

                return capabilities.containsAll(requiredCapabilities);
            })
            .toList();
    }

    /**
     * Warns once at startup for each enabled runner that can reach outside the sandbox. Trusted GraalVM is the loudest
     * of them: it runs guest code inside this JVM with full reflection, so a script can reach the application context,
     * the datasource and decrypted credentials for every tenant.
     */
    @PostConstruct
    public void logEnabledRunners() {
        for (TaskRunner taskRunner : taskRunnerMap.values()) {
            String type = taskRunner.getType();

            if (!isEnabled(type)) {
                continue;
            }

            if (GRAALVM.equals(type) && isTrustedEnabled(type)) {
                log.warn(
                    "Trusted GraalVM task runner is enabled; scripts selecting it run inside this JVM with full " +
                        "host access. Enable it only on a single-tenant deployment you control.");
            } else if (!GRAALVM.equals(type)) {
                log.warn("Task runner '{}' is enabled; workflows may execute code outside the script sandbox", type);
            }
        }
    }

    private boolean isTrustedEnabled(String type) {
        ApplicationProperties.Script.Runner runner = applicationProperties.getScript()
            .getRunners()
            .get(type);

        if (runner == null) {
            return false;
        }

        return Boolean.parseBoolean(
            runner.getProperties()
                .getOrDefault("trusted-enabled", "false"));
    }

    private boolean isEnabled(String type) {
        Map<String, ApplicationProperties.Script.Runner> runners = applicationProperties.getScript()
            .getRunners();

        ApplicationProperties.Script.Runner runner = runners.get(type);

        return runner != null && runner.isEnabled();
    }
}
```

Add the imports `jakarta.annotation.PostConstruct`, `org.slf4j.Logger`, `org.slf4j.LoggerFactory`, and the static import of `TaskRunnerConstants.GRAALVM`, plus the field:

```java
    private static final Logger log = LoggerFactory.getLogger(TaskRunnerRegistryImpl.class);
```

The constructor sorts once and stores into a `LinkedHashMap`, so `getTaskRunners` returns a stable order without re-sorting on every call. That ordering is what keeps the assembled `taskRunner` property — and therefore the definition snapshots — from reordering between builds.

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t4.log
```

Expected: PASS, `exit=0`.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-component/platform-component-runner
git commit -m "3901 Add the task runner registry and operator allowlist"
```

---

## Task 5: GraalVmTaskRunner

**Files:**
- Create: `.../platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/GraalVmTaskRunner.java`
- Test: `.../platform-component-runner-impl/src/test/java/com/bytechef/platform/component/runner/GraalVmTaskRunnerTest.java`
- Modify: `server/libs/modules/components/script/src/main/java/com/bytechef/component/script/engine/PolyglotEngine.java`
- Modify: `.../platform-component-runner-impl/build.gradle.kts`

`PolyglotEngine` currently lives in the `script` component module, but `GraalVmTaskRunner` must not depend on a component. Move it into `platform-component-runner-impl` alongside its two collaborators.

**Interfaces:**
- Consumes: `TaskRunner`, `TaskRunnerRequest`, `TaskRunnerResult`, `TaskRunnerCapability`, `TaskRunnerConstants` (Task 3); `ScriptSandboxMode` and `PolyglotSandbox.call(mode, languageId, fn)` (Task 2).
- Produces: `GraalVmTaskRunner` as a `@Component` with `getType()` returning `"graalvm"`.

- [ ] **Step 1: Move PolyglotEngine into the impl module**

```bash
git mv server/libs/modules/components/script/src/main/java/com/bytechef/component/script/engine/PolyglotEngine.java \
       server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/PolyglotEngine.java
git mv server/libs/modules/components/script/src/main/java/com/bytechef/component/script/engine/ScriptComponentCatalog.java \
       server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/PolyglotComponentCatalog.java
git mv server/libs/modules/components/script/src/main/java/com/bytechef/component/script/engine/ScriptComponentActionInvoker.java \
       server/libs/platform/platform-component/platform-component-runner/platform-component-runner-impl/src/main/java/com/bytechef/platform/component/runner/PolyglotComponentActionInvoker.java
```

Update the `package` declaration in all three to `com.bytechef.platform.component.runner`, rename the two classes to match their new file names, and fix every reference. Change `PolyglotEngine.execute` to take the mode and to import `INPUT` locally rather than from the component's constants:

```java
    private static final String INPUT = "input";

    public Object execute(
        ScriptSandboxMode mode, Duration timeout, String languageId, Parameters inputParameters,
        Map<String, ComponentConnection> componentConnections, JobContextAware jobContextAware) {

        return PolyglotSandbox.call(mode, languageId, timeout, polyglotContext -> {
```

Leave the rest of the method body as it is.

Add the dependencies `PolyglotEngine` needs to `platform-component-runner-impl/build.gradle.kts`:

```kotlin
    implementation(project(":server:libs:atlas:atlas-worker:atlas-worker-api"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))
    implementation(project(":server:libs:platform:platform-workflow:platform-workflow-worker:platform-workflow-worker-api"))
```

- [ ] **Step 2: Write the failing tests**

```java
package com.bytechef.platform.component.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.definition.ParametersFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
public class GraalVmTaskRunnerTest {

    private final PolyglotEngine polyglotEngine = mock(PolyglotEngine.class);
    private final GraalVmTaskRunner graalVmTaskRunner = new GraalVmTaskRunner(polyglotEngine);

    @Test
    public void testTypeAndCapabilities() {
        assertThat(graalVmTaskRunner.getType()).isEqualTo("graalvm");
        assertThat(graalVmTaskRunner.getCapabilities())
            .containsExactlyInAnyOrder(
                TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.COMPONENT_BRIDGE);
    }

    @Test
    public void testRunReturnsPerformResult() {
        when(polyglotEngine.execute(any(), any(), anyString(), any(), anyMap(), any())).thenReturn("done");

        TaskRunnerResult taskRunnerResult = graalVmTaskRunner.run(newRequest(Map.of()));

        assertThat(taskRunnerResult.output()).isEqualTo("done");
        assertThat(taskRunnerResult.exitCode()).isNull();
        assertThat(taskRunnerResult.outputFiles()).isEmpty();
    }

    @Test
    public void testValidateRejectsInputFiles() {
        TaskRunnerRequest request = new TaskRunnerRequest(
            "js", "function perform(input, context) { return null; }", List.of(), Map.of(), Map.of(),
            Map.of("data.csv", "a,b"), List.of(), ParametersFactory.create(Map.of()),
            ParametersFactory.create(Map.of()), Duration.ofMinutes(5), Map.of(), null);

        assertThatThrownBy(() -> graalVmTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputFiles");
    }

    @Test
    public void testValidateRejectsCommands() {
        TaskRunnerRequest request = new TaskRunnerRequest(
            "js", null, List.of("echo hi"), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()),
            Duration.ofMinutes(5), Map.of(), null);

        assertThatThrownBy(() -> graalVmTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("commands");
    }

    @Test
    public void testValidateRejectsUnknownMode() {
        TaskRunnerRequest request = newRequest(Map.of("mode", "permissive"));

        assertThatThrownBy(() -> graalVmTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("permissive");
    }

    @Test
    public void testValidateAcceptsStrictAndTrusted() {
        graalVmTaskRunner.validate(newRequest(Map.of("mode", "strict")));
        graalVmTaskRunner.validate(newRequest(Map.of("mode", "trusted")));
        graalVmTaskRunner.validate(newRequest(Map.of()));
    }

    private static TaskRunnerRequest newRequest(Map<String, ?> runnerParameters) {
        return new TaskRunnerRequest(
            "js", "function perform(input, context) { return null; }", List.of(), Map.of(), Map.of(), Map.of(),
            List.of(), ParametersFactory.create(Map.of()),
            ParametersFactory.create(runnerParameters), Duration.ofMinutes(5), Map.of(), null);
    }
}
```

`ParametersFactory` here is the PRODUCTION one at
`com.bytechef.platform.component.definition.ParametersFactory` (module `platform-component-api`), whose
method is `create(Map<String, ?>)`. Do not use `MockParametersFactory` — it lives in the
`component-test` module and is not on a production classpath.

- [ ] **Step 3: Run the tests and confirm they fail**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test --tests '*GraalVmTaskRunnerTest*' > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|cannot find symbol' /tmp/t5.log | head
```

Expected: FAIL — `cannot find symbol: GraalVmTaskRunner`.

- [ ] **Step 4: Implement GraalVmTaskRunner**

```java
package com.bytechef.platform.component.runner;

import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.MODE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.STRICT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TRUSTED;

import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import com.bytechef.platform.component.definition.JobContextAware;
import com.bytechef.platform.component.polyglot.ScriptSandboxMode;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Runs the script in this JVM, in a GraalVM polyglot context.
 *
 * <p>
 * The only runner that can offer {@link TaskRunnerCapability#COMPONENT_BRIDGE}: the bridge letting a script invoke
 * other components is a live host object, so it cannot cross a process boundary.
 *
 * @author Ivica Cardic
 */
@Component
public class GraalVmTaskRunner implements TaskRunner {

    private final PolyglotEngine polyglotEngine;

    public GraalVmTaskRunner(PolyglotEngine polyglotEngine) {
        this.polyglotEngine = polyglotEngine;
    }

    @Override
    public String getType() {
        return GRAALVM;
    }

    @Override
    public String getTitle() {
        return "GraalVM";
    }

    @Override
    public List<? extends ModifiableValueProperty<?, ?>> getProperties() {
        return List.of(
            string(MODE)
                .label("Mode")
                .description(
                    "Strict runs the script with no access to the host: no file system, no environment, no process " +
                        "creation, and CPU and memory ceilings. Trusted lifts every restriction and runs the script " +
                        "inside the server process with full reflection; use it only on a single-tenant deployment " +
                        "you control.")
                .options(option("Strict", STRICT), option("Trusted", TRUSTED))
                .defaultValue(STRICT)
                .required(false));
    }

    @Override
    public Set<TaskRunnerCapability> getCapabilities() {
        return Set.of(TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.COMPONENT_BRIDGE);
    }

    @Override
    public void validate(TaskRunnerRequest request) {
        if (!request.commands()
            .isEmpty()) {

            throw new IllegalArgumentException("The GraalVM runner does not support commands; use a script instead");
        }

        if (!request.inputFiles()
            .isEmpty()) {

            throw new IllegalArgumentException("The GraalVM runner does not support inputFiles");
        }

        if (!request.outputFilePatterns()
            .isEmpty()) {

            throw new IllegalArgumentException("The GraalVM runner does not support outputFiles");
        }

        if (!request.env()
            .isEmpty()) {

            throw new IllegalArgumentException("The GraalVM runner does not support env");
        }

        getMode(request);
    }

    @Override
    public TaskRunnerResult run(TaskRunnerRequest request) {
        validate(request);

        Object output = polyglotEngine.execute(
            getMode(request), request.timeout(), request.languageId(), request.inputParameters(),
            request.componentConnections(), (JobContextAware) request.actionContext());

        return TaskRunnerResult.ofOutput(output);
    }

    private static ScriptSandboxMode getMode(TaskRunnerRequest request) {
        String mode = request.runnerParameters()
            .getString(MODE, STRICT);

        return switch (mode) {
            case STRICT -> ScriptSandboxMode.STRICT;
            case TRUSTED -> ScriptSandboxMode.TRUSTED;
            default -> throw new IllegalArgumentException(
                "Unknown GraalVM sandbox mode '%s'; expected '%s' or '%s'".formatted(mode, STRICT, TRUSTED));
        };
    }
}
```

`run` calls `validate` first rather than trusting the caller. The registry is the allowlist boundary; this is the configuration boundary, and both must hold for a hand-edited workflow.

The two `Parameters` on the request are not interchangeable. `runnerParameters()` is the `taskRunner` sub-map and is where `mode` lives; `inputParameters()` is the action's own parameter map and is where `PolyglotEngine` reads `script` and `input` from. Passing one where the other belongs compiles cleanly and fails at runtime with a missing-key error, so keep them straight.

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl:test > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t5.log
```

Expected: PASS, `exit=0`.

- [ ] **Step 6: Confirm the script module still compiles after the move**

```bash
./gradlew :server:libs:modules:components:script:compileJava --continue > /tmp/t5b.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t5b.log
```

Expected: FAIL — the script module still imports `com.bytechef.component.script.engine.PolyglotEngine`. Add the runner-impl dependency to `server/libs/modules/components/script/build.gradle.kts`:

```kotlin
    implementation(project(":server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api"))
    implementation(project(":server:libs:platform:platform-component:platform-component-runner:platform-component-runner-impl"))
```

and update every import of `PolyglotEngine` in the script module to `com.bytechef.platform.component.runner.PolyglotEngine`. Re-run until `exit=0`.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-component/platform-component-runner server/libs/modules/components/script
git commit -m "3901 Add the GraalVM task runner"
```

---

## Task 6: The taskRunner property factory

**Files:**
- Create: `.../platform-component-runner-api/src/main/java/com/bytechef/platform/component/runner/TaskRunnerPropertyFactory.java`
- Test: `.../platform-component-runner-api/src/test/java/com/bytechef/platform/component/runner/TaskRunnerPropertyFactoryTest.java`

**Interfaces:**
- Consumes: `TaskRunnerRegistry`, `TaskRunner`, `TaskRunnerCapability`, `TaskRunnerConstants` (Task 3).
- Produces: `TaskRunnerPropertyFactory.taskRunnerProperty(TaskRunnerRegistry registry, Set<TaskRunnerCapability> requiredCapabilities)` returning a `ModifiableObjectProperty`. Task 7 calls it.

- [ ] **Step 1: Write the failing tests**

```java
package com.bytechef.platform.component.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static com.bytechef.component.definition.ComponentDsl.string;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ComponentDsl.ModifiableObjectProperty;
import com.bytechef.component.definition.Property;
import com.bytechef.component.definition.Property.ValueProperty;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
public class TaskRunnerPropertyFactoryTest {

    @Test
    public void testStampsDisplayConditionOnRunnerProperties() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry();

        ModifiableObjectProperty objectProperty = TaskRunnerPropertyFactory.taskRunnerProperty(
            taskRunnerRegistry, Set.of(TaskRunnerCapability.INLINE_SCRIPT));

        List<? extends ValueProperty<?>> properties = objectProperty.getProperties();

        ValueProperty<?> modeProperty = properties.stream()
            .filter(property -> "mode".equals(property.getName()))
            .findFirst()
            .orElseThrow();

        assertThat(modeProperty.getDisplayCondition())
            .contains("taskRunner.type == 'graalvm'");
    }

    @Test
    public void testTypeSelectOffersOnlyEnabledRunners() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry();

        ModifiableObjectProperty objectProperty = TaskRunnerPropertyFactory.taskRunnerProperty(
            taskRunnerRegistry, Set.of(TaskRunnerCapability.INLINE_SCRIPT));

        Property.StringProperty typeProperty = (Property.StringProperty) objectProperty.getProperties()
            .stream()
            .filter(property -> "type".equals(property.getName()))
            .findFirst()
            .orElseThrow();

        assertThat(typeProperty.getOptions())
            .extracting(Property.Option::getValue)
            .containsExactly("graalvm");
    }

    @Test
    public void testDoesNotMutateTheRunnersOwnProperties() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry();

        TaskRunnerPropertyFactory.taskRunnerProperty(taskRunnerRegistry, Set.of(TaskRunnerCapability.INLINE_SCRIPT));

        TaskRunner taskRunner = taskRunnerRegistry.getTaskRunners(Set.of(TaskRunnerCapability.INLINE_SCRIPT))
            .getFirst();

        // A second read must come back unstamped. If getProperties() returned a shared list, the first call above
        // would have written a displayCondition onto it, and every other action including this runner would inherit
        // whichever condition was applied last.
        ValueProperty<?> modeProperty = taskRunner.getProperties()
            .getFirst();

        assertThat(modeProperty.getDisplayCondition()).isEmpty();
    }

    private static TaskRunnerRegistry newRegistry() {
        TaskRunner taskRunner = mock(TaskRunner.class);

        when(taskRunner.getType()).thenReturn("graalvm");
        when(taskRunner.getTitle()).thenReturn("GraalVM");
        when(taskRunner.getCapabilities()).thenReturn(Set.of(TaskRunnerCapability.INLINE_SCRIPT));
        when(taskRunner.getProperties())
            .thenAnswer(invocation -> List.of(string("mode").label("Mode")));

        TaskRunnerRegistry taskRunnerRegistry = mock(TaskRunnerRegistry.class);

        when(taskRunnerRegistry.getTaskRunners(Set.of(TaskRunnerCapability.INLINE_SCRIPT)))
            .thenReturn(List.of(taskRunner));

        return taskRunnerRegistry;
    }
}
```

Note `thenAnswer` rather than `thenReturn` for `getProperties()`: it returns a fresh list on every call, which is exactly the contract the SPI javadoc states and the third test verifies.

- [ ] **Step 2: Run the tests and confirm they fail**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api:test > /tmp/t6.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|cannot find symbol' /tmp/t6.log | head
```

Expected: FAIL — `cannot find symbol: TaskRunnerPropertyFactory`.

- [ ] **Step 3: Implement the factory**

```java
package com.bytechef.platform.component.runner;

import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TYPE;

import com.bytechef.component.definition.ComponentDsl.ModifiableObjectProperty;
import com.bytechef.component.definition.ComponentDsl.ModifiableOption;
import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@code taskRunner} object property from the enabled runners.
 *
 * <p>
 * The property is assembled from the registry rather than declared literally, so a runner contributed by another
 * module appears without any component change. The cost is that a component's definition can no longer be read in one
 * file - each action's README must enumerate the built-in runners, because the definition no longer shows them.
 *
 * @author Ivica Cardic
 */
public class TaskRunnerPropertyFactory {

    private TaskRunnerPropertyFactory() {
    }

    /**
     * Builds the {@code taskRunner} property offering every enabled runner that declares all of the required
     * capabilities.
     *
     * @param taskRunnerRegistry   the registry to read runners from
     * @param requiredCapabilities the capabilities an offered runner must declare
     * @return the assembled object property
     */
    public static ModifiableObjectProperty taskRunnerProperty(
        TaskRunnerRegistry taskRunnerRegistry, Set<TaskRunnerCapability> requiredCapabilities) {

        List<TaskRunner> taskRunners = taskRunnerRegistry.getTaskRunners(requiredCapabilities);

        List<ModifiableOption<String>> options = new ArrayList<>();
        List<ModifiableValueProperty<?, ?>> properties = new ArrayList<>();

        for (TaskRunner taskRunner : taskRunners) {
            String type = taskRunner.getType();

            options.add(option(taskRunner.getTitle(), type));

            // getProperties() is contracted to return fresh instances, so stamping in place cannot leak a condition
            // onto a list another action also holds.
            for (ModifiableValueProperty<?, ?> property : taskRunner.getProperties()) {
                property.displayCondition("%s.%s == '%s'".formatted(TASK_RUNNER, TYPE, type));

                properties.add(property);
            }
        }

        String defaultType = taskRunners.isEmpty() ? null : taskRunners.getFirst()
            .getType();

        List<ModifiableValueProperty<?, ?>> allProperties = new ArrayList<>();

        allProperties.add(
            string(TYPE)
                .label("Type")
                .description("Where this task runs.")
                .options(options)
                .defaultValue(defaultType)
                .required(true));
        allProperties.addAll(properties);

        return object(TASK_RUNNER)
            .label("Task Runner")
            .description("Selects the environment this task executes in.")
            .properties(allProperties)
            .required(false)
            .expressionEnabled(false);
    }
}
```

If `properties(allProperties)` does not compile because of generic inference on `List<P extends ValueProperty<?>>`, change `allProperties` to `List<ValueProperty<?>>` and add the elements through a local variable typed as `ModifiableValueProperty<?, ?>`; the DSL accepts any `ValueProperty<?>`.

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api:test > /tmp/t6.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t6.log
```

Expected: PASS, `exit=0`.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-component/platform-component-runner
git commit -m "3901 Assemble the taskRunner property from the runner registry"
```

---

## Task 7: Route the script actions through the registry

**Files:**
- Modify: `server/libs/modules/components/script/src/main/java/com/bytechef/component/script/ScriptComponentHandler.java`
- Modify: `.../script/action/ScriptJavaScriptAction.java`, `.../script/action/ScriptPythonAction.java`
- Modify: `.../script/action/definition/ScriptActionDefinition.java`
- Modify: `.../script/cluster/tool/ScriptJavaScriptTool.java`, `.../ScriptPythonTool.java`, `.../cluster/datastream/ScriptJavaScriptItemProcessor.java`, `.../ScriptPythonItemProcessor.java`
- Test: `server/libs/modules/components/script/src/test/java/com/bytechef/component/script/ScriptComponentHandlerTest.java`
- Regenerate: `server/libs/modules/components/script/src/test/resources/definition/script_v1.json`
- Modify: `server/apps/server-app/build.gradle.kts`

The cluster elements stay GraalVM-strict per the spec, so they keep calling `PolyglotEngine` directly and gain no `taskRunner` property. Only the two actions change.

**Interfaces:**
- Consumes: `TaskRunnerRegistry` (Task 4), `TaskRunnerPropertyFactory` (Task 6), `GraalVmTaskRunner` (Task 5).
- Produces: nothing later tasks consume. This is the phase's integration point.

- [ ] **Step 1: Add the taskRunner property to the two actions**

In `ScriptJavaScriptAction.java`, change the factory signature and add the property as the last one:

```java
    public static ScriptActionDefinition of(
        PolyglotEngine polyglotEngine, TaskRunnerRegistry taskRunnerRegistry) {

        return new ScriptActionDefinition(
            action("javascript")
                .title("JavaScript")
                .description("Executes custom JavaScript code.")
                .properties(
                    object(INPUT)
                        .label("Input")
                        .description("Initialize parameter values used in the custom code.")
                        .additionalProperties(
                            array(), bool(), date(), dateTime(), integer(), nullable(), number(), object(), string(),
                            time())
                        .expressionEnabled(false),
                    string(SCRIPT)
                        .label("JavaScript Code")
                        .description("Add your JavaScript custom logic here.")
                        .controlType(Property.ControlType.CODE_EDITOR)
                        .languageId("javascript")
                        .defaultValue(
                            """
                                function perform(input, context) {
                                    // input holds the values declared above under Input.
                                    //
                                    // Reach another component's action through the context:
                                    // const response = context.component.httpClient.get(
                                    //     {uri: "https://api.example.com/items"}, "my-connection");

                                    return null;
                                }""")
                        .required(true),
                    TaskRunnerPropertyFactory.taskRunnerProperty(
                        taskRunnerRegistry, Set.of(TaskRunnerCapability.INLINE_SCRIPT)))
                .output(),
            "js", polyglotEngine, taskRunnerRegistry);
    }
```

Add the imports `java.util.Set`, `com.bytechef.platform.component.runner.TaskRunnerCapability`, `com.bytechef.platform.component.runner.TaskRunnerPropertyFactory`, `com.bytechef.platform.component.runner.TaskRunnerRegistry`.

Make the same change in `ScriptPythonAction.java`, keeping its own title, description, `languageId("python")` and default snippet exactly as they are.

- [ ] **Step 2: Route perform through the registry**

Replace `ScriptActionDefinition.java`:

```java
package com.bytechef.component.script.action.definition;

import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TYPE;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.AbstractActionDefinitionWrapper;
import com.bytechef.platform.component.definition.JobContextAware;
import com.bytechef.platform.component.definition.MultipleConnectionsPerformFunction;
import com.bytechef.platform.component.runner.PolyglotEngine;
import com.bytechef.platform.component.runner.TaskRunner;
import com.bytechef.platform.component.runner.TaskRunnerRegistry;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ScriptActionDefinition extends AbstractActionDefinitionWrapper {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final String languageId;
    private final PolyglotEngine polyglotEngine;
    private final TaskRunnerRegistry taskRunnerRegistry;

    public ScriptActionDefinition(
        ActionDefinition actionDefinition, String languageId, PolyglotEngine polyglotEngine,
        TaskRunnerRegistry taskRunnerRegistry) {

        super(actionDefinition);

        this.languageId = languageId;
        this.polyglotEngine = polyglotEngine;
        this.taskRunnerRegistry = taskRunnerRegistry;
    }

    @Override
    public Optional<MultipleConnectionsPerformFunction> getPerform() {
        return Optional.of(this::perform);
    }

    protected Object perform(
        Parameters inputParameters, Map<String, ComponentConnection> connectionParameters,
        Parameters extensions, ActionContext context) {

        Map<String, ?> taskRunnerMap = inputParameters.getMap(TASK_RUNNER, Object.class, Map.of());

        Parameters runnerParameters = ParametersFactory.create(taskRunnerMap);

        String type = runnerParameters.getString(TYPE, GRAALVM);

        TaskRunner taskRunner = taskRunnerRegistry.getTaskRunner(type);

        TaskRunnerRequest taskRunnerRequest = new TaskRunnerRequest(
            languageId, inputParameters.getRequiredString("script"), List.of(),
            inputParameters.getMap("input", Object.class, Map.of()), Map.of(), Map.of(), List.of(), inputParameters,
            runnerParameters, DEFAULT_TIMEOUT, connectionParameters, context);

        TaskRunnerResult taskRunnerResult = taskRunner.run(taskRunnerRequest);

        return taskRunnerResult.output();
    }
}
```

The request carries both parameter maps: `inputParameters` (the action's own, which `PolyglotEngine` reads `script` and `input` from) and `runnerParameters` (the `taskRunner` sub-map, which carries `mode`). The `polyglotEngine` field is retained for the cluster elements, which construct `ScriptActionDefinition` and still run GraalVM-strict directly.

No change to `GraalVmTaskRunner` is needed — it already reads the two maps separately.

- [ ] **Step 3: Thread the registry through the component handler**

In `ScriptComponentHandler.java`:

```java
    public ScriptComponentHandler(PolyglotEngine polyglotEngine, TaskRunnerRegistry taskRunnerRegistry) {
        this.componentDefinition = new ScriptComponentDefinitionImpl(polyglotEngine, taskRunnerRegistry);
    }
```

and inside `ScriptComponentDefinitionImpl`, pass both to the two actions while leaving the cluster element registrations exactly as they are:

```java
                    .actions(
                        ScriptJavaScriptAction.of(polyglotEngine, taskRunnerRegistry),
                        ScriptPythonAction.of(polyglotEngine, taskRunnerRegistry))
```

The four cluster element factories (`ScriptJavaScriptTool`, `ScriptPythonTool`, `ScriptJavaScriptItemProcessor`, `ScriptPythonItemProcessor`) construct `ScriptActionDefinition` too. Update each to pass `taskRunnerRegistry` through, keeping their own properties unchanged — they gain no `taskRunner` property, and their `perform` reaching the registry with no `taskRunner` map resolves to `graalvm`, which is enabled by default.

Keep every `RUBY-DISABLED` comment block untouched.

- [ ] **Step 4: Put the GraalVM runner on by default**

Add to `server/apps/server-app/src/main/resources/config/application.yml`, under the existing `bytechef:` block:

```yaml
  script:
    runners:
      graalvm:
        enabled: true
```

Without this the registry rejects every script task: a runner absent from the map is disabled, which is the intended default for Process and Docker but must not be the default for GraalVM.

- [ ] **Step 5: Run the script component tests and regenerate the snapshot**

```bash
rm -f server/libs/modules/components/script/src/test/resources/definition/script_v1.json
rm -rf server/libs/modules/components/script/build/resources/test/definition
./gradlew :server:libs:modules:components:script:test > /tmp/t7a.log 2>&1; echo "first run exit=$?"
./gradlew :server:libs:modules:components:script:test > /tmp/t7b.log 2>&1; echo "second run exit=$?"; grep -E '^> Task .* FAILED' /tmp/t7b.log
```

Expected: the **first** run fails with `NullPointerException: url` — the snapshot was written to `src` but not yet copied onto the test classpath. That is the expected midpoint, not a bug. The **second** run passes (`exit=0`) after `processTestResources` copies it across.

- [ ] **Step 6: Verify the snapshot actually contains the taskRunner property**

```bash
grep -c '"taskRunner"' server/libs/modules/components/script/src/test/resources/definition/script_v1.json
grep -o '"graalvm"' server/libs/modules/components/script/src/test/resources/definition/script_v1.json | head -1
```

Expected: a count of at least 2 (one per action) and a `"graalvm"` match. If the count is 0, the registry had no enabled runners in the test context — check that the test configuration supplies an `ApplicationProperties` with `graalvm.enabled=true`.

- [ ] **Step 7: Run the full server build**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t7c.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t7c.log
```

Expected: `exit=0`, no `FAILED` lines. Pay attention to the four EE modules that depend on `platform-component-polyglot` — `platform-custom-component-loader`, `automation-code-workflow-loader`, `embedded-code-workflow-loader` and the `code-workflow` component. They now transitively pull `app-config`; if any fails to resolve it, add the dependency explicitly to that module.

- [ ] **Step 8: Run the checks**

```bash
./gradlew check --continue > /tmp/t7d.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t7d.log
```

Expected: `exit=0`. For any SpotBugs failure, read the HTML report, not the XML — the XML report is disabled in this repo and is never rewritten.

- [ ] **Step 9: Document the runners in the component README**

The spec makes this a requirement, not a nicety: because the `taskRunner` property is assembled from the registry, the generated definition no longer shows which runners exist. The README is the only place a reader can find out.

Append to `server/libs/modules/components/script/src/main/resources/README.mdx`:

```markdown
## Task Runners

Each action runs in the environment selected by its **Task Runner** property. The runners
available in a given deployment depend on what the operator has enabled with
`bytechef.script.runners.<type>.enabled`, so this list may be shorter in your installation.

| Runner | Type | What it does |
| --- | --- | --- |
| GraalVM | `graalvm` | Runs the script inside the server process, in a GraalVM polyglot context. The only runner where `context.component.*` is available. |

The GraalVM runner has two modes:

- **Strict** (default) — no file system, no environment, no process creation, and CPU and memory
  ceilings. Use this unless you have a specific reason not to.
- **Trusted** — every restriction lifted. The script runs inside the server process with full
  reflection, so it can reach anything the server can, including other tenants' data. Enable it
  only on a single-tenant deployment you control, with
  `bytechef.script.runners.graalvm.properties.trusted-enabled=true`.

Workflow data reaches a script through the **Input** property, not by interpolating expressions
into the source.
```

Then regenerate the docs:

```bash
./gradlew generateDocumentation > /tmp/t7e.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t7e.log
```

- [ ] **Step 10: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
git add server/libs/modules/components/script server/apps/server-app docs
git commit -m "3901 Route script actions through the task runner registry"
```

---

## Phase 1 Done Criteria

- `bytechef.script.sandbox.*` and `bytechef.script.runners.*` bind, proven by `ApplicationPropertiesScriptBindingTest`.
- `PolyglotSandbox.call(TRUSTED, …)` permits host class lookup; `STRICT` and the legacy overload deny it.
- A `script` action with no `taskRunner` runs exactly as before.
- A `script` action with `taskRunner.type = graalvm`, `mode = trusted` can read a host file, and the same workflow fails with `TaskRunnerNotEnabledException` when `bytechef.script.runners.graalvm.enabled=false`.
- `./gradlew check` passes.

## What Phase 2 Adds

Phase 2 (its own plan) brings `TaskRunnerWorkingDirectory`, the appended bootstrap, the external I/O contract, `ProcessTaskRunner`, and the `commands` component. Two things this phase deliberately leaves for it, so they are not forgotten:

- `TaskRunnerRequest` already carries `env`, `inputFiles` and `outputFilePatterns`, and `GraalVmTaskRunner.validate` rejects all three. Phase 2 adds the action properties that populate them, gated on the selected runner's capabilities.
- The `commands` component must call `DeferredEvaluationParameterKeys.register("commands/", "commands")`, because POSIX shell uses `${VAR}` and the workflow evaluator would otherwise consume it. See [`the expression-evaluation spec`](../specs/2026-08-31-script-source-expression-evaluation-design.md).
