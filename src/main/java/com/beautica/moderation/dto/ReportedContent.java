package com.beautica.moderation.dto;

import com.beautica.moderation.support.BidiEscaper;
import com.beautica.moderation.support.UrlDefanger;

/**
 * Phase 414 D1 — the reported target as shown in the support e-mail. Only the fields that apply to the target type are
 * set; {@code exists = false} means the target is already gone. User-written strings ({@code text},
 * {@code displayName}) pass through {@link BidiEscaper} and {@link UrlDefanger} here, once (see {@link #userText}); the
 * template still escapes HTML with {@code th:text}.
 *
 * @param exists        whether the target row still exists
 * @param text          review / client review comment
 * @param rating        review / client review stars (1..5)
 * @param hiddenPending review / client review is auto-hidden ({@code HIDDEN_PENDING}): the e-mail offers RESTORE
 * @param photoUrl      portfolio photo public URL ({@code R2StorageService#buildPublicUrl})
 * @param displayName   master / salon / client profile name
 * @param avatarUrl     profile avatar public URL
 */
public record ReportedContent(
        boolean exists,
        String text,
        Integer rating,
        boolean hiddenPending,
        String photoUrl,
        String displayName,
        String avatarUrl
) {

    public static ReportedContent gone() {
        return new ReportedContent(false, null, null, false, null, null, null);
    }

    public static ReportedContent review(String text, Short rating, boolean hiddenPending) {
        return new ReportedContent(true, userText(text), rating == null ? null : rating.intValue(),
                hiddenPending, null, null, null);
    }

    public static ReportedContent photo(String photoUrl) {
        return new ReportedContent(true, null, null, false, blankToNull(photoUrl), null, null);
    }

    public static ReportedContent profile(String displayName, String avatarUrl) {
        return new ReportedContent(true, null, null, false, null, userText(displayName),
                blankToNull(avatarUrl));
    }

    /** User-written text as the e-mail shows it: invisible controls made visible, then URLs made non-clickable. */
    public static String userText(String value) {
        return UrlDefanger.defang(BidiEscaper.escape(value));
    }

    /** {@code R2StorageService#buildPublicUrl} answers "" when R2 is disabled: render no image rather than a broken one. */
    private static String blankToNull(String url) {
        return url == null || url.isBlank() ? null : url;
    }
}
