/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.advisor;

import com.bytechef.ee.platform.ai.guardrails.AiGuardrailMetrics;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails;
import com.bytechef.ee.platform.ai.workspace.JobPrincipalWorkspaceResolver;
import com.bytechef.platform.ai.guardrails.AiGuardrailsAdvisorProvider;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.constant.PlatformType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * EE implementation of the CE {@link AiGuardrailsAdvisorProvider} SPI: resolves the calling run's workspace via
 * {@link JobPrincipalWorkspaceResolver} and, when at least one guardrail is active for it, returns an
 * {@link AiGuardrailsAdvisor} bound to that workspace.
 *
 * <p>
 * Only the workspace SCOPE is fail-open (see {@link JobPrincipalWorkspaceResolver}): guardrails themselves are never
 * silently skipped because attribution failed.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnBean(AiGuardrails.class)
public class AiGuardrailsAdvisorProviderImpl implements AiGuardrailsAdvisorProvider {

    private final AiGuardrails aiGuardrails;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final JobPrincipalWorkspaceResolver jobPrincipalWorkspaceResolver;

    @SuppressFBWarnings("EI2")
    public AiGuardrailsAdvisorProviderImpl(
        AiGuardrails aiGuardrails, ObjectProvider<MeterRegistry> meterRegistryProvider,
        JobPrincipalWorkspaceResolver jobPrincipalWorkspaceResolver) {

        this.aiGuardrails = aiGuardrails;
        this.meterRegistryProvider = meterRegistryProvider;
        this.jobPrincipalWorkspaceResolver = jobPrincipalWorkspaceResolver;
    }

    @Override
    public Optional<Advisor> getAdvisor(
        @Nullable PlatformType platformType, @Nullable Long jobPrincipalId, String surface) {

        Long workspaceId = jobPrincipalWorkspaceResolver.resolve(platformType, jobPrincipalId);

        if (!aiGuardrails.isActive(workspaceId)) {
            return Optional.empty();
        }

        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistryProvider.getIfAvailable(), surface);

        return Optional.of(new AiGuardrailsAdvisor(aiGuardrails, workspaceId, metrics));
    }
}
