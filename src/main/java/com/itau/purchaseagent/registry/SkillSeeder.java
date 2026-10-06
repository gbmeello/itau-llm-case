package com.itau.purchaseagent.registry;

import com.itau.purchaseagent.registry.SkillVersionEntity.Type;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Carrega as skills versionadas em {@code resources/skills/<tipo>/<skillId>/<versão>.<ext>} no registry.
 * Os arquivos (versionados em git) são a fonte inicial; o registry permite evoluir em runtime via API.
 * A versão mais alta de cada skill é ativada.
 */
@Component
@Order(0)
public class SkillSeeder implements ApplicationRunner {

    private static final Pattern PATH = Pattern.compile(
            "skills/(prompts|policies|examples)/([a-z0-9-]+)/(\\d+\\.\\d+\\.\\d+)\\.(md|json)$");
    private static final Pattern CHANGELOG = Pattern.compile("(?m)^changelog:\\s*(.+)$");

    private final SkillRegistry registry;

    public SkillSeeder(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAll();
    }

    public void seedAll() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:skills/**/*.*");
            Map<String, List<Seed>> bySkill = new LinkedHashMap<>();
            for (Resource r : resources) {
                Matcher m = PATH.matcher(r.getURL().toString().replace('\\', '/'));
                if (!m.find()) {
                    continue;
                }
                String content = r.getContentAsString(StandardCharsets.UTF_8);
                Type type = switch (m.group(1)) {
                    case "prompts" -> Type.PROMPT;
                    case "policies" -> Type.POLICY;
                    default -> Type.EXAMPLES;
                };
                Matcher cl = CHANGELOG.matcher(content);
                String changelog = cl.find() ? cl.group(1).trim() : "seed";
                bySkill.computeIfAbsent(m.group(2), k -> new ArrayList<>())
                        .add(new Seed(m.group(2), type, m.group(3), content, changelog));
            }
            bySkill.values().forEach(seeds -> {
                seeds.sort(Comparator.comparing(Seed::version, SkillRegistry::compareSemver));
                for (int i = 0; i < seeds.size(); i++) {
                    Seed s = seeds.get(i);
                    registry.seed(s.skillId(), s.type(), s.version(), s.content(), s.changelog(),
                            i == seeds.size() - 1);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record Seed(String skillId, Type type, String version, String content, String changelog) {}
}
