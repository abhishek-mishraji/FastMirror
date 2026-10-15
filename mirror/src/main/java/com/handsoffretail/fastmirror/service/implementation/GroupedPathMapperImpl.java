package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.entity.Selection;
import com.handsoffretail.fastmirror.service.PathMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Maps a selected source file to {@code <yyyy>/<month>/<yyyy-MM-dd>/<TYPE>/<name>}: business date
 * first, then type, the same layout as the outbox and S3. The type is the upper-case prefix of a
 * root file ({@code PJR}) or the name of an extra folder ({@code PassportReports}).
 *
 * <p>Every mapping is checked for containment, so nothing in a file or folder name can produce a
 * target outside the mirror root. That check is not decoration: a path that escaped the mirror
 * root would be a write outside the one directory FastMirror is allowed to write to.
 */
public final class GroupedPathMapperImpl implements PathMapper {

    private final Path sourceRoot;
    private final Path mirrorRoot;

    /**
     * Folders outside the source root, by name. A file in one is identified by the relative path
     * {@code <name>/<file>}, as if the folder sat inside the source root, so the rest of the agent -
     * selection, layout, ledger - treats both kinds of folder the same way. Case-insensitive,
     * because ledger keys are lower case.
     */
    private final Map<String, Path> externalFolders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public GroupedPathMapperImpl(Path sourceRoot, Path mirrorRoot) {
        this(sourceRoot, mirrorRoot, Map.of());
    }

    public GroupedPathMapperImpl(Path sourceRoot, Path mirrorRoot, Map<String, Path> externalFolders) {
        this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        this.mirrorRoot = Objects.requireNonNull(mirrorRoot, "mirrorRoot").toAbsolutePath().normalize();
        Objects.requireNonNull(externalFolders, "externalFolders")
                .forEach((name, folder) -> this.externalFolders.put(name, folder.toAbsolutePath().normalize()));
    }

    @Override
    public Path relativize(Path sourceFile) {
        Objects.requireNonNull(sourceFile, "sourceFile");
        final Path absolute = sourceFile.toAbsolutePath().normalize();
        for (final Map.Entry<String, Path> external : externalFolders.entrySet()) {
            final Path folder = external.getValue();
            if (absolute.startsWith(folder) && !absolute.equals(folder)) {
                return Path.of(external.getKey()).resolve(folder.relativize(absolute));
            }
        }
        if (!absolute.startsWith(sourceRoot)) {
            throw new IllegalArgumentException(
                    "Path is outside the source root: " + absolute + " (source root " + sourceRoot + ")");
        }
        if (absolute.equals(sourceRoot)) {
            throw new IllegalArgumentException("Path is the source root itself: " + absolute);
        }
        return sourceRoot.relativize(absolute);
    }

    @Override
    public Path targetFor(Selection selection, Path relativeSource, LocalDate fileDate) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(relativeSource, "relativeSource");
        Objects.requireNonNull(fileDate, "fileDate");

        // The type is exactly one folder. Containment alone would not catch "../x" here, because
        // the date folders above it would absorb the climb and leave it inside the mirror.
        final String type = selection.typeFolder();
        if (type.equals(".") || type.equals("..") || type.contains("/") || type.contains("\\")
                || type.contains(":")) {
            throw new IllegalArgumentException("Type folder must be a single folder name: " + type);
        }

        // Date first, then type: one business day is one folder, holding one folder per type.
        final Path target =
                Path.of(
                        String.valueOf(fileDate.getYear()),
                        monthName(fileDate),
                        fileDate.toString(), // ISO yyyy-MM-dd
                        selection.typeFolder(),
                        relativeSource.getFileName().toString());

        // Validates containment; the relative form is what the job carries.
        toTarget(target);
        return target;
    }

    @Override
    public Path toTarget(Path relative) {
        return resolveWithin(mirrorRoot, relative, "mirror");
    }

    @Override
    public Path toSource(Path relative) {
        Objects.requireNonNull(relative, "relative");
        if (!relative.isAbsolute() && relative.getNameCount() > 1) {
            final Path folder = externalFolders.get(relative.getName(0).toString());
            if (folder != null) {
                return resolveWithin(folder, relative.subpath(1, relative.getNameCount()), "folder");
            }
        }
        return resolveWithin(sourceRoot, relative, "source");
    }

    @Override
    public Path sourceRoot() {
        return sourceRoot;
    }

    @Override
    public Path mirrorRoot() {
        return mirrorRoot;
    }

    /** {@code january} ... {@code december}, independent of the PC's language settings. */
    static String monthName(LocalDate date) {
        return date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
    }

    private static Path resolveWithin(Path root, Path relative, String rootName) {
        Objects.requireNonNull(relative, "relative");
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Expected a relative path, got absolute: " + relative);
        }
        final Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Relative path escapes the " + rootName + " root: " + relative + " -> " + resolved);
        }
        if (resolved.equals(root)) {
            throw new IllegalArgumentException("Relative path resolves to the " + rootName + " root itself");
        }
        return resolved;
    }
}
