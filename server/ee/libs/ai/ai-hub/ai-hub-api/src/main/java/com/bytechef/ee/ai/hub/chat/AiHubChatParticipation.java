/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

/**
 * Whether a person a chat has been shared with may only follow it live, or may also contribute turns. Scoped to a
 * single {@link AiHubChat} row and orthogonal to {@link com.bytechef.platform.security.domain.ResourceVisibility},
 * which decides who can reach the chat at all.
 *
 * <p>
 * Persisted as an INT ordinal by Spring Data JDBC. Appending a value is safe; reordering existing values would
 * reinterpret every persisted row.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum AiHubChatParticipation {

    VIEW,
    PARTICIPATE
}
