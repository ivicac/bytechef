/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.routing.PromptComplexityScorer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import opennlp.tools.doccat.DoccatFactory;
import opennlp.tools.doccat.DoccatModel;
import opennlp.tools.doccat.DocumentCategorizerME;
import opennlp.tools.doccat.DocumentSample;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.ObjectStreamUtils;
import opennlp.tools.util.TrainingParameters;

/**
 * Bake-off candidate C. Trains an OpenNLP maximum-entropy document categoriser over the labelled exemplar set at
 * construction time and reports the model's probability of the COMPLEX category as the complexity score. Training a few
 * dozen short documents takes well under a second, which is why no model artifact is shipped: the exemplar set stays
 * the single source of truth shared with candidate B.
 *
 * @version ee
 */
public final class OpenNlpPromptComplexityScorer implements PromptComplexityScorer {

    private static final int CUTOFF = 1;
    private static final int ITERATIONS = 100;
    private static final String LANGUAGE = "en";

    private final DocumentCategorizerME categorizer;
    private final int complexIndex;

    public OpenNlpPromptComplexityScorer(List<BakeoffPrompt> exemplars) {
        List<DocumentSample> samples = new ArrayList<>();

        for (BakeoffPrompt exemplar : exemplars) {
            samples.add(new DocumentSample(exemplar.label(), tokenize(exemplar.text())));
        }

        TrainingParameters trainingParameters = new TrainingParameters();

        trainingParameters.put(TrainingParameters.CUTOFF_PARAM, CUTOFF);
        trainingParameters.put(TrainingParameters.ITERATIONS_PARAM, ITERATIONS);

        try (ObjectStream<DocumentSample> sampleStream = ObjectStreamUtils.createObjectStream(samples)) {
            DoccatModel model = DocumentCategorizerME.train(
                LANGUAGE, sampleStream, trainingParameters, new DoccatFactory());

            this.categorizer = new DocumentCategorizerME(model);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to train the OpenNLP document categoriser", exception);
        }

        this.complexIndex = categorizer.getIndex(BakeoffPrompt.COMPLEX);
    }

    @Override
    public double score(AiGatewayChatCompletionRequest request) {
        String[] tokens = tokenize(concatenateContent(request));

        if (tokens.length == 0) {
            return 0.0;
        }

        double[] outcomes = categorizer.categorize(tokens);

        return Math.max(0.0, Math.min(outcomes[complexIndex], 1.0));
    }

    private static String concatenateContent(AiGatewayChatCompletionRequest request) {
        StringBuilder builder = new StringBuilder();

        for (AiGatewayChatMessage message : request.messages()) {
            String content = message.content();

            if (content != null) {
                builder.append(content)
                    .append(' ');
            }
        }

        return builder.toString();
    }

    private static String[] tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }

        return SimpleTokenizer.INSTANCE.tokenize(text);
    }
}
