/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.guardrail;

/**
 * Thrown when an inbound AI Gateway request violates a configured content guardrail (a blocked term). The message names
 * the matched term but never echoes the offending prompt content.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class AiGatewayGuardrailException extends RuntimeException {

    public AiGatewayGuardrailException(String message) {
        super(message);
    }
}
