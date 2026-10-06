package com.itau.purchaseagent.registry;

import com.itau.purchaseagent.registry.SkillVersionEntity.Status;
import com.itau.purchaseagent.registry.SkillVersionEntity.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD versionado de skills (prompts, políticas, exemplos).
 * <ul>
 *   <li>Versões são imutáveis: "update" = nova versão.</li>
 *   <li>Uma versão ACTIVE por skill; ativar outra deprecia a anterior (rollback = reativar versão antiga).</li>
 *   <li>Delete é lógico (DELETED) para manter rastreabilidade das decisões já tomadas.</li>
 *   <li>Skills essenciais ao agente não podem ser removidas, apenas versionadas.</li>
 * </ul>
 */
@Service
public class SkillRegistry {

    public static final String ANALYST = "analyst";
    public static final String REPAIR = "repair";
    public static final String COMPLIANCE = "compliance-reviewer";
    public static final String CASE_SUMMARIZER = "case-summarizer";
    public static final String EXAMPLES = "analyst-examples";
    public static final String POLICIES = "purchase-policies";

    static final Set<String> PROTECTED = Set.of(ANALYST, REPAIR, COMPLIANCE, CASE_SUMMARIZER, POLICIES);

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillVersionRepository repo;
    private final Clock clock;

    public SkillRegistry(SkillVersionRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public record ActiveSkill(String skillId, String version, String content) {
        public String ref() {
            return skillId + "@" + version;
        }
    }

    @Transactional(readOnly = true)
    public Optional<ActiveSkill> active(String skillId) {
        return repo.findBySkillIdAndStatus(skillId, Status.ACTIVE)
                .map(e -> new ActiveSkill(e.getSkillId(), e.getVersion(), e.getContent()));
    }

    public ActiveSkill required(String skillId) {
        return active(skillId).orElseThrow(() -> new SkillException(SkillException.Reason.NOT_FOUND,
                "Skill obrigatória sem versão ativa: " + skillId));
    }

    @Transactional(readOnly = true)
    public Map<String, List<SkillVersionEntity>> listAll() {
        Map<String, List<SkillVersionEntity>> out = new TreeMap<>();
        repo.findAll().forEach(v -> out.computeIfAbsent(v.getSkillId(), k -> new java.util.ArrayList<>()).add(v));
        return out;
    }

    @Transactional(readOnly = true)
    public List<SkillVersionEntity> versions(String skillId) {
        List<SkillVersionEntity> v = repo.findBySkillIdOrderByCreatedAtAsc(skillId);
        if (v.isEmpty()) {
            throw new SkillException(SkillException.Reason.NOT_FOUND, "Skill não encontrada: " + skillId);
        }
        return v;
    }

    /** Cria uma skill nova (primeira versão já ativa). */
    @Transactional
    public SkillVersionEntity create(String skillId, Type type, String version, String content, String changelog,
                                     String author) {
        if (repo.existsBySkillId(skillId)) {
            throw new SkillException(SkillException.Reason.CONFLICT, "Skill já existe: " + skillId);
        }
        SkillVersionEntity e = repo.save(new SkillVersionEntity(skillId, type, version, Status.ACTIVE, content,
                sha256(content), changelog, author, now()));
        log.info("skill_created skill={} version={} author={}", skillId, version, author);
        return e;
    }

    /** "Update": adiciona uma versão nova. Opcionalmente já ativa. */
    @Transactional
    public SkillVersionEntity addVersion(String skillId, String version, String content, String changelog,
                                         String author, boolean activate) {
        List<SkillVersionEntity> existing = versions(skillId);
        SkillVersionEntity last = existing.getLast();
        if (last.getStatus() == Status.DELETED && existing.stream().allMatch(v -> v.getStatus() == Status.DELETED)) {
            throw new SkillException(SkillException.Reason.CONFLICT, "Skill removida: " + skillId);
        }
        if (repo.findBySkillIdAndVersion(skillId, version).isPresent()) {
            throw new SkillException(SkillException.Reason.CONFLICT,
                    "Versão já existe (versões são imutáveis): " + skillId + "@" + version);
        }
        if (compareSemver(version, maxVersion(existing)) <= 0) {
            throw new SkillException(SkillException.Reason.INVALID,
                    "Nova versão deve ser maior que " + maxVersion(existing));
        }
        SkillVersionEntity e = repo.save(new SkillVersionEntity(skillId, last.getType(), version, Status.DRAFT,
                content, sha256(content), changelog, author, now()));
        log.info("skill_version_added skill={} version={} author={}", skillId, version, author);
        if (activate) {
            return activate(skillId, version, author);
        }
        return e;
    }

    /** Ativa uma versão (também usado para rollback). */
    @Transactional
    public SkillVersionEntity activate(String skillId, String version, String author) {
        SkillVersionEntity target = repo.findBySkillIdAndVersion(skillId, version)
                .orElseThrow(() -> new SkillException(SkillException.Reason.NOT_FOUND,
                        "Versão não encontrada: " + skillId + "@" + version));
        if (target.getStatus() == Status.DELETED) {
            throw new SkillException(SkillException.Reason.CONFLICT, "Versão removida não pode ser ativada");
        }
        repo.findBySkillIdAndStatus(skillId, Status.ACTIVE)
                .filter(cur -> !cur.getId().equals(target.getId()))
                .ifPresent(cur -> cur.changeStatus(Status.DEPRECATED, now()));
        target.changeStatus(Status.ACTIVE, now());
        log.info("skill_activated skill={} version={} author={}", skillId, version, author);
        return target;
    }

    /** Remoção lógica de todas as versões. */
    @Transactional
    public void delete(String skillId, String author) {
        if (PROTECTED.contains(skillId)) {
            throw new SkillException(SkillException.Reason.CONFLICT,
                    "Skill essencial ao agente não pode ser removida (crie uma nova versão): " + skillId);
        }
        versions(skillId).forEach(v -> v.changeStatus(Status.DELETED, now()));
        log.info("skill_deleted skill={} author={}", skillId, author);
    }

    /** Usado pelo seed: idempotente por (skillId, version). */
    @Transactional
    public void seed(String skillId, Type type, String version, String content, String changelog, boolean activate) {
        if (repo.findBySkillIdAndVersion(skillId, version).isPresent()) {
            return;
        }
        repo.save(new SkillVersionEntity(skillId, type, version, Status.DRAFT, content, sha256(content), changelog,
                "seed", now()));
        if (activate) {
            activate(skillId, version, "seed");
        } else {
            repo.findBySkillIdAndVersion(skillId, version).ifPresent(v -> v.changeStatus(Status.DEPRECATED, now()));
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static String maxVersion(List<SkillVersionEntity> versions) {
        return versions.stream().map(SkillVersionEntity::getVersion).max(SkillRegistry::compareSemver).orElse("0.0.0");
    }

    static int compareSemver(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null || !v.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new SkillException(SkillException.Reason.INVALID, "Versão deve ser semver MAJOR.MINOR.PATCH: " + v);
        }
        String[] p = v.split("\\.");
        return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
