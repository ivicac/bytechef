/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.platform.ai.auto.memory;

/**
 * Thrown by {@link AiAutoMemoryService} when the requested memory row cannot be located within the supplied workspace +
 * user + environment scope. Distinct from {@link DuplicateAiAutoMemoryNameException} (the inverse outcome on the same
 * uniqueness key) so callers can branch cleanly without string-matching messages.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class AiAutoMemoryNotFoundException extends RuntimeException {

    public AiAutoMemoryNotFoundException(String message) {
        super(message);
    }
}
