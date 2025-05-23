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

import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.commons.util.OptionalUtils;
import com.bytechef.platform.workflow.task.dispatcher.definition.Property;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @author Ivica Cardic
 */
public class DateProperty extends ValueProperty<LocalDate> {

    private List<Option> options;

    private DateProperty() {
    }

    public DateProperty(Property.DateProperty dateProperty) {
        super(dateProperty);

        this.options = CollectionUtils.map(OptionalUtils.orElse(dateProperty.getOptions(), List.of()), Option::new);
    }

    @Override
    public Object accept(PropertyVisitor propertyVisitor) {
        return propertyVisitor.visit(this);
    }

    public List<Option> getOptions() {
        return Collections.unmodifiableList(options);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof DateProperty that)) {
            return false;
        }

        return Objects.equals(options, that.options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(options);
    }

    @Override
    public String toString() {
        return "DateProperty{" +
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
            ", options=" + options +
            "} " + super.toString();
    }
}
