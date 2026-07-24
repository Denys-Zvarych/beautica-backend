package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.AuditableEntity;
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

    @Column(name = "email", nullable = false, unique = true)
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
     * {@code ON DELETE CASCADE}. Any user-deletion flow MUST call
     * {@code MediaService.deleteByUploader(userId)} before deleting the row — it sweeps this
     * blob together with the user's {@code media_files} blobs. Skipping it leaves the avatar
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

    public boolean isEmailVerified() {
        return emailVerified;
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
     * Marks every access token issued before {@code tokensValidAfter} as invalid.
     * Callers MUST supply the current instant at the moment of a password reset —
     * this mutator is intentionally narrow: it exists solely for
     * {@code PasswordResetService.resetPassword} and must not be called from any
     * other write path.
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
}
