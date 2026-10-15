package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.config.PackageConfig;
import com.handsoffretail.fastmirror.entity.FolderContent;
import com.handsoffretail.fastmirror.entity.PackageManifest;
import com.handsoffretail.fastmirror.entity.PackagedFile;
import com.handsoffretail.fastmirror.entity.TypeFolder;
import com.handsoffretail.fastmirror.service.DayPackager;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zips one type folder into {@code <outbox>/<store>/<yyyy>/<month>/<yyyy-MM-dd>/<TYPE>/}.
 *
 * <p>The steps, in order:
 *
 * <ol>
 *   <li>write the zip into the staging folder, reading mirror files only: entries sorted by path,
 *       each entry's time the file's modified time (UTC), and {@code manifest.json} last;
 *   <li>re-read the zip and check every entry against the manifest;
 *   <li>move it into the outbox in one step.
 * </ol>
 *
 * <p>The zip is written with {@code SYNC}, so it is on disk before the move. The S3 agent ignores
 * only {@code ~$} names, so a zip must never be visible in the outbox half-written: the move is
 * what makes it appear, complete.
 *
 * <p>Nothing here depends on the PC's time zone, and nothing varies between runs except
 * {@code createdAt}, which the caller supplies: the same files give a byte-identical zip.
 */
public final class ZipDayPackagerImpl implements DayPackager {

    /** The suffix of a zip still being built in the staging folder. */
    public static final String PART_SUFFIX = ".part";

    private static final Pattern YEAR = Pattern.compile("\\d{4}");
    private static final int BUFFER = 64 * 1024;

    private final Path mirrorRoot;
    private final PackageConfig config;
    private final String agentVersion;

    public ZipDayPackagerImpl(Path mirrorRoot, PackageConfig config) {
        this.mirrorRoot = Objects.requireNonNull(mirrorRoot, "mirrorRoot").toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
        final String version = ZipDayPackagerImpl.class.getPackage().getImplementationVersion();
        this.agentVersion = version == null ? "dev" : version;
    }

    @Override
    public List<TypeFolder> typeFolders() throws IOException {
        final List<TypeFolder> folders = new ArrayList<>();
        for (final Path year : directories(mirrorRoot)) {
            if (!YEAR.matcher(year.getFileName().toString()).matches()) {
                continue;
            }
            for (final Path month : directories(year)) {
                for (final Path day : directories(month)) {
                    final LocalDate date = dayFolderDate(year, month, day);
                    if (date == null) {
                        continue;
                    }
                    for (final Path type : directories(day)) {
                        try {
                            folders.add(new TypeFolder(date, type.getFileName().toString()));
                        } catch (final IllegalArgumentException e) {
                            // Not a name FastMirror writes; leave it alone.
                        }
                    }
                }
            }
        }
        folders.sort(
                Comparator.comparing(TypeFolder::businessDate).thenComparing(TypeFolder::type));
        return folders;
    }

