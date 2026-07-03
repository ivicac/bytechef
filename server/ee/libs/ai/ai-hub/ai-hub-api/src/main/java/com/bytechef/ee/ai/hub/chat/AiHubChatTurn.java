/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Records who sent one turn on a {@link AiHubChat}. Persisted separately from the chat-memory transcript itself —
 * Spring AI's {@code Message} carries no durable attribution field, so a row here is the only way to recover which user
 * (owner or participant) posted a given {@code USER} event once the chat is shared and more than one person can
 * contribute turns.
 *
 * <p>
 * {@code runId} is the AG-UI run id the turn started, kept for diagnostics; it is nullable because a turn recorded
 * outside the normal dispatch path (a future migration, a manual repair) may have none.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("ai_hub_chat_turn")
public class AiHubChatTurn {

    @Id
    private Long id;

    @Column("chat_id")
    private long chatId;

    @Column("user_id")
    private long userId;

    @Column("run_id")
    private @Nullable String runId;

    @CreatedDate
    @Column("created_date")
    private Instant createdDate;

    public AiHubChatTurn() {
    }

    public AiHubChatTurn(long chatId, long userId, @Nullable String runId) {
        this.chatId = chatId;
        this.userId = userId;
        this.runId = runId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getChatId() {
        return chatId;
    }

    public void setChatId(long chatId) {
        this.chatId = chatId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public @Nullable String getRunId() {
        return runId;
    }

    public void setRunId(@Nullable String runId) {
        this.runId = runId;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof AiHubChatTurn that)) {
            return false;
        }

        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "AiHubChatTurn{" +
            "id=" + id +
            ", chatId=" + chatId +
            ", userId=" + userId +
            ", runId='" + runId + '\'' +
            ", createdDate=" + createdDate +
            '}';
    }
}
