/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.public_.web.rest;

import com.bytechef.ee.automation.configuration.public_.web.rest.model.EnvironmentModel;
import com.bytechef.web.rest.AbstractEnvironmentHeaderConverter;
import org.springframework.stereotype.Component;

/**
 * Binds the automation configuration API's {@code X-Environment} header the way the API-key filter reads it.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component("automationConfigurationEnvironmentModelConverter")
public class EnvironmentModelConverter extends AbstractEnvironmentHeaderConverter<EnvironmentModel> {

    public EnvironmentModelConverter() {
        super(EnvironmentModel.class);
    }
}
