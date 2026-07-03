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
import com.bytechef.ee.automation.configuration.domain.WorkspaceUser;
import com.bytechef.ee.automation.configuration.service.WorkspaceUserService;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.domain.ResourceVisibility;
import java.util.Arrays;
import java.util.Optional;
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
 * <p>
 * {@link ResourceVisibilityResolver} is stubbed throughout, so each visibility rung can be isolated. That stays honest
 * about the membership half of the policy's contract only because
 * {@link #testNonMemberIsRejectedEvenWhenVisibilityWouldAllowIt} stubs the resolver at its MOST PERMISSIVE and demands
 * a refusal anyway: a stub can launder an {@code isTrue()} assertion, but it cannot produce that {@code isFalse()}. The
 * fact that makes membership the policy's own job rather than the resolver's — {@code filterVisibleIds} accepts a
 * {@code workspaceId} and never reads it — is pinned where the resolver lives, by
 * {@code ResourceVisibilityResolverImplTest#testWorkspaceIdIsAcceptedButNeverRead}. Deliberately NOT by constructing
 * the real resolver here: that dependency edge drags EE automation-configuration's changelogs onto this module's test
 * classpath, where {@code master.xml}'s {@code includeAll} picks up a changeset referencing a
 * {@code code_workflow_container} table no module on this classpath creates, and every integration test in the module
 * dies on Liquibase before its Spring context refreshes.
 * </p>
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
    private final WorkspaceUserService workspaceUserService = mock(WorkspaceUserService.class);
    private final AiHubChatAccessPolicy policy =
        new AiHubChatAccessPolicyImpl(visibilityResolver, workspaceUserService);

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
        verifyNoInteractions(workspaceUserService);
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

        makeWorkspaceMember(OTHER_ID);

        when(visibilityResolver.filterVisibleIds(eq("AiHubChat"), eq(WORKSPACE_ID), any())).thenReturn(Set.of());

        assertThat(policy.canView(chat, OTHER_ID)).isFalse();
        assertThat(policy.canParticipate(chat, OTHER_ID)).isFalse();
    }

    @Test
    void testReachOrGrantAllowsViewOnly() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.VIEW);

        makeWorkspaceMember(OTHER_ID);

        when(visibilityResolver.filterVisibleIds(eq("AiHubChat"), eq(WORKSPACE_ID), any())).thenReturn(Set.of(11L));

        assertThat(policy.canView(chat, OTHER_ID)).isTrue();
        assertThat(policy.canParticipate(chat, OTHER_ID)).isFalse();
        assertThat(policy.canManage(chat, OTHER_ID)).isFalse();
    }

    @Test
    void testParticipateModeAllowsTurns() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);

        makeWorkspaceMember(OTHER_ID);

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
        verifyNoInteractions(workspaceUserService);
    }

    @Test
    void testNonMemberIsRejectedWithoutConsultingVisibility() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);

        when(workspaceUserService.fetchWorkspaceUser(OTHER_ID, WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThat(policy.canView(chat, OTHER_ID)).isFalse();
        assertThat(policy.canParticipate(chat, OTHER_ID)).isFalse();
        verifyNoInteractions(visibilityResolver);
    }

    /**
     * The C1 regression test, and the one case a stubbed resolver cannot launder. The resolver is stubbed to return the
     * chat's id — VISIBLE, the most permissive answer it can give — and the policy must refuse anyway, because
     * membership of the chat's workspace is a precondition the resolver does not answer: {@code filterVisibleIds}
     * accepts a {@code workspaceId} and never reads it (pinned by
     * {@code ResourceVisibilityResolverImplTest#testWorkspaceIdIsAcceptedButNeverRead}). Before the membership check
     * existed, this test failed on its first assertion, with the stub supplying the "yes" the hole rested on.
     *
     * <p>
     * Shaped as a channel-born agent chat — {@code WORKSPACE}/{@code VIEW}, what {@code AiHubAgentConversationRecorder}
     * writes — because those are the rows the REST attach/status/presence endpoints exposed to any non-member who
     * learned a thread id.
     * </p>
     */
    @Test
    void testNonMemberIsRejectedEvenWhenVisibilityWouldAllowIt() {
        authenticate("ana");

        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.VIEW);

        when(workspaceUserService.fetchWorkspaceUser(OTHER_ID, WORKSPACE_ID)).thenReturn(Optional.empty());
        when(visibilityResolver.filterVisibleIds(eq("AiHubChat"), eq(WORKSPACE_ID), any())).thenReturn(Set.of(11L));

        assertThat(policy.canView(chat, OTHER_ID)).isFalse();
        assertThat(policy.canParticipate(chat, OTHER_ID)).isFalse();
    }

    private void makeWorkspaceMember(long userId) {
        when(workspaceUserService.fetchWorkspaceUser(userId, WORKSPACE_ID))
            .thenReturn(Optional.of(mock(WorkspaceUser.class)));
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
