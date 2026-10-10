package com.beautica.ci;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One parsed {@code .java} file of the main or test source set. Immutable value. */
record SourceFile(String path, boolean test, String pkg, String simpleName, String body, List<String> imports) {

    static final String MAIN_ROOT = "src/main/java/";
    static final String TEST_ROOT = "src/test/java/";

    private static final Pattern IMPORT = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?(com\\.beautica\\.[\\w.]*?)(\\.\\*)?\\s*;", Pattern.MULTILINE);
    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
        "^(?:(?:public|final|abstract|sealed|non-sealed|strictfp)\\s+)*(?:class|interface|enum|record|@interface)\\s+(\\w+)",
        Pattern.MULTILINE);
    private static final Pattern TEST_ANNOTATION = Pattern.compile(
        "(?m)^\\s*@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|Nested)\\b");

    private static final Pattern NESTED = Pattern.compile("(?m)^\\s*@Nested\\b");

    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|^[ \\t]*//[^\\n]*", Pattern.DOTALL | Pattern.MULTILINE);

    static SourceFile parse(String path, String body) {
        boolean test = path.startsWith(TEST_ROOT);
        String root = test ? TEST_ROOT : MAIN_ROOT;
        String rel = path.substring(root.length());
        int slash = rel.lastIndexOf('/');
        String pkg = slash < 0 ? "" : rel.substring(0, slash).replace('/', '.');
        String simple = rel.substring(slash + 1, rel.length() - ".java".length());
        return new SourceFile(path, test, pkg, simple, body, parseImports(body));
    }

    static boolean isJavaSource(String path) {
        return path.endsWith(".java") && (path.startsWith(MAIN_ROOT) || path.startsWith(TEST_ROOT));
    }

    /** Packages of a path that may no longer exist on disk (deleted or renamed-away files). */
    static String packageOf(String path) {
        String root = path.startsWith(TEST_ROOT) ? TEST_ROOT : MAIN_ROOT;
        String rel = path.substring(root.length());
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash).replace('/', '.');
    }

    /** Names a same-package file may use to refer to this file: its own and any other top-level type declared in it. */
    java.util.Set<String> declaredTypeNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.add(simpleName);
        Matcher m = TOP_LEVEL_TYPE.matcher(body);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** First package segment under {@code com.beautica} ({@code booking} for {@code com.beautica.booking.service}); empty at the root. */
    String feature() {
        String prefix = "com.beautica.";
        if (!pkg.startsWith(prefix)) {
            return "";
        }
        int dot = pkg.indexOf('.', prefix.length());
        return dot < 0 ? pkg.substring(prefix.length()) : pkg.substring(prefix.length(), dot);
    }

    /** Body with block comments and whole-line comments removed: a javadoc that mentions {@code @Scheduled} wires nothing. */
    String codeWithoutComments() {
        return COMMENTS.matcher(body).replaceAll("");
    }

    String fqn() {
        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }

    boolean isAbstract() {
        return Pattern.compile("\\babstract\\s+(?:static\\s+)?class\\s+" + simpleName + "\\b").matcher(body).find();
    }

    /** A concrete JUnit test class: named like one or carrying test annotations, never abstract. */
    boolean isConcreteTestClass() {
        if (!test || isAbstract() || !Pattern.compile("\\bclass\\s+" + simpleName + "\\b").matcher(body).find()) {
            return false;
        }
        boolean named = simpleName.endsWith("Test") || simpleName.endsWith("IT") || simpleName.endsWith("Tests");
        return named || TEST_ANNOTATION.matcher(body).find();
    }

    /** True when the class declares a JUnit {@code @Nested} inner class (Gradle reports those under THIS class, not a subclass). */
    boolean declaresNested() {
        return NESTED.matcher(codeWithoutComments()).find();
    }

    /** The (possibly qualified) name after {@code extends} on the top-level class declaration, or null. */
    String superclassName() {
        Matcher m = Pattern.compile("\\bclass\\s+" + simpleName + "\\b(?:\\s*<[^{]*?>)?\\s+extends\\s+([\\w.]+)")
            .matcher(codeWithoutComments());
        return m.find() ? m.group(1) : null;
    }

    private static List<String> parseImports(String body) {
        Matcher m = IMPORT.matcher(body);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group(2) == null ? m.group(1) : m.group(1) + ".*");
        }
        return List.copyOf(out);
    }
}
