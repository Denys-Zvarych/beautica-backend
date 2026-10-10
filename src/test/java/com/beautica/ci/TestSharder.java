package com.beautica.ci;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Deterministic weight-balanced split of test classes into N shards (phase 360 D2, reworked after
 * the cycle-1 perf audit F4).
 *
 * <p>A count-based, package-contiguous split put most slow Spring/Testcontainers integration tests
 * into the same shard (~20% wall-clock skew). Classes are therefore weighted (see
 * {@link AffectedTestSelector#weightOf}) and assigned by greedy LPT: heaviest first, each to the
 * currently lightest shard. Ties break on FQN and shard index, so the result depends only on the
 * set of classes and their weights, never on input order. Package contiguity is intentionally
 * given up: heavy ITs share one Spring context through their abstract base regardless of package.
 * Each shard is emitted sorted by FQN.
 */
final class TestSharder {

    private TestSharder() {
    }

    static List<List<String>> split(List<String> fqns, ToIntFunction<String> weigher, int shards) {
        return split(fqns, weigher, shards, fqn -> List.of());
    }

    /**
     * As above, but classes sharing a {@code @Nested}-declaring ancestor (see
     * {@link SourceGraph#nestedAncestors}) form one indivisible family: summed weight, one shard.
     * Their nested-class reports carry the ANCESTOR's name, so two shards each running one subclass
     * would upload identically named report files and the merged artifact would silently lose one.
     */
    static List<List<String>> split(List<String> fqns, ToIntFunction<String> weigher, int shards,
                                    Function<String, List<String>> nestedAncestors) {
        if (shards < 1) {
            throw new IllegalArgumentException("shards must be >= 1");
        }
        List<List<String>> units = families(fqns.stream().distinct().sorted().toList(), nestedAncestors);
        ToLongFunction<List<String>> unitWeight = unit -> unit.stream().mapToLong(weigher::applyAsInt).sum();
        List<List<String>> byWeight = units.stream()
            .sorted(Comparator.<List<String>>comparingLong(unitWeight::applyAsLong).reversed().thenComparing(unit -> unit.get(0)))
            .toList();
        List<List<String>> result = new ArrayList<>();
        long[] load = new long[shards];
        for (int i = 0; i < shards; i++) {
            result.add(new ArrayList<>());
        }
        for (List<String> unit : byWeight) {
            int lightest = 0;
            for (int i = 1; i < shards; i++) {
                if (load[i] < load[lightest]) {
                    lightest = i;
                }
            }
            result.get(lightest).addAll(unit);
            load[lightest] += unitWeight.applyAsLong(unit);
        }
        return result.stream().map(shard -> List.copyOf(shard.stream().sorted().toList())).toList();
    }

    /** Connected components of classes linked through a shared nested-declaring ancestor; each sorted, singletons included. */
    private static List<List<String>> families(List<String> sortedFqns, Function<String, List<String>> nestedAncestors) {
        Map<String, String> parent = new HashMap<>();
        for (String fqn : sortedFqns) {
            for (String ancestor : nestedAncestors.apply(fqn)) {
                union(parent, fqn, "ancestor:" + ancestor);
            }
        }
        Map<String, List<String>> byRoot = new TreeMap<>();
        for (String fqn : sortedFqns) {
            byRoot.computeIfAbsent(find(parent, fqn), k -> new ArrayList<>()).add(fqn);
        }
        return new ArrayList<>(byRoot.values());
    }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (parent.containsKey(root) && !parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        parent.putIfAbsent(x, root);
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.putIfAbsent(a, a);
        parent.putIfAbsent(b, b);
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            // deterministic root: the lexicographically smaller one
            if (ra.compareTo(rb) < 0) {
                parent.put(rb, ra);
            } else {
                parent.put(ra, rb);
            }
        }
    }

    static List<Long> weights(List<List<String>> shards, ToIntFunction<String> weigher) {
        return shards.stream().map(shard -> shard.stream().mapToLong(weigher::applyAsInt).sum()).toList();
    }
}
