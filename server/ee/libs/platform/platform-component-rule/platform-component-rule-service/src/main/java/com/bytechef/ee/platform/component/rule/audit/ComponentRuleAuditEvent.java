/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.audit;

/**
 * Audit event types emitted through {@link ComponentRuleAuditPublisher}.
 *
 * <p>
 * The payload key contract is documented per constant below. Every event carries {@code ruleId}, {@code componentName},
 * {@code toolName}, {@code phase} and, when the call came from a workflow execution, {@code jobId} and
 * {@code taskExecutionId}; an event tied to one model-invoked tool call also carries {@code toolCallName}, and a
 * resolved approval carries {@code approvedBy} when the resolving channel established an identity. The contract is
 * convention-enforced rather than type-checked, so a change must be applied at every emitter.
 * </p>
 *
 * <p>
 * All are {@code strictAudit = false}, a deliberate departure from {@code ConnectionAuditEvent}'s strict events. Those
 * are low-frequency admin actions where a lost trail is a compliance problem. These fire on live workflow executions,
 * potentially once per action call, and making an audit-capture failure fail the underlying job would turn an
 * observability feature into an availability risk.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum ComponentRuleAuditEvent {

    /**
     * A {@code BEFORE}-phase {@code BLOCK} rule matched and the action call was refused. Published synchronously in the
     * same guard that throws — there is no commit to defer to, because the call is refused rather than committed.
     */
    RULE_BLOCKED(false),

    /**
     * A {@code TAG} rule matched. Emitted for both {@code BEFORE}-phase and {@code AFTER}-phase matches; the
     * {@code phase} payload field distinguishes them. Never emitted for a call that a {@code BLOCK} rule refused — that
     * call never happened, so there is nothing to review.
     */
    RULE_TAGGED(false),

    /**
     * A {@code BEFORE}-phase {@code REQUIRE_APPROVAL} rule matched and the agent turn was suspended for a human.
     */
    RULE_APPROVAL_REQUESTED(false),

    /**
     * A human approved a request an approval rule raised. Carries {@code approvedBy} when the resolving channel
     * established an identity.
     */
    RULE_APPROVED(false),

    /**
     * A human rejected a request an approval rule raised.
     */
    RULE_REJECTED(false),

    /**
     * A rule matched while observe mode was on: nothing was enforced, and {@code wouldHave} carries the enforcement the
     * rule's action would have applied had observe mode been off.
     */
    RULE_OBSERVED(false);

    private final boolean strictAudit;

    ComponentRuleAuditEvent(boolean strictAudit) {
        this.strictAudit = strictAudit;
    }

    /**
     * Always {@code false} for this enum — see the class javadoc. The accessor exists so these events read the same way
     * as {@code ConnectionAuditEvent} to anything that inspects audit metadata generically.
     */
    public boolean isStrictAudit() {
        return strictAudit;
    }
}
