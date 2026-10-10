package com.beautica.ci;

import java.util.List;

/** Outcome of one selector run. {@code tests} is only meaningful for {@link Mode#SELECTIVE}. */
record Selection(Mode mode, String reason, List<String> tests, int totalTestClasses) {

    enum Mode { FULL, SELECTIVE, NONE }

    static Selection full(String reason, int total) {
        return new Selection(Mode.FULL, reason, List.of(), total);
    }

    static Selection none(String reason, int total) {
        return new Selection(Mode.NONE, reason, List.of(), total);
    }

    static Selection selective(String reason, List<String> tests, int total) {
        return new Selection(Mode.SELECTIVE, reason, tests, total);
    }
}
