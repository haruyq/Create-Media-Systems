package org.haruyq.createmediasystems;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Holds CMS to what other mods were built against, read from the class files as the JVM links them.
 *
 * <p>api-surface.txt is every public or protected member under the api package, one "owner [static|abstract] name
 * descriptor" a line: none may go (a mod built on it would fail with NoSuchMethodError), and a new one is recorded
 * there, so each change to the API shows in a commit. Re-record with {@code ./gradlew test -PrecordApi}; a new
 * abstract method in an interface other mods implement breaks them, so belongs in a default method instead.
 *
 * <p>legacy-links.txt is what released add-ons link to outside the api package, kept until 2.0: members as
 * "owner name descriptor", classes as "class owner", what an add-on implements as "implements owner" (none of whose
 * abstract methods may go unimplemented), what it overrides as "override owner name descriptor", and by hand what
 * its mixins need: a method they target by name alone as "mixin owner name" (exactly one so named) and a field they
 * shadow as "shadow owner name descriptor". Recorded from add-on jars, keeping the hand-written lines, with
 * {@code ./gradlew test -PlegacyJars=a.jar,b.jar}.
 */
class ApiCompatibilityTest {
    /** The project's directory: NeoForge's unit tests run elsewhere. */
    private static final Path PROJECT = Path.of(System.getProperty("api.project", "."));
    private static final Path CLASSES = PROJECT.resolve("build/classes/java/main");
    private static final Path SURFACE = PROJECT.resolve("src/test/resources/api/api-surface.txt");
    private static final Path LEGACY = PROJECT.resolve("src/test/resources/api/legacy-links.txt");
    private static final String CMS = "org/haruyq/createmediasystems/";
    private static final String API = CMS + "api/";

    /** A class as the JVM links against it. */
    record Info(String name, int access, String superName, List<String> interfaces, Set<String> members,
                Set<String> abstracts, List<String> methodNames) {
    }

