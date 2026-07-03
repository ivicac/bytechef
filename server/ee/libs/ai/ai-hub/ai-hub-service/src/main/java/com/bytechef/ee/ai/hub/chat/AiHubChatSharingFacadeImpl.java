/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import com.bytechef.ee.ai.hub.audit.AiHubAuditEvent;
import com.bytechef.ee.ai.hub.audit.AiHubAuditPublisher;
import com.bytechef.ee.ai.hub.exception.NotFoundException;
import com.bytechef.ee.ai.hub.metric.AiHubChatSharingMetrics;
import com.bytechef.ee.automation.configuration.service.WorkspaceUserService;
import com.bytechef.ee.platform.resource.grant.service.ResourceGrantService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.domain.ResourceVisibility;
import com.bytechef.platform.security.domain.ResourceVisibilityPolicyRegistry;
import com.bytechef.platform.user.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link AiHubChatSharingFacade}.
 *
 * <p>
 * A chat has no {@code PermissionService} resource type wired for {@code isResourceOwner} the way a project does, so
 * the owner-or-admin check is programmatic ({@link AiHubChatService#getManageable}) on every method rather than a
 * {@code @PreAuthorize} expression. The class is annotated {@code @PreAuthorize("isAuthenticated()")} so the facade
 * never runs anonymous, while the programmatic check remains the authoritative gate.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
@PreAuthorize("isAuthenticated()")
public class AiHubChatSharingFacadeImpl implements AiHubChatSharingFacade {

    private final @Nullable AiHubAuditPublisher auditPublisher;
    private final AiHubChatService chatService;
    private final AiHubChatSharingMetrics sharingMetrics;
    private final ResourceVisibilityPolicyRegistry policyRegistry;
    private final ResourceGrantService resourceGrantService;
    private final UserService userService;
    private final WorkspaceUserService workspaceUserService;

    /**
     * {@code auditPublisher} is nullable because an app variant assembled without the audit module has no such bean;
     * the private {@link #publish} helper guards on it, so sharing still works with audit absent. Tests pass a mock,
     * not {@code null} — a null publisher makes every {@code publish} call a silent no-op, so nothing would assert that
     * the three sharing events fire or what their payload keys are.
     */
    @SuppressFBWarnings("EI")
    public AiHubChatSharingFacadeImpl(
        @Nullable AiHubAuditPublisher auditPublisher, AiHubChatService chatService,
        AiHubChatSharingMetrics sharingMetrics, ResourceVisibilityPolicyRegistry policyRegistry,
        ResourceGrantService resourceGrantService, UserService userService,
        WorkspaceUserService workspaceUserService) {

        this.auditPublisher = auditPublisher;
        this.chatService = chatService;
        this.sharingMetrics = sharingMetrics;
        this.policyRegistry = policyRegistry;
        this.resourceGrantService = resourceGrantService;
        this.userService = userService;
        this.workspaceUserService = workspaceUserService;
    }

    @Override
    public AiHubChat setVisibility(
        long workspaceId, long chatId, ResourceVisibility visibility, AiHubChatParticipation participation) {

        if (!policyRegistry.supports(AiHubChatVisibilityPolicy.RESOURCE_TYPE, visibility)) {
            throw new IllegalArgumentException("Unsupported visibility for a chat: " + visibility);
        }

        AiHubChat chat = chatService.getManageable(chatId, workspaceId, currentUserId());
        AiHubChat updated = chatService.patchSharing(chat.getId(), visibility, participation);

        sharingMetrics.recordShare(visibility.name());

        publish(AiHubAuditEvent.AI_HUB_CHAT_VISIBILITY_CHANGED, updated, Map.of(
            "visibility", visibility.name(), "participation", participation.name()));

        return updated;
    }

    @Override
    public AiHubChat grantAccess(long workspaceId, long chatId, long userId) {
        AiHubChat chat = chatService.getManageable(chatId, workspaceId, currentUserId());

        if (workspaceUserService.fetchWorkspaceUser(userId, workspaceId)
            .isEmpty()) {

            throw new NotFoundException("AiHubChat not found");
        }

        resourceGrantService.grant(AiHubChatVisibilityPolicy.RESOURCE_TYPE, chat.getId(), userId);
        publish(AiHubAuditEvent.AI_HUB_CHAT_ACCESS_GRANTED, chat, Map.of("granteeUserId", userId));

        return chat;
    }

    @Override
    public AiHubChat revokeAccess(long workspaceId, long chatId, long userId) {
        AiHubChat chat = chatService.getManageable(chatId, workspaceId, currentUserId());

        resourceGrantService.revoke(AiHubChatVisibilityPolicy.RESOURCE_TYPE, chat.getId(), userId);
        publish(AiHubAuditEvent.AI_HUB_CHAT_ACCESS_REVOKED, chat, Map.of("granteeUserId", userId));

        return chat;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> getGrants(long workspaceId, long chatId) {
        AiHubChat chat = chatService.getManageable(chatId, workspaceId, currentUserId());

        return resourceGrantService.getGrantedUserIds(AiHubChatVisibilityPolicy.RESOURCE_TYPE, chat.getId());
    }

    private long currentUserId() {
        return userService.getCurrentUser()
            .getId();
    }

    private void publish(AiHubAuditEvent event, AiHubChat chat, Map<String, Object> extraData) {
        if (auditPublisher == null) {
            return;
        }

        Map<String, Object> data = new HashMap<>();

        data.put("chatId", chat.getId());
        data.put("workspaceId", chat.getWorkspaceId());
        data.putAll(extraData);

        auditPublisher.publish(event, data);
    }
}
