package com.itau.purchaseagent.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.purchaseagent.TestData;
import com.itau.purchaseagent.context.FactsCollector;
import com.itau.purchaseagent.context.MockErpGateway;
import com.itau.purchaseagent.context.PurchaseFacts;
import com.itau.purchaseagent.contract.PurchaseRequest;
import com.itau.purchaseagent.intake.InjectionDetector;
import com.itau.purchaseagent.intake.IntakeService;
import com.itau.purchaseagent.intake.NormalizedRequest;
import com.itau.purchaseagent.policy.PolicyOutcome.Result;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PolicyEngineTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockErpGateway erp = new MockErpGateway(mapper);
    private final IntakeService intake = new IntakeService(new InjectionDetector(), TestData.CLOCK);
    private final FactsCollector facts = new FactsCollector(erp, TestData.CLOCK);
    private final PolicyEngine engine = new PolicyEngine(TestData.CLOCK);
    private final PolicyConfig cfg;

    PolicyEngineTest() throws IOException {
        cfg = mapper.readValue(new ClassPathResource("skills/policies/purchase-policies/1.0.0.json").getInputStream(),
                PolicyConfig.class);
    }

    private PolicyOutcome eval(PurchaseRequest r) {
        NormalizedRequest n = intake.normalize(r);
        PurchaseFacts f = facts.collect(n);
        return engine.evaluate(n, f, cfg);
    }

    @Test
    void smallPurchaseFromApprovedSupplierPassesAtLevelZero() {
        PolicyOutcome o = eval(TestData.acme(2, "1950"));

        assertThat(o.approvalLevel()).isZero();
        assertThat(o.blocksApproval()).isFalse();
        assertThat(o.hardReject()).isFalse();
    }

    @Test
    void blockedSupplierIsHardReject() {
        PolicyOutcome o = eval(TestData.request("CC-4410", TestData.BLOCKED, "IT_HARDWARE", 1, "100", "x"));

        assertThat(o.hardReject()).isTrue();
        assertThat(o.withResult(Result.HARD_REJECT)).extracting(PolicyOutcome.PolicyCheck::reasonCode)
                .containsExactly("SUPPLIER_BLOCKED");
    }

    @Test
    void amountAboveAvailableBudgetIsHardReject() {
        // CC-4410: 600k - 410k = 190k disponíveis
        PolicyOutcome o = eval(TestData.acme(4, "50000"));

        assertThat(o.withResult(Result.HARD_REJECT)).extracting(PolicyOutcome.PolicyCheck::reasonCode)
                .containsExactly("BUDGET_INSUFFICIENT");
    }

    @Test
    void approvalLevelsFollowThresholds() {
        assertThat(eval(TestData.acme(1, "10000")).approvalLevel()).isZero();
        assertThat(eval(TestData.acme(1, "10000.01")).approvalLevel()).isEqualTo(1);
        assertThat(eval(TestData.acme(1, "60000")).approvalLevel()).isEqualTo(2);
    }

    @Test
    void newHighRiskSupplierBlocksApproval() {
        PolicyOutcome o = eval(TestData.request("CC-9000", TestData.RAPIDEZ, "CONSULTING", 1, "45000", "x"));

        assertThat(o.blocksApproval()).isTrue();
        assertThat(o.withResult(Result.BLOCKS_APPROVAL)).extracting(PolicyOutcome.PolicyCheck::reasonCode)
                .contains("SUPPLIER_HIGH_RISK", "SUPPLIER_NEW");
    }

    @Test
    void detectsSplitPurchaseAcrossThirtyDays() {
        // CC-2200 já tem 4.800 + 4.900 com o mesmo fornecedor/categoria nos últimos 30 dias
        PolicyOutcome o = eval(TestData.request("CC-2200", "78124569000156", "OFFICE_SUPPLIES", 1, "4950", "x"));

        assertThat(o.withResult(Result.BLOCKS_APPROVAL)).extracting(PolicyOutcome.PolicyCheck::reasonCode)
                .contains("SPLIT_PURCHASE_SUSPECTED");
    }

    @Test
    void unavailableErpBlocksApprovalInsteadOfFailing() {
        erp.setUnavailable(true);
        try {
            PolicyOutcome o = eval(TestData.acme(1, "100"));
            assertThat(o.blocksApproval()).isTrue();
            assertThat(o.hardReject()).isFalse();
        } finally {
            erp.setUnavailable(false);
        }
    }

    @Test
    void restrictedCategoryAboveLimitBlocksApproval() {
        PolicyOutcome o = eval(TestData.request("CC-2200", "60504030000167", "GIFTS", 10, "350", "x"));

        assertThat(o.withResult(Result.BLOCKS_APPROVAL)).extracting(PolicyOutcome.PolicyCheck::policyId)
                .contains("POL-CAT-004");
    }
}
