package com.itau.purchaseagent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Valida JSON contra os schemas versionados em {@code resources/schemas}. Schemas são compilados uma vez. */
@Component
public class SchemaValidator {

    public static final String REQUEST_V1 = "purchase-request.v1.json";
    public static final String DECISION_V1 = "purchase-decision.v1.json";
    public static final String LLM_ASSESSMENT_V1 = "llm-assessment.v1.json";

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String, JsonSchema> cache = new ConcurrentHashMap<>();

    public List<String> validate(String schemaName, JsonNode json) {
        return schema(schemaName).validate(json).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
    }

    private JsonSchema schema(String name) {
        return cache.computeIfAbsent(name, n -> {
            try (InputStream in = new ClassPathResource("schemas/" + n).getInputStream()) {
                return factory.getSchema(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Schema não encontrado: " + n, e);
            }
        });
    }
}
