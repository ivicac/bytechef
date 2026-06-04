/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.agent;

import com.bytechef.platform.user.service.AuthorityService;
import com.bytechef.platform.user.service.UserService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

/**
 * Shared wrapping for AI Hub tool callbacks. Applies two layers, in order: (1) {@link NonEmptyToolCallback} so an empty
 * tool result does not trigger an Anthropic "non-empty content" HTTP 400, then (2)
 * {@link RehydrateSecurityContextToolCallback} so {@code @PreAuthorize}-protected service calls run under the invoking
 * user's SecurityContext on Reactor scheduler threads. When the user/authority services are absent the SecurityContext
 * layer is skipped (rehydration is impossible). Centralised so the agent's per-request tool list and the tool-search
 * advisor's resolver wrap callbacks identically.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class AiHubToolCallbackWrappers {

    private AiHubToolCallbackWrappers() {
    }

    public static ToolCallback wrap(
        ToolCallback callback, @Nullable UserService userService, @Nullable AuthorityService authorityService) {

        ToolCallback nonEmpty = NonEmptyToolCallback.wrap(callback);

        if (userService == null || authorityService == null) {
            return nonEmpty;
        }

        return RehydrateSecurityContextToolCallback.wrap(nonEmpty, userService, authorityService);
    }
}
