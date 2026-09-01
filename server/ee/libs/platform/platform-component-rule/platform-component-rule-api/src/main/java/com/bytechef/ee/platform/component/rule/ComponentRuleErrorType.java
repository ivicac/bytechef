/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import com.bytechef.exception.AbstractErrorType;

/**
 * Typed save-time rejections, so the GraphQL surface can report which rule was refused and why instead of a generic
 * 500.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class ComponentRuleErrorType extends AbstractErrorType {

    public static final ComponentRuleErrorType BLOCK_AFTER_UNSUPPORTED = new ComponentRuleErrorType(100);
    public static final ComponentRuleErrorType INVALID_CONDITION = new ComponentRuleErrorType(101);
    public static final ComponentRuleErrorType COMPONENT_RULE_NOT_FOUND = new ComponentRuleErrorType(102);

    private ComponentRuleErrorType(int errorKey) {
        super(ComponentRule.class, errorKey);
    }
}
