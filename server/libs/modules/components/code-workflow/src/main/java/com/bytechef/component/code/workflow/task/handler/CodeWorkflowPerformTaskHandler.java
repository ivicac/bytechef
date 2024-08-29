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

package com.bytechef.component.code.workflow.task.handler;

import static com.bytechef.component.code.workflow.constant.CodeWorkflowConstants.CODE_WORKFLOW;
import static com.bytechef.component.code.workflow.constant.CodeWorkflowConstants.PERFORM;

import com.bytechef.platform.component.registry.facade.ActionDefinitionFacade;
import com.bytechef.platform.component.registry.handler.AbstractTaskHandler;
import org.springframework.stereotype.Component;

/**
 * @author Ivica Cardic
 */
@Component(CODE_WORKFLOW + "/v1/" + PERFORM)
public class CodeWorkflowPerformTaskHandler extends AbstractTaskHandler {

    public CodeWorkflowPerformTaskHandler(ActionDefinitionFacade actionDefinitionFacade) {
        super(CODE_WORKFLOW, 1, PERFORM, actionDefinitionFacade);
    }
}
