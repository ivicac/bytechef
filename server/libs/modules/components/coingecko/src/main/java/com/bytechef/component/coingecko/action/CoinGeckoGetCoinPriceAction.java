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

package com.bytechef.component.coingecko.action;

import static com.bytechef.component.coingecko.constant.CoinGeckoConstants.IDS;
import static com.bytechef.component.coingecko.constant.CoinGeckoConstants.VS_CURRENCIES;
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
public class CoinGeckoGetCoinPriceAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getCoinPrice")
        .title("Get Coin Price")
        .description("Returns the current price of coins in the given currencies.")
        .properties(
            string(IDS)
                .label("Coin Ids")
                .description("Comma-separated CoinGecko coin ids (e.g. bitcoin,ethereum).")
                .required(true),
            string(VS_CURRENCIES)
                .label("Currencies")
                .description("Comma-separated target currencies (e.g. usd,eur).")
                .required(true))
        .output(
            outputSchema(
                object()
                    .description("The prices of the coins keyed by coin id and currency.")))
        .help("", "https://docs.bytechef.io/reference/components/coinGecko_v1#get-coin-price")
        .perform(CoinGeckoGetCoinPriceAction::perform);

    private CoinGeckoGetCoinPriceAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/simple/price"))
            .queryParameters(
                "ids", inputParameters.getRequiredString(IDS),
                "vs_currencies", inputParameters.getRequiredString(VS_CURRENCIES))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
