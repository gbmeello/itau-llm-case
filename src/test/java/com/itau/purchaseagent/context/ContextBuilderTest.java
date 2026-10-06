package com.itau.purchaseagent.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.itau.purchaseagent.TestData;
import com.itau.purchaseagent.context.ContextItem.Layer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextBuilderTest {

    private static ContextItem item(String id, Layer layer, int chars) {
        return new ContextItem(id, layer, "test", "x".repeat(chars), Map.of());
    }

    @Test
    void cutsLowestPriorityFirstAndNeverCutsMandatoryLayers() {
        // Budget apertado: 1.000 tokens no total, 200 reservados ao system
        ContextBudget tight = new ContextBudget(1000, 200, 2000, 2000, 300, 300, 300, 300, 300);
        ContextBuilder builder = new ContextBuilder(tight, TestData.CLOCK);
        List<ContextItem> candidates = new ArrayList<>(List.of(
                item("EV-HIST-1", Layer.P3_HISTORY, 700),
                item("EV-REQ", Layer.P0_REQUEST, 1400),
                item("EV-POL-1", Layer.P0_POLICY, 350),
                item("EV-BUD", Layer.P1_FACTS, 300),
                item("EV-PT-1", Layer.P2_POLICY_TEXT, 700)));

        AgentContext ctx = builder.select(candidates, 0);

        assertThat(ctx.evidenceIds()).contains("EV-REQ", "EV-POL-1", "EV-BUD");
        assertThat(ctx.excludedIds()).contains("EV-HIST-1");
        assertThat(ctx.estimatedTokens()).isLessThanOrEqualTo(800);
        assertThat(ctx.truncated()).isTrue();
    }

    @Test
    void respectsPerLayerLimitEvenWhenTotalHasRoom() {
        ContextBudget budget = new ContextBudget(100000, 0, 5000, 5000, 5000, 5000, 100, 5000, 5000);
        ContextBuilder builder = new ContextBuilder(budget, TestData.CLOCK);

        AgentContext ctx = builder.select(List.of(
                item("EV-REQ", Layer.P0_REQUEST, 100),
                item("EV-HIST-1", Layer.P3_HISTORY, 200),
                item("EV-HIST-2", Layer.P3_HISTORY, 200)), 0);

        assertThat(ctx.evidenceIds()).contains("EV-HIST-1").doesNotContain("EV-HIST-2");
    }

    @Test
    void dropsFewShotExamplesWhenBudgetIsExhausted() {
        ContextBudget budget = new ContextBudget(600, 100, 5000, 5000, 5000, 5000, 5000, 5000, 5000);
        ContextBuilder builder = new ContextBuilder(budget, TestData.CLOCK);

        AgentContext ctx = builder.select(List.of(item("EV-REQ", Layer.P0_REQUEST, 1600)), 400);

        assertThat(ctx.excludedIds()).contains("FEW_SHOT_EXAMPLES");
    }
}
