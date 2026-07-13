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

package com.bytechef.component.recall.ai.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.recall.ai.constant.RecallAiConstants.BOT_NAME;
import static com.bytechef.component.recall.ai.constant.RecallAiConstants.MEETING_URL;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class RecallAiCreateBotAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createBot")
        .title("Create Bot")
        .description("Creates a bot that joins a meeting to record and transcribe it.")
        .properties(
            string(MEETING_URL)
                .label("Meeting URL")
                .description("The URL of the meeting the bot will join (e.g. a Zoom or Google Meet link).")
                .required(true),
            string(BOT_NAME)
                .label("Bot Name")
                .description("The name the bot will use in the meeting.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the created bot."),
                        string("meeting_url")
                            .description("The URL of the meeting the bot will join."),
                        string("bot_name")
                            .description("The name of the bot."),
                        string("join_at")
                            .description("The time at which the bot will join the meeting."))))
        .help("", "https://docs.bytechef.io/reference/components/recallAi_v1#create-bot")
        .perform(RecallAiCreateBotAction::perform);

    private RecallAiCreateBotAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(MEETING_URL, inputParameters.getRequiredString(MEETING_URL));

        String botName = inputParameters.getString(BOT_NAME);

        if (botName != null) {
            body.put(BOT_NAME, botName);
        }

        return context.http(http -> http.post("/bot/"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
