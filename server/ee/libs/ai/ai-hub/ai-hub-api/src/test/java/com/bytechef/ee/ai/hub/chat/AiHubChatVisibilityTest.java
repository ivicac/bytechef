/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.security.domain.ResourceVisibility;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link AiHubChatParticipation}'s ordinals and verifies {@link AiHubChat} carries a {@link ResourceVisibility}
 * and an {@link AiHubChatParticipation}, both defaulting to the most restrictive setting for a newly constructed row.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubChatVisibilityTest {

    @Test
    void testParticipationOrdinalsAreStable() {
        assertThat(AiHubChatParticipation.VIEW.ordinal()).isEqualTo(0);
        assertThat(AiHubChatParticipation.PARTICIPATE.ordinal()).isEqualTo(1);
        assertThat(AiHubChatParticipation.values()).hasSize(2);
    }

    @Test
    void testNewChatIsPrivateAndViewOnly() {
        AiHubChat chat = new AiHubChat(3L);

        assertThat(chat.getVisibility()).isEqualTo(ResourceVisibility.PRIVATE);
        assertThat(chat.getParticipation()).isEqualTo(AiHubChatParticipation.VIEW);
    }

    @Test
    void testVisibilityRoundTripsThroughTheOrdinal() {
        AiHubChat chat = new AiHubChat(3L);

        chat.setVisibility(ResourceVisibility.WORKSPACE);
        chat.setParticipation(AiHubChatParticipation.PARTICIPATE);

        assertThat(chat.getVisibility()).isEqualTo(ResourceVisibility.WORKSPACE);
        assertThat(chat.getParticipation()).isEqualTo(AiHubChatParticipation.PARTICIPATE);
    }
}
