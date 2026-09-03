/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@link AiGatewayEmbeddingModelFactoryImpl#getEmbeddingModel} runs a customer-supplied (BYOK) provider's
 * {@code baseUrl} through the same {@code AiObservabilityUrlValidator} SSRF guard a tenant-owned provider goes through
 * — there is no separate, unguarded path for a connected user's own provider. A customer-supplied {@code baseUrl} is
 * strictly more hostile input than a tenant-supplied one, so this guard is not optional.
 *
 * <p>
 * Uses the cloud metadata address {@code 169.254.169.254} deliberately: it is the canonical SSRF target — an unguarded
 * gateway pointed at it would leak the host's cloud instance credentials to whichever provider account supplied the
 * {@code baseUrl}.
 *
 * @version ee
 */
class AiGatewayEmbeddingModelFactoryBaseUrlSsrfTest {

    @Test
    void testCustomerSuppliedBaseUrlIsValidated() {
        AiGatewayProvider customerProvider = newProvider(1L, AiGatewayProviderType.OPENAI);

        customerProvider.setConnectedUserId(5L);
        customerProvider.setBaseUrl("http://169.254.169.254/latest/meta-data/");

        AiGatewayEmbeddingModelFactory aiGatewayEmbeddingModelFactory = new AiGatewayEmbeddingModelFactoryImpl();

        assertThatThrownBy(() -> aiGatewayEmbeddingModelFactory.getEmbeddingModel(customerProvider))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiGatewayProvider newProvider(long id, AiGatewayProviderType type) {
        AiGatewayProvider provider = new AiGatewayProvider("byok-ssrf-test-provider", type, "sk-test-key");

        try {
            Field idField = AiGatewayProvider.class.getDeclaredField("id");

            idField.setAccessible(true);
            idField.set(provider, id);
        } catch (ReflectiveOperationException reflectiveOperationException) {
            throw new AssertionError("failed to seed id on test provider", reflectiveOperationException);
        }

        return provider;
    }
}
