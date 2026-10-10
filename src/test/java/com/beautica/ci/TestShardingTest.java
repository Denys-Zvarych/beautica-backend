package com.beautica.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class TestShardingTest {

    private static final ToIntFunction<String> BY_NAME = fqn -> fqn.endsWith("IT") ? 10 : 1;

    /** Class counts per package, shaped like the real suite: a few big feature packages, a long tail. */
    private static final int[] PACKAGE_SIZES = {
        120, 80, 60, 50, 40, 40, 30, 30, 25, 20, 20, 15, 15, 12, 10, 10, 8, 8, 6, 5, 5, 3, 2, 2, 1,
    };

    /** Every fifth class is a heavy IT, and the ITs cluster in the first packages (the skew the audit found). */
    private static List<String> realisticSuite() {
        List<String> fqns = new ArrayList<>();
        for (int p = 0; p < PACKAGE_SIZES.length; p++) {
            for (int c = 0; c < PACKAGE_SIZES[p]; c++) {
                boolean heavy = p < 6 ? c % 2 == 0 : c % 10 == 0;
                fqns.add(String.format("com.beautica.pkg%02d.C%03d%s", p, c, heavy ? "IT" : "Test"));
            }
        }
        return fqns;
    }

    @Test
    void should_coverEveryClassExactlyOnce_when_splitIntoShards() {
        List<String> suite = realisticSuite();

        List<List<String>> shards = TestSharder.split(suite, BY_NAME, 3);

        List<String> union = shards.stream().flatMap(List::stream).toList();
        assertThat(union).hasSize(suite.size());
        assertThat(new HashSet<>(union)).isEqualTo(new HashSet<>(suite));
    }

    @Test
    void should_keepShardsPairwiseDisjoint_when_split() {
        List<List<String>> shards = TestSharder.split(realisticSuite(), BY_NAME, 3);

        Set<String> seen = new HashSet<>();
        shards.forEach(shard -> shard.forEach(fqn -> assertThat(seen.add(fqn)).as(fqn).isTrue()));
    }

    @Test
    void should_giveSameSplit_when_inputOrderDiffers() {
        List<String> suite = realisticSuite();
        List<String> shuffled = new ArrayList<>(suite);
        Collections.shuffle(shuffled, new Random(42));

        assertThat(TestSharder.split(shuffled, BY_NAME, 3)).isEqualTo(TestSharder.split(suite, BY_NAME, 3));
    }

    @Test
    void should_balanceWeightWithinOneHeaviestClass_when_splitRealisticSuite() {
        List<List<String>> shards = TestSharder.split(realisticSuite(), BY_NAME, 3);

        List<Long> weights = TestSharder.weights(shards, BY_NAME);
        long spread = Collections.max(weights) - Collections.min(weights);
        assertThat(spread).isLessThanOrEqualTo(10L);
    }

    @Test
    void should_spreadHeavyClassesEvenly_when_theyClusterInOnePackage() {
        List<String> suite = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            suite.add("com.beautica.slow.S" + i + "IT");
        }
        for (int i = 0; i < 30; i++) {
            suite.add(String.format("com.beautica.fast.F%02dTest", i));
        }

        List<List<String>> shards = TestSharder.split(suite, BY_NAME, 3);

        shards.forEach(shard -> assertThat(shard.stream().filter(f -> f.endsWith("IT")).count()).isEqualTo(3L));
    }

    @Test
    void should_assignHeaviestFirstToLightestShard_when_splitSmallSuite() {
        ToIntFunction<String> weigher = fqn -> switch (fqn) {
            case "a.A" -> 7;
            case "a.B" -> 5;
            case "a.C" -> 4;
            case "a.D" -> 3;
            default -> 1;
        };

        List<List<String>> shards = TestSharder.split(List.of("a.D", "a.B", "a.A", "a.C", "a.E"), weigher, 2);

        assertThat(shards).containsExactly(List.of("a.A", "a.D"), List.of("a.B", "a.C", "a.E"));
        assertThat(TestSharder.weights(shards, weigher)).containsExactly(10L, 10L);
    }

    @Test
    void should_returnWholeListInOneShard_when_singleShard() {
        List<String> suite = realisticSuite();

        assertThat(TestSharder.split(suite, BY_NAME, 1)).containsExactly(suite.stream().sorted().toList());
    }

    @Test
    void should_returnEmptyShards_when_listIsEmpty() {
        assertThat(TestSharder.split(List.of(), BY_NAME, 3)).containsExactly(List.of(), List.of(), List.of());
    }

    @Test
    void should_dropDuplicates_when_inputRepeatsAClass() {
        assertThat(TestSharder.split(List.of("a.ATest", "a.ATest"), BY_NAME, 2)).containsExactly(List.of("a.ATest"), List.of());
    }

    @Test
    void should_rejectNonPositiveShardCount_when_split() {
        assertThatThrownBy(() -> TestSharder.split(List.of("a.BTest"), BY_NAME, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
