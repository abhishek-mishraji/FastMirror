package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.Selection;
import com.handsoffretail.fastmirror.service.ArchiveFileFilter;
import com.handsoffretail.fastmirror.service.PathFilter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Selection by prefix, extension and subfolder, plus the {@code COPY_FROM_DATE} check.
 *
 * <p>Prefixes, extensions and subfolder names are all compared ignoring case, because Windows file
 * names are case-insensitive. The destination folder of a root file is always the upper-case prefix,
 * so {@code cpj432.xml} and {@code CPJ433.xml} end up side by side.
 */
public final class ArchiveFileFilterImpl implements ArchiveFileFilter {

    private static final int PREFIX_LENGTH = 3;

    private final PathFilter excludes;
    private final Set<String> prefixes;
    private final Set<String> rootExtensions;
    private final Set<String> subfolderExtensions;
    private final LocalDate copyFromDate;

    /** Lower-cased subfolder name to the configured name. */
    private final Map<String, String> subfolders = new HashMap<>();

    public ArchiveFileFilterImpl(MirrorConfig config, PathFilter excludes) {
        Objects.requireNonNull(config, "config");
        this.excludes = Objects.requireNonNull(excludes, "excludes");
        this.prefixes = Set.copyOf(config.includePrefixes());
        this.rootExtensions = Set.copyOf(config.includeExtensions());
        this.subfolderExtensions = Set.copyOf(config.subfolderExtensions());
        this.copyFromDate = config.copyFromDate();
        for (final String name : config.includeSubfolders()) {
            subfolders.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
    }

    @Override
    public Optional<Selection> select(Path relative) {
        Objects.requireNonNull(relative, "relative");

        if (relative.isAbsolute() || excludes.isExcluded(relative)) {
            return Optional.empty();
        }

        final String name = relative.getFileName().toString();
        return switch (relative.getNameCount()) {
            case 1 -> selectRootFile(name);
            case 2 -> selectSubfolderFile(relative.getName(0).toString(), name);
            default -> Optional.empty(); // Nested deeper than an included subfolder.
        };
    }

    @Override
    public boolean withinDateRange(LocalDate fileDate) {
        Objects.requireNonNull(fileDate, "fileDate");
        return copyFromDate == null || !fileDate.isBefore(copyFromDate);
    }

    private Optional<Selection> selectRootFile(String name) {
        if (name.length() < PREFIX_LENGTH || !hasExtension(name, rootExtensions)) {
            return Optional.empty();
        }
        final String prefix = name.substring(0, PREFIX_LENGTH).toUpperCase(Locale.ROOT);
        if (!prefixes.contains(prefix)) {
            return Optional.empty();
        }
        return Optional.of(new Selection(prefix));
    }

    private Optional<Selection> selectSubfolderFile(String folder, String name) {
        if (!subfolders.containsKey(folder.toLowerCase(Locale.ROOT))
                || !hasExtension(name, subfolderExtensions)) {
            return Optional.empty();
        }
        // The folder name as configured, so the mirror matches what the operator sees.
        return Optional.of(new Selection(subfolders.get(folder.toLowerCase(Locale.ROOT))));
    }

    private static boolean hasExtension(String name, Set<String> extensions) {
        final int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return false;
        }
        return extensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
