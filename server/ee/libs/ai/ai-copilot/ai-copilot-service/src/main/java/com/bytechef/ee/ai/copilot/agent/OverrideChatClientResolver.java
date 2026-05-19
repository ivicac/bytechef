/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.agent;

import com.agui.core.state.State;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Per-request resolver of an override {@link ChatClient} for Copilot agents. Used to swap the LLM at runtime when the
 * client has supplied a user-selected (provider, model) pair via AG-UI state keys, instead of the workspace-default
 * {@code @Primary ChatModel} the agent was built against.
 *
 * <p>
 * Returning {@code null} means "no override — use the agent's builder-time default ChatClient." Implementations are
 * expected to tolerate every failure mode (state keys missing, provider not enabled in this workspace, factory bean
 * absent, transient half-set state during user picking) by returning {@code null} so the agent falls back to the
 * default cleanly. The Copilot override path is opt-in: when no implementation bean is registered (CE builds, or EE
 * builds without {@code bytechef.ai.gateway.enabled=true}), the agent simply skips the override and runs against its
 * builder-time default — no resolver, no behavior change from pre-feature.
 *
 * <p>
 * A sibling interface lives in {@code AiHubSpringAIAgent.OverrideChatClientResolver}; the two have identical shape but
 * stay decoupled across module boundaries (Copilot service module vs. AI Hub service module) so neither end takes on a
 * cross-module compile dependency. A future cleanup could unify them by lifting both into a shared
 * {@code platform-ai-agent} module, but the duplication cost (one file, four lines each) is below the threshold that
 * justifies an extra Gradle module today.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@FunctionalInterface
public interface OverrideChatClientResolver {

    @Nullable
    ChatClient resolve(State state);
}
