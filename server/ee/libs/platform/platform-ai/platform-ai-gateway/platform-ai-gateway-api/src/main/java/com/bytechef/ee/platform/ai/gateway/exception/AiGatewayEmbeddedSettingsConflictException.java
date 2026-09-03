/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.exception;

/**
 * Thrown when saving {@link com.bytechef.ee.platform.ai.gateway.domain.AiGatewayEmbeddedSettings} races another writer
 * for the same environment. The settings row has a null {@code scopeId}, so uniqueness is enforced only by the partial
 * index {@code uk_property_key_scope_environment_null_scope_id} — the write path is check-then-insert, not an atomic
 * upsert, so two concurrent first writes for the same environment can both pass the pre-save check and then race at the
 * database. This exception replaces the resulting {@link org.springframework.dao.DataIntegrityViolationException} so
 * callers see a meaningful conflict rather than a raw constraint violation.
 *
 * @version ee
 */
public class AiGatewayEmbeddedSettingsConflictException extends RuntimeException {

    public AiGatewayEmbeddedSettingsConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
