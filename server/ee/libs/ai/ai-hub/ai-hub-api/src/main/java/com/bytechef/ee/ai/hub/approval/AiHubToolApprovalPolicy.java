/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Decides, for one chat turn, which tool names require a person's approval before they execute. Combines the built-in
 * defaults ({@link AiHubToolApprovalDefaults}), the workspace's own {@link AiHubToolApprovalRule} rows, and the
 * per-tool {@code requiresApproval} owner flag on {@code AiHubChatTool} into a single {@link Decision}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiHubToolApprovalPolicy {

    Decision decide(long workspaceId, long userId, @Nullable Long chatId);

    /**
     * {@code required} and {@code exempt} are evaluated together: an exempt tool is never gated, even when it also
     * matches a built-in default prefix or an explicit {@code required} entry — a workspace {@code EXEMPT} rule is the
     * most restrictive-wins escape hatch for a default that doesn't fit a particular workspace.
     */
    record Decision(Set<String> required, Set<String> exempt) {

        public boolean isGated(String toolName) {
            if (exempt.contains(toolName)) {
                return false;
            }

            return required.contains(toolName) || AiHubToolApprovalDefaults.isGatedByDefault(toolName);
        }
    }
}
