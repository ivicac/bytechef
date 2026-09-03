/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

/**
 * Component rule settings, stored as a single {@code Property} row rather than a table. A workspace may override the
 * tenant default; {@link ComponentRuleSettingsService#getSettings(Long)} resolves the workspace's own row, else the
 * tenant default, else {@link #DEFAULT}.
 *
 * @param observeMode            when true every rule is evaluated and none is enforced: a would-be block or approval is
 *                               audited as observed and the call proceeds. The way to turn rules on in a live tenant
 *                               without risking a mis-authored rule.
 * @param approvalExpiresInHours how long a rule-raised approval request stays valid. Defaults to 60 days, matching the
 *                               approval gate and the standalone Approval action.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public record ComponentRuleSettings(boolean observeMode, int approvalExpiresInHours) {

    public static final String PROPERTY_KEY = "component_rule_settings";

    public static final ComponentRuleSettings DEFAULT = new ComponentRuleSettings(false, 1440);
}
