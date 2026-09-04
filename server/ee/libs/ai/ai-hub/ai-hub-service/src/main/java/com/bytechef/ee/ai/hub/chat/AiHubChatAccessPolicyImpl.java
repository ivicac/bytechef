/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import com.bytechef.automation.configuration.service.ResourceVisibilityResolver;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver.VisibilityRecord;
import com.bytechef.ee.automation.configuration.service.WorkspaceUserService;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.util.SecurityUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link AiHubChatAccessPolicy}.
 *
 * <p>
 * {@link AiHubChat} has no {@code created_by} column — its owner is {@code user_id} — so every {@link VisibilityRecord}
 * built here carries a {@code null createdBy}. Ownership is instead decided by the explicit
 * {@code chat.getUserId() == userId} check in {@link #isOwnerOrAdmin}, which always runs before the visibility resolver
 * is consulted; the resolver's own ownership rung is therefore never the deciding factor for a chat.
 * </p>
 *
 * <p>
 * A non-owner is decided by {@link #isSharedWith}, which pairs two halves. {@link ResourceVisibilityResolver} accepts a
 * {@code workspaceId} but never reads it: visibility is a <em>precondition</em>, so the filter alone would make
 * {@code WORKSPACE} mean "any authenticated user in the tenant". Membership of the chat's workspace is therefore
 * required before the filter is consulted, matching the pairing {@code PermissionServiceImpl#hasResourceScope} makes
 * between {@code isResourceVisible} and {@code hasWorkspaceScope}. It is also what makes removal from a workspace
 * revoke access to a chat shared inside it, for the resource-grant rung as much as the {@code WORKSPACE} rung — grants
 * already require membership at grant time ({@code AiHubChatSharingFacadeImpl#grantAccess}).
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubChatAccessPolicyImpl implements AiHubChatAccessPolicy {

    private final ResourceVisibilityResolver visibilityResolver;
    private final WorkspaceUserService workspaceUserService;

    @SuppressFBWarnings("EI")
    public AiHubChatAccessPolicyImpl(
        ResourceVisibilityResolver visibilityResolver, WorkspaceUserService workspaceUserService) {

        this.visibilityResolver = visibilityResolver;
        this.workspaceUserService = workspaceUserService;
    }

    @Override
    public boolean canView(AiHubChat chat, long userId) {
        return isOwnerOrAdmin(chat, userId) || isSharedWith(chat, userId);
    }

    @Override
    public boolean canParticipate(AiHubChat chat, long userId) {
        if (isOwnerOrAdmin(chat, userId)) {
            return true;
        }

        return chat.getParticipation() == AiHubChatParticipation.PARTICIPATE && isSharedWith(chat, userId);
    }

    @Override
    public boolean canManage(AiHubChat chat, long userId) {
        return isOwnerOrAdmin(chat, userId);
    }

    private static boolean isOwnerOrAdmin(AiHubChat chat, long userId) {
        return chat.getUserId() == userId || SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN);
    }

    private boolean isSharedWith(AiHubChat chat, long userId) {
        Long workspaceId = chat.getWorkspaceId();

        if (workspaceId == null) {
            return false;
        }

        if (workspaceUserService.fetchWorkspaceUser(userId, workspaceId)
            .isEmpty()) {

            return false;
        }

        Set<Long> visibleIds = visibilityResolver.filterVisibleIds(
            AiHubChatVisibilityPolicy.RESOURCE_TYPE, workspaceId,
            List.of(new VisibilityRecord(chat.getId(), chat.getVisibility(), null)));

        return visibleIds.contains(chat.getId());
    }
}
