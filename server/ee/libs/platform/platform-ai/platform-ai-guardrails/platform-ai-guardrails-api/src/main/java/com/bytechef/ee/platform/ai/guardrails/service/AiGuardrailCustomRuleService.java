/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.service;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailCustomRule;
import java.util.List;
import java.util.Optional;

/**
 * Stores and reads a workspace's own detection rules.
 *
 * <p>
 * Every operation is workspace-scoped, including the by-id ones. A rule id from another workspace therefore reads as
 * absent rather than as forbidden — the same rule {@code VariableServiceImpl} follows, so ids never confirm the
 * existence of another scope's rows.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiGuardrailCustomRuleService {

    /**
     * Creates a rule, <b>disabled</b>. Enabling is a separate call, deliberately: writing a rule and turning it on are
     * different decisions, and the route between them runs through observe mode.
     *
     * <p>
     * Validates the pattern, so this is the gate rather than the caller. A validator every write path has to remember
     * to call is one a future importer or admin tool will not.
     * </p>
     *
     * @param customRule the rule to create
     * @return the created rule
     */
    AiGuardrailCustomRule create(AiGuardrailCustomRule customRule);

    /**
     * Replaces a rule's pattern, re-validating it.
     *
     * @param id          the rule
     * @param workspaceId the owning workspace
     * @param pattern     the new pattern source
     * @return the updated rule
     */
    AiGuardrailCustomRule updatePattern(long id, long workspaceId, String pattern);

    /**
     * @param id          the rule
     * @param workspaceId the owning workspace
     * @param enabled     whether the rule should detect
     * @return the updated rule
     */
    AiGuardrailCustomRule setEnabled(long id, long workspaceId, boolean enabled);

    void delete(long id, long workspaceId);

    Optional<AiGuardrailCustomRule> fetchRule(long id, long workspaceId);

    List<AiGuardrailCustomRule> getRules(long workspaceId);

    /**
     * Returns the workspace's <b>enabled</b> rules, for the detection path.
     *
     * @param workspaceId the workspace
     * @return the enabled rules
     */
    List<AiGuardrailCustomRule> getEnabledRules(long workspaceId);
}
