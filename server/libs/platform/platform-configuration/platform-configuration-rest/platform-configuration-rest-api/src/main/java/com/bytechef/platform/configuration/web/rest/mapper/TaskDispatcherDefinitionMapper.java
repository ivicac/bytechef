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

package com.bytechef.platform.configuration.web.rest.mapper;

import com.bytechef.platform.configuration.web.rest.mapper.config.PlatformConfigurationMapperSpringConfig;
import com.bytechef.platform.configuration.web.rest.model.TaskDispatcherDefinitionBasicModel;
import com.bytechef.platform.configuration.web.rest.model.TaskDispatcherDefinitionModel;
import com.bytechef.platform.workflow.task.dispatcher.domain.TaskDispatcherDefinition;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.springframework.core.convert.converter.Converter;

/**
 * @author Ivica Cardic
 */
public class TaskDispatcherDefinitionMapper {

    @Mapper(config = PlatformConfigurationMapperSpringConfig.class)
    public interface TaskDispatcherDefinitionToTaskDispatcherDefinitionModelMapper
        extends Converter<TaskDispatcherDefinition, TaskDispatcherDefinitionModel> {

        @AfterMapping
        default void afterMapping(
            TaskDispatcherDefinition taskDispatcherDefinition,
            @MappingTarget TaskDispatcherDefinitionModel taskDispatcherDefinitionModel) {

            taskDispatcherDefinitionModel.setIcon("/icons/%s.svg".formatted(taskDispatcherDefinition.getName()));
        }

        @Override
        TaskDispatcherDefinitionModel convert(TaskDispatcherDefinition taskDispatcherDefinition);
    }

    @Mapper(config = PlatformConfigurationMapperSpringConfig.class)
    public interface TaskDispatcherDefinitionToTaskDispatcherDefinitionBasicModelMapper
        extends Converter<TaskDispatcherDefinition, TaskDispatcherDefinitionBasicModel> {

        @Override
        TaskDispatcherDefinitionBasicModel convert(TaskDispatcherDefinition taskDispatcherDefinition);

        @AfterMapping
        default void afterMapping(
            TaskDispatcherDefinition taskDispatcherDefinition,
            @MappingTarget TaskDispatcherDefinitionBasicModel taskDispatcherDefinitionBasicModel) {

            taskDispatcherDefinitionBasicModel.setIcon("/icons/%s.svg".formatted(taskDispatcherDefinition.getName()));
        }
    }
}
