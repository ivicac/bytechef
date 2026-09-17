/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.atlas.configuration.exception.WorkflowErrorType;
import com.bytechef.exception.ConfigurationException;

/**
 * A reference whose catalog template was removed cannot be enabled, nor can its inputs be written: the template has no
 * row left to carry them. Its own type, rather than a plain {@link ConfigurationException}, only so
 * {@code ConnectedUserCodeWorkflowReferenceFacadeImpl#enableReference} can name it in {@code noRollbackFor}: the lazy
 * catch-up that found the template gone has already written the reference dangling and must not be rolled back.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
final class DanglingReferenceException extends ConfigurationException {

    DanglingReferenceException(String catalogWorkflowUuid) {
        super(
            "Reference to catalog workflow %s is dangling".formatted(catalogWorkflowUuid),
            WorkflowErrorType.WORKFLOW_NOT_FOUND);
    }
}
