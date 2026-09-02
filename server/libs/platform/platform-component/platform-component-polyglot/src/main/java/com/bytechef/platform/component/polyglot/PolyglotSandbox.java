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

package com.bytechef.platform.component.polyglot;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.io.IOAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the strict-sandbox {@link Context} shared by every guest polyglot execution that runs user-supplied code (the
 * Script component, Script Tool and SkillsTool scripts, and code workflow tasks).
 *
 * @author Ivica Cardic
 */
public final class PolyglotSandbox {

    private static final Logger log = LoggerFactory.getLogger(PolyglotSandbox.class);

    /**
     * Languages built under {@link SandboxPolicy#CONSTRAINED}.
     *
     * <p>
     * Only the two Truffle languages whose sandboxing is exercised and supported. {@code java} (Espresso) and {@code R}
     * deliberately stay on {@code TRUSTED} - they keep every restriction below, they simply do not get the policy's
     * resource ceilings. A context permitting any language outside this set is built TRUSTED as a whole, because
     * GraalVM applies one policy per context.
     */
    private static final Set<String> CONSTRAINED_LANGUAGE_IDS = Set.of("js", "python");

    /**
     * {@link HostAccess#NONE} with mutable target mappings stripped.
     *
     * <p>
     * Every policy above {@code TRUSTED} rejects a host access that permits host object mappings of mutable target
     * types - which {@code HostAccess.NONE} does, despite its name, because the mappings are a separate axis from
     * member access. This grants no host access whatsoever; it only removes an axis the policy forbids.
     */
    private static final HostAccess CONSTRAINED_HOST_ACCESS = HostAccess.newBuilder(HostAccess.NONE)
        .allowMutableTargetMappings()
        .build();

    /**
     * Engines keyed by policy, {@link ScriptSandboxMode} and permitted languages. GraalVM requires a context and its
     * engine to carry the SAME {@link SandboxPolicy}, so engines cannot be shared across the TRUSTED/CONSTRAINED split,
     * and it requires every context on one engine to carry the same host access, which is why the mode is part of the
     * key too - see {@link #getEngine}.
     */
    private static final Map<String, Engine> ENGINES = new ConcurrentHashMap<>();

    private static final Object GUEST_EXECUTOR_LOCK = new Object();

    /**
     * Schedules the cancellation of guest executions that outlive their wall-clock timeout.
     *
     * <p>
     * Built as a {@link ScheduledThreadPoolExecutor} directly rather than through {@link Executors}, because only the
     * concrete type exposes {@link ScheduledThreadPoolExecutor#setRemoveOnCancelPolicy}. Without it, a task cancelled
     * before it runs - the common case, since most timed executions finish well inside their timeout - stays queued for
     * the remainder of the delay anyway, pinning its already-closed {@link Context} (captured by the cancelled lambda)
     * in memory for no reason; retention would then scale with throughput times timeout, entirely from executions that
     * succeeded promptly. One thread: scheduling is cheap, and the actual cancellation work runs on
     * {@link #WATCHDOG_CLOSE_EXECUTOR} instead of here.
     */
    private static final ScheduledExecutorService WATCHDOG_SCHEDULER = newWatchdogScheduler();

    /**
     * Performs the blocking {@link Context#close(boolean)} call a scheduled watchdog task requests.
     *
     * <p>
     * {@code close(true)}'s javadoc warns a thread may not be interruptible if it "executes non-interruptible host
     * code" - and {@link ScriptSandboxMode#TRUSTED} is exactly the mode where guest code can block in host code (a JDBC
     * round-trip, a socket read, {@code Thread.sleep}), because {@code allowAllAccess(true)} grants IO, native access
     * and process creation. If the close ran on {@link #WATCHDOG_SCHEDULER} itself, one such blocked close would stall
     * every other pending watchdog task process-wide - disabling the timeout, TRUSTED's only safety net, for every
     * other concurrent trusted execution, which typically run inline on the caller's virtual thread (see
     * {@link #requiresGuestThread}) and so can be many at once. An unbounded cached pool keeps one blocked close from
     * blocking any other.
     */
    private static final ExecutorService WATCHDOG_CLOSE_EXECUTOR = Executors.newCachedThreadPool(
        newDaemonThreadFactory("polyglot-sandbox-watchdog-close"));

