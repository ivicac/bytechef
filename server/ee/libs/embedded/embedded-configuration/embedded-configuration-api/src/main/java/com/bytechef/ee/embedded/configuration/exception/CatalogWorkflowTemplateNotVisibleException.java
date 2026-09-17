/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.exception;

/**
 * Provisioning a reference named a catalog workflow template the permission-filtered catalog does not show the
 * connected user -- either because no such published template exists or because its permission expression hides it.
 * Both cases throw this same exception, and the REST controllers map it to the same bodyless 404, so a response never
 * reveals whether the template exists.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class CatalogWorkflowTemplateNotVisibleException extends RuntimeException {

    public CatalogWorkflowTemplateNotVisibleException(String catalogWorkflowUuid) {
        super("Not a published catalog workflow template: " + catalogWorkflowUuid);
    }
}
