/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Workspace-scoped CRUD over {@link AiHubToolApprovalRule} rows, plus the built-in default tool names a rule set can
 * override. Authorization is enforced here, not on the GraphQL controller — every method carries the same workspace
 * role guard {@code AiHubWorkspaceSettingsFacade} uses.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiHubToolApprovalRuleFacade {

    List<AiHubToolApprovalRule> getRules(long workspaceId);

    List<String> getDefaultToolNames();

    AiHubToolApprovalRule createRule(
        long workspaceId, AiHubToolApproval.ToolKind toolKind, @Nullable String componentName, String toolName,
        AiHubToolApprovalRule.Mode mode);

    void deleteRule(long workspaceId, long ruleId);
}
