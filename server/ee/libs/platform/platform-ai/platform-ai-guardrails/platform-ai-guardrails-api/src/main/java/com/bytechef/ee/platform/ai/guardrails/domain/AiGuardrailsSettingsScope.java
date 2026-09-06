/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.domain;

/**
 * Which scope a guardrails settings row belongs to. Explicit rather than inferred from a nullable {@code workspaceId},
 * because that inference could express only two of the three scopes and made {@code null} mean two different things:
 * the tenant default, and (once embedded MCP needed its own row) embedded.
 *
 * <p>
 * Deliberately not {@code Property.Scope}: that type is persistence-layer and carries values ({@code AUTOMATION},
 * {@code PROJECT}, {@code INTEGRATION}) meaningless here, and a settings record in an {@code -api} module should not
 * depend on the property store's shape. Not persisted at all, so it is not ordinal-sensitive: the scope IS the row's
 * identity -- it selects the {@code Property.Scope}/{@code scopeId} pair the row is read from and written to
 * ({@code AiGuardrailsWorkspaceSettingsServiceImpl#scopeOf}) -- and is reconstructed from that on read. Storing it in
 * the value map as well would create a second copy that could disagree with the row it sits in.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum AiGuardrailsSettingsScope {

    PLATFORM,
    WORKSPACE,
    EMBEDDED
}
