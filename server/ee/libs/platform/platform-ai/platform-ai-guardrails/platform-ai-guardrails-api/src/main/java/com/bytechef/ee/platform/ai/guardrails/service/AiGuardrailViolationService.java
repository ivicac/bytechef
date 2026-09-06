/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.service;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolation;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads per-detection guardrail drill-down records.
 *
 * <p>
 * <b>There is deliberately no by-id read.</b> Every read is scoped to a workspace the caller has already been
 * authorized for, so there is no lookup that could confirm the existence of another workspace's record — the
 * probe-oracle problem the codebase solves elsewhere by making a cross-scope id indistinguishable from a missing one
 * (see {@code VariableServiceImpl}) simply cannot arise here. If a by-id read is ever added it needs that treatment,
 * and this javadoc is where whoever adds it should find that out.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiGuardrailViolationService {

    /**
     * Returns a workspace's records, newest first.
     *
     * @param workspaceId the workspace to read, or {@code null} for the tenant-default bucket every unattributed call
     *                    lands in
     * @param limit       the maximum number of records to return
     * @return the records, newest first
     */
    List<AiGuardrailViolation> getViolations(@Nullable Long workspaceId, int limit);
}
