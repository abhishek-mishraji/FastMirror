package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.service.PathFilter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Excludes paths whose <em>file name</em> matches any configured glob (FR-18).
 *
 * <p>Matching is deliberately against the file name alone, not the whole path, so that a pattern
 * such as {@code *.tmp} behaves the same at every depth of the tree.
 *
 * <p>The default patterns include {@code *.fmtmp}. That one is load-bearing rather than cosmetic:
 * without it a staging file could be treated as a source in a misconfigured overlapping setup.
 * Overlap is already rejected at startup (CV-5), so this is the second of two independent guards.
 */
public final class GlobPathFilterImpl implements PathFilter {

    private final List<PathMatcher> matchers;
    private final List<String> patterns;

    public GlobPathFilterImpl(List<String> patterns) {
        Objects.requireNonNull(patterns, "patterns");
        final FileSystem fileSystem = FileSystems.getDefault();
        final List<PathMatcher> built = new ArrayList<>(patterns.size());
        final List<String> kept = new ArrayList<>(patterns.size());
        for (final String raw : patterns) {
            if (raw == null) {
                continue;
            }
            final String pattern = raw.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            try {
                built.add(fileSystem.getPathMatcher("glob:" + pattern));
                kept.add(pattern);
            } catch (final IllegalArgumentException | UnsupportedOperationException e) {
                throw new IllegalArgumentException("Invalid exclude pattern: " + pattern, e);
            }
        }
        this.matchers = List.copyOf(built);
        this.patterns = List.copyOf(kept);
    }

    @Override
    public boolean isExcluded(Path path) {
        Objects.requireNonNull(path, "path");
        final Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        for (final PathMatcher matcher : matchers) {
            if (matcher.matches(fileName)) {
                return true;
            }
        }
        return false;
    }

    /** The patterns actually in force, for the startup log line. */
    public List<String> patterns() {
        return patterns;
    }
}
