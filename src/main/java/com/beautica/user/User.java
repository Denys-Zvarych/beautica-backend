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
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "users")
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
