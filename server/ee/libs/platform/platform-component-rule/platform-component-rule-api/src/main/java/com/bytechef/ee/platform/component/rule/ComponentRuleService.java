/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import java.util.List;

/**
 * Tenant-wide component rule operations.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ComponentRuleService {

    /**
     * Every rule in the tenant, enabled or not — the flat list the Rules page renders.
     */
    List<ComponentRule> getComponentRules();

    List<ComponentRule> getComponentRules(String componentName);

    /**
     * The enforcement-path query: every enabled rule for one component, both phases and both actions-scoped and
     * all-actions rules. Deliberately not narrowed by action or phase, because the enforcer caches one entry per
     * component and narrowing in SQL would make that cache useless.
     */
    List<ComponentRule> getEnabledComponentRules(String componentName);

    /**
     * Validates and persists. Rejects a {@code BLOCK}+{@code AFTER} combination and a condition that does not parse as
     * a formula expression, both before touching the database.
     */
    ComponentRule saveComponentRule(ComponentRule componentRule);

    void deleteComponentRule(long id);
}
