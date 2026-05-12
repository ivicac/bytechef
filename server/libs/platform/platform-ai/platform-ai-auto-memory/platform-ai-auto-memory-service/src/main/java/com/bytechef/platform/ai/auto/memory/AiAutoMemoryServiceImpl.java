/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.platform.ai.auto.memory;

import com.bytechef.platform.ai.auto.memory.repository.AiAutoMemoryRepository;
import com.bytechef.platform.ai.auto.memory.repository.WorkspaceAiAutoMemoryRepository;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link AiAutoMemoryService}.
 *
 * <p>
 * <b>Workspace association via the relation table.</b> The {@code ai_auto_memory} entity is workspace-agnostic;
 * {@code workspace_ai_auto_memory} carries the (workspace, memory) link. {@code create} writes both rows; ownership
 * checks JOIN through the relation. Mirrors {@code WorkspaceAiHubPersonalAgent}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
public class AiAutoMemoryServiceImpl implements AiAutoMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AiAutoMemoryServiceImpl.class);

    private final AiAutoMemoryRepository aiMemoryRepository;
    private final WorkspaceAiAutoMemoryRepository workspaceAiMemoryRepository;
    private final Clock clock;

    @Autowired
    public AiAutoMemoryServiceImpl(
        AiAutoMemoryRepository aiMemoryRepository, WorkspaceAiAutoMemoryRepository workspaceAiMemoryRepository) {
        this(aiMemoryRepository, workspaceAiMemoryRepository, Clock.systemUTC());
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AiAutoMemoryServiceImpl(
        AiAutoMemoryRepository aiMemoryRepository, WorkspaceAiAutoMemoryRepository workspaceAiMemoryRepository,
        Clock clock) {
        this.aiMemoryRepository = aiMemoryRepository;
        this.workspaceAiMemoryRepository = workspaceAiMemoryRepository;
        this.clock = clock;
    }

    @Override
    public AiAutoMemory create(
        long workspaceId, long userId, int environment, String name, String title, @Nullable String description,
        AiAutoMemoryType memoryType, String content) {

        validateName(name);
        validateRequired(title, "title");
        validateRequired(content, "content");

        if (memoryType == null) {
            throw new IllegalArgumentException("memoryType is required");
        }

        // Service-layer duplicate-name check is the only gate now (DB no longer enforces (user, env, name) unique).
        // Concurrent same-name creates CAN race; the resulting redundant row is removable from the UI.
        if (!aiMemoryRepository
            .findAllByWorkspaceIdAndUserIdAndEnvironmentAndName(workspaceId, userId, environment, name)
            .isEmpty()) {
            throw new DuplicateAiAutoMemoryNameException(name);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        AiAutoMemory memory = new AiAutoMemory(userId);

        memory.setName(name);
        memory.setTitle(title);
        memory.setDescription(description);
        memory.setMemoryType(memoryType);
        memory.setContent(content);
        memory.setEnvironment(resolveEnvironment(environment));
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);

        AiAutoMemory saved = aiMemoryRepository.save(memory);

        try {
            workspaceAiMemoryRepository.save(new WorkspaceAiAutoMemory(workspaceId, saved.getId()));
        } catch (DataIntegrityViolationException exception) {
            // The (workspace_id, ai_auto_memory_id) unique constraint is the only DB-level dup gate. Concurrent
            // creates that slipped past the service-layer check but lost the membership race surface as a
            // dup-name error for uniform caller experience.
            throw new DuplicateAiAutoMemoryNameException(name, exception);
        }

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiAutoMemory> read(long workspaceId, long userId, int environment, String name) {
        List<AiAutoMemory> matches = aiMemoryRepository
            .findAllByWorkspaceIdAndUserIdAndEnvironmentAndName(workspaceId, userId, environment, name);

        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    public AiAutoMemory update(
        long workspaceId, long userId, int environment, String name,
        @Nullable String title, @Nullable String description,
        @Nullable AiAutoMemoryType memoryType, @Nullable String content) {

        if (title == null && description == null && memoryType == null && content == null) {
            throw new IllegalArgumentException(
                "At least one of title, description, memoryType, content must be provided");
        }

        AiAutoMemory memory = loadByName(workspaceId, userId, environment, name);

        applyPartial(memory, title, description, memoryType, content);

        return aiMemoryRepository.save(memory);
    }

    @Override
    public AiAutoMemory updateById(
        long workspaceId, long userId, long memoryId,
        @Nullable String title, @Nullable String description,
        @Nullable AiAutoMemoryType memoryType, @Nullable String content) {

        AiAutoMemory memory = loadAndCheckOwnership(workspaceId, userId, memoryId);

        applyPartial(memory, title, description, memoryType, content);

        return aiMemoryRepository.save(memory);
    }

    @Override
    public AiAutoMemory delete(long workspaceId, long userId, int environment, String name) {
        AiAutoMemory memory = loadByName(workspaceId, userId, environment, name);

        aiMemoryRepository.delete(memory);

        return memory;
    }

    @Override
    public AiAutoMemory deleteById(long workspaceId, long userId, long memoryId) {
        AiAutoMemory memory = loadAndCheckOwnership(workspaceId, userId, memoryId);

        aiMemoryRepository.delete(memory);

        return memory;
    }

    @Override
    public AiAutoMemory rename(
        long workspaceId, long userId, int environment, String oldName, String newName) {

        validateName(newName);

        if (oldName.equals(newName)) {
            return loadByName(workspaceId, userId, environment, oldName);
        }

        if (!aiMemoryRepository
            .findAllByWorkspaceIdAndUserIdAndEnvironmentAndName(workspaceId, userId, environment, newName)
            .isEmpty()) {
            throw new DuplicateAiAutoMemoryNameException(newName);
        }

        AiAutoMemory memory = loadByName(workspaceId, userId, environment, oldName);

        memory.setName(newName);
        memory.setUpdatedAt(LocalDateTime.now(clock));

        return aiMemoryRepository.save(memory);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiAutoMemory> list(
        long workspaceId, long userId, int environment, @Nullable AiAutoMemoryType memoryType) {

        if (memoryType == null) {
            return aiMemoryRepository
                .findByWorkspaceIdAndUserIdAndEnvironmentOrderByUpdatedAtDesc(workspaceId, userId, environment);
        }

        return aiMemoryRepository.findByWorkspaceIdAndUserIdAndEnvironmentAndMemoryTypeOrderByUpdatedAtDesc(
            workspaceId, userId, environment, memoryType.ordinal());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiAutoMemory> findById(long workspaceId, long userId, long memoryId) {
        return aiMemoryRepository.findById(memoryId)
            .filter(memory -> memory.getUserId() == userId)
            .filter(memory -> workspaceAiMemoryRepository
                .findByWorkspaceIdAndAiAutoMemoryId(workspaceId, memory.getId())
                .isPresent());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiAutoMemory> listByUserAndWorkspace(long workspaceId, long userId, int environment) {
        return aiMemoryRepository
            .findByWorkspaceIdAndUserIdAndEnvironmentOrderByUpdatedAtDesc(workspaceId, userId, environment);
    }

    private AiAutoMemory loadByName(long workspaceId, long userId, int environment, String name) {
        List<AiAutoMemory> matches = aiMemoryRepository
            .findAllByWorkspaceIdAndUserIdAndEnvironmentAndName(workspaceId, userId, environment, name);

        if (matches.isEmpty()) {
            throw new AiAutoMemoryNotFoundException(
                "Memory '" + name + "' not found for workspace " + workspaceId + " user " + userId
                    + " environment " + environment);
        }

        return matches.get(0);
    }

    private void applyPartial(
        AiAutoMemory memory, @Nullable String title, @Nullable String description,
        @Nullable AiAutoMemoryType memoryType, @Nullable String content) {

        if (title != null) {
            validateRequired(title, "title");

            memory.setTitle(title);
        }

        if (description != null) {
            memory.setDescription(description.isBlank() ? null : description);
        }

        if (memoryType != null) {
            memory.setMemoryType(memoryType);
        }

        if (content != null) {
            validateRequired(content, "content");

            memory.setContent(content);
        }

        memory.setUpdatedAt(LocalDateTime.now(clock));
    }

    private AiAutoMemory loadAndCheckOwnership(long workspaceId, long userId, long memoryId) {
        // Probe-oracle defense: collapse "does not exist" and "exists in another workspace/user" into the SAME
        // 404 response so an authenticated attacker cannot enumerate memory ids across workspaces.
        Optional<AiAutoMemory> memoryOptional = aiMemoryRepository.findById(memoryId);

        if (memoryOptional.isEmpty()) {
            throw new AiAutoMemoryNotFoundException("Memory not found");
        }

        AiAutoMemory memory = memoryOptional.get();

        boolean userOwns = memory.getUserId() == userId;
        boolean workspaceClaims = workspaceAiMemoryRepository
            .findByWorkspaceIdAndAiAutoMemoryId(workspaceId, memoryId)
            .isPresent();

        if (!userOwns || !workspaceClaims) {
            log.warn(
                "Memory ownership mismatch: requester userId={} workspaceId={} attempted to access memoryId={} "
                    + "owned by userId={}. Returning 404 to avoid leaking existence.",
                userId, workspaceId, memoryId, memory.getUserId());

            throw new AiAutoMemoryNotFoundException("Memory not found");
        }

        return memory;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }

        if (name.length() > 128) {
            throw new IllegalArgumentException("name must be 128 characters or fewer");
        }
    }

    private static void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static Environment resolveEnvironment(int ordinal) {
        Environment[] all = Environment.values();

        if (ordinal < 0 || ordinal >= all.length) {
            throw new IllegalStateException(
                "AiAutoMemory.environment ordinal " + ordinal + " is out of range for Environment (0.."
                    + (all.length - 1) + ")");
        }

        return all[ordinal];
    }
}
