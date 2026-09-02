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

package com.bytechef.platform.component.runner.external;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves a directory across the container boundary as a tar archive.
 *
 * <p>
 * Files cross by archive rather than by bind mount because a bind mount is resolved by the <em>daemon</em>: when
 * ByteChef itself runs in a container with the Docker socket mounted, the host path it names does not exist on the
 * daemon's filesystem and Docker mounts an empty directory instead of failing. An archive copy is
 * daemon-location-agnostic and works unchanged against a remote {@code DOCKER_HOST}.
 *
 * <p>
 * Extraction is a security boundary, because the archive coming back out of a container was produced by guest code the
 * workflow author wrote. That author controls every entry name, every mode bit and every link target in it: an entry
 * named {@code ../../etc/cron.d/x}, an absolute {@code /etc/passwd}, or a symbolic link pointing at a host file are all
 * trivially constructed. This is Zip Slip with the workflow author as the attacker.
 *
 * <p>
 * Every entry name is therefore validated rather than sanitised, with the same guard shape
 * {@code TaskRunnerWorkingDirectory} uses for input file names: resolve against the target, {@code normalize}, and
 * accept only when the result still {@code startsWith} the target. A name that escapes is reported by name rather than
 * rewritten into something safe, and nothing is created on disk before that check runs.
 *
 * <p>
 * Containment of the name is not containment of the <strong>path</strong>. A name that normalises back inside says
 * nothing about the directories it passes through, and an intermediate directory that is a symbolic link would carry
 * every write inside it somewhere else - which is exactly the gap that reopened this hole in {@code TaskRunnerOutputs},
 * where {@code NOFOLLOW_LINKS} on a file said nothing about the directory holding it. Each component between the target
 * and the entry is therefore created or verified one at a time, and a component that already exists as anything other
 * than a real directory rejects the entry.
 *
 * <p>
 * Volume is bounded as well as shape. Nothing in a tar header obliges an archive to be as small as it claims, and the
 * archive coming back out of a container is written by guest code that can emit as many bytes as the container's own
 * disk allows - so an unbounded extraction turns a workflow into a way of filling the <em>host</em> disk. Extraction
 * therefore carries a byte ceiling, counted across every entry and enforced on the bytes actually written rather than
 * on the sizes the headers declare, and the failure names the limit. The ceiling lives here rather than in the caller
 * because it can only be enforced while the entry is being streamed: a caller outside this class would have to buffer
 * the whole archive to measure it, which is the very thing being prevented.
 *
 * <p>
 * Bytes are not the only resource an archive can exhaust. Ten million zero-byte files - or ten million directory
 * entries - spend no byte budget at all and still consume ten million inodes on the host, which is the same exhaustion
 * reached by a different route. Entries are therefore counted as well as bytes, against a ceiling of their own, and an
 * entry is counted before it is created rather than after.
 *
 * <p>
 * Symbolic link and hard link entries are skipped outright in both directions rather than made safe. There is no
 * representation of a link that is worth the analysis: a link written during extraction is a link a later entry can be
 * routed through, and a link read during archiving would send the target's content into the container. An execution
 * that wants a file moved can write the file. Mode bits coming <em>out</em> of a container are not applied either -
 * nothing needs a guest-chosen {@code setuid} bit on the host.
 *
 * <p>
 * Mode bits going <em>in</em> are set rather than copied, for a reason that is invisible until a container runs as
 * somebody: the working directory is a host temporary directory, so it is created {@code 0700} and its entries carry
 * whatever the server's umask gave them. Extraction inside the container runs as root and applies those modes, so a
 * container told to run as {@code 1000} found {@code output/} owned by root and unwritable - and the bootstrap writes
 * {@code output.json} there, so every such execution failed at its last step. The host directory's own modes are not
 * touched; only the copy inside a single-use container, whose one user is the execution itself, is widened.
 *
 * @author Ivica Cardic
 */
final class ContainerArchive {

