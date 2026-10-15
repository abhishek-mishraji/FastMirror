package com.handsoffretail.fastmirror.entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * What a type folder holds at one moment, sorted by path: the input of one zip.
 *
 * @param folder the type folder
 * @param files  its files, sorted by path
 */
public record FolderContent(TypeFolder folder, List<PackagedFile> files) {

    public FolderContent {
        Objects.requireNonNull(folder, "folder");
        files = Objects.requireNonNull(files, "files").stream()
                .sorted(Comparator.comparing(PackagedFile::path))
                .toList();
    }

    /**
     * A short identity of the folder's content: SHA-256 of every file's path, size and modified time.
     * Equal fingerprints mean nothing was added, removed or replaced, so no new version is needed.
     * Built from attributes only, so checking a folder never reads its files.
     */
    public String fingerprint() {
        return fingerprint(files);
    }

    public static String fingerprint(List<PackagedFile> files) {
        final MessageDigest digest = sha256();
        files.stream()
                .sorted(Comparator.comparing(PackagedFile::path))
                .forEach(
                        file ->
                                digest.update(
                                        (file.path() + "\t" + file.size() + "\t"
                                                        + file.modified().toEpochMilli() + "\n")
                                                .getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
