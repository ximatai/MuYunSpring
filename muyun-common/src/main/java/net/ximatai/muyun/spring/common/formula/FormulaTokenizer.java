package net.ximatai.muyun.spring.common.formula;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class FormulaTokenizer {
    enum TokenType {
        VALUE, OP, LPAREN, RPAREN, COMMA
    }

    record Token(TokenType type, String value) {
    }

    List<Token> tokenize(String expression) {
        if (expression == null) {
            return List.of();
        }
        List<Token> tokens = new ArrayList<>();
        Set<String> twoCharOps = Set.of(">=", "<=", "==", "!=", "&&", "||");
        Set<Character> oneCharOps = Set.of('+', '-', '*', '/', '%', '>', '<', '!', '=');
        String text = expression;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '{') {
                int end = text.indexOf('}', i + 1);
                if (end > -1) {
                    tokens.add(new Token(TokenType.VALUE, text.substring(i, end + 1)));
                    i = end + 1;
                    continue;
                }
            }
            if (ch == '\'' || ch == '"') {
                int end = scanQuoted(text, i, ch);
                tokens.add(new Token(TokenType.VALUE, text.substring(i, end + 1)));
                i = end + 1;
                continue;
            }
            if (Character.isDigit(ch) || ch == '.' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                int end = scanNumber(text, i);
                tokens.add(new Token(TokenType.VALUE, text.substring(i, end)));
                i = end;
                continue;
            }
            if (i + 1 < text.length() && twoCharOps.contains(text.substring(i, i + 2))) {
                tokens.add(new Token(TokenType.OP, text.substring(i, i + 2)));
                i += 2;
                continue;
            }
            if (oneCharOps.contains(ch)) {
                tokens.add(new Token(TokenType.OP, String.valueOf(ch)));
                i++;
                continue;
            }
            if (ch == '(') {
                tokens.add(new Token(TokenType.LPAREN, "("));
                i++;
                continue;
            }
            if (ch == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")"));
                i++;
                continue;
            }
            if (ch == ',') {
                tokens.add(new Token(TokenType.COMMA, ","));
                i++;
                continue;
            }
            int j = i;
            while (j < text.length() && !isBoundary(text, j, oneCharOps, twoCharOps)) {
                j++;
            }
            tokens.add(new Token(TokenType.VALUE, text.substring(i, j)));
            i = j;
        }
        return tokens;
    }

    private int scanQuoted(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == quote && text.charAt(i - 1) != '\\') {
                return i;
            }
            i++;
        }
        return text.length() - 1;
    }

    /** Keeps signed exponent parts inside a single numeric token, e.g. {@code 1e-3} and {@code 0x1p-2}. */
    private int scanNumber(String text, int start) {
        int index = start;
        boolean hexadecimal = index + 2 <= text.length()
                && text.charAt(index) == '0'
                && index + 1 < text.length()
                && (text.charAt(index + 1) == 'x' || text.charAt(index + 1) == 'X');
        if (hexadecimal) {
            index += 2;
            while (index < text.length() && (Character.digit(text.charAt(index), 16) >= 0 || text.charAt(index) == '.')) {
                index++;
            }
            if (index < text.length() && (text.charAt(index) == 'p' || text.charAt(index) == 'P')) {
                index = scanExponent(text, index + 1);
            }
        } else {
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index = scanExponent(text, index + 1);
            }
        }
        if (index < text.length() && (text.charAt(index) == 'd' || text.charAt(index) == 'D'
                || text.charAt(index) == 'f' || text.charAt(index) == 'F')) {
            index++;
        }
        return index;
    }

    private int scanExponent(String text, int start) {
        int index = start;
        if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
            index++;
        }
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private boolean isBoundary(String text, int index, Set<Character> oneCharOps, Set<String> twoCharOps) {
        char ch = text.charAt(index);
        String two = index + 1 < text.length() ? text.substring(index, index + 2) : "";
        return Character.isWhitespace(ch)
                || ch == '{'
                || ch == '\''
                || ch == '"'
                || ch == '('
                || ch == ')'
                || ch == ','
                || oneCharOps.contains(ch)
                || twoCharOps.contains(two);
    }
}
