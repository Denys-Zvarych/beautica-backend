package com.beautica.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SlowTestExtension implements BeforeEachCallback, AfterEachCallback {

    private static final long THRESHOLD_MS = 10_000L;
    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(SlowTestExtension.class);
    private static final String START_KEY = "startMs";

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NS).put(START_KEY, System.currentTimeMillis());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Long start = context.getStore(NS).remove(START_KEY, Long.class);
        if (start == null) return;
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > THRESHOLD_MS) {
            throw new AssertionError(String.format(
                    "Test '%s' exceeded %d ms time limit (took %d ms)",
                    context.getDisplayName(), THRESHOLD_MS, elapsed));
        }
    }
}