    private static final Logger log = LoggerFactory.getLogger(ContainerArchive.class);

    /**
     * {@code rwxrwxrwx}, the mode every directory entry is archived with. Written in binary because the ruleset forbids
     * octal literals, which is how a file mode would ordinarily be spelled.
     */
    private static final int ARCHIVED_DIRECTORY_MODE = 0b111_111_111;

    /**
     * {@code rw-rw-rw-}, the mode every file entry is archived with.
     */
    private static final int ARCHIVED_FILE_MODE = 0b110_110_110;

    /**
     * The default ceiling on the total number of bytes one extraction may write.
     *
     * <p>
     * Generous for an execution's output files and far below what it takes to fill a host disk. An execution that
     * legitimately produces more than this is producing a data set, not a task result.
     */
    static final long DEFAULT_MAX_EXTRACTED_BYTES = 256L * 1024 * 1024;

    /**
     * The default ceiling on the number of entries one extraction may create.
     *
     * <p>
     * Far more files than a task result has and far fewer than it takes to exhaust a filesystem's inodes.
     */
    static final int DEFAULT_MAX_EXTRACTED_ENTRIES = 10_000;

    /**
     * The defaults the two-argument {@link #extractTar(InputStream, Path)} - the overload every production caller
     * reaches - actually applies.
     *
     * <p>
     * They are fields rather than the constants themselves so that a test can observe the default being
     * <em>consulted</em> without manufacturing a 256 MiB stream. Asserting the constant's value proves only that a
     * number exists; it stays green if the overload delegates with {@code Long.MAX_VALUE}, which is precisely the
     * mutation that has to fail. A test that lowers these and then hands the two-argument overload a few hundred bytes
     * fails on that mutation and on nothing else. Package-private and never written by production code.
     */
    static long maxExtractedBytes = DEFAULT_MAX_EXTRACTED_BYTES;

    static int maxExtractedEntries = DEFAULT_MAX_EXTRACTED_ENTRIES;

    private ContainerArchive() {
    }

    /**
     * Archives the directory's regular files and subdirectories, named relative to it, and returns the archive as a
     * stream that removes its own backing file when closed.
     *
     * <p>
     * Security Note: PATH_TRAVERSAL_IN - the temporary file is created in the system temp location with a generated
     * name, not a user-controlled path.
     */
    @SuppressFBWarnings("PATH_TRAVERSAL_IN")
    static InputStream toTar(Path directoryPath) throws IOException {
        Path archivePath = Files.createTempFile("bytechef-archive-" + UUID.randomUUID() + "-", ".tar");

        try {
            try (OutputStream outputStream = Files.newOutputStream(archivePath);
                TarArchiveOutputStream tarArchiveOutputStream = new TarArchiveOutputStream(outputStream)) {

                tarArchiveOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tarArchiveOutputStream.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

                for (Path path : listArchivablePaths(directoryPath)) {
                    writeArchiveEntry(tarArchiveOutputStream, directoryPath, path);
                }
            }

            return newSelfDeletingInputStream(archivePath);
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(archivePath);

            throw exception;
        }
    }

    /**
     * Extracts the archive into the target directory, creating it and any intermediate directory an entry needs, within
     * the default byte ceiling.
     */
    static void extractTar(InputStream inputStream, Path targetDirectoryPath) throws IOException {
        extractTar(inputStream, targetDirectoryPath, maxExtractedBytes);
    }

    /**
     * Extracts the archive into the target directory, failing once the entries written together exceed
     * {@code maxExtractedBytes}.
     */
    static void extractTar(InputStream inputStream, Path targetDirectoryPath, long maxExtractedBytes)
        throws IOException {

        extractTar(inputStream, targetDirectoryPath, maxExtractedBytes, maxExtractedEntries);
    }

