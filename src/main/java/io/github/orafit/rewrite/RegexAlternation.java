package io.github.orafit.rewrite;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a regular-expression alternation means the same in Oracle and PostgreSQL.
 *
 * <p>Oracle tries alternatives left to right and keeps the first that lets the whole pattern match;
 * PostgreSQL keeps the longest match. The two agree only when no two alternatives can match at the
 * same position, which this class proves for the one shape it understands: alternatives made only
 * of literal characters, none a prefix of another. Any other alternation is reported as ambiguous.
 */
final class RegexAlternation {
    private static final String LITERAL_PUNCTUATION = " _-,:;@=!%&/<>'\"";

    private RegexAlternation() {}

    static boolean ambiguous(String pattern) {
        Deque<Level> stack = new ArrayDeque<>();
        Level level = new Level();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
                level.opaque();
            } else if (c == '[') {
                i = endOfBracket(pattern, i);
                level.opaque();
            } else if (c == '(') {
                stack.push(level);
                level = new Level();
            } else if (c == ')' && !stack.isEmpty()) {
                boolean alternation = level.alternatives.size() > 1;
                if (alternation && !level.safe()) return true;
                level = stack.pop();
                level.opaque();
                if (alternation) level.nestedAlternation = true;
            } else if (c == '|') {
                level.alternatives.add(new StringBuilder());
                level.currentLiteral = true;
            } else if (Character.isLetterOrDigit(c) || LITERAL_PUNCTUATION.indexOf(c) >= 0) {
                level.literal(c);
            } else {
                level.opaque();
            }
        }
        // An unbalanced pattern is PostgreSQL's to reject; treat any open group like the top level.
        while (!stack.isEmpty()) {
            if (level.alternatives.size() > 1 && !level.safe()) return true;
            level = stack.pop();
        }
        return level.alternatives.size() > 1 && !level.safe();
    }

    private static int endOfBracket(String pattern, int open) {
        int i = open + 1;
        if (i < pattern.length() && pattern.charAt(i) == '^') i++;
        if (i < pattern.length() && pattern.charAt(i) == ']') i++;
        while (i < pattern.length() && pattern.charAt(i) != ']') {
            if (pattern.charAt(i) == '['
                    && i + 1 < pattern.length()
                    && ":.=".indexOf(pattern.charAt(i + 1)) >= 0) {
                int close = pattern.indexOf(pattern.charAt(i + 1) + "]", i + 2);
                if (close > 0) i = close + 1;
            }
            i++;
        }
        return Math.min(i, pattern.length() - 1);
    }

    /** The alternatives of one group, and whether each is still plain literal text. */
    private static final class Level {
        final List<StringBuilder> alternatives = new ArrayList<>(List.of(new StringBuilder()));
        final List<Boolean> plain = new ArrayList<>(List.of(Boolean.TRUE));
        boolean currentLiteral = true;
        boolean nestedAlternation;

        void literal(char c) {
            alternatives.get(alternatives.size() - 1).append(c);
            sync();
        }

        void opaque() {
            currentLiteral = false;
            sync();
        }

        private void sync() {
            while (plain.size() < alternatives.size()) plain.add(Boolean.TRUE);
            if (!currentLiteral) plain.set(alternatives.size() - 1, Boolean.FALSE);
        }

        boolean safe() {
            sync();
            if (nestedAlternation) return false;
            List<String> literals = new ArrayList<>();
            for (int i = 0; i < alternatives.size(); i++) {
                String text = alternatives.get(i).toString().toLowerCase(Locale.ROOT);
                if (!plain.get(i) || text.isEmpty()) return false;
                literals.add(text);
            }
            for (int i = 0; i < literals.size(); i++) {
                for (int j = 0; j < literals.size(); j++) {
                    if (i != j && literals.get(j).startsWith(literals.get(i))) return false;
                }
            }
            return true;
        }
    }
}
