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

package com.bytechef.component.tailscale.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.tailscale.constant.TailscaleConstants.TAILNET;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class TailscaleListDevicesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listDevices")
        .title("List Devices")
        .description("Returns the devices of the tailnet.")
        .properties(
            string(TAILNET)
                .label("Tailnet")
                .description(
                    "The name of the tailnet (e.g. example.com). Use \"-\" for the default tailnet of the token.")
                .defaultValue("-")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("devices")
                            .description("The devices of the tailnet.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the device."),
                                        string("name")
                                            .description("The name of the device."),
                                        string("hostname")
                                            .description("The hostname of the device."),
                                        string("os")
                                            .description("The operating system of the device."))))))
        .help("", "https://docs.bytechef.io/reference/components/tailscale_v1#list-devices")
        .perform(TailscaleListDevicesAction::perform);

    private TailscaleListDevicesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get("/tailnet/%s/devices".formatted(inputParameters.getRequiredString(TAILNET))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
