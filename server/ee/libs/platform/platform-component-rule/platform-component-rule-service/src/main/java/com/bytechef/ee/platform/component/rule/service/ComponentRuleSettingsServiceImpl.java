/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.service;

import com.bytechef.ee.platform.component.rule.ComponentRuleSettings;
import com.bytechef.ee.platform.component.rule.ComponentRuleSettingsService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Property.Scope;
import com.bytechef.platform.configuration.service.PropertyService;
import com.bytechef.platform.security.constant.AuthorityConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link ComponentRuleSettings} with a single {@code Property} row per workspace, keyed by
 * {@link ComponentRuleSettings#PROPERTY_KEY}. Reuses the platform configuration store rather than introducing a
 * dedicated table.
 *
 * <p>
 * <strong>Scope decision for the tenant default row</strong> (a {@code null} {@code workspaceId}): stored under
 * {@link Scope#PLATFORM} with a {@code null} {@code scopeId}, not {@link Scope#WORKSPACE} with a sentinel id such as
 * {@code 0L} — a scope-less row cannot be confused with a real workspace's row, whereas a sentinel id could alias one.
 * A workspace override is stored under {@link Scope#WORKSPACE} with that workspace's id. This mirrors
 * {@code AiGuardrailsWorkspaceSettingsServiceImpl}'s storage shape.
 * </p>
 *
 * <p>
 * {@link #getSettings(Long)} owns the whole fallback chain itself, unlike the guardrails precedent: it tries the
 * workspace's own override first, then the tenant default, then {@link ComponentRuleSettings#DEFAULT} — the workspace
 * read is skipped entirely once an override is found, and the tenant read is skipped entirely once the workspace was
 * asked for directly with a {@code null} id. Component rules have no higher layer that unions scopes the way
 * {@code AiGuardrails#resolvePolicy} does for guardrails, so this method is that layer.
 * </p>
 *
 * <p>
 * {@link #getSettings(Long)} also degrades to {@link ComponentRuleSettings#DEFAULT} when {@code propertyService} itself
 * throws, rather than only when the row is absent. On a distributed worker, {@code PropertyService} resolves to a
 * remote-client stub whose every method throws {@link UnsupportedOperationException} — the worker has no local database
 * and no read-only remote settings lookup exists yet. Without this fallback, evaluating any rule on the worker would
 * crash the tool call outright, which is worse than the observe-mode-off default this falls back to.
 * {@link #fetchWorkspaceOverride(Long)} degrades the same way, to {@link Optional#empty()}.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
@SuppressFBWarnings("EI")
public class ComponentRuleSettingsServiceImpl implements ComponentRuleSettingsService {

    private static final String KEY_APPROVAL_EXPIRES_IN_HOURS = "approvalExpiresInHours";
    private static final String KEY_OBSERVE_MODE = "observeMode";

    private static final Logger log = LoggerFactory.getLogger(ComponentRuleSettingsServiceImpl.class);

    private final PropertyService propertyService;

    public ComponentRuleSettingsServiceImpl(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @Override
    @Transactional(readOnly = true)
    public ComponentRuleSettings getSettings(@Nullable Long workspaceId) {
        try {
            if (workspaceId != null) {
                ComponentRuleSettings workspaceComponentRuleSettings = fetchSettings(Scope.WORKSPACE, workspaceId);

                if (workspaceComponentRuleSettings != null) {
                    return workspaceComponentRuleSettings;
                }
            }

            ComponentRuleSettings tenantComponentRuleSettings = fetchSettings(Scope.PLATFORM, null);

            return tenantComponentRuleSettings == null ? ComponentRuleSettings.DEFAULT : tenantComponentRuleSettings;
        } catch (RuntimeException exception) {
            log.warn(
                "Could not read component rule settings; falling back to the default (observe mode off)",
                exception);

            return ComponentRuleSettings.DEFAULT;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ComponentRuleSettings> fetchWorkspaceOverride(@Nullable Long workspaceId) {
        if (workspaceId == null) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(fetchSettings(Scope.WORKSPACE, workspaceId));
        } catch (RuntimeException exception) {
            log.warn("Could not read the workspace's component rule settings override", exception);

            return Optional.empty();
        }
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ComponentRuleSettings saveSettings(ComponentRuleSettings componentRuleSettings, @Nullable Long workspaceId) {
        propertyService.save(
            ComponentRuleSettings.PROPERTY_KEY, toMap(componentRuleSettings), scopeOf(workspaceId), workspaceId);

        return componentRuleSettings;
    }

    private @Nullable ComponentRuleSettings fetchSettings(Scope scope, @Nullable Long scopeId) {
        return propertyService.fetchProperty(ComponentRuleSettings.PROPERTY_KEY, scope, scopeId)
            .map(property -> toSettings(property.getValue()))
            .orElse(null);
    }

    private static Scope scopeOf(@Nullable Long workspaceId) {
        return workspaceId == null ? Scope.PLATFORM : Scope.WORKSPACE;
    }

    private static Map<String, Object> toMap(ComponentRuleSettings componentRuleSettings) {
        return Map.of(
            KEY_OBSERVE_MODE, componentRuleSettings.observeMode(),
            KEY_APPROVAL_EXPIRES_IN_HOURS, componentRuleSettings.approvalExpiresInHours());
    }

    private static ComponentRuleSettings toSettings(Map<String, ?> value) {
        Object observeMode = value.get(KEY_OBSERVE_MODE);
        Object approvalExpiresInHours = value.get(KEY_APPROVAL_EXPIRES_IN_HOURS);

        return new ComponentRuleSettings(
            observeMode instanceof Boolean observeModeBoolean
                ? observeModeBoolean : ComponentRuleSettings.DEFAULT.observeMode(),
            approvalExpiresInHours instanceof Number approvalExpiresInHoursNumber
                ? approvalExpiresInHoursNumber.intValue() : ComponentRuleSettings.DEFAULT.approvalExpiresInHours());
    }
}
