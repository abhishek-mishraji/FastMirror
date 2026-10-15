package com.handsoffretail.fastmirror.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code manifest.json}, the last entry of every zip: what the zip is and exactly what it holds.
 *
 * <p>Written by hand rather than with a JSON library (FastMirror has no third-party dependencies),
 * in one fixed layout with one file per line. That keeps it byte-for-byte deterministic, and lets
 * FastMirror read its own manifests back when it finds a zip it has not recorded.
 *
 * @param store        {@code STORE_FOLDER}
 * @param folder       the business day and type
 * @param version      the zip's version
 * @param createdAt    when it was built, UTC
 * @param agentVersion the FastMirror version that built it
 * @param files        every file, sorted by path
 */
public record PackageManifest(
        String store,
        TypeFolder folder,
        int version,
        Instant createdAt,
        String agentVersion,
        List<Entry> files) {

    /** The entry name inside the zip. */
    public static final String ENTRY_NAME = "manifest.json";

    /**
     * @param file   the file's path, size and modified time
     * @param sha256 its content's SHA-256, lower-case hex
     */
    public record Entry(PackagedFile file, String sha256) {
        public Entry {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    private static final Pattern STRING_FIELD = Pattern.compile("^  \"(\\w+)\": \"((?:[^\"\\\\]|\\\\.)*)\",?$");
    private static final Pattern NUMBER_FIELD = Pattern.compile("^  \"(\\w+)\": (\\d+),?$");
    private static final Pattern FILE_LINE =
            Pattern.compile(
                    "^    \\{ \"path\": \"((?:[^\"\\\\]|\\\\.)*)\", \"size\": (\\d+),"
                            + " \"modified\": \"([^\"]+)\", \"sha256\": \"([0-9a-f]{64})\" },?$");

    public PackageManifest {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(agentVersion, "agentVersion");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
    }

    /** The files without their hashes, for comparing with a folder listing. */
    public List<PackagedFile> packagedFiles() {
        return files.stream().map(Entry::file).toList();
    }

    public String toJson() {
        final StringBuilder json = new StringBuilder(256 + files.size() * 200);
        json.append("{\n");
        field(json, "store", quoted(store));
        field(json, "type", quoted(folder.type()));
        field(json, "businessDate", quoted(folder.businessDate().toString()));
        field(json, "version", String.valueOf(version));
        field(json, "createdAt", quoted(createdAt.toString()));
        field(json, "fastMirrorVersion", quoted(agentVersion));
        field(json, "fileCount", String.valueOf(files.size()));
        json.append("  \"files\": [\n");
        for (int i = 0; i < files.size(); i++) {
            final Entry entry = files.get(i);
            json.append("    { \"path\": ").append(quoted(entry.file().path()))
                    .append(", \"size\": ").append(entry.file().size())
                    .append(", \"modified\": ").append(quoted(entry.file().modified().toString()))
                    .append(", \"sha256\": ").append(quoted(entry.sha256()))
                    .append(" }")
                    .append(i < files.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    /** A manifest written by {@link #toJson()}, or empty if the text is not one. */
    public static Optional<PackageManifest> parse(String json) {
        String store = null;
        String type = null;
        String businessDate = null;
        String createdAt = null;
        String agentVersion = null;
        Integer version = null;
        Integer fileCount = null;
        final List<Entry> files = new ArrayList<>();
        try {
            for (final String line : json.split("\n")) {
                final Matcher file = FILE_LINE.matcher(line);
                if (file.matches()) {
                    files.add(
                            new Entry(
                                    new PackagedFile(
                                            unquote(file.group(1)),
                                            Long.parseLong(file.group(2)),
                                            Instant.parse(file.group(3))),
                                    file.group(4)));
                    continue;
                }
                final Matcher text = STRING_FIELD.matcher(line);
                if (text.matches()) {
                    final String value = unquote(text.group(2));
                    switch (text.group(1)) {
                        case "store" -> store = value;
                        case "type" -> type = value;
                        case "businessDate" -> businessDate = value;
                        case "createdAt" -> createdAt = value;
                        case "fastMirrorVersion" -> agentVersion = value;
                        default -> { }
                    }
                    continue;
                }
                final Matcher number = NUMBER_FIELD.matcher(line);
                if (number.matches()) {
                    switch (number.group(1)) {
                        case "version" -> version = Integer.parseInt(number.group(2));
                        case "fileCount" -> fileCount = Integer.parseInt(number.group(2));
                        default -> { }
                    }
                }
            }
            if (store == null || type == null || businessDate == null || createdAt == null
                    || agentVersion == null || version == null || fileCount == null
                    || fileCount != files.size()) {
                return Optional.empty();
            }
            return Optional.of(
                    new PackageManifest(
                            store,
                            new TypeFolder(LocalDate.parse(businessDate), type),
                            version,
                            Instant.parse(createdAt),
                            agentVersion,
                            files));
        } catch (final DateTimeParseException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void field(StringBuilder json, String name, String value) {
        json.append("  \"").append(name).append("\": ").append(value).append(",\n");
    }

    private static String quoted(String value) {
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (final char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String unquote(String escaped) {
        final StringBuilder out = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            final char c = escaped.charAt(i);
            if (c != '\\' || i + 1 >= escaped.length()) {
                out.append(c);
                continue;
            }
            final char next = escaped.charAt(++i);
            if (next == 'u' && i + 4 < escaped.length()) {
                out.append((char) Integer.parseInt(escaped.substring(i + 1, i + 5), 16));
                i += 4;
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }
}
