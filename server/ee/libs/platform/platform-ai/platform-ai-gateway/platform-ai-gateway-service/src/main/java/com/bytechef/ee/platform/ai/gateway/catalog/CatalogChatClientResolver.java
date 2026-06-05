/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.catalog;

import com.bytechef.component.ai.llm.Provider;
import com.bytechef.platform.configuration.domain.Property;
import com.bytechef.platform.configuration.domain.Property.Scope;
import com.bytechef.platform.configuration.service.PropertyService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

/**
 * Resolves an override {@link ChatClient} from the platform AI provider catalog: given a catalog provider key (e.g.
 * {@code "ai.provider.openAi"}) + model name, reads the environment-scoped platform API key and builds a Spring-AI
 * {@link ChatModel} via {@link CatalogChatModelFactory}. Returns {@code null} (caller falls back) when the key is
 * unknown, the provider is disabled, no API key is stored, or the factory can't build it.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class CatalogChatClientResolver {

    private final PropertyService propertyService;
    private final CatalogChatModelFactory catalogChatModelFactory;

    @SuppressFBWarnings("EI")
    public CatalogChatClientResolver(
        PropertyService propertyService, CatalogChatModelFactory catalogChatModelFactory) {

        this.propertyService = propertyService;
        this.catalogChatModelFactory = catalogChatModelFactory;
    }

    public @Nullable ChatClient resolve(int environment, String providerKey, String model) {
        Provider provider = Arrays.stream(Provider.values())
            .filter(curProvider -> curProvider.getKey()
                .equals(providerKey))
            .findFirst()
            .orElse(null);

        if (provider == null) {
            return null;
        }

        Optional<Property> property =
            propertyService.fetchProperty(provider.getKey(), Scope.PLATFORM, null, (long) environment);

        if (property.isEmpty() || !property.get()
            .isEnabled()) {

            return null;
        }

        Object apiKey = property.get()
            .get("apiKey");

        if (apiKey == null) {
            return null;
        }

        ChatModel chatModel = catalogChatModelFactory.createChatModel(provider, model, apiKey.toString());

        if (chatModel == null) {
            return null;
        }

        return ChatClient.builder(chatModel)
            .defaultOptions(
                ChatOptions.builder()
                    .model(model))
            .build();
    }
}
