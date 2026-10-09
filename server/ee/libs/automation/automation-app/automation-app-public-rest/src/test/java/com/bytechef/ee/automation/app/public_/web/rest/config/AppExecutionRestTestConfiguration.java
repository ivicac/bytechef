/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.app.public_.web.rest.config;

import com.bytechef.ee.automation.app.public_.web.rest.AppConnectionApiController;
import com.bytechef.ee.automation.app.public_.web.rest.AppProxyApiController;
import com.bytechef.ee.automation.app.public_.web.rest.AppToolApiController;
import com.bytechef.ee.automation.app.public_.web.rest.AppWorkflowApiController;
import com.bytechef.jackson.config.JacksonConfiguration;
import com.bytechef.web.rest.error.GlobalResponseEntityExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The four {@code /api/app/v1} controllers an app's secret key reaches, with the global exception advice they share in
 * production, for {@code @WebMvcTest}s. Each test declares {@code @MockitoBean}s for {@code AppExecutionFacade} and
 * {@code AppWorkflowExecutionFacade}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@Import({
    AppConnectionApiController.class, AppProxyApiController.class, AppToolApiController.class,
    AppWorkflowApiController.class, GlobalResponseEntityExceptionHandler.class, JacksonConfiguration.class
})
public class AppExecutionRestTestConfiguration {
}