    private static volatile PolyglotSandboxSettings settings = PolyglotSandboxSettings.defaults();

    @Nullable
    private static volatile PolyglotGuestExecutor guestExecutor;

    private PolyglotSandbox() {
    }

    /**
     * Replaces the settings every subsequently built context uses. Called once at startup; contexts already built keep
     * the settings they were built with.
     *
     * @param polyglotSandboxSettings the settings to apply
     */
    public static void setSettings(PolyglotSandboxSettings polyglotSandboxSettings) {
        settings = polyglotSandboxSettings;

        ENGINES.clear();

        synchronized (GUEST_EXECUTOR_LOCK) {
            PolyglotGuestExecutor previousGuestExecutor = guestExecutor;

            guestExecutor = null;

            if (previousGuestExecutor != null) {
                previousGuestExecutor.shutdown();
            }
        }
    }

    /**
     * Runs the given function against a strictly sandboxed {@link Context}. Equivalent to
     * {@link #call(ScriptSandboxMode, String, Function)} with {@link ScriptSandboxMode#STRICT}.
     *
     * @param languageId    the language id the context is permitted to evaluate
     * @param guestFunction the guest execution
     * @return whatever the function returned
     */
    public static <V> V call(String languageId, Function<Context, V> guestFunction) {
        return call(ScriptSandboxMode.STRICT, languageId, guestFunction);
    }

    /**
     * Runs the given function against a {@link Context} built for the given mode, with no wall-clock timeout.
     * Equivalent to {@link #call(ScriptSandboxMode, String, Duration, Function)} with a null timeout.
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
     * Runs the given function against a {@link Context} built for the given mode, on a thread the sandbox's resource
     * ceilings can be measured on, cancelling the execution if it outlives the timeout, and closes the context before
     * returning.
     *
     * <p>
     * This is the only way to reach a guest context: the ceilings GraalVM applies are metered per thread, so a context
     * must be built and evaluated on the same platform thread. Handing a {@link Context} back to the caller would let
     * guest code run on a thread the ceilings do not hold for. See {@link PolyglotGuestExecutor}.
     *
     * <p>
     * The value the function returns must not be backed by the guest context - convert guest values to host values (see
     * {@link PolyglotValues}) before returning them, or they are read after their context is closed.
     *
     * <p>
     * The timeout matters most under {@link ScriptSandboxMode#TRUSTED}, which carries no resource ceilings at all -
     * {@code sandbox.*} options exist only under {@code CONSTRAINED} - so it is the only thing that can stop a runaway
     * trusted script.
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
            // running on this thread and the watchdog fires only if it overstays. The close itself is handed off to
            // WATCHDOG_CLOSE_EXECUTOR rather than run on WATCHDOG_SCHEDULER - see that field's javadoc for why.
            ScheduledFuture<?> watchdogFuture = WATCHDOG_SCHEDULER.schedule(
                () -> submitWatchdogClose(context), timeout.toMillis(), TimeUnit.MILLISECONDS);

            try {
                return guestFunction.apply(context);
            } finally {
                watchdogFuture.cancel(false);
            }
        }
    }

    /**
     * Hands the blocking close to {@link #WATCHDOG_CLOSE_EXECUTOR}, turning a failure to hand it over into a log line.
     *
     * <p>
     * The submit itself can fail - {@link java.util.concurrent.RejectedExecutionException} once the pool is shut down,
     * or an {@link OutOfMemoryError} while starting the platform thread the close would run on, which is precisely the
     * state a mass timeout drives an unbounded pool into. The throwable would otherwise land in a
     * {@link ScheduledFuture} nobody reads, so the timeout would be lost in silence and a runaway trusted script would
     * keep running with nothing in the log to say why. Logging cannot rescue the execution; it makes an invisible
     * failure visible.
     */
    private static void submitWatchdogClose(Context context) {
        try {
            WATCHDOG_CLOSE_EXECUTOR.execute(() -> context.close(true));
        } catch (RuntimeException | Error exception) {
            log.error(
                "Could not schedule the guest execution's timeout close; the execution is no longer bounded by its " +
                    "wall-clock timeout",
                exception);
        }
    }

