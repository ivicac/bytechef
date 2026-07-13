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

package com.bytechef.component.aws.sqs.util;

import static com.bytechef.component.aws.sqs.constant.AwsSqsConstants.ACCESS_KEY_ID;
import static com.bytechef.component.aws.sqs.constant.AwsSqsConstants.REGION;
import static com.bytechef.component.aws.sqs.constant.AwsSqsConstants.SECRET_ACCESS_KEY;

import com.bytechef.component.definition.Parameters;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * @author Ivica Cardic
 */
public class AwsSqsUtils {

    public static SqsClient buildSqsClient(Parameters connectionParameters) {
        return SqsClient.builder()
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        connectionParameters.getRequiredString(ACCESS_KEY_ID),
                        connectionParameters.getRequiredString(SECRET_ACCESS_KEY))))
            .region(Region.of(connectionParameters.getRequiredString(REGION)))
            .build();
    }

    private AwsSqsUtils() {
    }
}
