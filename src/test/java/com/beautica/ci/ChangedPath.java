package com.beautica.ci;

import java.util.ArrayList;
import java.util.List;

/** A changed path from {@code git diff --name-status -M}. {@code deleted} also covers the old side of a rename. */
record ChangedPath(String path, boolean deleted) {

    /** Parses {@code M\tp}, {@code A\tp}, {@code D\tp}, {@code R100\told\tnew}; blank lines are skipped. */
    static List<ChangedPath> parseNameStatus(String text) {
        List<ChangedPath> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] cols = line.split("\t");
            char status = cols[0].charAt(0);
            if ((status == 'R' || status == 'C') && cols.length >= 3) {
                if (status == 'R') {
                    out.add(new ChangedPath(cols[1], true));
                }
                out.add(new ChangedPath(cols[2], false));
            } else if (cols.length >= 2) {
                out.add(new ChangedPath(cols[1], status == 'D'));
            }
        }
        return out;
    }
}
