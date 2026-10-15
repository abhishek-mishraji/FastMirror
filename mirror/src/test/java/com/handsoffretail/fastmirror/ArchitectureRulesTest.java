package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.support.ClassFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AT-01 to AT-04 — FastMirror's read-only and structure rules, enforced against the compiled bytecode.
 *
 * <p>AT-02 and AT-03 are the ones that matter. They forbid a short list of ordinary, idiomatic
 * Java calls because each of them can make the producer's own file operations fail on Windows. A
 * prohibition that lives only in a document survives until the next person writes the obvious
 * three-line copy loop. These tests make the build fail instead.
 */
class ArchitectureRulesTest {

    private static final String ROOT_PACKAGE = "com.handsoffretail.fastmirror";
    private static final String COPIER = ROOT_PACKAGE + ".service.implementation.AtomicFileCopierImpl";

    private static List<ClassFile> classes;

    @BeforeAll
    static void loadCompiledClasses() throws IOException {
        final Path classesDirectory = Path.of("build", "classes", "java", "main");
        assertTrue(
                Files.isDirectory(classesDirectory),
                "compiled classes not found at " + classesDirectory.toAbsolutePath()
                        + " - run the tests through Gradle so main is compiled first");

        try (Stream<Path> files = Files.walk(classesDirectory)) {
            classes =
                    files.filter(path -> path.toString().endsWith(".class"))
                            .map(
                                    path -> {
                                        try {
                                            return ClassFile.read(path);
                                        } catch (final IOException e) {
                                            throw new UncheckedIOException(e);
                                        }
                                    })
                            .toList();
        }

        assertTrue(classes.size() > 20, "expected the whole agent to be compiled, found " + classes.size());
    }

    // ------------------------------------------------------------------ AT-01 / AT-04 structure

