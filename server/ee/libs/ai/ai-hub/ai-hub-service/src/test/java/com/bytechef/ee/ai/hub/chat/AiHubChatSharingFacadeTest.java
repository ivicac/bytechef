/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.ai.hub.exception.NotFoundException;
import com.bytechef.ee.ai.hub.metric.AiHubChatSharingMetrics;
import com.bytechef.ee.automation.configuration.domain.WorkspaceUser;
import com.bytechef.ee.automation.configuration.service.WorkspaceUserService;
import com.bytechef.ee.platform.resource.grant.service.ResourceGrantService;
import com.bytechef.platform.security.domain.ResourceVisibility;
import com.bytechef.platform.security.domain.ResourceVisibilityPolicyRegistry;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubChatSharingFacadeTest {

    private static final long CHAT_ID = 10L;
    private static final long CURRENT_USER_ID = 3L;
    private static final long GRANTEE_USER_ID = 42L;
    private static final long WORKSPACE_ID = 1L;

    private AiHubChatService chatService;
    private ResourceGrantService resourceGrantService;
    private WorkspaceUserService workspaceUserService;
    private MeterRegistry meterRegistry;
    private AiHubChatSharingFacadeImpl sharingFacade;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        chatService = mock(AiHubChatService.class);
        resourceGrantService = mock(ResourceGrantService.class);
        workspaceUserService = mock(WorkspaceUserService.class);
        meterRegistry = new SimpleMeterRegistry();

        UserService userService = mock(UserService.class);
        User currentUser = mock(User.class);

        when(currentUser.getId()).thenReturn(CURRENT_USER_ID);
        when(userService.getCurrentUser()).thenReturn(currentUser);

        AiHubChat chat = new AiHubChat(CURRENT_USER_ID);

        chat.setId(CHAT_ID);
        chat.setWorkspaceId(WORKSPACE_ID);

        when(chatService.getManageable(CHAT_ID, WORKSPACE_ID, CURRENT_USER_ID)).thenReturn(chat);

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        sharingFacade = new AiHubChatSharingFacadeImpl(
            null, chatService, new AiHubChatSharingMetrics(meterRegistryProvider),
            new ResourceVisibilityPolicyRegistry(List.of(new AiHubChatVisibilityPolicy())), resourceGrantService,
            userService, workspaceUserService);
    }

    @Test
    void testSetVisibilityRejectsOrganization() {
        assertThatThrownBy(
            () -> sharingFacade.setVisibility(
                WORKSPACE_ID, CHAT_ID, ResourceVisibility.ORGANIZATION, AiHubChatParticipation.VIEW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ORGANIZATION");

        verify(chatService, never()).patchSharing(anyLong(), any(), any());
        verify(chatService, never()).getManageable(anyLong(), anyLong(), anyLong());
        assertThat(meterRegistry.find(AiHubChatSharingMetrics.SHARE_COUNTER)
            .counter()).isNull();
    }

    @Test
    void testSetVisibilityPersistsBothFields() {
        AiHubChat updated = new AiHubChat(CURRENT_USER_ID);

        updated.setId(CHAT_ID);
        updated.setWorkspaceId(WORKSPACE_ID);
        updated.setVisibility(ResourceVisibility.WORKSPACE);
        updated.setParticipation(AiHubChatParticipation.PARTICIPATE);

        when(chatService.patchSharing(CHAT_ID, ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE))
            .thenReturn(updated);

        AiHubChat result = sharingFacade.setVisibility(
            WORKSPACE_ID, CHAT_ID, ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);

        assertThat(result).isSameAs(updated);
        verify(chatService).patchSharing(CHAT_ID, ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);
        assertThat(meterRegistry.counter(AiHubChatSharingMetrics.SHARE_COUNTER, "visibility", "WORKSPACE")
            .count()).isEqualTo(1.0);
    }

    @Test
    void testGrantRequiresWorkspaceMember() {
        when(workspaceUserService.fetchWorkspaceUser(GRANTEE_USER_ID, WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sharingFacade.grantAccess(WORKSPACE_ID, CHAT_ID, GRANTEE_USER_ID))
            .isInstanceOf(NotFoundException.class);

        verify(resourceGrantService, never()).grant(anyString(), anyLong(), anyLong());
    }

    @Test
    void testGrantIsIdempotentThroughTheService() {
        when(workspaceUserService.fetchWorkspaceUser(GRANTEE_USER_ID, WORKSPACE_ID))
            .thenReturn(Optional.of(mock(WorkspaceUser.class)));

        sharingFacade.grantAccess(WORKSPACE_ID, CHAT_ID, GRANTEE_USER_ID);

        verify(resourceGrantService, times(1)).grant("AiHubChat", CHAT_ID, GRANTEE_USER_ID);
    }

    @Test
    void testRevokeDoesNotCheckMembership() {
        sharingFacade.revokeAccess(WORKSPACE_ID, CHAT_ID, GRANTEE_USER_ID);

        verify(resourceGrantService).revoke("AiHubChat", CHAT_ID, GRANTEE_USER_ID);
        verify(workspaceUserService, never()).fetchWorkspaceUser(anyLong(), anyLong());
    }

    @Test
    void testGetGrantsReturnsUserIds() {
        when(resourceGrantService.getGrantedUserIds("AiHubChat", CHAT_ID)).thenReturn(List.of(GRANTEE_USER_ID));

        assertThat(sharingFacade.getGrants(WORKSPACE_ID, CHAT_ID)).containsExactly(GRANTEE_USER_ID);
    }
}
