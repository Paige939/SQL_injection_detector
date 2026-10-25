package tw.edu.cse.nsysu.api.service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import tw.edu.cse.nsysu.ExtendedRuleMiner;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ExtendedRule;

/** Research-only filters over the same mined, ordered candidate schema. */
enum ExtendedRulePolicy {
    BASELINE,
    NO_NEGATIVE,
    SQL_CONTEXT;

    private static final Set<Integer> SQL_TOKENS =
            Set.of(5, 6, 7, 8, 9, 10, 11, 12, 16, 17, 18, 21, 22, 26, 27, 28);

    boolean accepts(ExtendedRule rule) {
        if (this == BASELINE) return true;
        if (rule.hasNegativeItem()) return false;
        if (this == NO_NEGATIVE) return true;
        int[] tokens = Arrays.stream(rule.antecedent)
                .map(item -> rule.sequential ? item : item - ExtendedRuleMiner.TOKEN_ITEM_BASE).toArray();
        // Token 15 is a comparison-expression shape, not proof of a true tautology.
        return Arrays.stream(tokens).anyMatch(token -> token == 15)
                || Arrays.stream(tokens).filter(SQL_TOKENS::contains).distinct().count() >= 2;
    }

    List<ExtendedRule> filter(List<ExtendedRule> candidates) {
        return candidates.stream().filter(this::accepts).toList();
    }
}
