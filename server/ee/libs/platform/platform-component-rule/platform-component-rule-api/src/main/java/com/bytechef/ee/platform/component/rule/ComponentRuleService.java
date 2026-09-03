/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Component rule operations. Each rule is scoped to one workspace or, with a null {@code workspaceId}, to every
 * workspace in the tenant.
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
     * A single rule by id. Exists mainly so a caller can inspect a rule's {@code workspaceId} before acting on it — for
     * example, requiring admin to delete a tenant-wide rule the same way creating or widening one requires it.
     */
    ComponentRule getComponentRule(long id);

    /**
     * The enforcement-path query: every enabled rule for one component that applies to the given workspace — the
     * workspace's own rules unioned with the tenant-wide ones — across both phases and both actions-scoped and
     * all-actions rules. Deliberately not narrowed by action or phase, because the enforcer caches one entry per
     * component and narrowing in SQL would make that cache useless. A null {@code workspaceId} narrows the result to
     * tenant-wide rules only, never widens it to another workspace's.
     */
    List<ComponentRule> getEnabledComponentRules(String componentName, @Nullable Long workspaceId);

    /**
     * Validates and persists. Rejects a {@code BLOCK}+{@code AFTER} combination and a condition that does not parse as
     * a formula expression, both before touching the database.
     */
    ComponentRule saveComponentRule(ComponentRule componentRule);

    void deleteComponentRule(long id);
}
