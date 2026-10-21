package io.github.gulliby.propdrift;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure check and filter for the rule codes PR001 to PR005. */
public final class RuleFilter {
    private static final Set<String> RULES = Set.of("PR001", "PR002", "PR003", "PR004", "PR005");

    private RuleFilter() {
    }

    /** Returns true exactly for the five upper-case rule codes. */
    static boolean isRule(String code) {
        return code != null && RULES.contains(code);
    }

    /** Returns the findings unchanged for an empty set, otherwise those whose rule is in the set. */
    static List<Finding> keep(List<Finding> findings, Set<String> rules) {
        if (rules.isEmpty()) {
            return findings;
        }
        List<Finding> kept = new ArrayList<>();
        for (Finding finding : findings) {
            if (rules.contains(finding.rule())) {
                kept.add(finding);
            }
        }
        return kept;
    }
}
