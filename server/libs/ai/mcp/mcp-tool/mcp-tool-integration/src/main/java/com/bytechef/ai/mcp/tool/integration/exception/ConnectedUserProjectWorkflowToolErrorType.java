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

package com.bytechef.ai.mcp.tool.integration.exception;

import com.bytechef.exception.AbstractErrorType;

/**
 * @author Ivica Cardic
 */
public class ConnectedUserProjectWorkflowToolErrorType extends AbstractErrorType {

    public static final ConnectedUserProjectWorkflowToolErrorType CREATE_WORKFLOW =
        new ConnectedUserProjectWorkflowToolErrorType(100);
    public static final ConnectedUserProjectWorkflowToolErrorType DELETE_WORKFLOW =
        new ConnectedUserProjectWorkflowToolErrorType(101);
    public static final ConnectedUserProjectWorkflowToolErrorType UPDATE_WORKFLOW =
        new ConnectedUserProjectWorkflowToolErrorType(102);

    private ConnectedUserProjectWorkflowToolErrorType(int errorKey) {
        super(ConnectedUserProjectWorkflowToolErrorType.class, errorKey);
    }
}
