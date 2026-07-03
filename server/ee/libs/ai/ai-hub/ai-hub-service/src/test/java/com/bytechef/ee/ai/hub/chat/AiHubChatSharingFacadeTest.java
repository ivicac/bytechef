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

import com.bytechef.ee.ai.hub.audit.AiHubAuditEvent;
import com.bytechef.ee.ai.hub.audit.AiHubAuditPublisher;
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    private AiHubAuditPublisher auditPublisher;
    private AiHubChatService chatService;
    private ResourceGrantService resourceGrantService;
    private WorkspaceUserService workspaceUserService;
    private MeterRegistry meterRegistry;
    private ObjectProvider<MeterRegistry> meterRegistryProvider;
    private UserService userService;
    private AiHubChatSharingFacadeImpl sharingFacade;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        auditPublisher = mock(AiHubAuditPublisher.class);
        chatService = mock(AiHubChatService.class);
        resourceGrantService = mock(ResourceGrantService.class);
        workspaceUserService = mock(WorkspaceUserService.class);
        meterRegistry = new SimpleMeterRegistry();

        userService = mock(UserService.class);

        User currentUser = mock(User.class);

        when(currentUser.getId()).thenReturn(CURRENT_USER_ID);
        when(userService.getCurrentUser()).thenReturn(currentUser);

        AiHubChat chat = new AiHubChat(CURRENT_USER_ID);

        chat.setId(CHAT_ID);
        chat.setWorkspaceId(WORKSPACE_ID);

        when(chatService.getManageable(CHAT_ID, WORKSPACE_ID, CURRENT_USER_ID)).thenReturn(chat);

        meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        sharingFacade = newSharingFacade(auditPublisher);
    }

    private AiHubChatSharingFacadeImpl newSharingFacade(AiHubAuditPublisher publisher) {
        return new AiHubChatSharingFacadeImpl(
            publisher, chatService, new AiHubChatSharingMetrics(meterRegistryProvider),
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

    /**
     * The audit payload keys are the whole point of these events — a reviewer reading the Audit Events page needs to
     * know which chat moved to which rung and who was granted what. Nothing asserted them while the facade under test
     * was built with a null publisher, so the three events could have shipped with any key names, or none.
     */
    @Test
    void testTheThreeSharingAuditEventsCarryTheirPayloadKeys() {
        AiHubChat updated = new AiHubChat(CURRENT_USER_ID);

        updated.setId(CHAT_ID);
        updated.setWorkspaceId(WORKSPACE_ID);

        when(chatService.patchSharing(CHAT_ID, ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE))
            .thenReturn(updated);
        when(workspaceUserService.fetchWorkspaceUser(GRANTEE_USER_ID, WORKSPACE_ID))
            .thenReturn(Optional.of(mock(WorkspaceUser.class)));

        sharingFacade.setVisibility(
            WORKSPACE_ID, CHAT_ID, ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);
        sharingFacade.grantAccess(WORKSPACE_ID, CHAT_ID, GRANTEE_USER_ID);
        sharingFacade.revokeAccess(WORKSPACE_ID, CHAT_ID, GRANTEE_USER_ID);

        ArgumentCaptor<AiHubAuditEvent> eventCaptor = ArgumentCaptor.forClass(AiHubAuditEvent.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditPublisher, times(3)).publish(eventCaptor.capture(), dataCaptor.capture());

        assertThat(eventCaptor.getAllValues()).containsExactly(
            AiHubAuditEvent.AI_HUB_CHAT_VISIBILITY_CHANGED, AiHubAuditEvent.AI_HUB_CHAT_ACCESS_GRANTED,
            AiHubAuditEvent.AI_HUB_CHAT_ACCESS_REVOKED);

        List<Map<String, Object>> payloads = dataCaptor.getAllValues();

        assertThat(payloads.get(0)).containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "chatId", CHAT_ID, "workspaceId", WORKSPACE_ID, "visibility", "WORKSPACE", "participation",
                "PARTICIPATE"));
        assertThat(payloads.get(1)).containsExactlyInAnyOrderEntriesOf(
            Map.of("chatId", CHAT_ID, "workspaceId", WORKSPACE_ID, "granteeUserId", GRANTEE_USER_ID));
        assertThat(payloads.get(2)).containsExactlyInAnyOrderEntriesOf(
            Map.of("chatId", CHAT_ID, "workspaceId", WORKSPACE_ID, "granteeUserId", GRANTEE_USER_ID));
    }

    /**
     * The publisher is nullable in production ({@code ObjectProvider}), so an app variant without the audit module must
     * still be able to share a chat. Built with an explicit null rather than reusing the field.
     */
    @Test
    void testSharingWorksWithoutAnAuditPublisher() {
        AiHubChat updated = new AiHubChat(CURRENT_USER_ID);

        updated.setId(CHAT_ID);
        updated.setWorkspaceId(WORKSPACE_ID);

        when(chatService.patchSharing(CHAT_ID, ResourceVisibility.WORKSPACE, AiHubChatParticipation.VIEW))
            .thenReturn(updated);

        AiHubChatSharingFacadeImpl publisherlessFacade = newSharingFacade(null);

        assertThat(
            publisherlessFacade.setVisibility(
                WORKSPACE_ID, CHAT_ID, ResourceVisibility.WORKSPACE, AiHubChatParticipation.VIEW))
                    .isSameAs(updated);
    }
}
