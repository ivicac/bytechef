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
 * Proves that {@link AiGatewayChatModelFactoryImpl#getChatModel} runs a customer-supplied (BYOK) provider's
 * {@code baseUrl} through the same {@code AiObservabilityUrlValidator} SSRF guard a tenant-owned provider goes through
 * — there is no separate, unguarded path for a connected user's own provider.
 *
 * <p>
 * The sibling test, {@link AiGatewayEmbeddingModelFactoryBaseUrlSsrfTest}, targets the EMBEDDING factory, but BYOK
 * never reaches embedding: {@code AiGatewayFacadeImpl#embedding} always resolves its provider with a {@code null}
 * connected user id, so a customer-supplied {@code baseUrl} can never flow through
 * {@code AiGatewayEmbeddingModelFactory}. This test targets {@link AiGatewayChatModelFactoryImpl} instead, the factory
 * {@code applyByokOverride} actually routes a BYOK provider through on the chat completion path — so the SSRF guard on
 * the path BYOK traffic can really take is the one under test here, not merely its sibling.
 *
 * <p>
 * Uses the cloud metadata address {@code 169.254.169.254} deliberately: it is the canonical SSRF target — an unguarded
 * gateway pointed at it would leak the host's cloud instance credentials to whichever provider account supplied the
 * {@code baseUrl}.
 *
 * @version ee
 */
class AiGatewayChatModelFactoryBaseUrlSsrfTest {

    @Test
    void testCustomerSuppliedBaseUrlIsValidated() {
        AiGatewayProvider customerProvider = newProvider(1L, AiGatewayProviderType.OPENAI);

        customerProvider.setConnectedUserId(5L);
        customerProvider.setBaseUrl("http://169.254.169.254/latest/meta-data/");

        AiGatewayChatModelFactory aiGatewayChatModelFactory = new AiGatewayChatModelFactoryImpl();

        assertThatThrownBy(() -> aiGatewayChatModelFactory.getChatModel(customerProvider))
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
