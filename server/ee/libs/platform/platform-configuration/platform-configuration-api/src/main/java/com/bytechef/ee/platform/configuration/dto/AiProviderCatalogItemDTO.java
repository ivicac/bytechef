/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.configuration.dto;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

/**
 * USER-safe projection of a chat-capable AI provider from the platform catalog. Deliberately omits the API key (unlike
 * {@link AiProviderDTO}) so it can be returned to non-admin chat users.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@SuppressFBWarnings("EI")
public record AiProviderCatalogItemDTO(
    String key, String name, String icon, boolean enabled, boolean supportsModelById, List<Model> models) {

    @SuppressFBWarnings("EI")
    public record Model(String name, String label) {
    }
}
