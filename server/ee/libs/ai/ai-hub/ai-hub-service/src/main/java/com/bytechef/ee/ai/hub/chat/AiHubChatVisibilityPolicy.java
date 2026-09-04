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
import org.springframework.stereotype.Component;

/**
 * Declares the rungs an AI Hub chat supports. {@code ORGANIZATION} is deliberately absent: a chat belongs to at most
 * one workspace, mirroring {@code ProjectVisibilityPolicy}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
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
