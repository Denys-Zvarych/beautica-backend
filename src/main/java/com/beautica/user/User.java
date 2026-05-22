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

import java.time.Instant;
import java.util.UUID;


@Entity
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

    @Column(name = "business_name", length = 255)
    private String businessName;

    @Column(name = "salon_id")
    private UUID salonId;

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

    public UUID getSalonId() {
        return salonId;
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
}
