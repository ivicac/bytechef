/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.event;

/**
 * Published after an embedded automation catalog project is published, so reference deployments can follow it.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public record CatalogProjectPublishedEvent(long projectId) {
}
