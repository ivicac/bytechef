/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.exception;

/**
 * Enabling a reference was refused because a required workflow input has no value. Mapped to 409, like
 * {@link MissingConnectionException}; deliberately not an {@code AbstractException}, which would map to 400.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class MissingInputException extends RuntimeException {

    private final String inputName;

    public MissingInputException(String inputName) {
        super("No value for required input: " + inputName);

        this.inputName = inputName;
    }

    public String getInputName() {
        return inputName;
    }
}
