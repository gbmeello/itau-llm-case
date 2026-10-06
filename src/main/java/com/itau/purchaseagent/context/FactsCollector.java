package com.itau.purchaseagent.context;

import com.itau.purchaseagent.intake.NormalizedRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pré-busca determinística dos fatos (budget, fornecedor, histórico de 12 meses).
 * Falha de uma fonte não derruba o fluxo: vira {@code unavailableSources} e bloqueia aprovação automática.
 */
@Service
public class FactsCollector {

    public static final String BUDGET = "erp.budget";
    public static final String SUPPLIER = "erp.supplier_registry";
    public static final String HISTORY = "erp.purchase_history";

    private static final Logger log = LoggerFactory.getLogger(FactsCollector.class);

    private final ErpGateway erp;
    private final Clock clock;

    public FactsCollector(ErpGateway erp, Clock clock) {
        this.erp = erp;
        this.clock = clock;
    }

    public PurchaseFacts collect(NormalizedRequest request) {
        List<String> unavailable = new ArrayList<>();
        var budget = fetch(BUDGET, unavailable, () -> erp.costCenterBudget(request.costCenter()));
        var supplier = request.supplierTaxId() == null
                ? Optional.<ErpGateway.SupplierProfile>empty()
                : fetch(SUPPLIER, unavailable, () -> erp.supplierProfile(request.supplierTaxId()));
        var history = fetch(HISTORY, unavailable,
                () -> Optional.of(erp.purchaseHistory(request.costCenter(), LocalDate.now(clock).minusMonths(12))))
                .orElse(List.of());
        return new PurchaseFacts(budget, supplier, history, List.copyOf(unavailable));
    }

    private <T> Optional<T> fetch(String source, List<String> unavailable, Supplier<Optional<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("context_source_unavailable source={} error={}", source, e.getMessage());
            unavailable.add(source);
            return Optional.empty();
        }
    }
}
