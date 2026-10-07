package com.itau.purchaseagent.intake;

import com.itau.purchaseagent.contract.PurchaseRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Estágio 1: transforma uma entrada possivelmente incompleta/ruidosa em {@link NormalizedRequest}.
 * Nunca rejeita por qualidade de dado (isso é papel do JSON Schema); registra o problema e segue.
 */
@Service
public class IntakeService {

    static final int MAX_JUSTIFICATION_CHARS = 2000;
    private static final BigDecimal TOTAL_TOLERANCE = new BigDecimal("0.01");
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("BRL");
    private static final Set<String> URGENCIES = Set.of("LOW", "NORMAL", "HIGH", "CRITICAL");

    private final InjectionDetector injectionDetector;
    private final Clock clock;

    public IntakeService(InjectionDetector injectionDetector, Clock clock) {
        this.injectionDetector = injectionDetector;
        this.clock = clock;
    }

    public NormalizedRequest normalize(PurchaseRequest in) {
        List<String> issues = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        TreeSet<String> pii = new TreeSet<>();

        List<NormalizedRequest.Item> items = normalizeItems(in.items(), issues, missing, pii);
        BigDecimal computed = items.stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = reconcileTotal(in.totalAmount(), computed, issues);

        String currency = normalizeCurrency(in.currency(), issues);

        String supplierTaxId = null;
        String supplierName = null;
        if (in.supplier() == null || (isBlank(in.supplier().taxId()) && isBlank(in.supplier().name()))) {
            issues.add("SUPPLIER_MISSING");
            missing.add("Fornecedor (CNPJ e razão social)");
        } else {
            supplierName = trimToNull(in.supplier().name());
            supplierTaxId = digits(in.supplier().taxId());
            if (supplierTaxId == null) {
                issues.add("SUPPLIER_TAXID_MISSING");
                missing.add("CNPJ do fornecedor");
            } else if (!Cnpj.isValid(supplierTaxId)) {
                issues.add("SUPPLIER_TAXID_INVALID");
                missing.add("CNPJ válido do fornecedor");
            }
        }

        String justification = masked(trimToNull(in.justification()), pii);
        if (justification == null) {
            issues.add("JUSTIFICATION_MISSING");
            missing.add("Justificativa de negócio da compra");
        } else {
            if (justification.length() > MAX_JUSTIFICATION_CHARS) {
                justification = justification.substring(0, MAX_JUSTIFICATION_CHARS);
                issues.add("JUSTIFICATION_TRUNCATED");
            }
            if (justification.length() < 15) {
                issues.add("JUSTIFICATION_TOO_SHORT");
            }
        }

        if (!pii.isEmpty()) {
            issues.add("PII_MASKED:" + String.join(",", pii));
        }
        boolean suspicious = injectionDetector.isSuspicious(in.justification())
                || in.items().stream().anyMatch(i -> injectionDetector.isSuspicious(i.description()));
        if (suspicious) {
            issues.add("SUSPICIOUS_INPUT");
        }

        LocalDate neededBy = parseDate(in.neededBy(), issues);
        String urgency = normalizeUrgency(in.urgency(), issues);

        return new NormalizedRequest(
                in.requestId().trim(),
                in.requester().employeeId(),
                trimToNull(in.requester().department()),
                in.requester().costCenter().trim().toUpperCase(Locale.ROOT),
                supplierTaxId,
                supplierName,
                List.copyOf(items),
                total,
                currency,
                justification,
                neededBy,
                urgency,
                List.copyOf(issues),
                List.copyOf(missing),
                suspicious);
    }

    private List<NormalizedRequest.Item> normalizeItems(
            List<PurchaseRequest.Item> raw, List<String> issues, List<String> missing, TreeSet<String> pii) {
        List<NormalizedRequest.Item> items = new ArrayList<>();
        boolean priceMissing = false;
        for (PurchaseRequest.Item it : raw) {
            int qty = it.quantity() == null ? 0 : it.quantity();
            if (it.quantity() == null || qty == 0) {
                issues.add("ITEM_QUANTITY_MISSING:" + safe(it.sku()));
                qty = Math.max(qty, 1);
            }
            BigDecimal price = it.unitPrice();
            if (price == null) {
                priceMissing = true;
                issues.add("ITEM_PRICE_MISSING:" + safe(it.sku()));
                price = BigDecimal.ZERO;
            }
            String category = trimToNull(it.category());
            if (category == null) {
                issues.add("ITEM_CATEGORY_MISSING:" + safe(it.sku()));
            } else {
                category = category.toUpperCase(Locale.ROOT).replace(' ', '_');
            }
            items.add(new NormalizedRequest.Item(trimToNull(it.sku()), masked(trimToNull(it.description()), pii), category, qty,
                    price.setScale(2, RoundingMode.HALF_UP)));
        }
        if (priceMissing) {
            missing.add("Preço unitário de todos os itens");
        }
        return items;
    }

    private BigDecimal reconcileTotal(BigDecimal declared, BigDecimal computed, List<String> issues) {
        if (declared == null) {
            issues.add("TOTAL_MISSING_COMPUTED");
            return computed;
        }
        BigDecimal d = declared.setScale(2, RoundingMode.HALF_UP);
        if (computed.signum() > 0 && d.subtract(computed).abs().compareTo(TOTAL_TOLERANCE) > 0) {
            // Fonte da verdade são os itens; a divergência vira sinal para o modelo e para auditoria.
            issues.add("TOTAL_MISMATCH_RECALCULATED");
            return computed;
        }
        return computed.signum() > 0 ? computed : d;
    }

    private String normalizeCurrency(String currency, List<String> issues) {
        if (isBlank(currency)) {
            issues.add("CURRENCY_DEFAULTED");
            return "BRL";
        }
        String c = currency.trim().toUpperCase(Locale.ROOT).replace("R$", "BRL");
        if (!SUPPORTED_CURRENCIES.contains(c)) {
            issues.add("UNSUPPORTED_CURRENCY:" + c);
        }
        return c;
    }

    private LocalDate parseDate(String value, List<String> issues) {
        if (isBlank(value)) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value.trim().substring(0, Math.min(10, value.trim().length())));
            if (date.isBefore(LocalDate.now(clock))) {
                issues.add("NEEDED_BY_IN_PAST");
            }
            return date;
        } catch (DateTimeParseException e) {
            issues.add("NEEDED_BY_INVALID");
            return null;
        }
    }

    private String normalizeUrgency(String urgency, List<String> issues) {
        if (isBlank(urgency)) {
            return "NORMAL";
        }
        String u = urgency.trim().toUpperCase(Locale.ROOT);
        if (u.equals("URGENTE") || u.equals("URGENT")) {
            u = "HIGH";
        }
        if (!URGENCIES.contains(u)) {
            issues.add("URGENCY_UNKNOWN");
            return "NORMAL";
        }
        return u;
    }

    /** Minimização (LGPD): PII em texto livre não chega ao LLM nem ao contexto. */
    private static String masked(String text, TreeSet<String> found) {
        PiiMasker.Result r = PiiMasker.mask(text);
        found.addAll(r.kinds());
        return r.text();
    }

    private static String digits(String s) {
        if (s == null) {
            return null;
        }
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    private static String safe(String s) {
        return s == null ? "?" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
