/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import com.bytechef.platform.security.domain.ResourceVisibility;
import java.util.List;

/**
 * Visibility and named-user grants for AI Hub chats. Authorization is owner-or-admin, enforced programmatically on the
 * implementation via {@link AiHubChatService#getManageable} rather than a {@code @PreAuthorize} expression — a chat has
 * no {@code PermissionService} resource type wired for {@code isResourceOwner} the way a project does.
 *
 * <p>
 * Every enumeration-relevant failure — an unknown chat, a chat in another workspace, or a grantee who is not a
 * workspace member — throws {@link com.bytechef.ee.ai.hub.exception.NotFoundException} with the same message, so a
 * caller cannot enumerate chat or user ids by distinguishing one rejection from another. An unsupported visibility rung
 * is a different, non-enumerating failure and keeps its own {@link IllegalArgumentException}.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiHubChatSharingFacade {

    /**
     * Changes a chat's visibility and participation in one write. Rejects a visibility rung this resource type does not
     * support, per {@code ResourceVisibilityPolicyRegistry}, before touching the chat.
     */
    AiHubChat setVisibility(
        long workspaceId, long chatId, ResourceVisibility visibility,
        AiHubChatParticipation participation);

    /**
     * Grants {@code userId} visibility of the chat. The grantee must already be a member of {@code workspaceId} —
     * granting to a non-member fails the same way an unknown chat does.
     */
    AiHubChat grantAccess(long workspaceId, long chatId, long userId);

    /**
     * Revokes {@code userId}'s grant. Unlike {@link #grantAccess}, no membership check runs on the way out — someone
     * removed from the workspace must still be revocable, and revoking a grant that is not there is already a no-op.
     */
    AiHubChat revokeAccess(long workspaceId, long chatId, long userId);

    /**
     * The users currently granted the chat.
     */
    List<Long> getGrants(long workspaceId, long chatId);
}
