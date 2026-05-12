/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.usage;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Thread-local agent identity binding used by the {@code UsageObservationHandler} (and the expensive tool callbacks) to
 * attribute LLM and tool calls to the right agent — including the parent-vs-subagent split.
 *
 * <p>
 * The parent {@code AI_HUB_ASK}/{@code AI_HUB_BUILD} agent binds itself before invoking the {@code ChatClient}. Each
 * subagent wrapping {@code ToolCallback} ({@link Agent#RESEARCH}, {@link Agent#WORKFLOW_BUILDER},
 * {@link Agent#DATA_ANALYST}, {@link Agent#IMAGE_GENERATOR}, {@link Agent#SLIDE_BUILDER}) re-binds with its own value
 * and the prior binding as {@code parentAgent} for the duration of the synchronous subagent {@code .call()} so that any
 * LLM observations the subagent's own ChatClient emits are attributed to the subagent.
 * </p>
 *
 * <p>
 * Uses an {@link InheritableThreadLocal} so any threads spawned during the synchronous invocation inherit the binding.
 * Always nest via {@link #runWith(Agent, Agent, Runnable)} or {@link #callWith(Agent, Agent, Supplier)} so the previous
 * binding is restored on the way out.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class CurrentAgentContext {

    /**
     * Immutable two-tuple of the agent and (optionally) the parent agent. {@code parentAgent} is {@code null} for
     * top-level ai_hub calls.
     */
    public record AgentBinding(Agent agentName, @Nullable Agent parentAgent) {
    }

    private static final InheritableThreadLocal<AgentBinding> HOLDER = new InheritableThreadLocal<>();

    private CurrentAgentContext() {
    }

    /**
     * Returns the agent binding currently in effect on this thread, or {@code null} when none has been bound.
     */
    public static AgentBinding current() {
        return HOLDER.get();
    }

    /**
     * Pushes a binding for the duration of {@code runnable}. The previous binding (if any) is restored even if the
     * runnable throws.
     */
    public static void runWith(Agent agentName, @Nullable Agent parentAgent, Runnable runnable) {
        AgentBinding previous = HOLDER.get();

        HOLDER.set(new AgentBinding(agentName, parentAgent));

        try {
            runnable.run();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /**
     * Variant of {@link #runWith(Agent, Agent, Runnable)} that returns the supplier's value.
     */
    public static <T> T callWith(Agent agentName, @Nullable Agent parentAgent, Supplier<T> supplier) {
        AgentBinding previous = HOLDER.get();

        HOLDER.set(new AgentBinding(agentName, parentAgent));

        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
