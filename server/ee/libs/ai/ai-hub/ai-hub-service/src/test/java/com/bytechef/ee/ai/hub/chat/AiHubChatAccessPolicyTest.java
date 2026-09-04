/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.service.ResourceVisibilityResolver;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.domain.ResourceVisibility;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link AiHubChatAccessPolicyImpl}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubChatAccessPolicyTest {

    private static final long OWNER_ID = 3L;
    private static final long OTHER_ID = 4L;
    private static final long WORKSPACE_ID = 7L;

    private final ResourceVisibilityResolver visibilityResolver = mock(ResourceVisibilityResolver.class);
    private final AiHubChatAccessPolicy policy = new AiHubChatAccessPolicyImpl(visibilityResolver);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testOwnerCanDoEverything() {
        AiHubChat chat = chat(ResourceVisibility.PRIVATE, AiHubChatParticipation.VIEW);

        assertThat(policy.canView(chat, OWNER_ID)).isTrue();
        assertThat(policy.canParticipate(chat, OWNER_ID)).isTrue();
        assertThat(policy.canManage(chat, OWNER_ID)).isTrue();
        verifyNoInteractions(visibilityResolver);
    }

    @Test
    void testAdminCanDoEverything() {
        authenticate("ana", AuthorityConstants.ADMIN);
        AiHubChat chat = chat(ResourceVisibility.PRIVATE, AiHubChatParticipation.VIEW);

        assertThat(policy.canManage(chat, OTHER_ID)).isTrue();
    }

    @Test
    void testStrangerCannotViewAPrivateChat() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.PRIVATE, AiHubChatParticipation.VIEW);
        when(visibilityResolver.filterVisibleIds(eq("AiHubChat"), eq(WORKSPACE_ID), any())).thenReturn(Set.of());

        assertThat(policy.canView(chat, OTHER_ID)).isFalse();
        assertThat(policy.canParticipate(chat, OTHER_ID)).isFalse();
    }

    @Test
    void testReachOrGrantAllowsViewOnly() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.VIEW);
        when(visibilityResolver.filterVisibleIds(eq("AiHubChat"), eq(WORKSPACE_ID), any())).thenReturn(Set.of(11L));

        assertThat(policy.canView(chat, OTHER_ID)).isTrue();
        assertThat(policy.canParticipate(chat, OTHER_ID)).isFalse();
        assertThat(policy.canManage(chat, OTHER_ID)).isFalse();
    }

    @Test
    void testParticipateModeAllowsTurns() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);
        when(visibilityResolver.filterVisibleIds(eq("AiHubChat"), eq(WORKSPACE_ID), any())).thenReturn(Set.of(11L));

        assertThat(policy.canParticipate(chat, OTHER_ID)).isTrue();
        assertThat(policy.canManage(chat, OTHER_ID)).isFalse();
    }

    @Test
    void testChatWithoutWorkspaceIsOwnerOnly() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);

        chat.setWorkspaceId(null);

        assertThat(policy.canView(chat, OTHER_ID)).isFalse();
        verifyNoInteractions(visibilityResolver);
    }

    private static AiHubChat chat(ResourceVisibility visibility, AiHubChatParticipation participation) {
        AiHubChat chat = new AiHubChat(OWNER_ID);

        ReflectionTestUtils.setField(chat, "id", 11L);
        chat.setWorkspaceId(WORKSPACE_ID);
        chat.setVisibility(visibility);
        chat.setParticipation(participation);

        return chat;
    }

    private static void authenticate(String login, String... authorities) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(
                login, "n/a", Arrays.stream(authorities)
                    .map(SimpleGrantedAuthority::new)
                    .toList()));
    }
}
