/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

/**
 * The visibility rungs the AI Hub chat GraphQL schema exposes. Deliberately narrower than the platform-wide
 * {@code ResourceVisibility}: {@code AiHubChatVisibilityPolicy} supports only {@code PRIVATE} and {@code WORKSPACE} — a
 * chat belongs to at most one workspace, so the model has no way to express one that reaches beyond it. Publishing the
 * platform's three-value enum here would advertise an {@code ORGANIZATION} rung the server always rejects, which a
 * client author would only discover at runtime.
 *
 * <p>
 * Owned by this schema on purpose, not shared with the platform {@code ResourceVisibility} GraphQL enum declared in
 * {@code connection.graphqls}: declaring that enum a second time here breaks any app (such as {@code server-app}) that
 * depends on both modules, since Spring GraphQL's schema merge rejects a duplicate top-level type declaration outright,
 * identical or not. {@link AiHubChatVisibilityMapper} converts to and from the platform type at the controller
 * boundary; the facade and service layers keep speaking the platform type throughout.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum AiHubChatVisibility {

    PRIVATE,
    WORKSPACE
}
