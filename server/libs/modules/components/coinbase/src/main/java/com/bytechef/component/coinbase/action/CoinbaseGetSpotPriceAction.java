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

package com.bytechef.component.coinbase.action;

import static com.bytechef.component.coinbase.constant.CoinbaseConstants.BASE_URL;
import static com.bytechef.component.coinbase.constant.CoinbaseConstants.CURRENCY_PAIR;
import static com.bytechef.component.definition.ComponentDsl.action;
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
public class CoinbaseGetSpotPriceAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getSpotPrice")
        .title("Get Spot Price")
        .description("Gets the current spot price of a cryptocurrency in the given fiat currency.")
        .properties(
            string(CURRENCY_PAIR)
                .label("Currency Pair")
                .description("The currency pair to get the spot price for (e.g. BTC-USD).")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The spot price data.")
                            .properties(
                                string("amount")
                                    .description("The spot price amount."),
                                string("base")
                                    .description("The base currency of the pair."),
                                string("currency")
                                    .description("The quote currency of the pair.")))))
        .help("", "https://docs.bytechef.io/reference/components/coinbase_v1#get-spot-price")
        .perform(CoinbaseGetSpotPriceAction::perform);

    private CoinbaseGetSpotPriceAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get(
                "%s/prices/%s/spot".formatted(BASE_URL, inputParameters.getRequiredString(CURRENCY_PAIR))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
