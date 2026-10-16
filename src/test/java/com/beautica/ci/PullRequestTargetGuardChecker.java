package com.beautica.ci;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.ScalarToken;
import org.yaml.snakeyaml.tokens.Token;

/**
 * Test-only checker that confines {@code pull_request_target} (and the other privileged triggers) to one
 * pinned workflow. Port of beautica-mobile/scripts/forbid_pull_request_target_outside_promotion.sh.
 *
 * <p>{@code pull_request_target} runs the BASE branch's workflow with a privileged token, so it is safe only
 * while nothing from the PR is fetched or executed. Layers:
 * <ol>
 *   <li>PRIMARY: SHA-256 of the raw bytes of {@code promotion-source.yml} must equal the pin (only when
 *       {@code checkPin}; structural tests pass {@code false}, the real test always passes {@code true}).</li>
 *   <li>Every top-level {@code *.yml}/{@code *.yaml} (case-insensitive) is parsed with SnakeYAML failing
 *       closed on duplicate keys, parse errors and multi-document files; symlinks are rejected; double-quoted
 *       scalars containing hex/unicode escapes (backslash x, u, U) or a trailing backslash are rejected from the
 *       raw token stream (they can hide a trigger name from grep).</li>
 *   <li>Denied triggers in every file; {@code pull_request_target} outside the allowed file.</li>
 *   <li>Exact structure of the allowed file plus raw-line backstops.</li>
 * </ol>
 * SnakeYAML resolves with YAML 1.1 rules: an unquoted {@code on} KEY becomes {@code Boolean.TRUE} (as do
 * {@code yes}/{@code true}); a quoted {@code "on"} stays a String. Both are treated as the trigger key.
 */
final class PullRequestTargetGuardChecker {

    static final String ALLOWED = "promotion-source.yml";
    static final String NAME_VALUE = "Promotion source (${{ github.event.pull_request.base.ref }})";
    static final String GROUP_VALUE = "promotion-source-${{ github.event.pull_request.number }}";
    static final String PRT = "pull_request_target";

    static final Set<String> DENIED = Set.of("workflow_run", "issue_comment", "pull_request_review_comment",
        "pull_request_review", "issues", "discussion", "discussion_comment", "gollum");

    static final Map<String, String> EXPECTED_ENV = Map.of(
        "BASE_REF", "${{ github.event.pull_request.base.ref }}",
        "HEAD_REF", "${{ github.event.pull_request.head.ref }}",
        "PR_TITLE", "${{ github.event.pull_request.title }}",
        "HEAD_REPO", "${{ github.event.pull_request.head.repo.full_name }}",
        "BASE_REPO", "${{ github.repository }}");

    /** The ONLY lines allowed to carry the expression opener: the 5 literal env lines, the job name, the group. */
    private static final Set<String> ALLOWED_EXPR_LINES = new LinkedHashSet<>();

    static {
        EXPECTED_ENV.forEach((k, v) -> ALLOWED_EXPR_LINES.add("          " + k + ": " + v));
        ALLOWED_EXPR_LINES.add("    name: " + NAME_VALUE);
        ALLOWED_EXPR_LINES.add("  group: " + GROUP_VALUE);
    }

    private static final Pattern PERM_KEY = Pattern.compile("^\\s*[\"']?permissions[\"']?\\s*:");
    private static final Pattern RAW_PRT = Pattern.compile("(^|[^A-Za-z0-9_])pull_request_target([^A-Za-z0-9_]|$)");
    private static final Pattern BAD_ESCAPE = Pattern.compile("\\\\[xuU]|\\\\[ \\t]*\\r?\\n");
    private static final int RE_FLAGS = Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final List<Pattern> RUN_DENY = Stream.of(
        "\\bcurl\\b", "\\bwget\\b", "\\bdocker\\b", "\\bnpm\\b", "\\bnpx\\b", "\\bpip3?\\b",
        "(^|[^\\w./-])(git|gh)[ \\t]", "pull/", "\\bsecrets\\b", "\\beval\\b", "\\bexec\\b",
        "(^|[;&|(])[ \\t]*(source|\\.)[ \\t]+\\S", "\\b(bash|sh|zsh)\\b[ \\t]+\\S",
        "\\bpython[0-9.]*\\b", "\\bperl\\b", "\\bruby\\b", "\\bnode\\b", "\\bphp\\b",
        "/dev/(tcp|udp)", "\\|[ \\t]*(ba|z|da)?sh\\b", "\\$\\(", "`",
        "\\bnc\\b", "\\bncat\\b", "\\bopenssl\\b", "/inet/", "\\bssh")
        .map(rx -> Pattern.compile(rx, RE_FLAGS)).toList();

