package com.itau.purchaseagent.api;

import com.itau.purchaseagent.registry.SkillRegistry;
import com.itau.purchaseagent.registry.SkillVersionEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CRUD versionado de skills (prompts, políticas, exemplos). */
@RestController
@RequestMapping("/v1/skills")
public class SkillController {

    private final SkillRegistry registry;

    public SkillController(SkillRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        registry.listAll().forEach((id, versions) -> out.put(id, versions.stream().map(v -> view(v, false)).toList()));
        return out;
    }

    @GetMapping("/{skillId}")
    public List<Map<String, Object>> versions(@PathVariable String skillId) {
        return registry.versions(skillId).stream().map(v -> view(v, true)).toList();
    }

    public record CreateSkill(String skillId, SkillVersionEntity.Type type, String version, String content,
                              String changelog) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateSkill body,
                                                      @RequestHeader(value = "X-User", defaultValue = "api") String user) {
        var v = registry.create(body.skillId(), body.type(), body.version() == null ? "1.0.0" : body.version(),
                required(body.content()), body.changelog(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(v, false));
    }

    public record NewVersion(String version, String content, String changelog, boolean activate) {}

    @PostMapping("/{skillId}/versions")
    public ResponseEntity<Map<String, Object>> addVersion(@PathVariable String skillId, @RequestBody NewVersion body,
                                                          @RequestHeader(value = "X-User", defaultValue = "api") String user) {
        var v = registry.addVersion(skillId, body.version(), required(body.content()), body.changelog(), user,
                body.activate());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(v, false));
    }

    @PostMapping("/{skillId}/versions/{version}/activate")
    public Map<String, Object> activate(@PathVariable String skillId, @PathVariable String version,
                                        @RequestHeader(value = "X-User", defaultValue = "api") String user) {
        return view(registry.activate(skillId, version, user), false);
    }

    @DeleteMapping("/{skillId}")
    public ResponseEntity<Void> delete(@PathVariable String skillId,
                                       @RequestHeader(value = "X-User", defaultValue = "api") String user) {
        registry.delete(skillId, user);
        return ResponseEntity.noContent().build();
    }

    private static String required(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content é obrigatório");
        }
        if (content.length() > 50_000) {
            throw new IllegalArgumentException("content acima de 50.000 caracteres");
        }
        return content;
    }

    private static Map<String, Object> view(SkillVersionEntity v, boolean withContent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skillId", v.getSkillId());
        m.put("type", v.getType());
        m.put("version", v.getVersion());
        m.put("status", v.getStatus());
        m.put("checksum", v.getChecksum());
        m.put("changelog", v.getChangelog());
        m.put("createdBy", v.getCreatedBy());
        m.put("createdAt", v.getCreatedAt());
        m.put("statusChangedAt", v.getStatusChangedAt());
        if (withContent) {
            m.put("content", v.getContent());
        }
        return m;
    }
}
