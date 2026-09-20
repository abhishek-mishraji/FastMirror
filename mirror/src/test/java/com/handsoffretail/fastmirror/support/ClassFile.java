package com.handsoffretail.fastmirror.support;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A minimal Java class-file reader, enough to answer "what does this class reference".
 *
 * <p>Written by hand because AR-8 permits no third-party library, not even in tests, and because
 * the rules of SRS 5.1 need enforcing mechanically rather than by review alone. A rule that is only
 * written down is a rule that gets broken during the next refactor.
 *
 * <p>Parses the constant pool precisely rather than scanning the file for strings. Precision
 * matters here: a text scan for {@code "map"} would match {@code Stream.map} and make the rule
 * useless, while a scan for {@code "lock"} would match any identifier containing it. Member
 * references are resolved to owner-plus-name pairs so a rule can name exactly the call it forbids.
 *
 * <p>See the JVM specification, chapter 4, for the structures decoded here.
 */
public final class ClassFile {

    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_INTEGER = 3;
    private static final int CONSTANT_FLOAT = 4;
    private static final int CONSTANT_LONG = 5;
    private static final int CONSTANT_DOUBLE = 6;
    private static final int CONSTANT_CLASS = 7;
    private static final int CONSTANT_STRING = 8;
    private static final int CONSTANT_FIELDREF = 9;
    private static final int CONSTANT_METHODREF = 10;
    private static final int CONSTANT_INTERFACE_METHODREF = 11;
    private static final int CONSTANT_NAME_AND_TYPE = 12;
    private static final int CONSTANT_METHOD_HANDLE = 15;
    private static final int CONSTANT_METHOD_TYPE = 16;
    private static final int CONSTANT_DYNAMIC = 17;
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;
    private static final int CONSTANT_MODULE = 19;
    private static final int CONSTANT_PACKAGE = 20;

    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_SYNTHETIC = 0x1000;

    /** A declared method: its name and whether it is visible outside the class. */
    public record Method(String name, boolean visibleOutside, boolean synthetic) {}

    private final String binaryName;
    private final Set<String> referencedTypes;
    private final Set<String> referencedMembers;
    private final List<Method> methods;

    private ClassFile(
            String binaryName,
            Set<String> referencedTypes,
            Set<String> referencedMembers,
            List<Method> methods) {
        this.binaryName = binaryName;
        this.referencedTypes = Collections.unmodifiableSet(referencedTypes);
        this.referencedMembers = Collections.unmodifiableSet(referencedMembers);
        this.methods = List.copyOf(methods);
    }

    /** The class's own name, e.g. {@code com.handsoffretail.fastmirror.Application}. */
    public String binaryName() {
        return binaryName;
    }

    public String simpleName() {
        final int lastDot = binaryName.lastIndexOf('.');
        return lastDot < 0 ? binaryName : binaryName.substring(lastDot + 1);
    }

    public String packageName() {
        final int lastDot = binaryName.lastIndexOf('.');
        return lastDot < 0 ? "" : binaryName.substring(0, lastDot);
    }

    /** Whether this is a nested or synthetic class such as {@code Outer$Inner} or a lambda. */
    public boolean isNested() {
        return simpleName().indexOf('$') >= 0;
    }

    /** Every type named in the constant pool, in binary form, e.g. {@code java.nio.file.Files}. */
    public Set<String> referencedTypes() {
        return referencedTypes;
    }

    /** Every field or method reference, as {@code owner#name}, e.g. {@code java.nio.file.Files#copy}. */
    public Set<String> referencedMembers() {
        return referencedMembers;
    }

    public List<Method> methods() {
        return methods;
    }

    /** Whether this class references {@code owner.name} anywhere. */
    public boolean references(String owner, String name) {
        return referencedMembers.contains(owner + "#" + name);
    }

