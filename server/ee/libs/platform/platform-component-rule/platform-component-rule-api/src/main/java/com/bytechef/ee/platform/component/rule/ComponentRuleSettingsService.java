/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes {@link ComponentRuleSettings}, per workspace with a tenant default.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ComponentRuleSettingsService {

    /**
     * The settings governing {@code workspaceId}: its own override if it has one, else the tenant default, else
     * {@link ComponentRuleSettings#DEFAULT}. A {@code null} workspace asks for the tenant default directly.
     */
    ComponentRuleSettings getSettings(@Nullable Long workspaceId);

    /**
     * The workspace's own override row, present only when {@code workspaceId} has one of its own -- never the tenant
     * default and never {@link ComponentRuleSettings#DEFAULT}. Empty for a {@code null} workspaceId, since the tenant
     * default is not an override of anything. Exists so a caller can tell "this workspace is following the tenant
     * default" apart from "this workspace has its own settings that happen to match the tenant default" --
     * {@link #getSettings(Long)} alone cannot make that distinction, since it only ever returns the already-resolved
     * effective value.
     */
    Optional<ComponentRuleSettings> fetchWorkspaceOverride(@Nullable Long workspaceId);

    ComponentRuleSettings saveSettings(ComponentRuleSettings componentRuleSettings, @Nullable Long workspaceId);
}
