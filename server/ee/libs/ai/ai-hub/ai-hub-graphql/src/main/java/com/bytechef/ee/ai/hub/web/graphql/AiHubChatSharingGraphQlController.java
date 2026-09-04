/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatParticipation;
import com.bytechef.ee.ai.hub.chat.AiHubChatSharingFacade;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * GraphQL surface for {@link AiHubChatSharingFacade}. Authorization is enforced on the facade, not here — this
 * controller only maps arguments, so it carries no class-level {@code @PreAuthorize}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubChatSharingGraphQlController {

    private final AiHubChatSharingFacade sharingFacade;

    @SuppressFBWarnings("EI")
    public AiHubChatSharingGraphQlController(AiHubChatSharingFacade sharingFacade) {
        this.sharingFacade = sharingFacade;
    }

    @QueryMapping
    public List<Long> aiHubChatGrants(@Argument long workspaceId, @Argument long chatId) {
        return sharingFacade.getGrants(workspaceId, chatId);
    }

    @MutationMapping
    public AiHubChat setAiHubChatVisibility(
        @Argument long workspaceId, @Argument long chatId, @Argument AiHubChatVisibility visibility,
        @Argument AiHubChatParticipation participation) {

        return sharingFacade.setVisibility(
            workspaceId, chatId, AiHubChatVisibilityMapper.toResourceVisibility(visibility), participation);
    }

    @MutationMapping
    public AiHubChat grantAiHubChatAccess(@Argument long workspaceId, @Argument long chatId, @Argument long userId) {
        return sharingFacade.grantAccess(workspaceId, chatId, userId);
    }

    @MutationMapping
    public AiHubChat revokeAiHubChatAccess(@Argument long workspaceId, @Argument long chatId, @Argument long userId) {
        return sharingFacade.revokeAccess(workspaceId, chatId, userId);
    }
}
