package com.beautica.ci;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory source tree for selector tests: builds {@code path -> body} maps from FQNs. */
final class SourceTreeFixture {

    private final Map<String, String> files = new LinkedHashMap<>();

    static String mainPath(String fqn) {
        return SourceFile.MAIN_ROOT + fqn.replace('.', '/') + ".java";
    }

    static String testPath(String fqn) {
        return SourceFile.TEST_ROOT + fqn.replace('.', '/') + ".java";
    }

    /** A main class; {@code refs} are free text appended to the body (e.g. {@code "private Foo foo;"}). */
    SourceTreeFixture main(String fqn, List<String> imports, String refs) {
        files.put(mainPath(fqn), source(fqn, imports, "", "class", refs));
        return this;
    }

    SourceTreeFixture main(String fqn) {
        return main(fqn, List.of(), "");
    }

    /** A concrete test class with one {@code @Test} method. */
    SourceTreeFixture test(String fqn, List<String> imports, String classAnnotations, String refs) {
        files.put(testPath(fqn), source(fqn, imports, classAnnotations, "class", "@Test void t() { }\n" + refs));
        return this;
    }

    SourceTreeFixture test(String fqn) {
        return test(fqn, List.of(), "", "");
    }

    /** A test class with a custom header: {@code extendsClause} (e.g. {@code "extends Base"}) and body text verbatim. */
    SourceTreeFixture subclassTest(String fqn, List<String> imports, String extendsClause, String body) {
        int dot = fqn.lastIndexOf('.');
        StringBuilder sb = new StringBuilder("package ").append(fqn, 0, dot).append(";\n");
        imports.forEach(i -> sb.append("import ").append(i).append(";\n"));
        sb.append("class ").append(fqn.substring(dot + 1)).append(' ').append(extendsClause).append(" {\n").append(body).append("\n}\n");
        files.put(testPath(fqn), sb.toString());
        return this;
    }

    SourceTreeFixture abstractTest(String fqn, List<String> imports, String refs) {
        files.put(testPath(fqn), source(fqn, imports, "", "abstract class", refs));
        return this;
    }

    /** {@code n} mutually unrelated test classes, so ratios against the whole suite are predictable. */
    SourceTreeFixture pad(int n) {
        for (int i = 0; i < n; i++) {
            test("com.beautica.pad" + i + ".Pad" + i + "Test");
        }
        return this;
    }

    Map<String, String> files() {
        return files;
    }

    SourceGraph graph() {
        return SourceGraph.of(files);
    }

    private static String source(String fqn, List<String> imports, String annotations, String kind, String body) {
        int dot = fqn.lastIndexOf('.');
        StringBuilder sb = new StringBuilder("package ").append(fqn, 0, dot).append(";\n");
        imports.forEach(i -> sb.append("import ").append(i).append(";\n"));
        sb.append(annotations).append("\npublic ").append(kind).append(' ').append(fqn.substring(dot + 1))
            .append(" {\n").append(body).append("\n}\n");
        return sb.toString();
    }
}
