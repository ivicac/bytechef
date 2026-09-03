/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = WorkerApplication.class)
public class WorkerApplicationIntTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    public void testContextLoads() {
    }

    /**
     * The agent runs on this app, so the {@link ComponentRuleEnforcer} SPI must resolve to a real implementation here —
     * an empty {@code List<ComponentRuleEnforcer>} makes the rule-enforcing tool callback wrapper a silent
     * pass-through, which is exactly the distributed-deployment gap this test guards against.
     */
    @Test
    public void testComponentRuleEnforcerIsReachable() {
        assertThat(applicationContext.getBeansOfType(ComponentRuleEnforcer.class)).isNotEmpty();
    }
}
