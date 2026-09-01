/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.ai.hub.exception.TitleGenerationFailedException;
import graphql.GraphQLError;
import graphql.execution.ExecutionStepInfo;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubGraphQlExceptionResolverTest {

    private final AiHubGraphQlExceptionResolver resolver = new AiHubGraphQlExceptionResolver();

    @Test
    void testResolvesTitleGenerationFailureToATypedErrorCode() {
        GraphQLError error = resolver.resolveToSingleError(
            new TitleGenerationFailedException("Title generation failed: boom", new RuntimeException("boom")),
            newEnvironment());

        assertThat(error).isNotNull();

        Map<String, Object> extensions = error.getExtensions();

        assertThat(extensions).containsEntry(
            "errorCode", AiHubGraphQlExceptionResolver.TITLE_GENERATION_FAILED_ERROR_CODE);
    }

    /**
     * The provider's own message can carry account and billing detail — an exhausted-credit 400 reading "Your credit
     * balance is too low" is what first exposed this path — so the resolved error must not forward it to the browser.
     */
    @Test
    void testDoesNotForwardTheUpstreamProviderMessage() {
        GraphQLError error = resolver.resolveToSingleError(
            new TitleGenerationFailedException(
                "Title generation failed: 400 Your credit balance is too low to access the Anthropic API",
                new RuntimeException("400")),
            newEnvironment());

        assertThat(error).isNotNull();
        assertThat(error.getMessage())
            .doesNotContain("credit balance")
            .doesNotContain("Anthropic");
    }

    /**
     * Returning null is what lets the remaining resolvers in the chain, including the CE global one, see the exception.
     * Claiming everything here would swallow types this class knows nothing about.
     */
    @Test
    void testDeclinesUnrelatedExceptions() {
        GraphQLError error = resolver.resolveToSingleError(
            new IllegalStateException("unrelated"), newEnvironment());

        assertThat(error).isNull();
    }

    private static DataFetchingEnvironment newEnvironment() {
        DataFetchingEnvironment environment = Mockito.mock(DataFetchingEnvironment.class);

        Mockito.when(environment.getField())
            .thenReturn(new Field("generateAiHubChatTitle"));
        Mockito.when(environment.getExecutionStepInfo())
            .thenReturn(Mockito.mock(ExecutionStepInfo.class));

        return environment;
    }
}
