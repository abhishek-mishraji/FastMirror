package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.entity.GroupBy;
import com.handsoffretail.fastmirror.entity.Selection;
import com.handsoffretail.fastmirror.service.PathMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps a selected source file to {@code <folder>/<yyyy>/<month>/[<yyyy-MM-dd>/]<name>}.
 *
 * <p>Every mapping is checked for containment, so nothing in a file or folder name can produce a
 * target outside the mirror root. That check is not decoration: a path that escaped the mirror
 * root would be a write outside the one directory FastMirror is allowed to write to.
 */
public final class GroupedPathMapperImpl implements PathMapper {

    private final Path sourceRoot;
    private final Path mirrorRoot;

    public GroupedPathMapperImpl(Path sourceRoot, Path mirrorRoot) {
        this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        this.mirrorRoot = Objects.requireNonNull(mirrorRoot, "mirrorRoot").toAbsolutePath().normalize();
    }

    @Override
    public Path relativize(Path sourceFile) {
        Objects.requireNonNull(sourceFile, "sourceFile");
        final Path absolute = sourceFile.toAbsolutePath().normalize();
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
    public Path targetFor(Selection selection, Path relativeSource, LocalDate creationDate) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(relativeSource, "relativeSource");
        Objects.requireNonNull(creationDate, "creationDate");

        Path target =
                Path.of(
                        selection.destinationFolder(),
                        String.valueOf(creationDate.getYear()),
                        monthName(creationDate));
        if (selection.groupBy() == GroupBy.DATE) {
            target = target.resolve(creationDate.toString()); // ISO yyyy-MM-dd
        }
        target = target.resolve(relativeSource.getFileName().toString());

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
