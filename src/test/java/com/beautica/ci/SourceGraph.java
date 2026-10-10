package com.beautica.ci;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Source-level dependency graph (phase 358 D1): import edges plus package-cohesion edges.
 *
 * <p>Cohesion: a same-package reference needs no import, so a file depends on a sibling of its
 * package when its body names one of the sibling's top-level types. A test file may depend on main
 * and test siblings, a main file only on main siblings. The literal "every file depends on every
 * file of its package" rule was measured first and selected 641 of 652 test classes for a three-file
 * service change (commit 7ac47870), so it always degenerated to a full run.
 */
final class SourceGraph {

    private final Map<String, SourceFile> files = new TreeMap<>();
    private final Map<String, String> pathByFqn = new HashMap<>();
    private final Map<String, List<SourceFile>> byPackage = new HashMap<>();
    private final Map<String, Set<String>> dependents = new HashMap<>();
    private final Set<String> testClassPaths = new HashSet<>();

    static SourceGraph scan(Path root) {
        SourceGraph graph = new SourceGraph();
        for (String sourceRoot : List.of(SourceFile.MAIN_ROOT, SourceFile.TEST_ROOT)) {
            Path dir = root.resolve(sourceRoot);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> graph.add(root, p));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        graph.link();
        return graph;
    }

    static SourceGraph of(Map<String, String> bodiesByPath) {
        SourceGraph graph = new SourceGraph();
        bodiesByPath.forEach((path, body) -> graph.register(SourceFile.parse(path, body)));
        graph.link();
        return graph;
    }

    Collection<SourceFile> files() {
        return files.values();
    }

    SourceFile file(String path) {
        return files.get(path);
    }

    /** The file declaring top-level type {@code fqn}, or null. */
    SourceFile fileByFqn(String fqn) {
        String path = pathByFqn.get(fqn);
        return path == null ? null : files.get(path);
    }

    List<SourceFile> filesOfPackage(String pkg) {
        return byPackage.getOrDefault(pkg, List.of());
    }

    /** Every file within {@code maxDepth} reverse hops of any seed, seeds included (hop 0). */
    Set<String> reverseReachable(Collection<String> seeds, int maxDepth) {
        Set<String> seen = new HashSet<>();
        List<String> frontier = new ArrayList<>();
        for (String seed : seeds) {
            if (files.containsKey(seed) && seen.add(seed)) {
                frontier.add(seed);
            }
        }
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String path : frontier) {
                for (String dependent : dependents.getOrDefault(path, Set.of())) {
                    if (seen.add(dependent)) {
                        next.add(dependent);
                    }
                }
            }
            frontier = next;
        }
        return seen;
    }

    private void add(Path root, Path file) {
        try {
            String rel = root.relativize(file).toString().replace('\\', '/');
            register(SourceFile.parse(rel, Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void register(SourceFile f) {
        files.put(f.path(), f);
        pathByFqn.put(f.fqn(), f.path());
        byPackage.computeIfAbsent(f.pkg(), k -> new java.util.ArrayList<>()).add(f);
    }

    /** True for concrete JUnit test classes; computed once per graph. */
    boolean isTestClass(String path) {
        return testClassPaths.contains(path);
    }

    private void link() {
        Map<String, Pattern> mentions = new HashMap<>();
        for (SourceFile f : files.values()) {
            if (f.isConcreteTestClass()) {
                testClassPaths.add(f.path());
            }
            mentions.put(f.path(), Pattern.compile("\\b(?:" + String.join("|", f.declaredTypeNames()) + ")\\b"));
        }
        for (SourceFile f : files.values()) {
            f.imports().forEach(imp -> resolve(imp).forEach(dep -> edge(f.path(), dep)));
            for (SourceFile sibling : filesOfPackage(f.pkg())) {
                if (sibling != f && (f.test() || !sibling.test()) && mentions.get(sibling.path()).matcher(f.body()).find()) {
                    edge(f.path(), sibling.path());
                }
            }
        }
    }

    /** Records that {@code from} depends on {@code to}. */
    private void edge(String from, String to) {
        if (!from.equals(to) && !(files.get(to).test() && !files.get(from).test())) {
            dependents.computeIfAbsent(to, k -> new HashSet<>()).add(from);
        }
    }

    /** Resolves an import (class, nested class, static member, or wildcard) to file paths. */
    private List<String> resolve(String imp) {
        boolean wildcard = imp.endsWith(".*");
        String name = wildcard ? imp.substring(0, imp.length() - 2) : imp;
        if (wildcard && byPackage.containsKey(name)) {
            return byPackage.get(name).stream().map(SourceFile::path).toList();
        }
        for (String candidate = name; candidate.contains("."); candidate = candidate.substring(0, candidate.lastIndexOf('.'))) {
            String path = pathByFqn.get(candidate);
            if (path != null) {
                return List.of(path);
            }
        }
        return List.of();
    }
}