    public static ClassFile read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
                DataInputStream data = new DataInputStream(new java.io.BufferedInputStream(in))) {
            return read(data);
        }
    }

    private static ClassFile read(DataInputStream data) throws IOException {
        if (data.readInt() != 0xCAFEBABE) {
            throw new IOException("Not a class file");
        }
        data.readUnsignedShort(); // minor
        data.readUnsignedShort(); // major

        final int poolCount = data.readUnsignedShort();
        final String[] utf8 = new String[poolCount];
        final int[] classNameIndex = new int[poolCount];
        final int[][] memberRef = new int[poolCount][]; // {classIndex, nameAndTypeIndex}
        final int[][] nameAndType = new int[poolCount][]; // {nameIndex, descriptorIndex}

        for (int index = 1; index < poolCount; index++) {
            final int tag = data.readUnsignedByte();
            switch (tag) {
                case CONSTANT_UTF8 -> utf8[index] = data.readUTF();
                case CONSTANT_INTEGER, CONSTANT_FLOAT -> data.readInt();
                case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                    data.readLong();
                    index++; // eight-byte constants occupy two pool slots
                }
                case CONSTANT_CLASS, CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE,
                        CONSTANT_PACKAGE -> {
                    final int nameIndex = data.readUnsignedShort();
                    if (tag == CONSTANT_CLASS) {
                        classNameIndex[index] = nameIndex;
                    }
                }
                case CONSTANT_FIELDREF, CONSTANT_METHODREF, CONSTANT_INTERFACE_METHODREF -> {
                    final int classIndex = data.readUnsignedShort();
                    final int nameAndTypeIndex = data.readUnsignedShort();
                    memberRef[index] = new int[] {classIndex, nameAndTypeIndex};
                }
                case CONSTANT_NAME_AND_TYPE -> {
                    final int nameIndex = data.readUnsignedShort();
                    final int descriptorIndex = data.readUnsignedShort();
                    nameAndType[index] = new int[] {nameIndex, descriptorIndex};
                }
                case CONSTANT_METHOD_HANDLE -> {
                    data.readUnsignedByte();
                    data.readUnsignedShort();
                }
                case CONSTANT_DYNAMIC, CONSTANT_INVOKE_DYNAMIC -> {
                    data.readUnsignedShort();
                    final int nameAndTypeIndex = data.readUnsignedShort();
                    // The bootstrap target is not a plain owner#name, so only the member name is
                    // recoverable here; it is recorded without an owner below.
                    memberRef[index] = new int[] {0, nameAndTypeIndex};
                }
                default -> throw new IOException("Unknown constant pool tag " + tag + " at " + index);
            }
        }

        final Set<String> types = new LinkedHashSet<>();
        for (int index = 1; index < poolCount; index++) {
            if (classNameIndex[index] != 0) {
                types.add(binary(utf8[classNameIndex[index]]));
            }
        }

        final Set<String> members = new LinkedHashSet<>();
        for (int index = 1; index < poolCount; index++) {
            final int[] reference = memberRef[index];
            if (reference == null) {
                continue;
            }
            final int[] signature = nameAndType[reference[1]];
            if (signature == null) {
                continue;
            }
            final String memberName = utf8[signature[0]];
            if (reference[0] == 0) {
                members.add("?#" + memberName);
                continue;
            }
            final String owner = binary(utf8[classNameIndex[reference[0]]]);
            members.add(owner + "#" + memberName);
        }

        data.readUnsignedShort(); // access_flags
        final int thisClass = data.readUnsignedShort();
        final String binaryName = binary(utf8[classNameIndex[thisClass]]);
        data.readUnsignedShort(); // super_class
        final int interfaceCount = data.readUnsignedShort();
        data.skipBytes(interfaceCount * 2);

        skipMembers(data); // fields
        final List<Method> methods = readMethods(data, utf8);

        return new ClassFile(binaryName, types, members, methods);
    }

    private static void skipMembers(DataInputStream data) throws IOException {
        final int count = data.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            data.readUnsignedShort(); // access_flags
            data.readUnsignedShort(); // name_index
            data.readUnsignedShort(); // descriptor_index
            skipAttributes(data);
        }
    }

    private static List<Method> readMethods(DataInputStream data, String[] utf8) throws IOException {
        final int count = data.readUnsignedShort();
        final List<Method> methods = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int accessFlags = data.readUnsignedShort();
            final int nameIndex = data.readUnsignedShort();
            data.readUnsignedShort(); // descriptor_index
            skipAttributes(data);
            methods.add(
                    new Method(
                            utf8[nameIndex],
                            (accessFlags & ACC_PRIVATE) == 0,
                            (accessFlags & ACC_SYNTHETIC) != 0));
        }
        return methods;
    }

    private static void skipAttributes(DataInputStream data) throws IOException {
        final int count = data.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            data.readUnsignedShort(); // attribute_name_index
            final int length = data.readInt();
            int remaining = length;
            while (remaining > 0) {
                final int skipped = data.skipBytes(remaining);
                if (skipped <= 0) {
                    throw new IOException("Truncated attribute");
                }
                remaining -= skipped;
            }
        }
    }

    /** {@code java/nio/file/Files} or {@code [Ljava/nio/file/Path;} to a readable binary name. */
    private static String binary(String internalName) {
        if (internalName == null) {
            return "";
        }
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        return name.replace('/', '.');
    }
}
