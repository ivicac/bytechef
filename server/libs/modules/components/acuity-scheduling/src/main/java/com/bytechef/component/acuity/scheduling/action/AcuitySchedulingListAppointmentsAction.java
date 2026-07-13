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

package com.bytechef.component.acuity.scheduling.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class AcuitySchedulingListAppointmentsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listAppointments")
        .title("List Appointments")
        .description("Returns a list of appointments.")
        .output(
            outputSchema(
                array()
                    .items(
                        object()
                            .properties(
                                integer("id")
                                    .description("The id of the appointment."),
                                string("firstName")
                                    .description("The first name of the client."),
                                string("lastName")
                                    .description("The last name of the client."),
                                string("date")
                                    .description("The date of the appointment."),
                                string("time")
                                    .description("The time of the appointment."),
                                string("type")
                                    .description("The appointment type.")))))
        .help("", "https://docs.bytechef.io/reference/components/acuityScheduling_v1#list-appointments")
        .perform(AcuitySchedulingListAppointmentsAction::perform);

    private AcuitySchedulingListAppointmentsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get("/appointments"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
