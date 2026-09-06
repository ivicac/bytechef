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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One workspace-defined detection pattern, as stored.
 *
 * <p>
 * Deliberately separate from {@code CustomPattern}, the CE runtime shape. Storage is per-workspace policy and belongs
 * in EE; matching is a mechanism and belongs in CE, so that the knowledge base can reuse the mechanism later without
 * depending on the policy. The service converts between them.
 * </p>
 *
 * <p>
 * <b>{@link #enabled} defaults to false and that is the feature, not a default value.</b> A new rule detects nothing
 * until someone enables it as a second, deliberate act — and the intended route from written to enforcing runs through
 * observe mode: enable it under {@code BlockingMode.ALLOW}, read {@code guardrail_allowed} against a week of real
 * traffic, then promote. Testing a rule against pasted samples was considered and rejected, because the samples an
 * operator pastes are the cases they already thought of and the false positives that matter are the ones they did not.
 * </p>
 *
 * <p>
 * {@link #kind} is an {@code int} rather than the CE {@code SensitiveKind}, matching {@code AiGuardrailViolation}: it
 * keeps this module free of a dependency on the sensitive-data API for one enum, and the service converts at the
 * boundary. Persisted as an ordinal, so that enum stays append-only.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("ai_guardrail_custom_rule")
public class AiGuardrailCustomRule {

    /**
     * Comma-delimited, matching how {@code AiGuardrailsWorkspaceSettings} stores blocked terms rather than inventing a
     * second convention. A keyword may therefore not contain a comma, which the validator enforces.
     */
    private @Nullable String contextKeywords;

    private @Nullable BigDecimal contextScore;

    private @Nullable Integer contextWindow;

    @CreatedDate
    private Instant createdDate;

    private boolean enabled;

    @Id
    private Long id;

    private int kind;

    @LastModifiedDate
    private Instant lastModifiedDate;

    private String pattern;

    private BigDecimal score;

    private String type;

    @Version
    private int version;

    private long workspaceId;

    private AiGuardrailCustomRule() {
    }

    public AiGuardrailCustomRule(
        long workspaceId, String type, String pattern, int kind, BigDecimal score,
        @Nullable String contextKeywords, @Nullable Integer contextWindow, @Nullable BigDecimal contextScore) {

        this.workspaceId = workspaceId;
        this.type = type;
        this.pattern = pattern;
        this.kind = kind;
        this.score = score;
        this.contextKeywords = contextKeywords;
        this.contextWindow = contextWindow;
        this.contextScore = contextScore;
    }

    public @Nullable String getContextKeywords() {
        return contextKeywords;
    }

    public @Nullable BigDecimal getContextScore() {
        return contextScore;
    }

    public @Nullable Integer getContextWindow() {
        return contextWindow;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public Long getId() {
        return id;
    }

    public int getKind() {
        return kind;
    }

    public Instant getLastModifiedDate() {
        return lastModifiedDate;
    }

    public String getPattern() {
        return pattern;
    }

    public BigDecimal getScore() {
        return score;
    }

    public String getType() {
        return type;
    }

    public long getWorkspaceId() {
        return workspaceId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setContextKeywords(@Nullable String contextKeywords) {
        this.contextKeywords = contextKeywords;
    }

    public void setContextScore(@Nullable BigDecimal contextScore) {
        this.contextScore = contextScore;
    }

    public void setContextWindow(@Nullable Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setKind(int kind) {
        this.kind = kind;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    @Override
    public String toString() {
        return "AiGuardrailCustomRule{" +
            "id=" + id +
            ", workspaceId=" + workspaceId +
            ", type='" + type + '\'' +
            ", kind=" + kind +
            ", score=" + score +
            ", enabled=" + enabled +
            ", createdDate=" + createdDate +
            '}';
    }
}
