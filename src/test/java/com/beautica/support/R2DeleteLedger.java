package com.beautica.support;

import com.beautica.media.service.R2StorageService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.mockito.Mockito.mockingDetails;

/**
 * Shared ledger over a {@code @MockBean R2StorageService}: every key handed to R2 for deletion, read back from
 * the mock's recorded invocations. Used by the R2 blob-purge ITs ({@code AvatarBlobLifecycleIT},
 * {@code R2BlobPurgeEndToEndIT}, {@code StaffRemovalBlobPurgeIT}, ...) — which sit in different packages and do
 * not share a media-specific base, hence a static helper rather than a base-class method.
 */
public final class R2DeleteLedger {

    private R2DeleteLedger() {
    }

    /** Every key passed to {@code deleteFile} or {@code deleteFiles}, in call order, with multiplicity. */
    @SuppressWarnings("unchecked")
    public static List<String> purgedKeys(R2StorageService r2) {
        List<String> keys = new ArrayList<>();
        mockingDetails(r2).getInvocations().forEach(inv -> {
            String name = inv.getMethod().getName();
            if (name.equals("deleteFile")) {
                keys.add(inv.getArgument(0));
            } else if (name.equals("deleteFiles")) {
                keys.addAll((Collection<String>) inv.getArgument(0));
            }
        });
        return keys;
    }
}