    @Test
    @DisplayName("AT-01: every *Impl class lives in an implementation package")
    void at01_implClassesLiveInImplementationPackages() {
        final List<String> offenders = new ArrayList<>();
        for (final ClassFile type : classes) {
            if (type.isNested()) {
                continue;
            }
            if (type.simpleName().endsWith("Impl")
                    && !type.packageName().endsWith(".implementation")) {
                offenders.add(type.binaryName() + " is named *Impl but is not in an implementation package");
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    @DisplayName("AT-01b: every class in an implementation package is named *Impl")
    void at01b_implementationPackagesHoldOnlyImpls() {
        final List<String> offenders = new ArrayList<>();
        for (final ClassFile type : classes) {
            if (type.isNested()) {
                continue;
            }
            if (type.packageName().endsWith(".implementation")
                    && !type.simpleName().endsWith("Impl")) {
                offenders.add(type.binaryName() + " is in an implementation package but is not named *Impl");
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    @DisplayName("AT-04: Application exposes only main, start and private wiring helpers")
    void at04_applicationIsWiringOnly() {
        final ClassFile application = find(ROOT_PACKAGE + ".Application");

        final List<String> unexpected =
                application.methods().stream()
                        .filter(method -> method.visibleOutside() && !method.synthetic())
                        .map(ClassFile.Method::name)
                        .filter(name -> !name.equals("main"))
                        .filter(name -> !name.equals("start"))
                        .filter(name -> !name.equals("<init>"))
                        .filter(name -> !name.equals("<clinit>"))
                        .toList();

        assertEquals(
                List.of(),
                unexpected,
                "Application is the composition root: anything beyond wiring belongs in a service");
    }

    // ------------------------------------------------------------------ AT-02 source access

    @Test
    @DisplayName("AT-02: only AtomicFileCopierImpl may open a file")
    void at02_onlyTheCopierOpensFiles() {
        final List<String> bannedTypes =
                List.of(
                        "java.nio.channels.FileChannel",
                        "java.io.FileInputStream",
                        "java.io.FileOutputStream",
                        "java.io.RandomAccessFile");

        final List<String> offenders = new ArrayList<>();
        for (final ClassFile type : classes) {
            if (owningClassOf(type).equals(COPIER)) {
                continue;
            }
            for (final String banned : bannedTypes) {
                if (type.referencedTypes().contains(banned)) {
                    offenders.add(type.binaryName() + " references " + banned);
                }
            }
            if (type.references("java.nio.file.Files", "newByteChannel")) {
                offenders.add(type.binaryName() + " calls Files.newByteChannel");
            }
        }

        assertEquals(
                List.of(),
                offenders,
                "All source-file access must stay in AtomicFileCopierImpl, where the read-only"
                        + " rules are reviewable in one place");
    }

    @Test
    @DisplayName("AT-02b: nothing anywhere calls Files.copy")
    void at02b_nobodyCallsFilesCopy() {
        final List<String> offenders =
                classes.stream()
                        .filter(type -> type.references("java.nio.file.Files", "copy"))
                        .map(ClassFile::binaryName)
                        .toList();

        assertEquals(
                List.of(),
                offenders,
                "Files.copy delegates to the Win32 copy API, whose source share mode is not ours"
                        + " to choose");
    }

    // ------------------------------------------------------------------ AT-03 mapping and locking

    @Test
    @DisplayName("AT-03: nothing memory-maps or locks a file")
    void at03_noMappingAndNoLocking() {
        final List<String> offenders = new ArrayList<>();

        for (final ClassFile type : classes) {
            if (type.referencedTypes().contains("java.nio.MappedByteBuffer")) {
                offenders.add(type.binaryName() + " references MappedByteBuffer");
            }
            for (final String forbidden : List.of("map", "transferTo", "transferFrom", "lock", "tryLock")) {
                // Owner-qualified on purpose: Stream.map and Optional.map are perfectly fine, and a
                // rule that could not tell them apart would be ignored within a week.
                if (type.references("java.nio.channels.FileChannel", forbidden)) {
                    offenders.add(type.binaryName() + " calls FileChannel." + forbidden);
                }
                if (type.references("java.nio.channels.FileChannelImpl", forbidden)) {
                    offenders.add(type.binaryName() + " calls FileChannelImpl." + forbidden);
                }
            }
        }

        assertEquals(
                List.of(),
                offenders,
                "Mapping is a version-dependent hazard to the producer's delete and an"
                        + " OS-level lock blocks the producer outright");
    }

    @Test
    @DisplayName("AT-03b: the copier opens the source with READ and no other option")
    void at03b_copierUsesReadOnly() {
        final ClassFile copier = find(COPIER);

        assertTrue(
                copier.referencedMembers().contains("java.nio.file.StandardOpenOption#READ"),
                "the sanctioned opener must use StandardOpenOption.READ");

        // Note on scope: this class legitimately references write options too, because it writes
        // its own staging file inside the mirror. Bytecode analysis cannot tell which open they
        // belong to, so "no write intent on the SOURCE open" is enforced by keeping all source
        // access in one small reviewable method, and verified behaviourally by
        // ProducerNonInterferenceTest - if a write option ever reached the source open, the
        // producer's own delete or write would start failing and those tests would go red.
    }

    // ------------------------------------------------------------------ AT-05 packaging

    @Test
    @DisplayName("AT-05: packaging cannot reach the POS folders: it knows only the mirror and outbox")
    void at05_packagingNeverTouchesTheSource() {
        final List<String> packaging =
                List.of(
                        ROOT_PACKAGE + ".service.DayPackager",
                        ROOT_PACKAGE + ".service.PackageLedger",
                        ROOT_PACKAGE + ".service.PackageScheduler",
                        ROOT_PACKAGE + ".service.implementation.ZipDayPackagerImpl",
                        ROOT_PACKAGE + ".service.implementation.DailyPackageSchedulerImpl",
                        ROOT_PACKAGE + ".service.implementation.FilePackageLedgerImpl",
                        ROOT_PACKAGE + ".config.PackageConfig",
                        ROOT_PACKAGE + ".entity.TypeFolder",
                        ROOT_PACKAGE + ".entity.PackagedFile",
                        ROOT_PACKAGE + ".entity.FolderContent",
                        ROOT_PACKAGE + ".entity.PackageManifest",
                        ROOT_PACKAGE + ".entity.PackageRecord");
        // The only ways to a POS folder: the configured source and extra folders, the path
        // mapper that resolves into them, and the copier that opens them.
        final List<String> bannedTypes =
                List.of(
                        ROOT_PACKAGE + ".service.PathMapper",
                        ROOT_PACKAGE + ".service.FileCopier",
                        ROOT_PACKAGE + ".service.ScanService",
                        ROOT_PACKAGE + ".service.implementation.GroupedPathMapperImpl",
                        COPIER);
        final List<String> bannedMembers =
                List.of("sourceFolder", "externalSubfolders", "includeSubfolders");

        final List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (final ClassFile type : classes) {
            if (!packaging.contains(owningClassOf(type))) {
                continue;
            }
            checked++;
            for (final String banned : bannedTypes) {
                if (type.referencedTypes().contains(banned)) {
                    offenders.add(type.binaryName() + " references " + banned);
                }
            }
            for (final String member : bannedMembers) {
                if (type.references(ROOT_PACKAGE + ".config.MirrorConfig", member)) {
                    offenders.add(type.binaryName() + " reads MirrorConfig." + member);
                }
            }
        }

        assertTrue(checked >= packaging.size(), "every packaging class must be compiled and checked");
        assertEquals(List.of(), offenders, "Packaging reads the mirror only, never the POS folders");
    }

    // ------------------------------------------------------------------ helpers

    /** Nested classes and lambdas are governed by the rules of the class that owns them. */
    private static String owningClassOf(ClassFile type) {
        final int nested = type.binaryName().indexOf('$');
        return nested < 0 ? type.binaryName() : type.binaryName().substring(0, nested);
    }

    private static ClassFile find(String binaryName) {
        return classes.stream()
                .filter(type -> type.binaryName().equals(binaryName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("class not compiled: " + binaryName));
    }
}