    /**
     * Extracts the archive into the target directory, failing once the entries written together exceed
     * {@code maxExtractedBytes} or the entries created exceed {@code maxExtractedEntries}.
     */
    static void extractTar(
        InputStream inputStream, Path targetDirectoryPath, long maxExtractedBytes, int maxExtractedEntries)
        throws IOException {

        Path targetPath = targetDirectoryPath.toAbsolutePath()
            .normalize();

        Files.createDirectories(targetPath);

        ExtractionBudget extractionBudget = new ExtractionBudget(maxExtractedBytes, maxExtractedEntries);

        try (TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(inputStream)) {
            TarArchiveEntry tarArchiveEntry;

            while ((tarArchiveEntry = tarArchiveInputStream.getNextEntry()) != null) {
                extractEntry(tarArchiveInputStream, tarArchiveEntry, targetPath, extractionBudget);
            }
        }
    }

    /**
     * Creates every component between the target and the entry, rejecting one that already exists as anything other
     * than a real directory - a symbolic link among them would carry the write outside the target however contained the
     * entry's own name is.
     */
    private static void createDirectoryInside(Path targetPath, Path directoryPath, String entryName)
        throws IOException {

        Path currentPath = targetPath;

        for (Path name : targetPath.relativize(directoryPath)) {
            currentPath = currentPath.resolve(name);

            if (Files.isSymbolicLink(currentPath)) {
                throw new IOException(
                    "Tar entry '%s' extracts through the symbolic link '%s'".formatted(entryName, currentPath));
            }

            if (!Files.exists(currentPath, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(currentPath);
            } else if (!Files.isDirectory(currentPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                    "Tar entry '%s' extracts through '%s', which is not a directory".formatted(entryName, currentPath));
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Could not remove {}", path, exception);
        }
    }

    private static void extractEntry(
        TarArchiveInputStream tarArchiveInputStream, TarArchiveEntry tarArchiveEntry, Path targetPath,
        ExtractionBudget extractionBudget) throws IOException {

        String entryName = tarArchiveEntry.getName();

        if (tarArchiveEntry.isSymbolicLink() || tarArchiveEntry.isLink()) {
            log.debug("Skipping link tar entry {}", entryName);

            return;
        }

        boolean directory = tarArchiveEntry.isDirectory();

        if (!directory && !tarArchiveEntry.isFile()) {
            log.debug("Skipping tar entry {} of an unsupported type", entryName);

            return;
        }

        Path entryPath = resolveInside(targetPath, entryName);

        if (entryPath.equals(targetPath)) {
            if (directory) {
                return;
            }

            throw new IOException("Tar entry '%s' names the target directory itself".formatted(entryName));
        }

        extractionBudget.countEntry(entryName);

        if (directory) {
            createDirectoryInside(targetPath, entryPath, entryName);
        } else {
            writeEntryFile(tarArchiveInputStream, entryPath, targetPath, entryName, extractionBudget);
        }
    }

    private static List<Path> listArchivablePaths(Path directoryPath) throws IOException {
        try (Stream<Path> paths = Files.walk(directoryPath)) {
            return paths.filter(path -> !path.equals(directoryPath))
                .filter(path -> !Files.isSymbolicLink(path))
                .filter(
                    path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.naturalOrder())
                .toList();
        }
    }

    private static InputStream newSelfDeletingInputStream(Path path) throws IOException {
        return new FilterInputStream(Files.newInputStream(path)) {

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    deleteQuietly(path);
                }
            }
        };
    }

    /**
     * The containment guard, in the shape {@code TaskRunnerWorkingDirectory} already uses: resolve, normalise, and
     * accept only what still starts with the directory. An absolute entry name replaces the target outright when
     * resolved, so it fails the same check without a case of its own.
     */
    private static Path resolveInside(Path directoryPath, String entryName) throws IOException {
        Path candidatePath;

        try {
            candidatePath = directoryPath.resolve(entryName)
                .normalize();
        } catch (RuntimeException exception) {
            throw new IOException("Tar entry '%s' is not a usable path".formatted(entryName), exception);
        }

        if (!candidatePath.startsWith(directoryPath)) {
            throw new IOException(
                "Tar entry '%s' resolves outside the target directory".formatted(entryName));
        }

        return candidatePath;
    }

