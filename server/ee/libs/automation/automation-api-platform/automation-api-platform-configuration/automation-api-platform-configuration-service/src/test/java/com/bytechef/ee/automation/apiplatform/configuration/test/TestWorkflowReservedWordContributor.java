/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.test;

import com.bytechef.atlas.configuration.workflow.contributor.WorkflowReservedWordContributor;
import com.bytechef.platform.configuration.constant.WorkflowExtConstants;
import java.util.List;

/**
 * Test-only stand-in for the production contributor in platform-configuration-service, which this module does not
 * depend on. It lets {@link com.bytechef.atlas.configuration.domain.Workflow} accept the {@code triggers} extension in
 * test workflow definitions.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class TestWorkflowReservedWordContributor implements WorkflowReservedWordContributor {

    @Override
    public List<String> getReservedWords() {
        return WorkflowExtConstants.RESERVED_WORDS;
    }
}
