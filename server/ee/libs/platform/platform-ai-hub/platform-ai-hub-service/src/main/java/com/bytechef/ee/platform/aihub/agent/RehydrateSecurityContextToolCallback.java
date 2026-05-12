/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.agent;

import com.bytechef.ee.platform.aihub.tool.AiHubToolInvocationContext;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.user.domain.Authority;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.AuthorityService;
import com.bytechef.platform.user.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Decorator that rehydrates Spring Security's {@link org.springframework.security.core.context.SecurityContextHolder
 * SecurityContext} from the {@code userId} carried on the AG-UI tool-invocation context before delegating to the
 * wrapped {@link ToolCallback}. Without this, any tool whose facade hits a {@code @PreAuthorize} method (or otherwise
 * reads {@link SecurityUtils#getCurrentUserLogin}) throws {@code AuthorizationDeniedException}: Spring AI dispatches
 * tool calls on Reactor's {@code BoundedElasticThreadPerTaskScheduler}, which doesn't inherit the HTTP request thread's
 * thread-local SecurityContext.
 *
 * <p>
 * Observed in production initially on {@code listConnectionsForComponent} (since fixed inline), then on
 * {@code listWorkflows} ({@code ProjectFacadeImpl.getWorkspaceProjectWorkflows} has {@code @PreAuthorize}). Rather than
 * re-patching every callback one at a time, applying this wrapper at registration covers every current and future tool
 * — including community / library tools we don't own.
 * </p>
 *
 * <p>
 * <b>Cost:</b> one {@code fetchUser(id)} call per tool invocation plus one {@code fetchAuthority(id)} per authority the
 * user holds (typically 1-3). Cached only via whatever caching the {@code UserService} / {@code AuthorityService}
 * implementations provide. For a typical 5-10 tool-call turn this adds well under 50 ms total — acceptable for now; a
 * follow-up could cache per-turn or migrate to {@code ReactiveSecurityContextHolder} + Reactor context propagation.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class RehydrateSecurityContextToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RehydrateSecurityContextToolCallback.class);

    private final ToolCallback delegate;
    private final UserService userService;
    private final AuthorityService authorityService;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    private RehydrateSecurityContextToolCallback(
        ToolCallback delegate, UserService userService, AuthorityService authorityService) {

        this.delegate = delegate;
        this.userService = userService;
        this.authorityService = authorityService;
    }

    /**
     * Wraps {@code delegate} so every {@link #call(String, ToolContext)} invocation runs under the SecurityContext of
     * the AG-UI invocation user. Idempotent — re-wrapping a callback already wrapped by this class returns the existing
     * wrapper unchanged so chained registrations don't double-wrap.
     */
    public static ToolCallback wrap(ToolCallback delegate, UserService userService, AuthorityService authorityService) {
        if (delegate instanceof RehydrateSecurityContextToolCallback) {
            return delegate;
        }

        return new RehydrateSecurityContextToolCallback(delegate, userService, authorityService);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        // No ToolContext means no AG-UI invocation user to rehydrate from. Delegate straight through so this
        // wrapper is transparent for non-AG-UI call sites (e.g. tests that exercise call(String) directly).
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        AiHubToolInvocationContext invocationContext = AiHubToolInvocationContext.fromToolContext(toolContext);

        if (invocationContext == null || invocationContext.userId() == null) {
            // No invocation context means we have no user to authenticate as. The callback's own authorization
            // checks will fail-closed if they require a SecurityContext — that's the correct outcome for an
            // unauthenticated path; we don't want to silently bypass auth just because the context is missing.
            return delegate.call(toolInput, toolContext);
        }

        Long userId = invocationContext.userId();
        Optional<User> userOptional = userService.fetchUser(userId);

        if (userOptional.isEmpty()) {
            log.debug("Skipping SecurityContext rehydration: user id {} not found in user repository", userId);

            return delegate.call(toolInput, toolContext);
        }

        User user = userOptional.get();
        List<GrantedAuthority> authorities = resolveAuthorities(user);

        return SecurityUtils.runAs(user.getLogin(), authorities, () -> delegate.call(toolInput, toolContext));
    }

    private List<GrantedAuthority> resolveAuthorities(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        for (Long authorityId : user.getAuthorityIds()) {
            Optional<Authority> authorityOptional = authorityService.fetchAuthority(authorityId);

            authorityOptional.map(Authority::getName)
                .map(SimpleGrantedAuthority::new)
                .ifPresent(authorities::add);
        }

        return authorities;
    }
}
