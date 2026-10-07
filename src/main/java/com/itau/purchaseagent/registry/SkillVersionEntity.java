package com.itau.purchaseagent.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** Uma versão imutável de uma skill. Só {@code status} muda depois de criada. */
@Entity
@Table(name = "skill_version", uniqueConstraints = @UniqueConstraint(columnNames = {"skillId", "version"}))
public class SkillVersionEntity {

    public enum Type { PROMPT, POLICY, EXAMPLES }

    public enum Status { DRAFT, ACTIVE, DEPRECATED, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String skillId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Column(nullable = false, length = 32)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, length = 1_048_576) // texto longo portável (H2 e PostgreSQL), sem LOB/oid
    private String content;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(length = 1000)
    private String changelog;

    @Column(nullable = false, length = 64)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant statusChangedAt;

    protected SkillVersionEntity() {}

    public SkillVersionEntity(String skillId, Type type, String version, Status status, String content,
                              String checksum, String changelog, String createdBy, Instant createdAt) {
        this.skillId = skillId;
        this.type = type;
        this.version = version;
        this.status = status;
        this.content = content;
        this.checksum = checksum;
        this.changelog = changelog;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.statusChangedAt = createdAt;
    }

    public void changeStatus(Status newStatus, Instant at) {
        this.status = newStatus;
        this.statusChangedAt = at;
    }

    public Long getId() { return id; }
    public String getSkillId() { return skillId; }
    public Type getType() { return type; }
    public String getVersion() { return version; }
    public Status getStatus() { return status; }
    public String getContent() { return content; }
    public String getChecksum() { return checksum; }
    public String getChangelog() { return changelog; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
}
