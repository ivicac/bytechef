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
 * The payload key contract is documented per constant below. Both events carry {@code ruleId}, {@code componentName},
 * {@code actionName}, {@code phase} and, when the call came from a workflow execution, {@code jobId} and
 * {@code taskExecutionId}. The contract is convention-enforced rather than type-checked, so a change must be applied at
 * every emitter.
 * </p>
 *
 * <p>
 * Both are {@code strictAudit = false}, a deliberate departure from {@code ConnectionAuditEvent}'s strict events. Those
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
    RULE_TAGGED(false);

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
