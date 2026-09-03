/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayChatModelFactory;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayEmbeddingModelFactory;
import com.bytechef.ee.platform.ai.gateway.repository.AiGatewayProviderRepository;
import com.bytechef.ee.platform.ai.model.catalog.domain.AiModel;
import com.bytechef.ee.platform.ai.model.catalog.service.AiModelService;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AiGatewayProviderServiceImpl}. Covers the three behaviors most likely to regress: the
 * {@code Validate.isTrue(id == null)} guard on create, the cascading evict/model-delete on delete, and the "getProvider
 * throws when missing" contract relied on by the facade-layer workspace-ownership checks.
 *
 * @version ee
 */
@ExtendWith(MockitoExtension.class)
class AiGatewayProviderServiceTest {

    @Mock
    private AiGatewayChatModelFactory aiGatewayChatModelFactory;

    @Mock
    private AiGatewayEmbeddingModelFactory aiGatewayEmbeddingModelFactory;

    @Mock
    private AiModelService aiModelService;

    @Mock
    private AiGatewayProviderRepository aiGatewayProviderRepository;

    private AiGatewayProviderService aiGatewayProviderService;

    @BeforeEach
    void setUp() {
        aiGatewayProviderService = new AiGatewayProviderServiceImpl(
            aiGatewayChatModelFactory, aiGatewayEmbeddingModelFactory, aiModelService,
            aiGatewayProviderRepository);
    }

    @Test
    void testCreateRejectsProviderWithAssignedId() {
        AiGatewayProvider provider = newProviderWithId(42L);

        assertThatThrownBy(() -> aiGatewayProviderService.create(provider))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'id' must be null");
    }

    @Test
    void testDeleteEvictsBothFactoryCachesAndCascadesModelDeletion() {
        AiModel firstModel = newModelWithId(1L);
        AiModel secondModel = newModelWithId(2L);

        when(aiModelService.getModelsByProviderId(50L)).thenReturn(List.of(firstModel, secondModel));

        aiGatewayProviderService.delete(50L);

        // Both cache evictions must fire before the repo delete — without them a deleted provider's
        // encrypted api key stays resident in the chat/embedding caches and is used on subsequent requests.
        verify(aiGatewayChatModelFactory).evict(50L);
        verify(aiGatewayEmbeddingModelFactory).evict(50L);
        verify(aiModelService).delete(1L);
        verify(aiModelService).delete(2L);
        verify(aiGatewayProviderRepository).deleteById(50L);
    }

    @Test
    void testGetProviderThrowsWhenMissing() {
        when(aiGatewayProviderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> aiGatewayProviderService.getProvider(999L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Provider not found: 999");
    }

    @Test
    void testGetEnabledProvidersDelegatesToEnabledFinder() {
        AiGatewayProvider enabledProvider =
            new AiGatewayProvider("openai", AiGatewayProviderType.OPENAI, "sk-123");

        when(aiGatewayProviderRepository.findAllByEnabled(true)).thenReturn(List.of(enabledProvider));

        List<AiGatewayProvider> providers = aiGatewayProviderService.getEnabledProviders();

        assertThat(providers).containsExactly(enabledProvider);

        verify(aiGatewayProviderRepository).findAllByEnabled(true);
    }

    @Test
    void testUpdateConnectedUserIdSetsConnectedUserId() {
        AiGatewayProvider provider = newProviderWithId(5L);

        when(aiGatewayProviderRepository.findById(5L)).thenReturn(Optional.of(provider));
        when(aiGatewayProviderRepository.save(provider)).thenReturn(provider);

        aiGatewayProviderService.updateConnectedUserId(5L, 42L);

        assertThat(provider.getConnectedUserId()).isEqualTo(42L);

        verify(aiGatewayProviderRepository).save(provider);
    }

    @Test
    void testUpdateConnectedUserIdClearsConnectedUserIdWhenArgumentIsNull() {
        AiGatewayProvider provider = newProviderWithId(5L);

        provider.setConnectedUserId(42L);

        when(aiGatewayProviderRepository.findById(5L)).thenReturn(Optional.of(provider));
        when(aiGatewayProviderRepository.save(provider)).thenReturn(provider);

        aiGatewayProviderService.updateConnectedUserId(5L, null);

        assertThat(provider.getConnectedUserId()).isNull();
    }

    /**
     * Final whole-branch review, I-6 — {@code updateConnectedUserId} on a workspace-scoped provider used to be a bare
     * setter: it would set {@code connectedUserId} and save, relying on
     * {@code ck_ai_gateway_provider_workspace_connected_user_not_both} to catch the violation as a raw
     * {@code DataIntegrityViolationException} at the database. This asserts the rejection now happens at the service
     * method itself, with a clear domain exception, mirroring the guard
     * {@code ConnectedUserAiGatewayRoutingPolicyFacadeImpl.bind} already applies for the identical constraint on
     * routing policies — and, since the guard runs BEFORE the setter, also proves the workspace scope is left
     * completely untouched (repository.save is never even called) rather than merely unchanged after a successful save.
     */
    @Test
    void testUpdateConnectedUserIdRejectsAWorkspaceScopedProviderAndDoesNotDisturbWorkspaceId() {
        AiGatewayProvider provider = newProviderWithId(5L);

        provider.setWorkspaceId(7L);

        when(aiGatewayProviderRepository.findById(5L)).thenReturn(Optional.of(provider));

        assertThatThrownBy(() -> aiGatewayProviderService.updateConnectedUserId(5L, 42L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Provider 5 is bound to a workspace and cannot be bound to a connected user");

        assertThat(provider.getWorkspaceId()).isEqualTo(7L);
        assertThat(provider.getConnectedUserId()).isNull();

        verify(aiGatewayProviderRepository, never()).save(provider);
    }

    /**
     * The clearing direction (a {@code null} argument) must remain unguarded: {@code unbind}-shaped callers never carry
     * a workspace scope into conflict, since they are only ever removing a connected-user binding, not creating one.
     * Without this the guard above would also have to special-case {@code null}, which it does — this pins that a
     * workspace-scoped provider can still have its connected-user scope cleared.
     */
    @Test
    void testUpdateConnectedUserIdClearingToNullIsAllowedOnAWorkspaceScopedProvider() {
        AiGatewayProvider provider = newProviderWithId(5L);

        provider.setWorkspaceId(7L);

        when(aiGatewayProviderRepository.findById(5L)).thenReturn(Optional.of(provider));
        when(aiGatewayProviderRepository.save(provider)).thenReturn(provider);

        aiGatewayProviderService.updateConnectedUserId(5L, null);

        assertThat(provider.getWorkspaceId()).isEqualTo(7L);
        assertThat(provider.getConnectedUserId()).isNull();

        verify(aiGatewayProviderRepository).save(provider);
    }

    private static AiGatewayProvider newProviderWithId(long id) {
        AiGatewayProvider provider = new AiGatewayProvider("openai", AiGatewayProviderType.OPENAI, "sk-test");

        setIdViaReflection(provider, id);

        return provider;
    }

    private static AiModel newModelWithId(long id) {
        AiModel model = new AiModel(1L, "gpt-4");

        setIdViaReflection(model, id);

        return model;
    }

    private static void setIdViaReflection(Object target, long id) {
        try {
            Field idField = target.getClass()
                .getDeclaredField("id");

            idField.setAccessible(true);
            idField.set(target, id);
        } catch (ReflectiveOperationException reflectiveOperationException) {
            throw new AssertionError("failed to seed id", reflectiveOperationException);
        }
    }
}
