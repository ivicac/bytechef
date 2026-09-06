/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.domain;

/**
 * What was done about a detection, recorded alongside it.
 *
 * <p>
 * Persisted as an INT ordinal, so append new values at the end only. {@code AiGuardrailViolationActionStabilityTest}
 * pins the ordinals.
 * </p>
 *
 * <p>
 * {@link #ALLOWED} is the one that must not be collapsed into the others: it is a counterfactual. Observe mode saw the
 * violation and forwarded the content unmodified, so a week of {@code ALLOWED} records is a week of things that WOULD
 * have been acted on. Reading them as enforcements would tell an operator their guardrails are working when nothing has
 * been enforced at all.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum AiGuardrailViolationAction {

    BLOCKED,
    REDACTED,
    ALLOWED
}
