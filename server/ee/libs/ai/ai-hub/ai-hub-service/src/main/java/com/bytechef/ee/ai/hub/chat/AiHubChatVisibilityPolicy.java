/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import com.bytechef.platform.security.domain.ResourceVisibility;
import com.bytechef.platform.security.domain.ResourceVisibilityPolicy;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Declares the rungs an AI Hub chat supports. {@code ORGANIZATION} is deliberately absent: a chat belongs to at most
 * one workspace, mirroring {@code ProjectVisibilityPolicy}.
 *
 * <p>
 * Conditioned on the same property as {@link AiHubChatVisibilityProvider}. The two are halves of one resource-type
 * registration — the policy declares the rungs, the provider resolves a row's visibility — so a deployment that carries
 * {@code ai-hub-service} with AI Hub switched off must register both or neither, rather than a resource type nothing
 * can resolve.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubChatVisibilityPolicy implements ResourceVisibilityPolicy {

    public static final String RESOURCE_TYPE = "AiHubChat";

    @Override
    public String resourceType() {
        return RESOURCE_TYPE;
    }

    @Override
    public ResourceVisibility defaultVisibility() {
        return ResourceVisibility.PRIVATE;
    }

    @Override
    public Set<ResourceVisibility> supportedVisibilities() {
        return Set.of(ResourceVisibility.PRIVATE, ResourceVisibility.WORKSPACE);
    }
}
