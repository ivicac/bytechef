/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.mcp;

import com.bytechef.ee.platform.ai.guardrails.AiGuardrailMetrics;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsTarget;
import com.bytechef.platform.ai.guardrails.McpOutboundRedactor;
import com.bytechef.platform.ai.guardrails.McpOutboundRedactorProvider;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.ai.sensitivedata.tokenization.SensitiveDataPolicy;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * EE implementation of the MCP outbound redaction seam. Resolves the outbound policy once per {@link #fetchRedactor}
 * call and closes over it, so the returned redactor carries its kinds, threshold and surface-tagged metrics rather than
 * re-resolving them for every value.
 *
 * <p>
 * {@code surface} is the discriminator for which settings scope to read: {@link #SURFACE_EMBEDDED} resolves the
 * {@code AiGuardrailsSettingsScope#EMBEDDED} row (embedded MCP servers are not workspace-scoped), and every other
 * surface resolves {@code workspaceId}'s row (or the tenant default when it is {@code null}). This mapping lives here
 * rather than on the CE-facing {@link McpOutboundRedactorProvider} SPI so that seam does not need to learn EE scope
 * types, the same reasoning that made it return a resolved redactor rather than a policy.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class McpOutboundRedactorProviderImpl implements McpOutboundRedactorProvider {

    private final AiGuardrails aiGuardrails;
    private final SensitiveDataRedactor sensitiveDataRedactor;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public McpOutboundRedactorProviderImpl(
        AiGuardrails aiGuardrails, SensitiveDataRedactor sensitiveDataRedactor,
        ObjectProvider<MeterRegistry> meterRegistryProvider) {

        this.aiGuardrails = aiGuardrails;
        this.sensitiveDataRedactor = sensitiveDataRedactor;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public Optional<McpOutboundRedactor> fetchRedactor(@Nullable Long workspaceId, String surface) {
        SensitiveDataPolicy policy = SURFACE_EMBEDDED.equals(surface)
            ? aiGuardrails.resolveEmbeddedMcpOutboundPolicy()
            : aiGuardrails.resolveMcpOutboundPolicy(AiGuardrailsSettingsTarget.resolve(null, workspaceId));

        if (policy == null) {
            return Optional.empty();
        }

        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistryProvider.getIfAvailable(), surface);

        return Optional.of(
            serializedResult -> sensitiveDataRedactor.redact(
                serializedResult, policy.kinds(), policy.minConfidence(), metrics));
    }
}
