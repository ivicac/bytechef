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

package com.bytechef.platform.workflow.task.dispatcher.domain;

import com.bytechef.platform.workflow.task.dispatcher.definition.Property;

/**
 * @author Ivica Cardic
 */
public class NullProperty extends ValueProperty<Void> {

    private NullProperty() {
    }

    public NullProperty(Property.NullProperty nullProperty) {
        super(nullProperty);
    }

    @Override
    public Object accept(PropertyVisitor propertyVisitor) {
        return propertyVisitor.visit(this);
    }

    @Override
    public String toString() {
        return "NullProperty{" +
            "name='" + name + '\'' +
            ", type=" + type +
            ", controlType=" + controlType +
            ", required=" + required +
            ", hidden=" + hidden +
            ", expressionEnabled=" + expressionEnabled +
            ", displayCondition='" + displayCondition + '\'' +
            ", description='" + description + '\'' +
            ", advancedOption=" + advancedOption +
            ", exampleValue=" + exampleValue +
            ", defaultValue=" + defaultValue +
            "} " + super.toString();
    }
}
