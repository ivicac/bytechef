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

package com.bytechef.component.freshservice.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.freshservice.constant.FreshserviceConstants.DESCRIPTION;
import static com.bytechef.component.freshservice.constant.FreshserviceConstants.EMAIL;
import static com.bytechef.component.freshservice.constant.FreshserviceConstants.PRIORITY;
import static com.bytechef.component.freshservice.constant.FreshserviceConstants.STATUS;
import static com.bytechef.component.freshservice.constant.FreshserviceConstants.SUBJECT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class FreshserviceCreateTicketAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createTicket")
        .title("Create Ticket")
        .description("Creates a new ticket.")
        .properties(
            string(SUBJECT)
                .label("Subject")
                .description("The subject of the ticket.")
                .required(true),
            string(DESCRIPTION)
                .label("Description")
                .description("The HTML content of the ticket.")
                .required(true),
            string(EMAIL)
                .label("Requester Email")
                .description("The email address of the requester.")
                .required(true),
            integer(PRIORITY)
                .label("Priority")
                .description("The priority of the ticket.")
                .options(
                    option("Low", 1),
                    option("Medium", 2),
                    option("High", 3),
                    option("Urgent", 4))
                .defaultValue(1)
                .required(true),
            integer(STATUS)
                .label("Status")
                .description("The status of the ticket.")
                .options(
                    option("Open", 2),
                    option("Pending", 3),
                    option("Resolved", 4),
                    option("Closed", 5))
                .defaultValue(2)
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("ticket")
                            .description("The created ticket.")
                            .properties(
                                integer("id")
                                    .description("The id of the ticket."),
                                string("subject")
                                    .description("The subject of the ticket."),
                                integer("priority")
                                    .description("The priority of the ticket."),
                                integer("status")
                                    .description("The status of the ticket."),
                                string("created_at")
                                    .description("The date the ticket was created.")))))
        .help("", "https://docs.bytechef.io/reference/components/freshservice_v1#create-ticket")
        .perform(FreshserviceCreateTicketAction::perform);

    private FreshserviceCreateTicketAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/tickets"))
            .body(
                Body.of(
                    SUBJECT, inputParameters.getRequiredString(SUBJECT),
                    DESCRIPTION, inputParameters.getRequiredString(DESCRIPTION),
                    EMAIL, inputParameters.getRequiredString(EMAIL),
                    PRIORITY, inputParameters.getRequiredInteger(PRIORITY),
                    STATUS, inputParameters.getRequiredInteger(STATUS)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
