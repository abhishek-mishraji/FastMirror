package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.service.DifferenceDetector;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.PathMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Compares size and last-modified time, and optionally content hashes (SRS 9.4).
 *
 * <p>The timestamp comparison is meaningful only because every successful copy stamps the target
 * with the source's timestamp (FR-09). A source that changes necessarily gets a new timestamp, a
 * new size, or both.
 *
 * <p>A target published as a partial capture is by construction shorter than its source, so the
 * size check re-queues it automatically. Partials need no special handling to converge.
 *
 * <p>Attribute reads only. When {@code CONTENT_VERIFY} is enabled the hashing is delegated to the
 * copier, because opening a source file is that class's exclusive privilege (AR-10, NI-12).
 */
public final class SizeAndTimeDifferenceDetectorImpl implements DifferenceDetector {

    private final PathMapper mapper;
    private final FileCopier copier;
    private final boolean contentVerify;

    public SizeAndTimeDifferenceDetectorImpl(
            PathMapper mapper, FileCopier copier, boolean contentVerify) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.copier = Objects.requireNonNull(copier, "copier");
        this.contentVerify = contentVerify;
    }

    @Override
    public boolean needsCopy(Path relative) {
        Objects.requireNonNull(relative, "relative");

        final Path source = mapper.toSource(relative);
        final Path target = mapper.toTarget(relative);

        final BasicFileAttributes sourceAttributes;
        try {
            sourceAttributes = attributesOf(source);
        } catch (final IOException e) {
            // The source has gone, or cannot be stat-ed. There is nothing left to copy, and
            // saying "yes" here would only send a worker to open a name that is not there.
            return false;
        }

        if (!sourceAttributes.isRegularFile()) {
            return false;
        }

        final BasicFileAttributes targetAttributes;
        try {
            targetAttributes = attributesOf(target);
        } catch (final IOException e) {
            // Missing or unreadable target: copy it. The mirror is ours, so an unreadable target
            // is a problem we are allowed to fix by overwriting.
            return true;
        }

        if (sourceAttributes.size() != targetAttributes.size()) {
            return true;
        }

        if (!sourceAttributes.lastModifiedTime().equals(targetAttributes.lastModifiedTime())) {
            return true;
        }

        // The pathological case: a rewrite that changed neither size nor timestamp. Off by
        // default because it reads and hashes both files on every check.
        return contentVerify && copier.contentsDiffer(relative);
    }

    private static BasicFileAttributes attributesOf(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
}
