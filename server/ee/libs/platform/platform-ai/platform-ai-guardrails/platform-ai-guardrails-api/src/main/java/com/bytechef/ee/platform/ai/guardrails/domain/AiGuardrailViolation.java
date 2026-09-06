/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.domain;

import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One detection, recorded so an operator can answer questions the counter cannot: which pattern fired, where in the
 * text, how confident the match was, on whose request, and what was done about it.
 *
 * <p>
 * <b>This row never holds the matched value, and no column for one may be added.</b> That is the single decision the
 * whole feature rests on. A record useful for debugging obviously wants the matched text, and storing it would build a
 * plaintext database of exactly the values the product exists to redact — with a longer retention than the request that
 * produced them and a read API in front. {@link #spanStart} and {@link #spanLength} locate the match instead;
 * {@link #category} names the rule. The obvious escape hatch ("a debug mode that keeps the raw value for 24 hours") is
 * forbidden by the design rather than merely absent, because it is the thing that will be asked for and it converts
 * this table into the database it must not be. See
 * {@code docs/superpowers/specs/2026-09-03-guardrail-violation-records-design.md} §2.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("ai_guardrail_violation")
public class AiGuardrailViolation {

    private static final AiGuardrailViolationAction[] ACTION_VALUES = AiGuardrailViolationAction.values();

    private int action;

    /**
     * The rule that fired — a {@code PiiPatternCatalog} type such as {@code EMAIL_ADDRESS}, or a custom rule's name
     * once those exist. The field that makes a rule debuggable, and the reason this feature is worth most after custom
     * rules land.
     */
    private String category;

    private BigDecimal confidence;

    @CreatedDate
    private Instant createdDate;

    private @Nullable Integer environment;

    @Id
    private Long id;

    private int kind;

    /**
     * Who made the request, when a principal was resolvable. Null on the no-principal callers — workflow component
     * actions, anonymous public download, agent-turn threads — which have no authenticated user by construction rather
     * than by omission.
     */
    private @Nullable String principal;

    private int spanLength;

    private int spanStart;

    private String surface;

    private @Nullable Long workspaceId;

    private AiGuardrailViolation() {
    }

    public AiGuardrailViolation(
        String category, int kind, int spanStart, int spanLength, BigDecimal confidence,
        AiGuardrailViolationAction action, String surface, @Nullable Long workspaceId,
        @Nullable Integer environment, @Nullable String principal) {

        this.category = category;
        this.kind = kind;
        this.spanStart = spanStart;
        this.spanLength = spanLength;
        this.confidence = confidence;
        this.action = action.ordinal();
        this.surface = surface;
        this.workspaceId = workspaceId;
        this.environment = environment;
        this.principal = principal;
    }

    public AiGuardrailViolationAction getAction() {
        return ACTION_VALUES[action];
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public @Nullable Integer getEnvironment() {
        return environment;
    }

    public Long getId() {
        return id;
    }

    public int getKind() {
        return kind;
    }

    public @Nullable String getPrincipal() {
        return principal;
    }

    public int getSpanLength() {
        return spanLength;
    }

    public int getSpanStart() {
        return spanStart;
    }

    public String getSurface() {
        return surface;
    }

    public @Nullable Long getWorkspaceId() {
        return workspaceId;
    }

    @Override
    public String toString() {
        // Deliberately reproduces no text: this type's whole contract is that it locates a match without holding it,
        // and a toString that leaked would put the value into every log line that renders a record.
        return "AiGuardrailViolation{" +
            "id=" + id +
            ", category='" + category + '\'' +
            ", kind=" + kind +
            ", spanStart=" + spanStart +
            ", spanLength=" + spanLength +
            ", confidence=" + confidence +
            ", action=" + getAction() +
            ", surface='" + surface + '\'' +
            ", workspaceId=" + workspaceId +
            ", environment=" + environment +
            ", createdDate=" + createdDate +
            '}';
    }
}
