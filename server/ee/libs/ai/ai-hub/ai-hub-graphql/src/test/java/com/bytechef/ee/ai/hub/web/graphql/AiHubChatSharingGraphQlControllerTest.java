/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatParticipation;
import com.bytechef.ee.ai.hub.chat.AiHubChatSharingFacade;
import com.bytechef.platform.security.domain.ResourceVisibility;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubChatSharingGraphQlControllerTest {

    @Test
    void testAiHubChatGrantsDelegatesToFacade() {
        AiHubChatSharingFacade sharingFacade = mock(AiHubChatSharingFacade.class);

        when(sharingFacade.getGrants(1L, 10L)).thenReturn(List.of(42L));

        AiHubChatSharingGraphQlController controller = new AiHubChatSharingGraphQlController(sharingFacade);

        List<Long> result = controller.aiHubChatGrants(1L, 10L);

        assertThat(result).containsExactly(42L);
        verify(sharingFacade).getGrants(1L, 10L);
    }

    @Test
    void testSetAiHubChatVisibilityConvertsToThePlatformTypeAndDelegatesToFacade() {
        AiHubChatSharingFacade sharingFacade = mock(AiHubChatSharingFacade.class);
        AiHubChat chat = mock(AiHubChat.class);

        when(sharingFacade.setVisibility(1L, 10L, ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE))
            .thenReturn(chat);

        AiHubChatSharingGraphQlController controller = new AiHubChatSharingGraphQlController(sharingFacade);

        AiHubChat result = controller.setAiHubChatVisibility(
            1L, 10L, AiHubChatVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);

        assertThat(result).isSameAs(chat);
        verify(sharingFacade).setVisibility(1L, 10L, ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);
    }

    @Test
    void testGrantAiHubChatAccessDelegatesToFacade() {
        AiHubChatSharingFacade sharingFacade = mock(AiHubChatSharingFacade.class);
        AiHubChat chat = mock(AiHubChat.class);

        when(sharingFacade.grantAccess(1L, 10L, 42L)).thenReturn(chat);

        AiHubChatSharingGraphQlController controller = new AiHubChatSharingGraphQlController(sharingFacade);

        AiHubChat result = controller.grantAiHubChatAccess(1L, 10L, 42L);

        assertThat(result).isSameAs(chat);
        verify(sharingFacade).grantAccess(1L, 10L, 42L);
    }

    @Test
    void testRevokeAiHubChatAccessDelegatesToFacade() {
        AiHubChatSharingFacade sharingFacade = mock(AiHubChatSharingFacade.class);
        AiHubChat chat = mock(AiHubChat.class);

        when(sharingFacade.revokeAccess(1L, 10L, 42L)).thenReturn(chat);

        AiHubChatSharingGraphQlController controller = new AiHubChatSharingGraphQlController(sharingFacade);

        AiHubChat result = controller.revokeAiHubChatAccess(1L, 10L, 42L);

        assertThat(result).isSameAs(chat);
        verify(sharingFacade).revokeAccess(1L, 10L, 42L);
    }
}
