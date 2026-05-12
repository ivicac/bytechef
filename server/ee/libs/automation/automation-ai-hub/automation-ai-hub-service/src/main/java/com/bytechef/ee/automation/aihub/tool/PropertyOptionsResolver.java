/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.tool;

import com.bytechef.platform.component.domain.Option;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.user.domain.Authority;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.AuthorityService;
import com.bytechef.platform.user.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Shared helper for the AI Hub property-options lookup tool callbacks (action and trigger variants). Centralises three
 * pieces of logic that would otherwise be duplicated between the two callbacks:
 *
 * <ul>
 * <li>SecurityContext rehydration on Reactor scheduler threads, mirroring the pattern used by
 * {@link ListConnectionsForComponentToolCallback} so the wrapped facade call sees the user's login + authorities
 * instead of throwing {@code IllegalStateException} for a missing current user.</li>
 * <li>Success envelope assembly with insertion-ordered keys so the LLM consistently sees
 * {@code componentName, actionName|triggerName, propertyName, options}.</li>
 * <li>Structured error envelopes for the {@code connection_required}, {@code dependency_missing}, and
 * {@code no_options_for_property} cases. The generic {@code lookup_failed} envelope stays with
 * {@code ToolErrors.runtimeFailure(...)} and is intentionally not handled here.</li>
 * </ul>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class PropertyOptionsResolver {

    private static final Logger log = LoggerFactory.getLogger(PropertyOptionsResolver.class);

    private final UserService userService;
    private final AuthorityService authorityService;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public PropertyOptionsResolver(UserService userService, AuthorityService authorityService) {
        this.userService = userService;
        this.authorityService = authorityService;
    }

    /**
     * Resolves the invocation user's login + authorities and executes {@code action} inside a temporary
     * SecurityContext. When {@code userId} is null or the user no longer exists, falls back to running the action
     * without rehydration — the facade then sees no current user and applies the same no-login branch as a
     * scheduler-initiated fetch. Authority resolution failures are logged at debug level and the user is treated as
     * having no authorities (effectively a non-admin) rather than aborting the turn.
     */
    public <T> T withUserSecurityContext(@Nullable Long userId, Supplier<T> action) {
        if (userId == null) {
            return action.get();
        }

        Optional<User> userOptional = userService.fetchUser(userId);

        if (userOptional.isEmpty()) {
            log.debug("Skipping SecurityContext rehydration: user id {} not found", userId);

            return action.get();
        }

        User user = userOptional.get();

        List<GrantedAuthority> authorities = new ArrayList<>();

        for (Long authorityId : user.getAuthorityIds()) {
            Optional<Authority> authorityOptional = authorityService.fetchAuthority(authorityId);

            authorityOptional.map(Authority::getName)
                .map(SimpleGrantedAuthority::new)
                .ifPresent(authorities::add);
        }

        return SecurityUtils.runAs(user.getLogin(), authorities, action);
    }

    /**
     * Builds the success envelope returned to the LLM. {@code entityKey} is {@code "actionName"} or
     * {@code "triggerName"} depending on the caller. Each {@link Option} is rendered as a {@code {label, value}} map,
     * preserving the runtime type of {@code value} so numeric / boolean / string options survive JSON serialization
     * without coercion.
     */
    public Map<String, Object> buildSuccessEnvelope(
        String componentName, String entityKey, String entityName, String propertyName, List<Option> options) {

        List<Map<String, Object>> optionRows = new ArrayList<>(options.size());

        for (Option option : options) {
            Map<String, Object> optionRow = new LinkedHashMap<>();

            optionRow.put("label", option.getLabel());
            optionRow.put("value", option.getValue());

            optionRows.add(optionRow);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();

        envelope.put("componentName", componentName);
        envelope.put(entityKey, entityName);
        envelope.put("propertyName", propertyName);
        envelope.put("options", optionRows);

        return envelope;
    }

    /**
     * Builds the {@code connection_required} error envelope returned when the LLM invoked the lookup without a
     * {@code connectionId} for a property whose options provider needs an active connection.
     */
    public Map<String, Object> connectionRequiredEnvelope(String componentName) {
        Map<String, Object> envelope = new LinkedHashMap<>();

        envelope.put("error", "connection_required");
        envelope.put("componentName", componentName);
        envelope.put(
            "hint",
            "No connectionId supplied. Call listConnectionsForComponent for '" + componentName
                + "' to pick an existing one, or createConnection to make a new one, then retry.");

        return envelope;
    }

    /**
     * Builds the {@code dependency_missing} error envelope returned when the options provider requires sibling input
     * properties that the LLM did not include in {@code inputParameters}.
     */
    public Map<String, Object> dependencyMissingEnvelope(List<String> missing) {
        Map<String, Object> envelope = new LinkedHashMap<>();

        envelope.put("error", "dependency_missing");
        envelope.put("missing", missing);
        envelope.put("hint", "Place values for these siblings first and include them in inputParameters, then retry.");

        return envelope;
    }

    /**
     * Builds the {@code no_options_for_property} error envelope returned when the resolved property has no dynamic
     * options provider — typically a free-form string / number property whose value the LLM should set directly.
     */
    public Map<String, Object> noOptionsForPropertyEnvelope() {
        Map<String, Object> envelope = new LinkedHashMap<>();

        envelope.put("error", "no_options_for_property");
        envelope.put(
            "hint",
            "This property does not have dynamic options. Set the value directly per the property's description.");

        return envelope;
    }
}
