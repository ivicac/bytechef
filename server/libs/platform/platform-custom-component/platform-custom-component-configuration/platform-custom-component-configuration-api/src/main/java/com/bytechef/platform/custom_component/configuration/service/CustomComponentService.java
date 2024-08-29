/*
 * Copyright 2023-present ByteChef Inc.
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

package com.bytechef.platform.custom_component.configuration.service;

import com.bytechef.platform.custom_component.configuration.domain.CustomComponent;
import java.util.List;
import java.util.Optional;
import org.springframework.lang.NonNull;

/**
 * @author Ivica Cardic
 */
public interface CustomComponentService {

    CustomComponent create(@NonNull CustomComponent customComponent);

    void delete(long id);

    void enableCustomComponent(long id, boolean enable);

    Optional<CustomComponent> fetchCustomComponent(String name, int version);

    CustomComponent getCustomComponent(long id);

    List<CustomComponent> getCustomComponents();

    CustomComponent update(@NonNull CustomComponent customComponent);
}