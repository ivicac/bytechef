/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.agent;

import com.agui.core.state.State;
import com.bytechef.ee.ai.hub.util.AiHubStateKeys;
import com.bytechef.platform.configuration.domain.Environment;
import org.jspecify.annotations.Nullable;

/**
 * Writes the server-verified identity values ({@code userId}, {@code workspaceId}, {@code threadId},
 * {@code environmentId}, {@code tenantId}) into the reserved {@link AiHubStateKeys} on an AG-UI {@link State}. Shared
 * by {@code AiHubApiController} (the first-turn / continuing-turn dispatch path) and
 * {@code AiHubToolApprovalFacadeImpl} (the resolution-triggered continuation turn), so both entry points stamp the same
 * server-controlled keys the agent and its tool callbacks trust.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class AiHubRunState {

    /**
     * Prefix marking a continuation message as the synthetic turn a tool approval resolution posts back into the
     * conversation, rather than a message a person actually typed. The client's transcript renderer uses this prefix to
     * render the continuation as a status line on the approval card instead of a chat bubble.
     */
    public static final String CONTINUATION_PREFIX = "[tool-approval #";

    private AiHubRunState() {
    }

    /**
     * Writes the verified identity values into {@code state}. {@code threadId} and {@code ownerUserId} are optional —
     * both omitted when no chat row exists yet (the very first turn); every other value is always written.
     *
     * <p>
     * The plain (unprefixed) key aliases are defensively overwritten with the same verified values as their
     * {@code VERIFIED_*} counterparts, so a future regression that reads {@code state.workspaceId} or
     * {@code state.userId} directly still gets server-controlled data rather than a client-supplied value.
     * </p>
     */
    public static void inject(
        State state, long userId, long workspaceId, @Nullable String threadId, long environmentId,
        String tenantId, @Nullable Long ownerUserId) {

        state.set(AiHubStateKeys.AUTHENTICATED_USER_ID, userId);
        state.set(AiHubStateKeys.VERIFIED_WORKSPACE_ID, workspaceId);
        state.set(AiHubStateKeys.WORKSPACE_ID, workspaceId);
        state.set(AiHubStateKeys.USER_ID, userId);

        if (threadId != null) {
            state.set(AiHubStateKeys.VERIFIED_THREAD_ID, threadId);
            state.set(AiHubStateKeys.THREAD_ID, threadId);
        }

        if (ownerUserId != null) {
            state.set(AiHubStateKeys.VERIFIED_OWNER_USER_ID, ownerUserId);
        }

        state.set(AiHubStateKeys.VERIFIED_ENVIRONMENT_ID, environmentId);
        state.set(AiHubStateKeys.ENVIRONMENT_ID, environmentId);

        state.set(AiHubStateKeys.VERIFIED_TENANT_ID, tenantId);
    }

    /**
     * Range-checks a client-supplied environment ordinal, falling back to {@code 0} (DEVELOPMENT) when it is missing or
     * out of range for {@link Environment}.
     */
    public static long clampEnvironmentId(@Nullable Long rawEnvironmentId) {
        return rawEnvironmentId != null && rawEnvironmentId >= 0 && rawEnvironmentId < Environment.values().length
            ? rawEnvironmentId
            : 0L;
    }
}
