package com.beautica.moderation.dto;

import com.beautica.moderation.support.BidiEscaper;
import com.beautica.moderation.support.UrlDefanger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

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
 * @param ownerEmail    profile targets only — the reported account's contact e-mail (master's user, salon owner,
 *                      client), so support can write to the owner. Our own data, not UGC: validated against
 *                      {@link #EMAIL} (anything else becomes {@code null}), never defanged, HTML-escaped by the
 *                      template. Never the reporter's address.
 * @param ownerEmailVerified whether that account confirmed the address; the e-mail marks an unverified one
 *                      «(не підтверджено)» (support may still write to it). Always false without an address.
 */
public record ReportedContent(
        boolean exists,
        String text,
        Integer rating,
        boolean hiddenPending,
        String photoUrl,
        String displayName,
        String avatarUrl,
        String ownerEmail,
        boolean ownerEmailVerified
) {

    /** Mailto subject of the «Написати власнику» button — fixed text, no UGC, no reporter info. */
    public static final String OWNER_MAIL_SUBJECT = "Beautica: скарга на ваш профіль";

    /**
     * A plain address with no character that could break out of a {@code mailto:} URI or an HTML attribute
     * ({@code ? & # % " ' < > space} are all excluded), at most 254 characters.
     */
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._+-]{1,64}@[A-Za-z0-9.-]{1,185}\\.[A-Za-z]{2,}$");

    public ReportedContent {
        ownerEmail = validEmailOrNull(ownerEmail);
        ownerEmailVerified = ownerEmail != null && ownerEmailVerified;
    }

    public static ReportedContent gone() {
        return new ReportedContent(false, null, null, false, null, null, null, null, false);
    }

    public static ReportedContent review(String text, Short rating, boolean hiddenPending) {
        return new ReportedContent(true, userText(text), rating == null ? null : rating.intValue(),
                hiddenPending, null, null, null, null, false);
    }

    public static ReportedContent photo(String photoUrl) {
        return new ReportedContent(true, null, null, false, blankToNull(photoUrl), null, null, null, false);
    }

    public static ReportedContent profile(String displayName, String avatarUrl) {
        return profile(displayName, avatarUrl, null, false);
    }

    /**
     * A profile target with the owner's contact e-mail (validated; an invalid or blank address is dropped) and whether
     * the owner verified it.
     */
    public static ReportedContent profile(String displayName, String avatarUrl, String ownerEmail,
                                          boolean ownerEmailVerified) {
        return new ReportedContent(true, null, null, false, null, userText(displayName),
                blankToNull(avatarUrl), ownerEmail, ownerEmailVerified);
    }

    /**
     * No UGC, URL or address in logs (audit-fix): only whether the target exists, which kind of content it is, and
     * whether an owner address is present.
     */
    @Override
    public String toString() {
        return "ReportedContent[exists=" + exists + ", kind=" + kind() + ", hasOwnerEmail=" + (ownerEmail != null)
                + "]";
    }

    private String kind() {
        if (!exists) {
            return "GONE";
        }
        if (rating != null || text != null) {
            return "REVIEW";
        }
        return photoUrl != null ? "PHOTO" : "PROFILE";
    }

    /**
     * The «Написати власнику» link: {@code mailto:<address>?subject=<fixed subject>}, or {@code null} without an
     * address. The address is pattern-checked, so it needs no encoding; the subject is percent-encoded.
     */
    public String ownerMailto() {
        if (ownerEmail == null) {
            return null;
        }
        return "mailto:" + ownerEmail + "?subject="
                + URLEncoder.encode(OWNER_MAIL_SUBJECT, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String validEmailOrNull(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.strip();
        return trimmed.length() <= 254 && EMAIL.matcher(trimmed).matches() ? trimmed : null;
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