    @Override
    public FolderContent list(TypeFolder folder) throws IOException {
        final Path directory = folder.resolveIn(mirrorRoot);
        final List<PackagedFile> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                if (name.endsWith(MirrorConfig.STAGING_SUFFIX)) {
                    continue; // the copier is still writing it
                }
                final BasicFileAttributes attributes;
                try {
                    attributes =
                            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (final NoSuchFileException e) {
                    continue; // gone since the listing
                }
                if (attributes.isRegularFile()) {
                    files.add(
                            new PackagedFile(
                                    folder.folderPath() + "/" + name,
                                    attributes.size(),
                                    attributes.lastModifiedTime().toInstant()));
                }
            }
        }
        return new FolderContent(folder, files);
    }

    @Override
    public PackageManifest build(FolderContent content, int version, Instant createdAt)
            throws IOException {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        final TypeFolder folder = content.folder();
        final Path target = outboxZip(folder, version);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The outbox already holds " + target);
        }

        final Path part = config.stagingFolder().resolve(target.getFileName() + PART_SUFFIX);
        Files.deleteIfExists(part);
        try {
            final PackageManifest manifest = write(part, content, version, createdAt);
            if (!matches(part, manifest)) {
                throw new IOException("The zip read back does not match its manifest: " + part);
            }
            Files.createDirectories(target.getParent());
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            return manifest;
        } catch (final IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(part);
            } catch (final IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    @Override
    public Path outboxZip(TypeFolder folder, int version) {
        return folder.resolveIn(config.storeOutbox()).resolve(folder.zipName(config.storeFolder(), version));
    }

    @Override
    public Optional<PackageManifest> verify(Path zip) {
        Objects.requireNonNull(zip, "zip");
        try {
            final Contents contents = read(zip);
            final Optional<PackageManifest> manifest = contents.manifest();
            if (manifest.isEmpty()
                    || !manifest.get().store().equals(config.storeFolder())
                    || !zip.getFileName().toString().equals(
                            manifest.get().folder().zipName(config.storeFolder(), manifest.get().version()))
                    || !contents.agrees(manifest.get())) {
                return Optional.empty();
            }
            return manifest;
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void clearStaging() throws IOException {
        try (DirectoryStream<Path> entries =
                Files.newDirectoryStream(config.stagingFolder(), "*" + PART_SUFFIX)) {
            for (final Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }

    // ---------------------------------------------------------------- writing

    private PackageManifest write(Path part, FolderContent content, int version, Instant createdAt)
            throws IOException {
        final Path directory = content.folder().resolveIn(mirrorRoot);
        final List<PackageManifest.Entry> entries = new ArrayList<>(content.files().size());
        final byte[] buffer = new byte[BUFFER];
        try (OutputStream file =
                        Files.newOutputStream(
                                part,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.SYNC);
                ZipOutputStream zip =
                        new ZipOutputStream(new BufferedOutputStream(file, BUFFER), StandardCharsets.UTF_8)) {
            for (final PackagedFile packaged : content.files()) {
                final Path source = directory.resolve(packaged.name());
                zip.putNextEntry(entry(packaged.path(), packaged.modified()));
                final MessageDigest digest = FolderContent.sha256();
                long copied = 0L;
                try (InputStream in = Files.newInputStream(source)) {
                    for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                        digest.update(buffer, 0, read);
                        zip.write(buffer, 0, read);
                        copied += read;
                    }
                }
                zip.closeEntry();
                // A file replaced or grown since the listing would make the manifest wrong.
                if (copied != packaged.size()
                        || !Files.getLastModifiedTime(source).toInstant().equals(packaged.modified())) {
                    throw new IOException("Changed while being zipped: " + source);
                }
                entries.add(new PackageManifest.Entry(packaged, HexFormat.of().formatHex(digest.digest())));
            }
            final PackageManifest manifest =
                    new PackageManifest(
                            config.storeFolder(), content.folder(), version, createdAt, agentVersion, entries);
            zip.putNextEntry(entry(PackageManifest.ENTRY_NAME, createdAt));
            zip.write(manifest.toJson().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            return manifest;
        }
    }

    /** An entry whose stored time is the UTC wall time, the same on every PC whatever its zone. */
    private static ZipEntry entry(String name, Instant time) {
        final ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(LocalDateTime.ofInstant(time, ZoneOffset.UTC));
        return entry;
    }

    // ---------------------------------------------------------------- reading back

    /**
     * @param hashes   every entry except the manifest: name to SHA-256
     * @param sizes    the same: name to size
     * @param manifest the parsed {@code manifest.json}, if present and readable
     */
    private record Contents(
            Map<String, String> hashes, Map<String, Long> sizes, Optional<PackageManifest> manifest) {

        boolean agrees(PackageManifest expected) {
            if (hashes.size() != expected.files().size()) {
                return false;
            }
            for (final PackageManifest.Entry entry : expected.files()) {
                final String path = entry.file().path();
                if (!entry.sha256().equals(hashes.get(path))
                        || !Long.valueOf(entry.file().size()).equals(sizes.get(path))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean matches(Path zip, PackageManifest expected) throws IOException {
        final Contents contents = read(zip);
        return contents.manifest().isPresent()
                && contents.manifest().get().equals(expected)
                && contents.agrees(expected);
    }

    private static Contents read(Path zip) throws IOException {
        final Map<String, String> hashes = new HashMap<>();
        final Map<String, Long> sizes = new HashMap<>();
        Optional<PackageManifest> manifest = Optional.empty();
        final byte[] buffer = new byte[BUFFER];
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().equals(PackageManifest.ENTRY_NAME)) {
                    final ByteArrayOutputStream json = new ByteArrayOutputStream();
                    in.transferTo(json);
                    manifest = PackageManifest.parse(json.toString(StandardCharsets.UTF_8));
                    continue;
                }
                final MessageDigest digest = FolderContent.sha256();
                long size = 0L;
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
                hashes.put(entry.getName(), HexFormat.of().formatHex(digest.digest()));
                sizes.put(entry.getName(), size);
            }
        }
        return new Contents(hashes, sizes, manifest);
    }

    // ---------------------------------------------------------------- mirror layout

    private static List<Path> directories(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        final List<Path> directories = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent, Files::isDirectory)) {
            entries.forEach(directories::add);
        }
        return directories;
    }

    /** The date of {@code <yyyy>/<month>/<yyyy-MM-dd>}, or {@code null} if it is not such a folder. */
    private static LocalDate dayFolderDate(Path year, Path month, Path day) {
        try {
            final LocalDate date = LocalDate.parse(day.getFileName().toString());
            final boolean consistent =
                    String.valueOf(date.getYear()).equals(year.getFileName().toString())
                            && TypeFolder.monthName(date).equals(month.getFileName().toString());
            return consistent ? date : null;
        } catch (final DateTimeException e) {
            return null;
        }
    }
}
