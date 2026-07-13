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

package com.bytechef.component.aws.lambda;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.aws.lambda.action.AwsLambdaInvokeFunctionAction;
import com.bytechef.component.aws.lambda.connection.AwsLambdaConnection;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class AwsLambdaComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("awsLambda")
        .title("AWS Lambda")
        .version(1)
        .description("AWS Lambda is a serverless compute service that runs code in response to events.")
        .icon("path:assets/aws-lambda.svg")
        .categories(ComponentCategory.DEVELOPER_TOOLS)
        .connection(AwsLambdaConnection.CONNECTION_DEFINITION)
        .actions(AwsLambdaInvokeFunctionAction.ACTION_DEFINITION)
        .clusterElements(tool(AwsLambdaInvokeFunctionAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
