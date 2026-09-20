package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.service.PathMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelativePathMapperImplTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;

    private PathMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new RelativePathMapperImpl(sourceRoot, mirrorRoot);
    }

    @Test
    @DisplayName("UT-01: a nested source file maps to the same relative path under the mirror root")
    void ut01_mapsNestedPathPreservingStructure() {
        final Path sourceFile = sourceRoot.resolve("a").resolve("b").resolve("c.xml");

        final Path relative = mapper.relativize(sourceFile);
        final Path target = mapper.toTarget(relative);

        assertEquals(Path.of("a", "b", "c.xml"), relative);
        assertEquals(mirrorRoot.resolve("a").resolve("b").resolve("c.xml"), target);
    }

    @Test
    @DisplayName("UT-02: a path outside the source root is rejected")
    void ut02_rejectsPathOutsideSourceRoot() {
        final Path outside = mirrorRoot.resolve("intruder.xml");

        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> mapper.relativize(outside));

        assertTrue(
                thrown.getMessage().contains("outside the source root"),
                "message should name the problem, was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("UT-02b: the source root itself is not a mappable file")
    void ut02b_rejectsSourceRootItself() {
        assertThrows(IllegalArgumentException.class, () -> mapper.relativize(sourceRoot));
    }

    @Test
    @DisplayName("UT-02c: a relative path that escapes the mirror root is rejected")
    void ut02c_rejectsTraversalEscape() {
        final Path escaping = Path.of("..", "..", "elsewhere.xml");

        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> mapper.toTarget(escaping));

        assertTrue(
                thrown.getMessage().contains("escapes the mirror root"),
                "message should name the problem, was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("UT-02d: an absolute path is not a valid relative path")
    void ut02d_rejectsAbsoluteRelative() {
        assertThrows(IllegalArgumentException.class, () -> mapper.toTarget(mirrorRoot.resolve("x.xml")));
    }

    @Test
    @DisplayName("relativize and toSource are inverses")
    void roundTripsThroughSource() {
        final Path sourceFile = sourceRoot.resolve("store001").resolve("PJR9.xml");

        assertEquals(sourceFile, mapper.toSource(mapper.relativize(sourceFile)));
    }

    @Test
    @DisplayName("roots are exposed absolute and normalized")
    void exposesNormalizedRoots() {
        final PathMapper unnormalized =
                new RelativePathMapperImpl(sourceRoot.resolve("sub").resolve(".."), mirrorRoot);

        assertEquals(sourceRoot, unnormalized.sourceRoot());
        assertEquals(mirrorRoot, unnormalized.mirrorRoot());
    }
}
