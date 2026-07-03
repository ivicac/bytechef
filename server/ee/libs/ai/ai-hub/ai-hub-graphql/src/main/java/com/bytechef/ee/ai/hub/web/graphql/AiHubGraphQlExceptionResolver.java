/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

import com.bytechef.ee.ai.hub.exception.TitleGenerationFailedException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * Resolves AI Hub exceptions that the CE {@code GlobalDataFetcherExceptionResolver} cannot see. That resolver lives in
 * {@code server/libs/core/graphql} and matches on {@code AbstractException}, {@code GraphQlBadRequestException} and two
 * Spring Security types, returning {@code null} for anything else; EE exception types are not on its classpath, so
 * without this bean they fall through unresolved.
 *
 * <p>
 * An unresolved exception is not merely untidy: Spring GraphQL's {@code ExceptionResolversExceptionHandler} logs it at
 * ERROR with the full stack (260+ frames for a chat turn), and the client receives an opaque {@code INTERNAL_ERROR}
 * with an execution id and nothing else to branch on. Resolving it turns both into a WARN line and a typed
 * {@code errorCode} the client can act on.
 * </p>
 *
 * <p>
 * Title generation stays a FAILED field rather than a silent success: a blank title is the model's own "nothing
 * sensible to say" answer and is already handled upstream by returning the row unchanged, so swallowing a genuine model
 * outage here would make an unhealthy provider indistinguishable from that, leaving the chat stuck on "Untitled" with
 * no signal. The message is deliberately generic — the underlying provider error can carry account and billing detail
 * (an exhausted-credit 400 was what first exposed this path) which must not reach the browser.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
class AiHubGraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    static final String TITLE_GENERATION_FAILED_ERROR_CODE = "TITLE_GENERATION_FAILED";

    private static final Logger log = LoggerFactory.getLogger(AiHubGraphQlExceptionResolver.class);

    @Override
    protected @Nullable GraphQLError resolveToSingleError(
        Throwable throwable, DataFetchingEnvironment dataFetchingEnvironment) {

        if (!(throwable instanceof TitleGenerationFailedException)) {
            return null;
        }

        log.warn("Chat title generation failed: {}", throwable.getMessage());

        return GraphqlErrorBuilder.newError(dataFetchingEnvironment)
            .message("Chat title generation is temporarily unavailable.")
            .errorType(ErrorType.INTERNAL_ERROR)
            .extensions(Map.of("errorCode", TITLE_GENERATION_FAILED_ERROR_CODE))
            .build();
    }
}