    private static void writeArchiveEntry(
        TarArchiveOutputStream tarArchiveOutputStream, Path directoryPath, Path path) throws IOException {

        Path relativePath = directoryPath.relativize(path);

        String entryName = relativePath.toString()
            .replace(path.getFileSystem()
                .getSeparator(), "/");

        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);

        TarArchiveEntry tarArchiveEntry = new TarArchiveEntry(
            path, directory ? entryName + "/" : entryName, LinkOption.NOFOLLOW_LINKS);

        tarArchiveEntry.setMode(directory ? ARCHIVED_DIRECTORY_MODE : ARCHIVED_FILE_MODE);

        tarArchiveOutputStream.putArchiveEntry(tarArchiveEntry);

        if (!directory) {
            try (InputStream inputStream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                inputStream.transferTo(tarArchiveOutputStream);
            }
        }

        tarArchiveOutputStream.closeArchiveEntry();
    }

    private static void writeEntryFile(
        TarArchiveInputStream tarArchiveInputStream, Path entryPath, Path targetPath, String entryName,
        ExtractionBudget extractionBudget) throws IOException {

        Path parentPath = entryPath.getParent();

        if (parentPath != null) {
            createDirectoryInside(targetPath, parentPath, entryName);
        }

        if (Files.isSymbolicLink(entryPath)) {
            throw new IOException(
                "Tar entry '%s' would be written through the symbolic link '%s'".formatted(entryName, entryPath));
        }

        // The file is opened before the budget can be spent, so a rejected entry would otherwise leave a truncated or
        // zero-byte file behind - the one rejection that writes anything, where every traversal rejection writes
        // nothing. It is removed on the way out, so the rule holds for all of them.
        try (OutputStream outputStream = Files.newOutputStream(
            entryPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {

            extractionBudget.transfer(tarArchiveInputStream, outputStream, entryName);
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(entryPath);

            throw exception;
        }
    }

    /**
     * The extraction's remaining byte allowance, spent as entries are written.
     *
     * <p>
     * The count is of bytes actually read out of the archive, not of the sizes its headers declare: a header is written
     * by the same guest code as the payload, so believing it would let an archive claim one byte and deliver a
     * gigabyte. The check runs inside the copy loop rather than after it, so an entry that is too large is stopped
     * partway rather than after it has already been written in full.
     */
    private static final class ExtractionBudget {

        private static final int BUFFER_SIZE = 8 * 1024;

        private final long maxExtractedBytes;
        private final int maxExtractedEntries;

        private long extractedBytes;
        private int extractedEntries;

        ExtractionBudget(long maxExtractedBytes, int maxExtractedEntries) {
            this.maxExtractedBytes = maxExtractedBytes;
            this.maxExtractedEntries = maxExtractedEntries;
        }

        /**
         * Spends one entry of the allowance, before the entry is created rather than after: an archive of empty files
         * or of bare directories spends no bytes at all, and the inodes it creates are just as finite as the disk.
         */
        void countEntry(String entryName) throws IOException {
            extractedEntries++;

            if (extractedEntries > maxExtractedEntries) {
                throw new IOException(
                    "Tar entry '%s' takes the extracted archive past the %d entry ceiling".formatted(
                        entryName, maxExtractedEntries));
            }
        }

        void transfer(InputStream inputStream, OutputStream outputStream, String entryName) throws IOException {
            byte[] buffer = new byte[BUFFER_SIZE];

            int read = inputStream.read(buffer);

            while (read != -1) {
                extractedBytes += read;

                if (extractedBytes > maxExtractedBytes) {
                    throw new IOException(
                        "Tar entry '%s' takes the extracted archive past the %d byte ceiling".formatted(
                            entryName, maxExtractedBytes));
                }

                outputStream.write(buffer, 0, read);

                read = inputStream.read(buffer);
            }
        }
    }
}
