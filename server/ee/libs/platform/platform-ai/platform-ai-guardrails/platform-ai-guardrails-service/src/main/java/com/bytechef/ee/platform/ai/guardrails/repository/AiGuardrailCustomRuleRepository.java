/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.repository;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailCustomRule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiGuardrailCustomRuleRepository extends ListCrudRepository<AiGuardrailCustomRule, Long> {

    List<AiGuardrailCustomRule> findAllByWorkspaceIdOrderByTypeAsc(long workspaceId);

    /**
     * The detection path's query: only enabled rules matter to it, and a disabled rule must cost nothing at match time
     * rather than being fetched and filtered.
     */
    List<AiGuardrailCustomRule> findAllByWorkspaceIdAndEnabledTrueOrderByTypeAsc(long workspaceId);

    /**
     * Scoped by workspace on purpose, even though {@code id} alone is unique. A by-id lookup that ignored the workspace
     * would make a rule id from another workspace indistinguishable from one that exists — the probe oracle
     * {@code VariableServiceImpl} avoids the same way.
     */
    Optional<AiGuardrailCustomRule> findByIdAndWorkspaceId(long id, long workspaceId);

    long countByWorkspaceId(long workspaceId);
}
