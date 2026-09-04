/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat.repository;

import com.bytechef.ee.ai.hub.chat.AiHubChatTurn;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link AiHubChatTurn} rows.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiHubChatTurnRepository extends CrudRepository<AiHubChatTurn, Long> {

    List<AiHubChatTurn> findAllByChatIdOrderByCreatedDateAsc(long chatId);

    Optional<AiHubChatTurn> findFirstByChatIdOrderByCreatedDateDesc(long chatId);

    void deleteAllByChatId(long chatId);

    /**
     * Truncates the turn history down to the oldest {@code keep} rows for the chat, matching the truncation
     * {@code AiHubChatServiceImpl#truncateMessagesFrom} applies to the session-store transcript — the caller passes the
     * count of {@code USER} events retained among the visible rows kept by that truncation, so the two histories stay
     * in step and a later turn is never attributed against a row that no longer exists.
     */
    @Modifying
    @Query("""
        DELETE FROM ai_hub_chat_turn
        WHERE chat_id = :chatId
          AND id IN (
              SELECT id FROM ai_hub_chat_turn WHERE chat_id = :chatId ORDER BY created_date ASC OFFSET :keep
          )
        """)
    int deleteFromOrdinal(long chatId, int keep);
}
