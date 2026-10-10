package com.beautica.ci;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

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
        if (shards < 1) {
            throw new IllegalArgumentException("shards must be >= 1");
        }
        List<String> byWeight = fqns.stream().distinct()
            .sorted(Comparator.<String>comparingInt(weigher::applyAsInt).reversed().thenComparing(Comparator.naturalOrder()))
            .toList();
        List<List<String>> result = new ArrayList<>();
        long[] load = new long[shards];
        for (int i = 0; i < shards; i++) {
            result.add(new ArrayList<>());
        }
        for (String fqn : byWeight) {
            int lightest = 0;
            for (int i = 1; i < shards; i++) {
                if (load[i] < load[lightest]) {
                    lightest = i;
                }
            }
            result.get(lightest).add(fqn);
            load[lightest] += weigher.applyAsInt(fqn);
        }
        return result.stream().map(shard -> List.copyOf(shard.stream().sorted().toList())).toList();
    }

    static List<Long> weights(List<List<String>> shards, ToIntFunction<String> weigher) {
        return shards.stream().map(shard -> shard.stream().mapToLong(weigher::applyAsInt).sum()).toList();
    }
}
