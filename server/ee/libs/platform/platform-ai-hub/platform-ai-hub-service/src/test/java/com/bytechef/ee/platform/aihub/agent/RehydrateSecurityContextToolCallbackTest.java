/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.aihub.tool.AiHubToolInvocationContext;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.user.domain.Authority;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.AuthorityService;
import com.bytechef.platform.user.service.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Pins the {@code @PreAuthorize}-on-Reactor-thread failure mode that the wrapper exists to prevent.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class RehydrateSecurityContextToolCallbackTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testRehydratesSecurityContextFromInvocationContextUserId() {
        // The wrapper's whole purpose: when a tool callback runs on a Reactor scheduler thread (where
        // SecurityContextHolder is empty), look up the AG-UI invocation user and run the callback under
        // their SecurityContext so @PreAuthorize-protected facade calls succeed.
        UserService userService = mock(UserService.class);
        AuthorityService authorityService = mock(AuthorityService.class);

        User user = mock(User.class);

        when(user.getLogin()).thenReturn("alice@example.com");
        when(user.getAuthorityIds()).thenReturn(List.of(1L, 2L));
        when(userService.fetchUser(42L)).thenReturn(Optional.of(user));

        Authority adminAuthority = mock(Authority.class);

        when(adminAuthority.getName()).thenReturn("ROLE_ADMIN");

        Authority userAuthority = mock(Authority.class);

        when(userAuthority.getName()).thenReturn("ROLE_USER");
        when(authorityService.fetchAuthority(1L)).thenReturn(Optional.of(adminAuthority));
        when(authorityService.fetchAuthority(2L)).thenReturn(Optional.of(userAuthority));

        ToolCallback delegate = mock(ToolCallback.class);

        when(delegate.call(anyString(), any(ToolContext.class))).thenAnswer(invocation -> {
            // Captured inside the wrapped call() so we observe the SecurityContext as the actual callback
            // body would see it. After call() returns the context restores to empty.
            String login = SecurityUtils.getCurrentUserLogin();

            assertThat(login).isEqualTo("alice@example.com");
            assertThat(SecurityUtils.hasCurrentUserThisAuthority("ROLE_ADMIN")).isTrue();

            return "{\"ok\":true}";
        });

        ToolCallback wrapped = RehydrateSecurityContextToolCallback.wrap(delegate, userService, authorityService);
        ToolContext toolContext = new ToolContext(
            new AiHubToolInvocationContext(7L, 42L, (short) 0, "find slack", 0L, "thread-1").toToolContext());

        String result = wrapped.call("{}", toolContext);

        assertThat(result).isEqualTo("{\"ok\":true}");
        // Crucial: the context restores after the wrapper returns so a thread-pool reuse can't leak this
        // user's authorities to a subsequent unrelated task on the same scheduler thread.
        assertThat(SecurityUtils.fetchCurrentUserLogin()).isEmpty();
    }

    @Test
    void testPassesThroughWhenToolContextIsAbsent() {
        // No ToolContext means no AG-UI invocation user — typically a test calling call(String) directly.
        // Delegate straight through; rehydrating from a missing context would be a no-op with extra DB lookups.
        UserService userService = mock(UserService.class);
        AuthorityService authorityService = mock(AuthorityService.class);

        ToolCallback delegate = mock(ToolCallback.class);

        when(delegate.call("{}")).thenReturn("ok");

        ToolCallback wrapped = RehydrateSecurityContextToolCallback.wrap(delegate, userService, authorityService);

        assertThat(wrapped.call("{}")).isEqualTo("ok");

        verify(userService, org.mockito.Mockito.never()).fetchUser(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void testDelegatesUnchangedWhenInvocationContextHasNoUserId() {
        // An invocation context with workspaceId but no userId (theoretical edge case — controller always
        // injects userId today, but defensive) skips rehydration. The callback's own auth checks fail-closed
        // in that case, which is the right outcome — we don't silently bypass auth.
        UserService userService = mock(UserService.class);
        AuthorityService authorityService = mock(AuthorityService.class);

        ToolCallback delegate = mock(ToolCallback.class);

        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");

        ToolCallback wrapped = RehydrateSecurityContextToolCallback.wrap(delegate, userService, authorityService);
        ToolContext toolContext = new ToolContext(
            new AiHubToolInvocationContext(7L, null, (short) 0, "x", 0L, "thread-1").toToolContext());

        wrapped.call("{}", toolContext);

        verify(userService, org.mockito.Mockito.never()).fetchUser(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void testSkipsRehydrationWhenUserNotFound() {
        // The userId in the AG-UI state references a user that no longer exists (stale state, deleted user).
        // Skip the rehydration rather than failing the call — the callback's own checks decide whether the
        // request can proceed under no SecurityContext.
        UserService userService = mock(UserService.class);
        AuthorityService authorityService = mock(AuthorityService.class);

        when(userService.fetchUser(99L)).thenReturn(Optional.empty());

        ToolCallback delegate = mock(ToolCallback.class);

        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");

        ToolCallback wrapped = RehydrateSecurityContextToolCallback.wrap(delegate, userService, authorityService);
        ToolContext toolContext = new ToolContext(
            new AiHubToolInvocationContext(7L, 99L, (short) 0, "x", 0L, "thread-1").toToolContext());

        assertThat(wrapped.call("{}", toolContext)).isEqualTo("ok");

        verify(authorityService, org.mockito.Mockito.never()).fetchAuthority(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void testWrapIsIdempotent() {
        // Double-wrapping would attribute SecurityContext lookups to the outer wrapper instead of the actual
        // inner callback, and would do redundant DB work. Short-circuit when the delegate is already wrapped.
        UserService userService = mock(UserService.class);
        AuthorityService authorityService = mock(AuthorityService.class);
        ToolCallback delegate = mock(ToolCallback.class);

        ToolCallback wrappedOnce = RehydrateSecurityContextToolCallback.wrap(delegate, userService, authorityService);
        ToolCallback wrappedTwice = RehydrateSecurityContextToolCallback.wrap(
            wrappedOnce, userService, authorityService);

        assertThat(wrappedTwice).isSameAs(wrappedOnce);
    }
}
