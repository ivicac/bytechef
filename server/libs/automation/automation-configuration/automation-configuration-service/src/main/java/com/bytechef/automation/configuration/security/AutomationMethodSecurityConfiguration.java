/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.automation.configuration.security;

import com.bytechef.automation.configuration.service.PermissionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;

/**
 * Registers {@link ProjectWorkspacePermissionEvaluator} and the single global {@link MethodSecurityExpressionHandler}
 * that backs the {@code hasPermission(...)} SpEL built-in. All other built-ins ({@code isAuthenticated()},
 * {@code hasAuthority(...)}, ...) keep their default behavior.
 *
 * <p>
 * Declared as an {@code @AutoConfiguration} (loaded via {@code AutoConfiguration.imports}) rather than a
 * component-scanned {@code @Configuration}/{@code @Component}, so these security beans are not swept into unrelated
 * integration-test slices that broadly scan {@code com.bytechef.automation.configuration}. They load in the real
 * application (where a {@link PermissionService} bean always exists) and in tests that opt in via
 * {@code @ImportAutoConfiguration}. {@code @ConditionalOnBean(PermissionService.class)} ensures the evaluator is only
 * created when its collaborator is present, so method-security-less slices load cleanly.
 *
 * @author Ivica Cardic
 */
@AutoConfiguration
@ConditionalOnBean(PermissionService.class)
public class AutomationMethodSecurityConfiguration {

    /**
     * {@code PermissionService} is injected {@code @Lazy} to break a bean-creation cycle: {@code PermissionService}
     * implementations are method-secured ({@code @PreAuthorize}), so building their AOP proxy sorts the method-security
     * advisors, which resolves the {@link MethodSecurityExpressionHandler} → this evaluator → {@code PermissionService}
     * again — while it is still in creation. The lazy proxy defers the real lookup until the first
     * {@code hasPermission(...)} call, by which time the service is fully initialized.
     */
    @Bean
    ProjectWorkspacePermissionEvaluator projectWorkspacePermissionEvaluator(@Lazy PermissionService permissionService) {
        return new ProjectWorkspacePermissionEvaluator(permissionService);
    }

    /**
     * Declared {@code static} so the method-security infrastructure can initialize this handler early without forcing
     * premature initialization of the surrounding configuration.
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(PermissionEvaluator permissionEvaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();

        handler.setPermissionEvaluator(permissionEvaluator);

        return handler;
    }
}
