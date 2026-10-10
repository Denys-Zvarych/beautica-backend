package com.beautica.ci;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which test classes can observe a change (phase 358), and splits test lists into shards
 * (phase 360). Deterministic, JDK-only, source-level: it runs before any consumer job compiles.
 *
 * <pre>
 * --root DIR            backend project root (default .)
 * --changes FILE        `git diff --name-status -M` output ('-' = stdin)
 * --deleted-content DIR base-revision copies of deleted files, same relative paths (optional)
 * --all                 emit every concrete test class instead of computing a selection
 * --list FILE           emit the FQNs listed in FILE instead of computing a selection
 * --shard I/N           keep only shard I of N of the emitted list
 * --shards N --out-dir D  write selected-tests.txt and shard-0..N-1.txt into D (weight-balanced, see TestSharder)
 * --exclude PREFIX      drop FQNs starting with PREFIX from every emitted list (repeatable)
 * --out FILE            write the (sharded) FQN list to FILE
 * </pre>
 * stdout: {@code MODE=full|selective|none}, {@code REASON=}, {@code COUNT=}, {@code TOTAL=}
 * (and {@code SHARD_WEIGHTS=} when {@code --out-dir} is given).
 */
public final class AffectedTestSelector {

    private static final Pattern WEB_MVC_TEST = Pattern.compile("@WebMvcTest\\b(\\s*\\(([^)]*)\\))?");
    private static final Pattern CLASS_LITERAL = Pattern.compile("(\\w+)\\.class");
    private static final Pattern CONTROLLER_MARKER = Pattern.compile("@(?:Rest)?(?:Controller|ControllerAdvice)\\b");

    private final SourceGraph graph;
    private final Function<String, Optional<String>> deletedContent;

    AffectedTestSelector(SourceGraph graph, Function<String, Optional<String>> deletedContent) {
        this.graph = graph;
        this.deletedContent = deletedContent;
    }

    Selection select(List<ChangedPath> changes) {
        int total = allTestClasses().size();
        Optional<Selection> forced = forcedFull(changes, total);
        if (forced.isPresent()) {
            return forced.get();
        }
        List<ChangedPath> javaChanges = changes.stream().filter(c -> SourceFile.isJavaSource(c.path())).toList();
        if (javaChanges.isEmpty()) {
            return Selection.none("no test-relevant path changed", total);
        }
        Set<String> affected = graph.reverseReachable(seeds(javaChanges), SelectorRules.MAX_REVERSE_HOPS);
        TreeSet<String> tests = new TreeSet<>();
        graph.files().stream()
            .filter(f -> affected.contains(f.path()) && graph.isTestClass(f.path()))
            .forEach(f -> tests.add(f.fqn()));
        tests.addAll(webMvcSlicesTouching(affected));
        tests.addAll(contextTestsOfChangedFeatures(javaChanges));
        if (tests.size() > SelectorRules.FULL_FALLBACK_RATIO * total) {
            return Selection.full("selection of " + tests.size() + "/" + total + " exceeds the fallback ratio", total);
        }
        return Selection.selective(tests.size() + "/" + total + " test classes reach the change", List.copyOf(tests), total);
    }

    /**
     * Relative runtime cost of one test class, used only to balance shards: Spring/Testcontainers
     * context tests ({@link SelectorRules#CONTEXT_TEST_MARKER}) and {@code *IT} classes are heavy,
     * {@code @WebMvcTest} slices medium, everything else light. A heuristic: it needs to rank, not measure.
     */
    int weightOf(String fqn) {
        SourceFile file = graph.fileByFqn(fqn);
        if (file == null) {
            return SelectorRules.LIGHT_TEST_WEIGHT;
        }
        if (file.simpleName().endsWith("IT") || SelectorRules.CONTEXT_TEST_MARKER.matcher(file.body()).find()) {
            return SelectorRules.HEAVY_TEST_WEIGHT;
        }
        return WEB_MVC_TEST.matcher(file.body()).find() ? SelectorRules.SLICE_TEST_WEIGHT : SelectorRules.LIGHT_TEST_WEIGHT;
    }

