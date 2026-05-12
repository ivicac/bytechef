/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.platform.ai.auto.memory;

/**
 * Classifies the kind of fact stored in a {@link AiAutoMemory}. Used to filter the index shown to the agent and to
 * group entries in the management UI.
 *
 * <ul>
 * <li>{@link #USER} — user profile / preferences (role, tone, defaults).</li>
 * <li>{@link #FEEDBACK} — corrections or confirmed approaches the user asked the agent to apply in future turns.</li>
 * <li>{@link #PROJECT} — decisions, deadlines, or domain constraints tied to the current workspace.</li>
 * <li>{@link #REFERENCE} — pointers to external systems (dashboards, boards, external docs).</li>
 * </ul>
 *
 * <p>
 * <b>Append-only.</b> The values are persisted as INT ordinals — reordering or deleting a value would silently re-map
 * every historical memory row to the wrong type. New values MUST be appended at the end. The
 * {@code EnumOrdinalStabilityTest#testAiAutoMemoryTypeOrdinalsAreStable} pinning test enforces this at build time.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum AiAutoMemoryType {

    // append-only
    USER,
    FEEDBACK,
    PROJECT,
    REFERENCE
}
