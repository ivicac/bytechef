/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.fieldmapping.test;

import com.bytechef.atlas.configuration.workflow.contributor.WorkflowReservedWordContributor;
import java.util.List;

/**
 * Test-only stand-in for the production
 * {@code com.bytechef.platform.configuration.workflow.contributor.WorkflowReservedWordContributorImpl}. Lets
 * {@link com.bytechef.atlas.configuration.domain.Workflow}'s validator accept the {@code objectName} extension key that
 * a {@code field_mapping} workflow input carries, without dragging the full platform-configuration-service module into
 * this module's test classpath.
 *
 * <p>
 * Registered via {@code META-INF/services/com.bytechef.atlas.configuration.workflow.contributor
 * .WorkflowReservedWordContributor} under {@code src/test/resources}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class TestWorkflowReservedWordContributor implements WorkflowReservedWordContributor {

    @Override
    public List<String> getReservedWords() {
        return List.of("objectName");
    }
}
