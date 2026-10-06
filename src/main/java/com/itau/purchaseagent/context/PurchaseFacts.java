package com.itau.purchaseagent.context;

import com.itau.purchaseagent.context.ErpGateway.CostCenterBudget;
import com.itau.purchaseagent.context.ErpGateway.PurchaseRecord;
import com.itau.purchaseagent.context.ErpGateway.SupplierProfile;
import java.util.List;
import java.util.Optional;

/** Fatos buscados nos sistemas externos para uma solicitação. Fontes indisponíveis são registradas, não escondidas. */
public record PurchaseFacts(
        Optional<CostCenterBudget> budget,
        Optional<SupplierProfile> supplier,
        List<PurchaseRecord> history,
        List<String> unavailableSources) {

    public boolean sourceUnavailable(String source) {
        return unavailableSources.contains(source);
    }
}
