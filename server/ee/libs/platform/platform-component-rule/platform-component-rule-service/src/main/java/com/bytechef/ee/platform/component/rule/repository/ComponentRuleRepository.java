/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.repository;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Repository
@ConditionalOnEEVersion
public interface ComponentRuleRepository extends CrudRepository<ComponentRule, Long> {

    List<ComponentRule> findAllByComponentName(String componentName);

    List<ComponentRule> findAllByComponentNameAndEnabled(String componentName, boolean enabled);
}
