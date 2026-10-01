package io.github.leadassimilator.reticulum.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the text form of a filter.
 *
 * <pre>
 * expr   := or
 * or     := and ( '|' and )*
 * and    := unary ( '&amp;'? unary )*          adjacent terms are ANDed
 * unary  := '!' unary | '(' expr ')' | term
 * term   := '*' | '@' name | '#' tag | key ':' value | id-pattern
 * value  := "quoted" | /regex/ | bare
 * </pre>
 *
 * A term whose key is not a known predicate is read as an id pattern, so {@code minecraft:*_ore} works as is.
 */
public final class FilterParser {
    public static final Set<String> KEYS = Set.of("id", "tag", "mod", "name", "dura", "ench", "has", "is", "in", "temp");

    /** How deep brackets and ! may nest: deeper than any real filter, shallow enough not to overflow the stack. */
    public static final int MAX_DEPTH = 64;
    /** The longest filter text accepted. */
    public static final int MAX_LENGTH = 4096;

    private final String text;
    private int pos;
    private int depth;

    private FilterParser(String text) {
        this.text = text;
    }

    public static FilterExpr parse(String text) throws FilterException {
        if (text.length() > MAX_LENGTH) {
            throw new FilterException("Filter is longer than " + MAX_LENGTH + " characters", MAX_LENGTH);
        }
        FilterParser parser = new FilterParser(text);
        parser.skipSpace();
        if (parser.atEnd()) {
            return new FilterExpr.Any();
        }
        FilterExpr expr = parser.or();
        parser.skipSpace();
        if (!parser.atEnd()) {
            throw new FilterException("Unexpected '" + parser.peek() + "'", parser.pos);
        }
        return expr;
    }

    private FilterExpr or() throws FilterException {
        List<FilterExpr> terms = new ArrayList<>();
        terms.add(and());
        while (true) {
            skipSpace();
            if (!atEnd() && peek() == '|') {
                pos++;
                terms.add(and());
            } else {
                break;
            }
        }
        return terms.size() == 1 ? terms.getFirst() : new FilterExpr.Or(terms);
    }

    private FilterExpr and() throws FilterException {
        List<FilterExpr> terms = new ArrayList<>();
        terms.add(unary());
        while (true) {
            skipSpace();
            if (atEnd() || peek() == '|' || peek() == ')') {
                break;
            }
            if (peek() == '&') {
                pos++;
            }
            terms.add(unary());
        }
        return terms.size() == 1 ? terms.getFirst() : new FilterExpr.And(terms);
    }

    private FilterExpr unary() throws FilterException {
        skipSpace();
        if (atEnd()) {
            throw new FilterException("Expected a filter term", pos);
        }
        char c = peek();
        if (c == '!' || c == '(') {
            if (++depth > MAX_DEPTH) {
                throw new FilterException("Filter is nested more than " + MAX_DEPTH + " deep", pos);
            }
            pos++;
            FilterExpr inner;
            if (c == '!') {
                inner = new FilterExpr.Not(unary());
            } else {
                inner = or();
                skipSpace();
                if (atEnd() || peek() != ')') {
                    throw new FilterException("Missing ')'", pos);
                }
                pos++;
            }
            depth--;
            return inner;
        }
        return term();
    }

    private FilterExpr term() throws FilterException {
        int start = pos;
        char c = peek();
        if (c == '*') {
            pos++;
            return new FilterExpr.Any();
        }
        if (c == '@') {
            pos++;
            String name = c == '@' && !atEnd() && peek() == '"' ? quoted() : bare();
            if (name.isEmpty()) {
                throw new FilterException("Expected a template name after '@'", start);
            }
            return new FilterExpr.Ref(name);
        }
        if (c == '#') {
            pos++;
            String tag = bare();
            if (tag.isEmpty()) {
                throw new FilterException("Expected a tag after '#'", start);
            }
            return new FilterExpr.Atom("tag", tag, false);
        }
        String word = bare();
        if (word.isEmpty()) {
            throw new FilterException("Unexpected '" + c + "'", start);
        }
        int colon = word.indexOf(':');
        String key = colon < 0 ? "" : word.substring(0, colon);
        if (colon >= 0 && KEYS.contains(key)) {
            String rest = word.substring(colon + 1);
            if (rest.isEmpty() && !atEnd() && (peek() == '"' || peek() == '/')) {
                boolean regex = peek() == '/';
                return new FilterExpr.Atom(key, regex ? delimited('/') : quoted(), regex);
            }
            if (rest.isEmpty()) {
                throw new FilterException("Expected a value after '" + key + ":'", pos);
            }
            return new FilterExpr.Atom(key, rest, false);
        }
        return new FilterExpr.Atom("id", word, false);
    }

    /** Characters up to whitespace, a bracket, an operator, or the start of a quoted or regex value after a colon. */
    private String bare() {
        int start = pos;
        while (!atEnd()) {
            char c = peek();
            if (Character.isWhitespace(c) || c == '(' || c == ')' || c == '&' || c == '|' || c == '!') {
                break;
            }
            if ((c == '"' || c == '/') && pos > start && text.charAt(pos - 1) == ':') {
                break;
            }
            pos++;
        }
        return text.substring(start, pos);
    }

    private String quoted() throws FilterException {
        return delimited('"');
    }

    private String delimited(char delimiter) throws FilterException {
        int start = pos;
        pos++;
        StringBuilder out = new StringBuilder();
        while (!atEnd()) {
            char c = peek();
            if (c == '\\' && pos + 1 < text.length() && text.charAt(pos + 1) == delimiter) {
                out.append(delimiter);
                pos += 2;
                continue;
            }
            if (c == delimiter) {
                pos++;
                return out.toString();
            }
            out.append(c);
            pos++;
        }
        throw new FilterException("Missing closing " + delimiter, start);
    }

    private void skipSpace() {
        while (!atEnd() && Character.isWhitespace(peek())) {
            pos++;
        }
    }

    private boolean atEnd() {
        return pos >= text.length();
    }

    private char peek() {
        return text.charAt(pos);
    }
}