    List<String> allTestClasses() {
        return graph.files().stream().filter(f -> graph.isTestClass(f.path())).map(SourceFile::fqn).sorted().toList();
    }

    private Optional<Selection> forcedFull(List<ChangedPath> changes, int total) {
        for (ChangedPath change : changes) {
            String path = change.path();
            if (SelectorRules.matchesAny(SelectorRules.ALWAYS_FULL_GLOBS, path)) {
                return Optional.of(Selection.full("always-full path: " + path, total));
            }
            boolean java = SourceFile.isJavaSource(path);
            if (!java && !SelectorRules.matchesAny(SelectorRules.NO_TEST_GLOBS, path)) {
                return Optional.of(Selection.full("unknown path: " + path, total));
            }
            if (java && path.startsWith(SourceFile.MAIN_ROOT)) {
                Optional<String> body = bodyOf(change);
                if (body.isEmpty()) {
                    return Optional.of(Selection.full("unreadable main file: " + path, total));
                }
                for (Pattern rule : SelectorRules.FULL_CONTENT_REGEXES) {
                    if (rule.matcher(body.get()).find()) {
                        return Optional.of(Selection.full("Spring-wired marker " + rule.pattern() + " in " + path, total));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> bodyOf(ChangedPath change) {
        SourceFile file = graph.file(change.path());
        if (file != null && !change.deleted()) {
            return Optional.of(file.codeWithoutComments());
        }
        return deletedContent.apply(change.path()).map(body -> SourceFile.parse(change.path(), body).codeWithoutComments());
    }

    /** A deleted or renamed-away file seeds every file of its package; a live file seeds itself. */
    private List<String> seeds(List<ChangedPath> javaChanges) {
        List<String> seeds = new ArrayList<>();
        for (ChangedPath change : javaChanges) {
            if (change.deleted() || graph.file(change.path()) == null) {
                graph.filesOfPackage(SourceFile.packageOf(change.path())).forEach(f -> seeds.add(f.path()));
            } else {
                seeds.add(change.path());
            }
        }
        return seeds;
    }

    /** Black-box context tests (see {@link SelectorRules#CONTEXT_TEST_MARKER}) of every feature that has a changed main file. */
    private Set<String> contextTestsOfChangedFeatures(List<ChangedPath> javaChanges) {
        Set<String> features = new HashSet<>();
        for (ChangedPath change : javaChanges) {
            if (change.path().startsWith(SourceFile.MAIN_ROOT)) {
                features.add(SourceFile.parse(change.path(), "").feature());
            }
        }
        features.remove("");
        Set<String> selected = new HashSet<>();
        for (SourceFile f : graph.files()) {
            if (graph.isTestClass(f.path()) && features.contains(f.feature()) && SelectorRules.CONTEXT_TEST_MARKER.matcher(f.body()).find()) {
                selected.add(f.fqn());
            }
        }
        return selected;
    }

    /** Slice-break trap: a {@code @WebMvcTest} slice is selected when any controller it loads is affected. */
    private Set<String> webMvcSlicesTouching(Set<String> affected) {
        Set<String> selected = new HashSet<>();
        Set<String> affectedSimpleNames = new HashSet<>();
        boolean controllerAffected = false;
        for (SourceFile f : graph.files()) {
            if (!f.test() && affected.contains(f.path())) {
                affectedSimpleNames.add(f.simpleName());
                controllerAffected |= CONTROLLER_MARKER.matcher(f.body()).find();
            }
        }
        for (SourceFile f : graph.files()) {
            Matcher slice = WEB_MVC_TEST.matcher(f.body());
            if (!graph.isTestClass(f.path()) || !slice.find()) {
                continue;
            }
            Set<String> targets = new HashSet<>();
            Matcher literal = CLASS_LITERAL.matcher(Optional.ofNullable(slice.group(2)).orElse(""));
            while (literal.find()) {
                targets.add(literal.group(1));
            }
            boolean hit = targets.isEmpty() ? controllerAffected : targets.stream().anyMatch(affectedSimpleNames::contains);
            if (hit) {
                selected.add(f.fqn());
            }
        }
        return selected;
    }

    public static void main(String[] args) throws IOException {
        Cli cli = Cli.parse(args);
        SourceGraph graph = SourceGraph.scan(cli.root);
        Function<String, Optional<String>> deleted = path -> cli.deletedContent == null
            ? Optional.empty()
            : readIfExists(cli.deletedContent.resolve(path));
        AffectedTestSelector selector = new AffectedTestSelector(graph, deleted);
        Selection selection;
        if (cli.all) {
            selection = Selection.selective("every concrete test class", selector.allTestClasses(), selector.allTestClasses().size());
        } else if (cli.list != null) {
            List<String> listed = Files.readAllLines(cli.list).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            selection = Selection.selective("explicit list", listed, selector.allTestClasses().size());
        } else {
            String changes = cli.changes.equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(cli.changes));
            selection = selector.select(ChangedPath.parseNameStatus(changes));
        }
        emit(selection, cli, selector);
    }

    private static void emit(Selection selection, Cli cli, AffectedTestSelector selector) throws IOException {
        List<String> all = selection.tests().stream()
            .filter(fqn -> cli.exclude.stream().noneMatch(fqn::startsWith))
            .toList();
        List<String> tests = all;
        if (cli.shardIndex >= 0) {
            tests = TestSharder.split(all, selector::weightOf, cli.shardCount).get(cli.shardIndex);
        }
        String shardWeights = null;
        if (cli.out != null) {
            Files.createDirectories(cli.out.toAbsolutePath().getParent());
            Files.write(cli.out, tests);
        }
        if (cli.outDir != null) {
            Files.createDirectories(cli.outDir);
            Files.write(cli.outDir.resolve("selected-tests.txt"), all);
            List<List<String>> shards = TestSharder.split(all, selector::weightOf, cli.shards);
            for (int i = 0; i < shards.size(); i++) {
                Files.write(cli.outDir.resolve("shard-" + i + ".txt"), shards.get(i));
            }
            shardWeights = TestSharder.weights(shards, selector::weightOf).stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        System.out.println("MODE=" + selection.mode().name().toLowerCase());
        System.out.println("REASON=" + selection.reason().replace('\n', ' '));
        System.out.println("COUNT=" + tests.size());
        System.out.println("TOTAL=" + selection.totalTestClasses());
        if (shardWeights != null) {
            System.out.println("SHARD_WEIGHTS=" + shardWeights);
        }
    }

    private static Optional<String> readIfExists(Path file) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static final class Cli {
        Path root = Path.of(".");
        String changes = "-";
        Path deletedContent;
        boolean all;
        Path list;
        int shardIndex = -1;
        int shardCount = 1;
        int shards = 1;
        Path outDir;
        Path out;
        final List<String> exclude = new ArrayList<>();

        static Cli parse(String[] args) {
            Cli cli = new Cli();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--root" -> cli.root = Path.of(args[++i]);
                    case "--changes" -> cli.changes = args[++i];
                    case "--deleted-content" -> cli.deletedContent = Path.of(args[++i]);
                    case "--all" -> cli.all = true;
                    case "--list" -> cli.list = Path.of(args[++i]);
                    case "--shard" -> {
                        String[] parts = args[++i].split("/");
                        cli.shardIndex = Integer.parseInt(parts[0]);
                        cli.shardCount = Integer.parseInt(parts[1]);
                        if (cli.shardIndex < 0 || cli.shardIndex >= cli.shardCount) {
                            throw new IllegalArgumentException("--shard I/N needs 0 <= I < N");
                        }
                    }
                    case "--shards" -> cli.shards = Integer.parseInt(args[++i]);
                    case "--out-dir" -> cli.outDir = Path.of(args[++i]);
                    case "--out" -> cli.out = Path.of(args[++i]);
                    case "--exclude" -> cli.exclude.add(args[++i]);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            return cli;
        }
    }
}