    private static Context newContext(
        PolyglotSandboxSettings currentSettings, ScriptSandboxMode mode, String... permittedLanguages) {

        if (mode == ScriptSandboxMode.TRUSTED) {
            return newTrustedContext(permittedLanguages);
        }

        boolean constrained = isConstrained(currentSettings, mode, permittedLanguages);

        // User-supplied scripts (Script component, Script Tool, and SkillsTool scripts) are evaluated here, so the
        // guest context is pinned to a no-host, no-IO sandbox. The script still interacts with the platform solely
        // through the ContextProxyObject/ComponentProxyObject guest proxies, which do not require host access. These
        // restrictions match GraalVM's secure defaults but are set explicitly so the sandbox cannot be silently
        // weakened by a future default change or accidental builder edit. The single carve-out is thread creation
        // for Ruby contexts: TruffleRuby backs core interop operations with fibers (host-side iteration of a guest
        // hash crosses an Enumerator, and Enumerator's generator is fiber-based), and fibers require thread
        // creation - without it any guest hash argument fails with "fibers not allowed with allowCreateThread(false)".
        Context.Builder builder = Context.newBuilder(permittedLanguages)
            .engine(getEngine(constrained, mode, permittedLanguages))
            .allowHostAccess(constrained ? CONSTRAINED_HOST_ACCESS : HostAccess.NONE)
            .allowHostClassLoading(false)
            .allowHostClassLookup(className -> false)
            .allowNativeAccess(false)
            // RUBY-DISABLED: while Ruby is disabled no permitted language needs the fiber carve-out, so
            // thread creation stays off unconditionally. Restore
            // .allowCreateThread(isRubyPermitted(permittedLanguages)) together with the commented-out
            // helper below once a polyglot ruby jar built on Truffle 25.2+ is published (or GraalVM is
            // downgraded). Grep RUBY-DISABLED.
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
     * A context with every restriction lifted.
     *
     * <p>
     * This method - and {@code call(TRUSTED, ...)} above it - enforces nothing. This class has no notion of "the
     * operator enabled it" or "the workflow selected it": whoever passes {@link ScriptSandboxMode#TRUSTED} gets a
     * trusted context. The gate is
     * {@code com.bytechef.platform.component.runner.GraalVmTaskRunner#validate(TaskRunnerRequest)}, which rejects
     * {@code mode: trusted} unless the operator set
     * {@code bytechef.script.runners.graalvm.properties.trusted-enabled=true}, and that runner is the sole authorised
     * caller of this path.
     *
     * <p>
     * The streams are bound explicitly rather than left to the engine. A trusted guest has full reflection and can
     * reach every tenant's decrypted credentials, so what it printed is the only forensic trace of what it did -
     * inheriting {@code System.in}/{@code System.out}/{@code System.err} would send that outside SLF4J: no
     * {@code [guest]} tag, no correlation, no log-level control, and nothing for the application's appenders to
     * capture. Context-level streams override the engine's, so binding them here cannot disturb the shared
     * {@link Engine} or any strict path.
     *
     * <p>
     * Nothing here is metered: {@code sandbox.*} options exist only under {@code CONSTRAINED}, so a trusted execution
     * is bounded by the caller's wall-clock timeout instead.
     */
    private static Context newTrustedContext(String... permittedLanguages) {
        return Context.newBuilder(permittedLanguages)
            .engine(getEngine(false, ScriptSandboxMode.TRUSTED, permittedLanguages))
            .allowAllAccess(true)
            .in(new ByteArrayInputStream(new byte[0]))
            .out(newGuestOutputStream(false))
            .err(newGuestOutputStream(true))
            .build();
    }

    private static void applySandboxPolicy(Context.Builder builder, PolyglotSandboxSettings currentSettings) {
        builder.sandbox(SandboxPolicy.CONSTRAINED)
            .in(new ByteArrayInputStream(new byte[0]))
            .out(newGuestOutputStream(false))
            .err(newGuestOutputStream(true));

        Duration maxCpuTime = currentSettings.maxCpuTime();

        if (maxCpuTime != null) {
            builder.option("sandbox.MaxCPUTime", maxCpuTime.toMillis() + "ms");
        }

        Long maxHeapMemory = currentSettings.maxHeapMemory();

        if (maxHeapMemory != null) {
            builder.option("sandbox.MaxHeapMemory", maxHeapMemory + "B");
        }
    }

    /**
     * Whether the execution has to be moved onto a platform thread.
     *
     * <p>
     * Only when the context would carry a thread-metered ceiling and the caller is a virtual thread - the combination
     * GraalVM refuses to build. A caller already on a platform thread stays there, which also keeps a guest script that
     * invokes a polyglot custom component from queueing behind itself on a saturated pool.
     */
    private static boolean requiresGuestThread(
        PolyglotSandboxSettings currentSettings, ScriptSandboxMode mode, String... permittedLanguages) {

        Thread currentThread = Thread.currentThread();

        if (!currentThread.isVirtual()) {
            return false;
        }

        return isConstrained(currentSettings, mode, permittedLanguages) && isThreadMetered(currentSettings);
    }

    private static boolean isThreadMetered(PolyglotSandboxSettings currentSettings) {
        return currentSettings.maxCpuTime() != null || currentSettings.maxHeapMemory() != null;
    }

    private static PolyglotGuestExecutor getGuestExecutor(PolyglotSandboxSettings currentSettings) {
        PolyglotGuestExecutor currentGuestExecutor = guestExecutor;

        if (currentGuestExecutor != null) {
            return currentGuestExecutor;
        }

        synchronized (GUEST_EXECUTOR_LOCK) {
            currentGuestExecutor = guestExecutor;

            if (currentGuestExecutor == null) {
                currentGuestExecutor = new PolyglotGuestExecutor(currentSettings.maxConcurrentExecutions());

                guestExecutor = currentGuestExecutor;
            }

            return currentGuestExecutor;
        }
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

    /**
     * Engines are keyed by mode as well as by policy and languages, because GraalVM requires every context on a shared
     * engine to carry the SAME host access configuration - a trusted context's {@code HostAccess.ALL} and a strict
     * one's {@code HostAccess.NONE} cannot meet on one engine, and the second of them to be built fails outright with
     * "Found different host access configuration for a context with a shared engine". Policy alone does not separate
     * them: a strict context is also built on a {@code TRUSTED}-policy engine whenever the sandbox kill switch is off
     * or the language is outside {@link #CONSTRAINED_LANGUAGE_IDS}, so without the mode in the key those two would
     * collide on {@code false:<languages>}.
     */
    private static Engine getEngine(boolean constrained, ScriptSandboxMode mode, String... permittedLanguages) {
        return ENGINES.computeIfAbsent(
            toEngineKey(constrained, mode, permittedLanguages), key -> newEngine(constrained, permittedLanguages));
    }

    private static String toEngineKey(boolean constrained, ScriptSandboxMode mode, String... permittedLanguages) {
        List<String> sortedLanguages = Arrays.stream(permittedLanguages)
            .sorted()
            .toList();

        return constrained + ":" + mode + ":" + String.join(",", sortedLanguages);
    }

    private static Engine newEngine(boolean constrained, String... permittedLanguages) {
        if (!constrained) {
            return Engine.create();
        }

        return Engine.newBuilder(permittedLanguages)
            .sandbox(SandboxPolicy.CONSTRAINED)
            .in(new ByteArrayInputStream(new byte[0]))
            .out(newGuestOutputStream(false))
            .err(newGuestOutputStream(true))
            .build();
    }

    private static OutputStream newGuestOutputStream(boolean error) {
        return new GuestLoggingOutputStream(line -> {
            if (error) {
                log.warn("[guest] {}", line);
            } else if (log.isInfoEnabled()) {
                log.info("[guest] {}", line);
            }
        });
    }

    private static ScheduledThreadPoolExecutor newWatchdogScheduler() {
        ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(
            1, newDaemonThreadFactory("polyglot-sandbox-watchdog"));

        // Without this, a cancelled-before-run task stays in the delay queue for the rest of its timeout instead
        // of being dropped immediately - see the WATCHDOG_SCHEDULER javadoc.
        scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);

        return scheduledThreadPoolExecutor;
    }

    private static ThreadFactory newDaemonThreadFactory(String threadName) {
        return runnable -> {
            Thread thread = new Thread(runnable, threadName);

            thread.setDaemon(true);

            return thread;
        };
    }

    // RUBY-DISABLED: unused while Ruby is disabled; see the allowCreateThread call above.
//    private static boolean isRubyPermitted(String... permittedLanguages) {
//        for (String permittedLanguage : permittedLanguages) {
//            if ("ruby".equals(permittedLanguage)) {
//                return true;
//            }
//        }
//
//        return false;
//    }
}