    static Info info(String name) {
        Path file = CLASSES.resolve(name + ".class");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(Files.readAllBytes(file));
            Set<String> members = new TreeSet<>();
            Set<String> abstracts = new TreeSet<>();
            List<String> methodNames = new ArrayList<>();
            int[] access = new int[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int flags, String n, String signature, String superName, String[] interfaces) {
                    access[0] = flags;
                }

                @Override
                public FieldVisitor visitField(int flags, String n, String descriptor, String signature, Object value) {
                    members.add(member(flags, n, descriptor));
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int flags, String n, String descriptor, String signature, String[] exceptions) {
                    if ((flags & Opcodes.ACC_SYNTHETIC) == 0) {
                        members.add(member(flags, n, descriptor));
                        methodNames.add(n);
                        if ((flags & Opcodes.ACC_ABSTRACT) != 0) {
                            abstracts.add(n + " " + descriptor);
                        }
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            return new Info(name, access[0], reader.getSuperName(), List.of(reader.getInterfaces()), members, abstracts,
                    methodNames);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "[visibility] [static|abstract] name descriptor": what linking a member depends on. */
    private static String member(int flags, String name, String descriptor) {
        String visibility = (flags & Opcodes.ACC_PUBLIC) != 0 ? "public" : (flags & Opcodes.ACC_PROTECTED) != 0 ? "protected"
                : (flags & Opcodes.ACC_PRIVATE) != 0 ? "private" : "package";
        String kind = (flags & Opcodes.ACC_STATIC) != 0 ? "static " : (flags & Opcodes.ACC_ABSTRACT) != 0 ? "abstract " : "";
        return visibility + " " + kind + name + " " + descriptor;
    }

    /** The CMS classes {@code name} links through: itself, then its superclasses' and interfaces', while they are CMS's. */
    static List<Info> chain(String name) {
        List<Info> found = new ArrayList<>();
        List<String> next = new ArrayList<>(List.of(name));
        while (!next.isEmpty()) {
            String each = next.removeFirst();
            Info info = each.startsWith(CMS) ? info(each) : null;
            if (info != null && found.stream().noneMatch(i -> i.name.equals(each))) {
                found.add(info);
                if (info.superName != null) next.add(info.superName);
                next.addAll(info.interfaces);
            }
        }
        return found;
    }

    /** Whether linking {@code name descriptor} through {@code owner} still finds it among CMS's classes. */
    static boolean resolves(String owner, String name, String descriptor) {
        return chain(owner).stream().anyMatch(info -> info.members.stream()
                .anyMatch(member -> member.endsWith(" " + name + " " + descriptor)));
    }

    /** Every non-private member of the api package's public classes, as api-surface.txt holds it. */
    static Set<String> surface() throws IOException {
        Set<String> lines = new TreeSet<>();
        try (Stream<Path> files = Files.walk(CLASSES.resolve(API))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = CLASSES.relativize(file).toString().replace('\\', '/').replace(".class", "");
                Info info = info(name);
                if ((info.access & Opcodes.ACC_PUBLIC) == 0 || name.endsWith("package-info")) {
                    continue;
                }
                lines.add(name + " class " + (info.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM | Opcodes.ACC_FINAL)));
                for (String member : info.members) {
                    if (member.startsWith("public ") || member.startsWith("protected ")) {
                        lines.add(name + " " + member.substring(member.indexOf(' ') + 1));
                    }
                }
            }
        }
        return lines;
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    /** A line of api-surface.txt as linking needs it: whether a method is abstract or default doesn't matter. */
    private static String linked(String line) {
        return line.replace(" abstract ", " ");
    }

    @Test
    void recordedApiStillExists() throws IOException {
        Set<String> now = new TreeSet<>(surface().stream().map(ApiCompatibilityTest::linked).toList());
        List<String> gone = lines(SURFACE).stream().map(ApiCompatibilityTest::linked)
                .filter(line -> !line.contains(" class ") && !now.contains(line)).toList();
        assertEquals(List.of(), gone, "API members other mods link to are gone: keep them, forwarding to their replacements");
    }

    @Test
    void newApiIsRecorded() throws IOException {
        Set<String> now = surface();
        if (System.getProperty("api.record") != null) {
            Files.createDirectories(SURFACE.getParent());
            Files.write(SURFACE, now);
        }
        assertEquals(String.join("\n", lines(SURFACE)), String.join("\n", now),
                "The API changed: re-record it with ./gradlew test -PrecordApi and commit api-surface.txt with the change");
    }

    @Test
    void legacyLinksStillResolve() throws IOException {
        List<String> broken = new ArrayList<>();
        Set<String> overridden = new TreeSet<>();
        List<String> lines = lines(LEGACY);
        for (String line : lines) {
            String[] part = line.split(" ");
            if (part[0].equals("override")) overridden.add(part[2] + " " + part[3]);
        }
        for (String line : lines) {
            String[] part = line.split(" ");
            boolean ok = switch (part[0]) {
                case "class" -> info(part[1]) != null;
                case "mixin" -> info(part[1]) != null
                        && info(part[1]).methodNames.stream().filter(part[2]::equals).count() == 1;
                case "implements" -> chain(part[1]).stream().flatMap(info -> info.abstracts.stream())
                        .allMatch(method -> overridden.contains(method)
                                || chain(part[1]).stream().anyMatch(info -> info.members.contains("public " + method)));
                case "override", "shadow" -> resolves(part[1], part[2], part[3]);
                default -> resolves(part[0], part[1], part[2]);
            };
            if (!ok) broken.add(line);
        }
        assertEquals(List.of(), broken, "Released add-ons link to these: keep them until 2.0, forwarding if need be");
    }

    /** Writes legacy-links.txt from the add-on jars in -PlegacyJars, keeping its hand-written mixin lines. */
    @Test
    void recordLegacyLinks() throws IOException {
        String jars = System.getProperty("api.legacyJars");
        Assumptions.assumeTrue(jars != null && !jars.isBlank());
        Set<String> found = new TreeSet<>();
        for (String jar : jars.split(",")) {
            try (JarFile file = new JarFile(jar.strip())) {
                for (var entry : file.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                    new ClassReader(file.getInputStream(entry).readAllBytes()).accept(new Links(found), 0);
                }
            }
        }
        found.removeIf(line -> line.contains(" " + API) || line.startsWith(API));
        if (Files.isRegularFile(LEGACY)) {
            found.addAll(lines(LEGACY).stream().filter(l -> l.startsWith("mixin ") || l.startsWith("shadow ")).toList());
        }
        List<String> out = new ArrayList<>(List.of("# What released add-ons link to outside the api package (see ApiCompatibilityTest).",
                "# Recorded from: " + jars));
        out.addAll(found);
        Files.write(LEGACY, out);
    }

    /** Collects what a class links to in CMS: its references, and what it implements or overrides of CMS's types. */
    private static final class Links extends ClassVisitor {
        private final Set<String> found;
        private final List<String> parents = new ArrayList<>();

        Links(Set<String> found) {
            super(Opcodes.ASM9);
            this.found = found;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (superName != null) parents.add(superName);
            parents.addAll(List.of(interfaces));
            parents.stream().filter(p -> p.startsWith(CMS)).forEach(p -> found.add("implements " + p));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            type(Type.getType(descriptor));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0 && !name.equals("<init>")) {
                for (String parent : parents) {
                    if (parent.startsWith(CMS) && resolves(parent, name, descriptor)) {
                        found.add("override " + parent + " " + name + " " + descriptor);
                    }
                }
            }
            type(Type.getMethodType(descriptor));
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String n, String d, boolean isInterface) {
                    link(owner, n, d);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String n, String d) {
                    link(owner, n, d);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    type(Type.getObjectType(type));
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type type) type(type);
                }

                @Override
                public void visitInvokeDynamicInsn(String n, String d, Handle bootstrap, Object... arguments) {
                    String made = Type.getReturnType(d).getInternalName();
                    if (bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory") && made.startsWith(CMS)) {
                        // A lambda implementing a CMS interface: its one abstract method must stay the one it implements.
                        found.add("implements " + made);
                        found.add("override " + made + " " + n + " " + arguments[0]);
                    }
                    type(Type.getMethodType(d));
                    for (Object argument : arguments) {
                        if (argument instanceof Handle handle) link(handle.getOwner(), handle.getName(), handle.getDesc());
                    }
                }
            };
        }

        private void type(Type type) {
            switch (type.getSort()) {
                case Type.ARRAY -> type(type.getElementType());
                case Type.OBJECT -> {
                    if (type.getInternalName().startsWith(CMS)) found.add("class " + type.getInternalName());
                }
                case Type.METHOD -> {
                    type(type.getReturnType());
                    for (Type argument : type.getArgumentTypes()) type(argument);
                }
                default -> {
                }
            }
        }

        /** A member reference, unless it is one CMS's class inherits from Minecraft or the JDK. */
        private void link(String owner, String name, String descriptor) {
            if (owner.startsWith("[")) return;
            type(Type.getObjectType(owner));
            if (owner.startsWith(CMS) && (resolves(owner, name, descriptor) || !inheritedFromOutside(owner))) {
                found.add(owner + " " + name + " " + descriptor);
            }
        }

        private static boolean inheritedFromOutside(String owner) {
            return chain(owner).stream().anyMatch(info -> info.superName != null && !info.superName.startsWith(CMS)
                    && !info.superName.equals("java/lang/Object") && !info.superName.equals("java/lang/Record"));
        }
    }
}
