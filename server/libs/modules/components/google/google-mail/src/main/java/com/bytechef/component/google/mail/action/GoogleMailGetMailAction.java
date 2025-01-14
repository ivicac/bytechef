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

package com.bytechef.component.google.mail.action;

import static com.bytechef.component.definition.ComponentDSL.action;
import static com.bytechef.component.definition.ComponentDSL.string;
import static com.bytechef.component.google.mail.constant.GoogleMailConstants.FORMAT;
import static com.bytechef.component.google.mail.constant.GoogleMailConstants.FORMAT_PROPERTY;
import static com.bytechef.component.google.mail.constant.GoogleMailConstants.GET_MAIL;
import static com.bytechef.component.google.mail.constant.GoogleMailConstants.ID;
import static com.bytechef.component.google.mail.constant.GoogleMailConstants.MESSAGE_PROPERTY;
import static com.bytechef.component.google.mail.constant.GoogleMailConstants.METADATA_HEADERS;
import static com.bytechef.component.google.mail.constant.GoogleMailConstants.METADATA_HEADERS_PROPERTY;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDSL.ModifiableActionDefinition;
import com.bytechef.component.definition.OptionsDataSource.ActionOptionsFunction;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.google.mail.util.GoogleMailUtils;
import com.bytechef.google.commons.GoogleServices;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * @author Monika Domiter
 */
public class GoogleMailGetMailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action(GET_MAIL)
        .title("Get Mail")
        .description("Get an email from your Gmail account via Id")
        .properties(
            string(ID)
                .label("Message ID")
                .description("The ID of the message to retrieve.")
                .options((ActionOptionsFunction<String>) GoogleMailUtils::getMessageIdOptions)
                .required(true),
            FORMAT_PROPERTY,
            METADATA_HEADERS_PROPERTY)
        .outputSchema(MESSAGE_PROPERTY)
        .perform(GoogleMailGetMailAction::perform);

    private GoogleMailGetMailAction() {
    }

    public static Message perform(
        Parameters inputParameters, Parameters connectionParameters, ActionContext actionContext) throws IOException {

        Gmail service = GoogleServices.getMail(connectionParameters);

        String responseMessageFormat = inputParameters.getString(FORMAT);

        Message responseMessage = service.users()
            .messages()
            .get("me", inputParameters.getRequiredString(ID))
            .setFormat(responseMessageFormat)
            .setMetadataHeaders(inputParameters.getList(METADATA_HEADERS, String.class, List.of()))
            .execute();

        if (!Objects.equals(responseMessageFormat, "full")) {
            return responseMessage;
        }

        return sortParts(responseMessage);
    }

    private static Message sortParts(Message message) throws IOException {
        List<MessagePart> parts = message.getPayload()
            .getParts();

        int textPlainPartIdx = 0;
        MessagePart messagePart = null;

        for (; textPlainPartIdx < parts.size(); textPlainPartIdx++) {
            messagePart = parts.get(textPlainPartIdx);

            if ((textPlainPartIdx == 0) && Objects.equals("text/plain", messagePart.getMimeType())) {
                return message;
            }
        }

        if (messagePart == null) {
            return message;
        }

        parts.remove(textPlainPartIdx);

        parts.addFirst(messagePart);

        return message;
    }
}
