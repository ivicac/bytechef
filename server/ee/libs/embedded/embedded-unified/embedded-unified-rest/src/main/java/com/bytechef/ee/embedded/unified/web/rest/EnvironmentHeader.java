/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.unified.web.rest;

import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.web.rest.AbstractEnvironmentHeaderConverter;
import org.jspecify.annotations.Nullable;

/**
 * Reads the unified API's {@code X-Environment} header, which its controllers receive as a plain string, the way the
 * API-key filter does: any letter case, surrounding whitespace ignored, and a missing or blank value meaning
 * PRODUCTION.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class EnvironmentHeader {

    private static final AbstractEnvironmentHeaderConverter<Environment> ENVIRONMENT_HEADER_CONVERTER =
        new AbstractEnvironmentHeaderConverter<>(Environment.class) {};

    private EnvironmentHeader() {
    }

    /**
     * @throws IllegalArgumentException when the header names no environment
     */
    public static Environment toEnvironment(@Nullable String environment) {
        if (environment == null) {
            return Environment.PRODUCTION;
        }

        Environment headerEnvironment = ENVIRONMENT_HEADER_CONVERTER.convert(environment);

        return headerEnvironment == null ? Environment.PRODUCTION : headerEnvironment;
    }
}
