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

package com.bytechef.component.aws.transcribe.action;

import static com.bytechef.component.aws.transcribe.constant.AwsTranscribeConstants.ACCESS_KEY_ID;
import static com.bytechef.component.aws.transcribe.constant.AwsTranscribeConstants.JOB_NAME;
import static com.bytechef.component.aws.transcribe.constant.AwsTranscribeConstants.LANGUAGE_CODE;
import static com.bytechef.component.aws.transcribe.constant.AwsTranscribeConstants.MEDIA_FILE_URI;
import static com.bytechef.component.aws.transcribe.constant.AwsTranscribeConstants.REGION;
import static com.bytechef.component.aws.transcribe.constant.AwsTranscribeConstants.SECRET_ACCESS_KEY;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import software.amazon.awssdk.services.transcribe.model.LanguageCode;
import software.amazon.awssdk.services.transcribe.model.Media;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobResponse;
import software.amazon.awssdk.services.transcribe.model.TranscriptionJob;

/**
 * @author Ivica Cardic
 */
public class AwsTranscribeStartTranscriptionJobAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("startTranscriptionJob")
        .title("Start Transcription Job")
        .description("Starts an asynchronous job that transcribes an audio or video file stored in S3.")
        .properties(
            string(JOB_NAME)
                .label("Job Name")
                .description("The unique name of the transcription job.")
                .required(true),
            string(MEDIA_FILE_URI)
                .label("Media File URI")
                .description("The S3 location of the media file (e.g. s3://bucket/audio.mp3).")
                .required(true),
            string(LANGUAGE_CODE)
                .label("Language Code")
                .description("The language spoken in the media file.")
                .options(
                    option("English (US)", "en-US"),
                    option("English (UK)", "en-GB"),
                    option("German", "de-DE"),
                    option("French", "fr-FR"),
                    option("Spanish", "es-ES"),
                    option("Italian", "it-IT"),
                    option("Portuguese (Brazil)", "pt-BR"),
                    option("Japanese", "ja-JP"),
                    option("Korean", "ko-KR"),
                    option("Chinese (Simplified)", "zh-CN"))
                .defaultValue("en-US")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("transcriptionJobName")
                            .description("The name of the transcription job."),
                        string("transcriptionJobStatus")
                            .description("The status of the transcription job."),
                        string("languageCode")
                            .description("The language code of the transcription job."))))
        .help("", "https://docs.bytechef.io/reference/components/awsTranscribe_v1#start-transcription-job")
        .perform(AwsTranscribeStartTranscriptionJobAction::perform);

    private AwsTranscribeStartTranscriptionJobAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        try (TranscribeClient transcribeClient = buildTranscribeClient(connectionParameters)) {
            StartTranscriptionJobResponse startTranscriptionJobResponse = transcribeClient.startTranscriptionJob(
                StartTranscriptionJobRequest.builder()
                    .transcriptionJobName(inputParameters.getRequiredString(JOB_NAME))
                    .media(Media.builder()
                        .mediaFileUri(inputParameters.getRequiredString(MEDIA_FILE_URI))
                        .build())
                    .languageCode(LanguageCode.fromValue(inputParameters.getRequiredString(LANGUAGE_CODE)))
                    .build());

            TranscriptionJob transcriptionJob = startTranscriptionJobResponse.transcriptionJob();

            return Map.of(
                "transcriptionJobName", transcriptionJob.transcriptionJobName(),
                "transcriptionJobStatus", transcriptionJob.transcriptionJobStatusAsString(),
                "languageCode", transcriptionJob.languageCodeAsString());
        }
    }

    private static TranscribeClient buildTranscribeClient(Parameters connectionParameters) {
        return TranscribeClient.builder()
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        connectionParameters.getRequiredString(ACCESS_KEY_ID),
                        connectionParameters.getRequiredString(SECRET_ACCESS_KEY))))
            .region(Region.of(connectionParameters.getRequiredString(REGION)))
            .build();
    }
}
