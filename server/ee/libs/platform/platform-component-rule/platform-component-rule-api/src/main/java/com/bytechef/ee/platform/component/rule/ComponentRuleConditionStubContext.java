/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import java.util.HashMap;
import java.util.Map;

/**
 * The evaluation context used to parse-check a condition at authoring time — save time in
 * {@code ComponentRuleServiceImpl} and propose time in {@code ProposeComponentRuleConditionToolCallback}.
 *
 * <p>
 * An empty context is not enough: {@code SpelEvaluator} treats an unresolved root reference as lenient — it returns the
 * original string instead of throwing — so {@code frobnicate(inputParameters['x'], 'y')} against an empty context
 * short-circuits at the unresolved {@code inputParameters} reference and never reaches function resolution, letting a
 * misspelled function name validate clean. Supplying the same root keys the enforcer evaluates against, present but
 * empty, lets evaluation reach past the reference and into function resolution, where an unknown function is a hard
 * failure ({@code UnsupportedOperationException}, not a {@code SpelEvaluationException}) that is never swallowed as
 * lenient.
 * </p>
 *
 * <p>
 * Both call sites must use this same stub, or a condition could validate at one and fail at the other.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class ComponentRuleConditionStubContext {

    private ComponentRuleConditionStubContext() {
    }

    /**
     * The root keys {@code ComponentRuleEnforcerImpl.buildEvaluationContext} puts in scope for a real call, each
     * present but empty: an empty {@link Map} for {@code inputParameters} and {@code output}, an empty string for the
     * name scalars, and {@code null} for {@code connectionId} — the same "no connection" value a real call without one
     * carries. A plain {@link HashMap} is used, not {@link Map#of}, because {@code connectionId} must be present with a
     * {@code null} value and {@code Map.of} rejects null values outright.
     */
    public static Map<String, Object> get() {
        Map<String, Object> context = new HashMap<>();

        context.put("inputParameters", Map.of());
        context.put("componentName", "");
        context.put("toolName", "");
        context.put("toolCallName", "");
        context.put("connectionId", null);
        context.put("output", Map.of());

        return context;
    }
}
