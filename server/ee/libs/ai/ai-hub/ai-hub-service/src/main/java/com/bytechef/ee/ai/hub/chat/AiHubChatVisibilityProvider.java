/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import com.bytechef.automation.configuration.security.ResourceVisibilityProvider;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver.VisibilityRecord;
import com.bytechef.ee.ai.hub.chat.repository.AiHubChatRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers AI Hub chats with the visibility precondition of {@code PermissionService.hasResourceScope}, mirroring
 * {@code ProjectVisibilityProvider}. Reads the repository directly (not the guarded service) to avoid recursion.
 *
 * <p>
 * The returned record's {@code createdBy} is always {@code null}: {@link AiHubChat} has no {@code created_by} column —
 * its owner is {@code user_id} — so the resolver's ownership rung never decides a chat, and
 * {@link AiHubChatAccessPolicy} carries out the owner check itself.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubChatVisibilityProvider implements ResourceVisibilityProvider {

    private final AiHubChatRepository chatRepository;

    @SuppressFBWarnings("EI")
    public AiHubChatVisibilityProvider(AiHubChatRepository chatRepository) {
        this.chatRepository = chatRepository;
    }

    @Override
    public String resourceType() {
        return AiHubChatVisibilityPolicy.RESOURCE_TYPE;
    }

    @Override
    public Optional<VisibilityRecord> fetchVisibility(long id) {
        return chatRepository.findById(id)
            .map(chat -> new VisibilityRecord(chat.getId(), chat.getVisibility(), null));
    }
}
