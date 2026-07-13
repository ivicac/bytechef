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

package com.bytechef.component.aws.textract.action;

import static com.bytechef.component.aws.textract.constant.AwsTextractConstants.ACCESS_KEY_ID;
import static com.bytechef.component.aws.textract.constant.AwsTextractConstants.BUCKET;
import static com.bytechef.component.aws.textract.constant.AwsTextractConstants.KEY;
import static com.bytechef.component.aws.textract.constant.AwsTextractConstants.REGION;
import static com.bytechef.component.aws.textract.constant.AwsTextractConstants.SECRET_ACCESS_KEY;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.S3Object;

/**
 * @author Ivica Cardic
 */
public class AwsTextractDetectDocumentTextAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("detectDocumentText")
        .title("Detect Document Text")
        .description("Detects lines of text in a document stored in an S3 bucket.")
        .properties(
            string(BUCKET)
                .label("S3 Bucket")
                .description("The name of the S3 bucket that contains the document.")
                .required(true),
            string(KEY)
                .label("S3 Object Key")
                .description("The key of the document object in the S3 bucket.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("text")
                            .description("The detected text, one line per row."),
                        integer("lineCount")
                            .description("The number of detected text lines."))))
        .help("", "https://docs.bytechef.io/reference/components/awsTextract_v1#detect-document-text")
        .perform(AwsTextractDetectDocumentTextAction::perform);

    private AwsTextractDetectDocumentTextAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        try (TextractClient textractClient = buildTextractClient(connectionParameters)) {
            DetectDocumentTextResponse detectDocumentTextResponse = textractClient.detectDocumentText(
                DetectDocumentTextRequest.builder()
                    .document(Document.builder()
                        .s3Object(S3Object.builder()
                            .bucket(inputParameters.getRequiredString(BUCKET))
                            .name(inputParameters.getRequiredString(KEY))
                            .build())
                        .build())
                    .build());

            List<String> lines = detectDocumentTextResponse.blocks()
                .stream()
                .filter(block -> block.blockType() == BlockType.LINE)
                .map(Block::text)
                .toList();

            return Map.of(
                "text", String.join("\n", lines),
                "lineCount", lines.size());
        }
    }

    private static TextractClient buildTextractClient(Parameters connectionParameters) {
        return TextractClient.builder()
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        connectionParameters.getRequiredString(ACCESS_KEY_ID),
                        connectionParameters.getRequiredString(SECRET_ACCESS_KEY))))
            .region(Region.of(connectionParameters.getRequiredString(REGION)))
            .build();
    }
}
