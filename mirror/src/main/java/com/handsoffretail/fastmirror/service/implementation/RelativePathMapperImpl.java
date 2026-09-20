package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.service.PathMapper;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Maps between the two trees by relative path, preserving directory structure exactly (FR-06).
 *
 * <p>Both roots are normalized at construction. Every mapping is checked for containment, so a
 * traversal sequence in a relative path can never produce a target outside the mirror root. That
 * check is not defensive decoration: a path that escaped the mirror root would be a write outside
 * the one directory FastMirror is allowed to write to.
 */
public final class RelativePathMapperImpl implements PathMapper {

    private final Path sourceRoot;
    private final Path mirrorRoot;

    public RelativePathMapperImpl(Path sourceRoot, Path mirrorRoot) {
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
