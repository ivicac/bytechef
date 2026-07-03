/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

/**
 * Decides who, beyond the chat's owner, may reach an {@link AiHubChat}. The owner and a tenant admin may always view,
 * participate in, and manage a chat; a non-owner's reach beyond that is governed by the chat's
 * {@link AiHubChat#getVisibility()} and, for turns, its {@link AiHubChat#getParticipation()} setting.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiHubChatAccessPolicy {

    /**
     * Whether {@code userId} may read the chat's metadata and transcript.
     */
    boolean canView(AiHubChat chat, long userId);

    /**
     * Whether {@code userId} may contribute a turn to the chat — append a message, truncate history, or cancel a run.
     */
    boolean canParticipate(AiHubChat chat, long userId);

    /**
     * Whether {@code userId} may change the chat's own settings or delete it. Owner-or-admin only: sharing a chat never
     * extends management rights to the people it is shared with.
     */
    boolean canManage(AiHubChat chat, long userId);
}
