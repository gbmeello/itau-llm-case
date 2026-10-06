package com.itau.purchaseagent.context;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;

/** ERP simulado a partir de {@code mock-data/erp.json}. Pode simular indisponibilidade para testes de fallback. */
public class MockErpGateway implements ErpGateway {

    private final Data data;
    private volatile boolean unavailable;

    public MockErpGateway(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("mock-data/erp.json").getInputStream()) {
            this.data = mapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(in, Data.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Usado em testes/evals para exercitar o caminho "fonte de contexto fora do ar". */
    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    @Override
    public Optional<CostCenterBudget> costCenterBudget(String costCenterId) {
        checkAvailable();
        return data.costCenters().stream().filter(c -> c.id().equalsIgnoreCase(costCenterId)).findFirst();
    }

    @Override
    public Optional<SupplierProfile> supplierProfile(String taxId) {
        checkAvailable();
        return data.suppliers().stream().filter(s -> s.taxId().equals(taxId)).findFirst();
    }

    @Override
    public List<PurchaseRecord> purchaseHistory(String costCenterId, LocalDate since) {
        checkAvailable();
        return data.purchaseHistory().stream()
                .filter(p -> p.costCenter().equalsIgnoreCase(costCenterId) && !p.date().isBefore(since))
                .sorted(Comparator.comparing(PurchaseRecord::date).reversed())
                .toList();
    }

    @Override
    public String sourceName() {
        return "erp-mock";
    }

    private void checkAvailable() {
        if (unavailable) {
            throw new ContextSourceUnavailableException("ERP mock indisponível (simulado)");
        }
    }

    record Data(List<CostCenterBudget> costCenters, List<SupplierProfile> suppliers,
                List<PurchaseRecord> purchaseHistory) {}
}