    private PullRequestTargetGuardChecker() {
    }

    /** Checks a workflows directory; returns violations (empty = clean). Fails closed on anything odd. */
    static List<String> check(Path dir, boolean checkPin) {
        List<String> out = new ArrayList<>();
        List<Path> entries;
        try (Stream<Path> s = Files.list(dir)) {
            entries = s.filter(p -> isWorkflowName(p.getFileName().toString()))
                .sorted().collect(Collectors.toList());
        } catch (IOException e) {
            out.add(ALLOWED + ": workflows directory unreadable, failing closed");
            return out;
        }
        for (Path p : entries) {
            String name = p.getFileName().toString();
            if (Files.isSymbolicLink(p)) {
                out.add(name + ": is a symlink (workflow files must be regular files)");
            } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    out.addAll(checkFile(name, Files.readAllBytes(p), checkPin));
                } catch (IOException e) {
                    out.add(name + ": unreadable (" + e.getClass().getSimpleName() + ")");
                }
            }
        }
        if (!Files.isRegularFile(dir.resolve(ALLOWED), LinkOption.NOFOLLOW_LINKS)) {
            out.add(ALLOWED + ": missing (the guard workflow must exist)");
        }
        return out;
    }

    /** Checks one workflow file given its raw bytes. */
    static List<String> checkFile(String name, byte[] bytes, boolean checkPin) {
        List<String> out = new ArrayList<>();
        boolean allowed = name.equals(ALLOWED);
        if (allowed && checkPin) {
            String actual = sha256(bytes);
            if (!actual.equals(CiInfraCoverageTest.PROMOTION_SOURCE_SHA256)) {
                out.add(ALLOWED + ": " + ALLOWED + " changed; review it and update the pinned hash in "
                    + "CiInfraCoverageTest in the same PR (expected " + CiInfraCoverageTest.PROMOTION_SOURCE_SHA256
                    + ", got " + actual + ")");
            }
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            out.add(name + ": unreadable (not valid UTF-8)");
            return out;
        }
        if (!allowed) {
            for (String line : splitRawLines(text)) {
                if (!line.stripLeading().startsWith("#") && RAW_PRT.matcher(line).find()) {
                    out.add(name + ": uses pull_request_target (only " + ALLOWED + " may)");
                    break;
                }
            }
        }
        Parsed parsed = parse(name, text, out);
        if (parsed == null) {
            return out;
        }
        for (String t : new TreeSet<>(parsed.triggers)) {
            if (DENIED.contains(t)) {
                out.add(name + ": uses privileged trigger '" + t + "' (denied in every workflow)");
            }
        }
        if (!allowed && parsed.triggers.contains(PRT)) {
            out.add(name + ": uses pull_request_target (only " + ALLOWED + " may)");
        }
        if (allowed) {
            checkAllowed(name, text, parsed, out);
        }
        return out;
    }

    /** Splits like SnakeYAML's line breaks: CRLF, LF, CR, U+0085, U+2028, U+2029 (String.lines() misses the last three). */
    static List<String> splitRawLines(String text) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|[\\n\\r\\u0085\\u2028\\u2029]", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private record Parsed(Map<?, ?> doc, Set<String> triggers, Object on) {
    }

    private static Parsed parse(String f, String text, List<String> out) {
        List<Object> docs = new ArrayList<>();
        try {
            rejectBadEscapes(f, text, out);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            for (Object d : yaml.loadAll(new StringReader(text))) {
                docs.add(d);
            }
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage()).lines().map(String::strip).filter(l -> !l.isEmpty())
                .collect(Collectors.joining("; "));
            out.add(f + ": unparseable YAML, failing closed (" + msg + ")");
            return null;
        }
        if (docs.size() != 1 || docs.get(0) == null) {
            out.add(f + ": expected exactly one YAML document");
            return null;
        }
        if (!(docs.get(0) instanceof Map<?, ?> doc)) {
            out.add(f + ": workflow root is not a mapping");
            return null;
        }
        List<Object> keys = doc.keySet().stream()
            .filter(k -> Boolean.TRUE.equals(k) || "on".equals(k)).collect(Collectors.toList());
        if (keys.size() != 1) {
            out.add(f + ": expected exactly one 'on' key, found " + keys.size());
            return null;
        }
        for (Object k : doc.keySet()) {
            if (!(k instanceof String) && !Boolean.TRUE.equals(k)) {
                out.add(f + ": non-string top-level key (" + (k == null ? "null" : k.getClass().getSimpleName())
                    + "), failing closed (a tagged key can hide a trigger)");
                return null;
            }
        }
        Object on = doc.get(keys.get(0));
        Set<String> triggers = new LinkedHashSet<>();
        List<Object> raw = new ArrayList<>();
        if (on instanceof String s) {
            raw.add(s);
        } else if (on instanceof List<?> l) {
            raw.addAll(l);
        } else if (on instanceof Map<?, ?> m) {
            raw.addAll(m.keySet());
        } else {
            out.add(f + ": 'on' is neither string, list nor map");
            return null;
        }
        for (Object t : raw) {
            if (!(t instanceof String ts)) {
                out.add(f + ": non-string trigger (" + (t == null ? "null" : t.getClass().getSimpleName())
                    + "), failing closed (a !!null/!!bool tag can hide a trigger name)");
                return null;
            }
            triggers.add(ts.toLowerCase(Locale.ROOT));
        }
        return new Parsed(doc, triggers, on);
    }

    /** Scans raw tokens: a double-quoted scalar with a hex/unicode escape or a trailing backslash can hide a trigger name. */
    private static void rejectBadEscapes(String f, String text, List<String> out) {
        LoaderOptions options = new LoaderOptions();
        ScannerImpl scanner = new ScannerImpl(new StreamReader(text), options);
        while (scanner.checkToken()) {
            Token tok = scanner.getToken();
            if (tok instanceof ScalarToken st && st.getStyle() == DumperOptions.ScalarStyle.DOUBLE_QUOTED) {
                int from = text.offsetByCodePoints(0, st.getStartMark().getIndex());
                int to = text.offsetByCodePoints(0, st.getEndMark().getIndex());
                if (BAD_ESCAPE.matcher(text.substring(from, to)).find()) {
                    out.add(f + ": line " + (st.getStartMark().getLine() + 1) + ": double-quoted scalar with "
                        + "\\x/\\u/\\U escape or trailing backslash (can hide a trigger name)");
                }
            }
        }
    }

    private static void checkAllowed(String f, String text, Parsed parsed, List<String> out) {
        Map<?, ?> doc = parsed.doc;
        if (!parsed.triggers.equals(Set.of(PRT))) {
            out.add(f + ": 'on:' must contain ONLY pull_request_target (found " + new TreeSet<>(parsed.triggers) + ")");
        }
        Object cfg = parsed.on instanceof Map<?, ?> m ? m.get(PRT) : null;
        boolean editedOk = cfg instanceof Map<?, ?> c && c.get("types") instanceof List<?> t && t.contains("edited");
        if (!editedOk) {
            out.add(f + ": pull_request_target must declare types including 'edited' (retargets must re-run)");
        }

        Set<Object> topAllowed = new LinkedHashSet<>(List.of("name", "on", Boolean.TRUE, "permissions", "concurrency", "jobs"));
        Set<String> extra = new TreeSet<>();
        doc.keySet().stream().filter(k -> !topAllowed.contains(k)).forEach(k -> extra.add(String.valueOf(k)));
        if (!extra.isEmpty()) {
            out.add(f + ": unexpected top-level keys " + extra);
        }
        if (!isEmptyMap(doc, "permissions")) {
            out.add(f + ": workflow-level 'permissions: {}' missing");
        }
        Object conc = doc.get("concurrency");
        if (!(conc instanceof Map<?, ?> cm) || !Set.of("group", "cancel-in-progress").containsAll(stringKeys(cm))) {
            out.add(f + ": concurrency must be a mapping of group / cancel-in-progress only");
        } else if (!GROUP_VALUE.equals(cm.get("group"))) {
            out.add(f + ": concurrency.group must be exactly '" + GROUP_VALUE + "'");
        }

        Object jobsObj = doc.get("jobs");
        Map<?, ?> jobs = Map.of();
        if (jobsObj instanceof Map<?, ?> jm && !jm.isEmpty()) {
            jobs = jm;
        } else {
            out.add(f + ": jobs must be a non-empty mapping");
        }
        boolean named = false;
        for (Map.Entry<?, ?> je : jobs.entrySet()) {
            Object jid = je.getKey();
            if (!(je.getValue() instanceof Map<?, ?> job)) {
                out.add(f + ": job '" + jid + "' is not a mapping");
                continue;
            }
            Set<String> bad = new TreeSet<>(stringKeys(job));
            bad.removeAll(Set.of("name", "runs-on", "timeout-minutes", "permissions", "steps"));
            if (!bad.isEmpty()) {
                out.add(f + ": job '" + jid + "' has forbidden keys " + bad + " (only name/runs-on/timeout-minutes/"
                    + "permissions/steps; no uses/with/container/services/needs/env/if)");
            }
            if (!isEmptyMap(job, "permissions")) {
                out.add(f + ": job '" + jid + "': 'permissions' must be exactly {}");
            }
            if (!"ubuntu-latest".equals(job.get("runs-on"))) {
                out.add(f + ": job '" + jid + "': runs-on must be exactly 'ubuntu-latest' (no self-hosted runner)");
            }
            if (NAME_VALUE.equals(job.get("name"))) {
                named = true;
            } else {
                out.add(f + ": job '" + jid + "': name must be exactly '" + NAME_VALUE + "'");
            }
            if (!(job.get("steps") instanceof List<?> steps) || steps.isEmpty()) {
                out.add(f + ": job '" + jid + "': steps must be a non-empty list");
                continue;
            }
            int i = 0;
            for (Object stObj : steps) {
                i++;
                if (!(stObj instanceof Map<?, ?> st)) {
                    out.add(f + ": job '" + jid + "' step " + i + " is not a mapping");
                    continue;
                }
                checkStep(f, jid, i, st, out);
            }
        }
        if (!named) {
            out.add(f + ": no job with name exactly '" + NAME_VALUE + "'");
        }

        int n = 0;
        for (String l : splitRawLines(text)) {
            n++;
            if (l.contains("${{") && !ALLOWED_EXPR_LINES.contains(l)) {
                out.add(f + ": line " + n + ": expression opener outside the 7 allowed literal lines "
                    + "(comments and every scalar style count): " + l.strip().substring(0, Math.min(90, l.strip().length())));
            }
            if (PERM_KEY.matcher(l).find() && !l.strip().equals("permissions: {}")) {
                out.add(f + ": line " + n + ": every permissions: line must be exactly 'permissions: {}': "
                    + l.strip().substring(0, Math.min(60, l.strip().length())));
            }
        }
    }

    private static void checkStep(String f, Object jid, int i, Map<?, ?> st, List<String> out) {
        Set<String> sbad = new TreeSet<>(stringKeys(st));
        sbad.removeAll(Set.of("name", "env", "run"));
        if (!sbad.isEmpty()) {
            out.add(f + ": job '" + jid + "' step " + i + " has forbidden keys " + sbad
                + " (only name/env/run; PR code must never be fetched)");
        }
        if (!(st.get("run") instanceof String run)) {
            out.add(f + ": job '" + jid + "' step " + i + " has no string 'run'");
            return;
        }
        if (run.contains("${{")) {
            out.add(f + ": job '" + jid + "' step " + i + ": run string contains the expression opener "
                + "(author-controlled values must arrive via env only)");
        }
        Object env = st.containsKey("env") ? st.get("env") : new LinkedHashMap<>();
        if (!EXPECTED_ENV.equals(env)) {
            out.add(f + ": job '" + jid + "' step " + i + ": env must equal exactly the 5 expected entries "
                + new TreeSet<>(EXPECTED_ENV.keySet()));
        }
        String body = run.lines().filter(l -> !l.stripLeading().startsWith("#")).collect(Collectors.joining("\n"));
        for (Pattern rx : RUN_DENY) {
            Matcher m = rx.matcher(body);
            if (m.find()) {
                out.add(f + ": job '" + jid + "' step " + i + ": run body contains forbidden construct /"
                    + rx.pattern() + "/ near '" + m.group().strip() + "'");
            }
        }
    }

    private static boolean isEmptyMap(Map<?, ?> parent, String key) {
        return parent.containsKey(key) && parent.get(key) instanceof Map<?, ?> m && m.isEmpty();
    }

    private static Set<String> stringKeys(Map<?, ?> m) {
        Set<String> keys = new LinkedHashSet<>();
        m.keySet().forEach(k -> keys.add(String.valueOf(k)));
        return keys;
    }

    private static boolean isWorkflowName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
