/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.exception;

/**
 * A code workflow template cannot be copied into a connected user's project; it can only be referenced. Mapped to 409.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class CodeWorkflowNotCopyableException extends RuntimeException {

    public CodeWorkflowNotCopyableException(String workflowUuid) {
        super("Catalog workflow template %s is a code workflow and can only be referenced".formatted(workflowUuid));
    }
}
