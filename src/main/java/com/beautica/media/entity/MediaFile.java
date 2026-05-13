package com.beautica.media.entity;

import com.beautica.common.AuditableEntity;
import com.beautica.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
    name = "media_files",
    indexes = {
        @Index(name = "idx_media_files_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_media_files_uploader_media", columnList = "uploader_id, media_type"),
        // ux_media_files_avatar is a partial unique index (media_type = 'AVATAR') defined in V39 migration;
        // JPA @Index has no WHERE clause support — the actual DB constraint is enforced by the migration only
        @Index(name = "ux_media_files_avatar", columnList = "entity_type, entity_id", unique = true)
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaFile extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploader_id", nullable = false)
    private User uploader;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 20)
    private MediaType mediaType;

    @Column(name = "r2_key", nullable = false, length = 500)
    private String r2Key;

    @Column(name = "r2_url", nullable = false, length = 1000)
    private String r2Url;
}
