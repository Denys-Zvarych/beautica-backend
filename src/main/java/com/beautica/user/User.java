package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.AuditableEntity;
import com.beautica.location.SettlementDisplayNames;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.UUID;


@Entity
@DynamicUpdate
@Table(
        name = "users",
        // Mirrors the partial index idx_users_stale_unverified_otp from V50 so
        // ddl-auto=validate catches drift. JPA cannot express the partial
        // predicate (WHERE email_verified = false) — the column list documents it.
        indexes = @Index(
                name = "idx_users_stale_unverified_otp",
                columnList = "verification_code_expires_at"))
public class User extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Optional since B514 (AUTH-D11, V244): a phone-registered account has no email until one is added and
     * confirmed later (B516). Unique among non-null values. Business emails go only to a non-null address
     * (NTF-D15 / NTF-56, {@code EmailNotificationService}).
     */
    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    private Role role;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    /**
     * B514 (AUTH-D08, V244) — when {@link #phoneNumber} was proven by SMS code. Non-null means the phone is the
     * normalised {@code +380…} form (CHECK {@code ck_users_verified_phone_format}), unique among verified accounts
     * (partial unique index {@code ux_users_verified_phone}) and usable as a login identifier (AUTH-D09). NULL on
     * every pre-B514 account (AUTH-D12).
     */
    @JsonIgnore
    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "region", length = 100)
    private String region;

    // ---- Phase 10.3 locality (raw UUID FK columns, NULLABLE) -------------
    // FKs to the Phase 10.1 taxonomy (cities / city_districts). Modeled as raw
    // UUIDs — not @ManyToOne — to keep this schema-only phase free of any
    // association traversal surface (no accidental N+1 / LazyInit on existing
    // user read paths) and consistent with the existing raw-UUID salonId
    // reference. Read/write semantics are owned by Phases 10.4/10.6.
    @Column(name = "city_id")
    private UUID cityId;

    @Column(name = "district_id")
    private UUID districtId;

    // Light, unvalidated structured address (M1) — separate street / building /
    // landmark fields, no geocoding now. Lengths mirror V54 exactly so
    // ddl-auto=validate catches drift.
    @Column(name = "street", length = 255)
    private String street;

    @Column(name = "building_no", length = 50)
    private String buildingNo;

    @Column(name = "location_note", columnDefinition = "TEXT")
    private String locationNote;

    /**
     * R2 object key of this user's avatar blob. External-storage cleanup contract
     * (Anti-Bug Playbook §O8): this pointer lives on the {@code users} row itself, NOT in
     * {@code media_files}, so deleting the user does not reach it through any
     * {@code ON DELETE CASCADE}. Any user-deletion flow MUST capture this pointer (and the user's
     * {@code media_files} keys) BEFORE deleting the row and register
     * {@code AccountBlobPurgeRegistrar#registerAfterCommit} — it purges this blob together with the
     * user's portfolio blobs strictly after the delete commits
     * ({@code MediaService#purgeUserBlobsAfterCommit}). Skipping it leaves the avatar
     * publicly retrievable at a URL that has been handed out as {@code clientAvatarUrl} to
     * every provider the user ever booked with.
     */
    @Column(name = "avatar_r2_key", length = 500)
    private String avatarR2Key;

    @Column(name = "avatar_url", length = 1000)
    private String avatarUrl;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @JsonIgnore
    @Column(name = "verification_code_hash", length = 64)
    private String verificationCodeHash;

    @JsonIgnore
    @Column(name = "verification_code_expires_at")
    private Instant verificationCodeExpiresAt;

    @JsonIgnore
    @Column(name = "verification_attempts", nullable = false)
    private short verificationAttempts = 0;

    // Lifetime failed-verify counter that resend does NOT reset. Backs the
    // resend-surviving cumulative brute-force bound (see V50 migration).
    @JsonIgnore
    @Column(name = "verification_failed_total", nullable = false)
    private short verificationFailedTotal = 0;

    // When non-null and in the future, both verifyEmail and resendVerification
    // reject — but with the wire-identical generic failure shape (no new oracle).
    @JsonIgnore
    @Column(name = "verification_locked_until")
    private Instant verificationLockedUntil;

    // ---- Phase A1 password-reset OTP columns (mirror verification_* exactly) --------
    // Backs PasswordResetOtpProcessor's locked critical section, which reuses
    // EmailVerificationProcessor's per-code attempt cap + resend-surviving cumulative
    // lockout verbatim. See V107 for the CHECK constraints mirroring V49/V50/V63.
    @JsonIgnore
    @Column(name = "password_reset_code_hash", length = 64)
    private String passwordResetCodeHash;

    @JsonIgnore
    @Column(name = "password_reset_code_expires_at")
    private Instant passwordResetCodeExpiresAt;

    @JsonIgnore
    @Column(name = "password_reset_attempts", nullable = false)
    private short passwordResetAttempts = 0;

    // Lifetime failed-verify counter that a fresh OTP request does NOT reset. Backs the
    // resend-surviving cumulative brute-force bound (mirrors verificationFailedTotal).
    @JsonIgnore
    @Column(name = "password_reset_failed_total", nullable = false)
    private short passwordResetFailedTotal = 0;

    // When non-null and in the future, both the OTP verify and any fresh OTP mint reject —
    // but with the wire-identical generic failure shape (no new oracle).
    @JsonIgnore
    @Column(name = "password_reset_locked_until")
    private Instant passwordResetLockedUntil;

    // B516 (AUTH-12/15, V248) — last password-reset SMS request on this account; the 60 s SMS-branch cooldown reads it
    // on the locked row (the phone_otps row is written after commit, too late to serialise concurrent requests).
    @JsonIgnore
    @Column(name = "password_reset_sms_sent_at")
    private Instant passwordResetSmsSentAt;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "instagram", length = 100)
    private String instagram;

    // Free-text professional headline/label (e.g. "Майстер манікюру", "Візажист").
    // Distinct from bio (long-form description) and from the role enum. Every role
    // EXCEPT CLIENT may set it — the CLIENT guard lives in UserService.updateProfile,
    // not here. Nullable: a provider with no title set returns null (mobile falls back
    // to the role label). Length mirrors V110 VARCHAR(100).
    @Column(name = "professional_title", length = 100)
    private String professionalTitle;

    /**
     * Persisted rating aggregate — a CLIENT's average rating as reviewed by providers via
     * {@code client_reviews} (Phase 27.4-27.6). Mirrors {@code Master#avgRating}/{@code
     * Salon#avgRating}'s exact JPA style (bare {@code @Column}, no {@code nullable = false}; the
     * DB column is nullable — {@code NULL} means never recalculated / zero reviews).
     *
     * <p>Written ONLY by {@code ClientReviewRepository#recalculateClientRating} (a native
     * {@code UPDATE}, never through this entity's Java field) — there is deliberately no public
     * setter, mirroring the "recalculate on write, read persisted on read" contract.
     */
    @Column(name = "avg_rating")
    private java.math.BigDecimal avgRating;

    /** Count of {@code client_reviews} rows for this user (as the subject). See {@link #avgRating}. */
    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "business_name", length = 255)
    private String businessName;

    @Column(name = "salon_id")
    private UUID salonId;

    // Stamped by PasswordResetService.resetPassword. NULL means "no reset has ever
    // occurred" (the default, common case). When non-null, JwtAuthenticationFilter
    // rejects any access token whose `iat` (issued-at) claim predates this instant —
    // access tokens are otherwise stateless JWTs verified purely by signature + expiry,
    // so without this a stolen access token would remain fully usable for its remaining
    // TTL even after the legitimate owner reset their password. See
    // com.beautica.auth.TokensValidAfterCache for the read-path (short-TTL cache backed
    // by this column) and com.beautica.auth.AccessTokenDenylist for the sibling
    // per-token (not per-user) revocation mechanism used by logout.
    @JsonIgnore
    @Column(name = "tokens_valid_after")
    private Instant tokensValidAfter;

    // Phase 442 — terms + privacy acceptance at account creation (V232, CHECK ck_users_terms_consent_triple:
    // all three NULL or all three set). NULL = registered before consent was recorded (no backfill). Written
    // once by recordTermsConsent; updatable = false until a re-consent flow exists. Never exposed in a DTO.
    @JsonIgnore
    @Column(name = "terms_accepted_at", updatable = false)
    private Instant termsAcceptedAt;

    @JsonIgnore
    @Column(name = "terms_version", length = 32, updatable = false)
    private String termsVersion;

    @JsonIgnore
    @Column(name = "privacy_policy_version", length = 32, updatable = false)
    private String privacyPolicyVersion;

    protected User() {
    }

    public User(
            String email,
            String passwordHash,
            Role role,
            String firstName,
            String lastName,
            String phoneNumber
    ) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phoneNumber = phoneNumber;
        this.isActive = true;
    }

    public User(
            String email,
            String passwordHash,
            Role role,
            String firstName,
            String lastName,
            String phoneNumber,
            String businessName
    ) {
        this(email, passwordHash, role, firstName, lastName, phoneNumber);
        this.businessName = businessName;
    }

    public User(
            String email,
            String passwordHash,
            Role role,
            String firstName,
            String lastName,
            String phoneNumber,
            UUID salonId
    ) {
        this(email, passwordHash, role, firstName, lastName, phoneNumber);
        this.salonId = salonId;
    }

    /**
     * B520 (AUTH-52, AUTH-D16) — the account an accepted PHONE invite creates: the invited phone, proven equal to the
     * registration ticket's phone, is verified from the start; no email (one is added later as a notifications channel,
     * AUTH-D11), so {@code emailVerified} stays {@code false}. The salon and role come from the invite token. Callers
     * persist through {@code PhoneRegistrationService.saveClaimingPhone} (a concurrently claimed phone is a 409).
     */
    public static User createFromPhoneInvite(
            String phoneE164,
            String passwordHash,
            Role role,
            String firstName,
            String lastName,
            UUID salonId,
            Instant phoneVerifiedAt
    ) {
        User user = new User(null, passwordHash, role, firstName, lastName, phoneE164, salonId);
        user.phoneVerifiedAt = phoneVerifiedAt;
        return user;
    }

    /**
     * B514 (AUTH-D11) — a self-registered account whose phone was proven by an SMS code (registration ticket). No
     * email: one is added and confirmed later (B516). The phone must already be normalised E.164 ({@code +380…}).
     */
    public static User registerWithVerifiedPhone(
            String phoneE164,
            String passwordHash,
            Role role,
            String firstName,
            String lastName,
            String businessName,
            Instant phoneVerifiedAt
    ) {
        User user = new User(null, passwordHash, role, firstName, lastName, phoneE164, businessName);
        user.phoneVerifiedAt = phoneVerifiedAt;
        return user;
    }

    /**
     * B514 (AUTH-43) — stamps the phone proven by a registration ticket on an invited account at accept time; B516
     * (AUTH-44, D3) — swaps an existing account's phone to the number its SMS code just proved, in the same single
     * write (number and {@code phone_verified_at} together). Callers persist through
     * {@code PhoneRegistrationService.saveClaimingPhone} so a number verified on another account is a clean 409.
     */
    public void markPhoneVerified(String phoneE164, Instant verifiedAt) {
        this.phoneNumber = phoneE164;
        this.phoneVerifiedAt = verifiedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    @JsonIgnore
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Replaces the stored password hash after a successful password-reset flow.
     *
     * <p>Callers MUST supply a BCrypt-encoded value — never a plaintext password.
     * This mutator is intentionally narrow: it exists solely for
     * {@code PasswordResetService.resetPassword} and must not be called from
     * any other write path (use {@link User#User(String, String, Role, String, String, String)}
     * constructor-based init for new users).
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    @JsonIgnore
    public String getPhoneNumber() {
        return phoneNumber;
    }

    public boolean isActive() {
        return isActive;
    }

    /**
     * Deactivates (or reactivates) this account.
     *
     * <p><b>The salon-deletion cascade no longer calls this (phase 295; this javadoc corrected by
     * the phase 295 audit, LOW-9).</b> Phase 290 did flip staff accounts to {@code false} here and
     * stamped {@link #setTokensValidAfter} alongside it. Phase 295 reversed that: a deleted
     * salon's {@code SALON_MASTER}/{@code SALON_ADMIN} rows are HARD-DELETED, so there is no row
     * left to flag and no stamp written — {@code grep -rn "setTokensValidAfter" src/main} now
     * finds only {@code PasswordResetService}.
     *
     * <p>That reversal is exactly why a deleted account's access token has to be rejected on
     * ROW ABSENCE rather than on a flag or a stamp; see {@link
     * com.beautica.auth.TokenValidityState}. This javadoc describing a stamp that is no longer
     * written is what masked that gap — do not restate a cascade's behaviour here without
     * re-reading the cascade.
     *
     * <p>No production caller sets {@code false} today. If a user-suspension feature ever does,
     * note that {@code JwtAuthenticationFilter} has no {@code is_active} check: the flag alone
     * would leave every outstanding access token usable for the rest of its TTL, and such a
     * feature must stamp {@link #setTokensValidAfter} too (see that setter's javadoc).
     */
    public void setActive(boolean active) {
        this.isActive = active;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    @JsonIgnore
    public Instant getPhoneVerifiedAt() {
        return phoneVerifiedAt;
    }

    /** B514 — the phone was proven by SMS code (AUTH-D08). */
    public boolean isPhoneVerified() {
        return phoneVerifiedAt != null;
    }

    /**
     * B514 (AUTH-08) — the account has proven at least one login identifier: a verified phone, or (legacy accounts)
     * a confirmed email. Gates login and refresh.
     */
    public boolean hasVerifiedIdentity() {
        return phoneVerifiedAt != null || emailVerified;
    }

    /**
     * B516 (AUTH-50, CLI-50) — stores an optional notifications email only once its emailed code was confirmed:
     * address and {@code email_verified = true} in one write. An unconfirmed address is never written here (it stays
     * in {@code user_pending_emails}, NTF-56). The address must already be lower-cased and trimmed.
     */
    public void confirmEmail(String normalisedEmail) {
        this.email = normalisedEmail;
        this.emailVerified = true;
    }

    /** B516 (AUTH-D18) — a non-null email that was confirmed by code: the forgot / change-password channel. */
    public boolean hasConfirmedEmail() {
        return email != null && emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    @JsonIgnore
    public String getVerificationCodeHash() {
        return verificationCodeHash;
    }

    public void setVerificationCodeHash(String verificationCodeHash) {
        this.verificationCodeHash = verificationCodeHash;
    }

    @JsonIgnore
    public Instant getVerificationCodeExpiresAt() {
        return verificationCodeExpiresAt;
    }

    public void setVerificationCodeExpiresAt(Instant verificationCodeExpiresAt) {
        this.verificationCodeExpiresAt = verificationCodeExpiresAt;
    }

    @JsonIgnore
    public short getVerificationAttempts() {
        return verificationAttempts;
    }

    public void setVerificationAttempts(short verificationAttempts) {
        this.verificationAttempts = verificationAttempts;
    }

    @JsonIgnore
    public short getVerificationFailedTotal() {
        return verificationFailedTotal;
    }

    public void setVerificationFailedTotal(short verificationFailedTotal) {
        this.verificationFailedTotal = verificationFailedTotal;
    }

    @JsonIgnore
    public Instant getVerificationLockedUntil() {
        return verificationLockedUntil;
    }

    public void setVerificationLockedUntil(Instant verificationLockedUntil) {
        this.verificationLockedUntil = verificationLockedUntil;
    }

    @JsonIgnore
    public String getPasswordResetCodeHash() {
        return passwordResetCodeHash;
    }

    public void setPasswordResetCodeHash(String passwordResetCodeHash) {
        this.passwordResetCodeHash = passwordResetCodeHash;
    }

    @JsonIgnore
    public Instant getPasswordResetCodeExpiresAt() {
        return passwordResetCodeExpiresAt;
    }

    public void setPasswordResetCodeExpiresAt(Instant passwordResetCodeExpiresAt) {
        this.passwordResetCodeExpiresAt = passwordResetCodeExpiresAt;
    }

    @JsonIgnore
    public short getPasswordResetAttempts() {
        return passwordResetAttempts;
    }

    public void setPasswordResetAttempts(short passwordResetAttempts) {
        this.passwordResetAttempts = passwordResetAttempts;
    }

    @JsonIgnore
    public short getPasswordResetFailedTotal() {
        return passwordResetFailedTotal;
    }

    public void setPasswordResetFailedTotal(short passwordResetFailedTotal) {
        this.passwordResetFailedTotal = passwordResetFailedTotal;
    }

    @JsonIgnore
    public Instant getPasswordResetSmsSentAt() {
        return passwordResetSmsSentAt;
    }

    /** B516 — stamps a password-reset SMS request (the cooldown source); call on the row-locked entity only. */
    public void markPasswordResetSmsRequested(Instant requestedAt) {
        this.passwordResetSmsSentAt = requestedAt;
    }

    public Instant getPasswordResetLockedUntil() {
        return passwordResetLockedUntil;
    }

    public void setPasswordResetLockedUntil(Instant passwordResetLockedUntil) {
        this.passwordResetLockedUntil = passwordResetLockedUntil;
    }

    public UUID getSalonId() {
        return salonId;
    }

    @JsonIgnore
    public Instant getTokensValidAfter() {
        return tokensValidAfter;
    }

    /**
     * Marks every access token issued before {@code tokensValidAfter} as invalid. Callers MUST
     * supply the current instant at the moment of the revoking event.
     *
     * <p>Two legitimate callers as of Phase 290 (previously restricted to the first alone):
     * <ul>
     *   <li>{@code PasswordResetService.resetPassword} — the original caller; a password reset
     *       must invalidate any access token issued before it.</li>
     *   <li>{@code SalonService}'s salon-deletion staff cascade — a scrubbed
     *       {@code SALON_MASTER}/{@code SALON_ADMIN} account's outstanding access token must stop
     *       working immediately, not merely at its natural TTL expiry. {@code isActive} alone is
     *       not enough: {@code JwtAuthenticationFilter} has no {@code isActive} check, only the
     *       {@code tokensValidAfter} one (via {@code TokensValidAfterCache}).</li>
     * </ul>
     * Both callers must also purge the user's refresh tokens
     * ({@code RefreshTokenRepository.deleteByUserId}) and evict
     * {@code TokensValidAfterCache} AFTER their transaction commits — never inline — for the same
     * stale-read race reason documented on {@code TokensValidAfterCache#invalidate}.
     */
    public void setTokensValidAfter(Instant tokensValidAfter) {
        this.tokensValidAfter = tokensValidAfter;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public void setSalonId(UUID salonId) {
        this.salonId = salonId;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * Denormalises a settlement's labels into the legacy {@code city}/{@code region} columns.
     * Called on every {@code cityId} write (profile PATCH and the salon-create owner sync) so
     * the text can never drift from the FK. {@code null} (the id did not resolve) CLEARS both
     * — a stale label must never sit beside a new id; {@code Salon} applies the same rule.
     *
     * @param names the resolved labels, or {@code null} to clear
     */
    public void applySettlementDisplayNames(SettlementDisplayNames names) {
        this.city = names == null ? null : names.city();
        this.region = names == null ? null : names.region();
    }

    public UUID getCityId() {
        return cityId;
    }

    public void setCityId(UUID cityId) {
        this.cityId = cityId;
    }

    public UUID getDistrictId() {
        return districtId;
    }

    public void setDistrictId(UUID districtId) {
        this.districtId = districtId;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public String getBuildingNo() {
        return buildingNo;
    }

    public void setBuildingNo(String buildingNo) {
        this.buildingNo = buildingNo;
    }

    public String getLocationNote() {
        return locationNote;
    }

    public void setLocationNote(String locationNote) {
        this.locationNote = locationNote;
    }

    @JsonIgnore
    public String getAvatarR2Key() {
        return avatarR2Key;
    }

    public void setAvatarR2Key(String avatarR2Key) {
        this.avatarR2Key = avatarR2Key;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getInstagram() {
        return instagram;
    }

    public void setInstagram(String instagram) {
        this.instagram = instagram;
    }

    public String getProfessionalTitle() {
        return professionalTitle;
    }

    public void setProfessionalTitle(String professionalTitle) {
        this.professionalTitle = professionalTitle;
    }

    /**
     * No setter — {@code avg_rating}/{@code review_count} are written ONLY via
     * {@code ClientReviewRepository#recalculateClientRating}'s native {@code UPDATE}, never
     * through this entity.
     */
    public java.math.BigDecimal getAvgRating() {
        return avgRating;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    /**
     * Phase 442 — records that the user accepted the given terms and privacy-policy versions at
     * {@code acceptedAt} (server clock). Call before the first save; the columns are insert-only.
     *
     * @throws IllegalStateException if consent was already recorded (insert-only; no re-consent flow yet)
     */
    public void recordTermsConsent(Instant acceptedAt, String termsVersion, String privacyPolicyVersion) {
        if (termsAcceptedAt != null) {
            throw new IllegalStateException("Terms consent already recorded");
        }
        this.termsAcceptedAt = acceptedAt;
        this.termsVersion = termsVersion;
        this.privacyPolicyVersion = privacyPolicyVersion;
    }

    public Instant getTermsAcceptedAt() {
        return termsAcceptedAt;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public String getPrivacyPolicyVersion() {
        return privacyPolicyVersion;
    }
}
