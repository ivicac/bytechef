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

package com.bytechef.component.aws.lambda.action;

import static com.bytechef.component.aws.lambda.constant.AwsLambdaConstants.FUNCTION_NAME;
import static com.bytechef.component.aws.lambda.constant.AwsLambdaConstants.PAYLOAD;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.aws.lambda.util.AwsLambdaUtils;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeRequest.Builder;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * @author Ivica Cardic
 */
public class AwsLambdaInvokeFunctionAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("invokeFunction")
        .title("Invoke Function")
        .description("Invokes a Lambda function synchronously and returns its response.")
        .properties(
            string(FUNCTION_NAME)
                .label("Function Name")
                .description("The name, ARN or partial ARN of the Lambda function that will be invoked.")
                .required(true),
            string(PAYLOAD)
                .label("Payload")
                .description("The JSON payload that will be passed to the function.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("statusCode")
                            .description("The HTTP status code of the invocation."),
                        string("executedVersion")
                            .description("The version of the function that was executed."),
                        string("payload")
                            .description("The response payload of the function."))))
        .help("", "https://docs.bytechef.io/reference/components/awsLambda_v1#invoke-function")
        .perform(AwsLambdaInvokeFunctionAction::perform);

    private AwsLambdaInvokeFunctionAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        try (LambdaClient lambdaClient = AwsLambdaUtils.buildLambdaClient(connectionParameters)) {
            Builder invokeRequestBuilder = InvokeRequest.builder()
                .functionName(inputParameters.getRequiredString(FUNCTION_NAME));

            String payload = inputParameters.getString(PAYLOAD);

            if (payload != null) {
                invokeRequestBuilder.payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8));
            }

            InvokeResponse invokeResponse = lambdaClient.invoke(invokeRequestBuilder.build());

            Map<String, Object> result = new HashMap<>();

            result.put("statusCode", invokeResponse.statusCode());
            result.put("executedVersion", invokeResponse.executedVersion());

            SdkBytes responsePayload = invokeResponse.payload();

            if (responsePayload != null) {
                result.put("payload", responsePayload.asUtf8String());
            }

            return result;
        }
    }
}
