/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.platform.ai.auto.memory;

/**
 * Thrown when an attempt is made to create or rename a {@link AiAutoMemory} using a name that already exists for the
 * same (workspaceId, userId) pair.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class DuplicateAiAutoMemoryNameException extends RuntimeException {

    private final String name;

    public DuplicateAiAutoMemoryNameException(String name) {
        super("A memory named '" + name + "' already exists for this workspace and user");

        this.name = name;
    }

    public DuplicateAiAutoMemoryNameException(String name, Throwable cause) {
        super("A memory named '" + name + "' already exists for this workspace and user", cause);

        this.name = name;
    }

    public String getName() {
        return name;
    }
}
