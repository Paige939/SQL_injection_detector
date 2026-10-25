package tw.edu.cse.nsysu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ExtendedRule;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ItemMode;

public class ExtendedRuleMinerTest {
    @Test
    public void tokenizesAttackTokensInOrderAndCollapsesRepeats() {
        List<Integer> tokens = ExtendedRuleMiner.tokenize("1' OR 1=1 --");
        // quote(1), or(3), tautology(15), comment(13)
        assertEquals(List.of(1, 3, 15, 13), tokens);
    }

    @Test
    public void negativeItemsMarkAbsentTokens() {
        String sql = "SELECT id FROM users";
        Set<Integer> items = ExtendedRuleMiner.items(sql, ExtendedRuleMiner.tokenize(sql), ItemMode.BASE_TOKEN_NEG);
        int unionToken = ExtendedRuleMiner.TOKEN_ITEM_BASE + 5;
        assertFalse(items.contains(unionToken));
        assertTrue(items.contains(ExtendedRuleMiner.NEG_OFFSET + unionToken));
    }

    @Test
    public void sequentialRuleRequiresTokenOrder() {
        ExtendedRule rule = new ExtendedRule(new int[] {1, 3, 13}, true, 0.1, 0.9, 1.5);
        assertTrue(rule.matches(Set.of(), List.of(1, 6, 3, 13)));
        assertFalse(rule.matches(Set.of(), List.of(13, 3, 1)));
    }

    @Test
    public void ruleOnlyConfidenceUsesStrongestMatchedRuleAndZeroWhenNoneMatch() {
        ExtendedRule matching = new ExtendedRule(new int[] {2}, false, 0.1, 0.8, 1.5);
        ExtendedRule stronger = new ExtendedRule(new int[] {3}, false, 0.1, 0.9, 1.7);
        assertEquals(0.9, ExtendedRuleMiner.maxMatchedConfidence(
            List.of(matching, stronger), Set.of(2, 3), List.of()), 0.0);
        assertEquals(0.0, ExtendedRuleMiner.maxMatchedConfidence(
            List.of(matching, stronger), Set.of(4), List.of()), 0.0);
    }

    @Test
    public void itemRuleWithNegativeItemMatchesOnlyWhenAbsent() {
        int neg = ExtendedRuleMiner.NEG_OFFSET + ExtendedRuleMiner.TOKEN_ITEM_BASE + 5;
        ExtendedRule rule = new ExtendedRule(new int[] {2, neg}, false, 0.1, 0.8, 1.2);
        assertTrue(rule.hasNegativeItem());
        assertTrue(rule.matches(Set.of(2, neg), List.of()));
        assertFalse(rule.matches(Set.of(2), List.of()));
    }

    @Test
    public void minesLiftFilteredRulesAndSequenceRules() throws Exception {
        List<Set<Integer>> transactions = new java.util.ArrayList<>();
        List<List<Integer>> sequences = new java.util.ArrayList<>();
        int[] labels = new int[20];
        for (int i = 0; i < 20; i++) {
            boolean attack = i < 10;
            labels[i] = attack ? 1 : 0;
            transactions.add(attack ? Set.of(2, 7) : Set.of(3, 7));
            sequences.add(attack ? List.of(1, 3, 13) : List.of(6, 7));
        }
        List<ExtendedRule> itemRules = ExtendedRuleMiner.mineItemRules(transactions, labels, 0.2, 0.6, 1.0, 3, 10);
        assertTrue(itemRules.stream().anyMatch(r -> r.antecedent.length == 1 && r.antecedent[0] == 2));
        assertTrue(itemRules.stream().noneMatch(r -> r.antecedent.length == 1 && r.antecedent[0] == 7));

        List<ExtendedRule> seqRules = ExtendedRuleMiner.mineSequenceRules(sequences, labels, 0.2, 0.6, 1.0, 3, 10);
        assertFalse(seqRules.isEmpty());
        assertTrue(seqRules.stream().allMatch(r -> r.sequential));
        assertTrue(seqRules.stream().anyMatch(r -> r.matches(Set.of(), List.of(1, 3, 13))));
        assertTrue(seqRules.stream().noneMatch(r -> r.matches(Set.of(), List.of(6, 7))));
    }
}
