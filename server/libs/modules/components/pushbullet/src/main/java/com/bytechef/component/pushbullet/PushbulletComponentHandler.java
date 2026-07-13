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

package com.bytechef.component.pushbullet;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.pushbullet.action.PushbulletCreatePushAction;
import com.bytechef.component.pushbullet.action.PushbulletGetPushesAction;
import com.bytechef.component.pushbullet.connection.PushbulletConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class PushbulletComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("pushbullet")
        .title("Pushbullet")
        .version(1)
        .description(
            "Pushbullet connects your devices, making it easy to send notes and links between them.")
        .customAction(true)
        .icon("path:assets/pushbullet.svg")
        .categories(ComponentCategory.COMMUNICATION)
        .connection(PushbulletConnection.CONNECTION_DEFINITION)
        .actions(
            PushbulletCreatePushAction.ACTION_DEFINITION,
            PushbulletGetPushesAction.ACTION_DEFINITION)
        .clusterElements(tool(PushbulletCreatePushAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
