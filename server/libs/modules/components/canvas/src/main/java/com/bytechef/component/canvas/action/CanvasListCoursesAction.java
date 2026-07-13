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

package com.bytechef.component.canvas.action;

import static com.bytechef.component.canvas.constant.CanvasConstants.ENROLLMENT_TYPE;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
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
public class CanvasListCoursesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listCourses")
        .title("List Courses")
        .description("Lists the active courses of the current user.")
        .properties(
            string(ENROLLMENT_TYPE)
                .label("Enrollment Type")
                .description("Only return courses where the user has this enrollment type.")
                .options(
                    option("Teacher", "teacher"),
                    option("Student", "student"),
                    option("TA", "ta"),
                    option("Observer", "observer"))
                .required(false))
        .output(
            outputSchema(
                array()
                    .description("The courses of the current user.")
                    .items(
                        object()
                            .properties(
                                integer("id")
                                    .description("The id of the course."),
                                string("name")
                                    .description("The name of the course."),
                                string("course_code")
                                    .description("The course code."),
                                string("workflow_state")
                                    .description("The state of the course."),
                                string("start_at")
                                    .description("The start date of the course.")))))
        .help("", "https://docs.bytechef.io/reference/components/canvas_v1#list-courses")
        .perform(CanvasListCoursesAction::perform);

    private CanvasListCoursesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/courses"))
            .queryParameters(ENROLLMENT_TYPE, inputParameters.getString(ENROLLMENT_TYPE))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
