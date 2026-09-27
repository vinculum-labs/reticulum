package io.github.leadassimilator.reticulum.filter;

import java.util.List;

/** Parsed filter: a tree of predicates joined by AND, OR and NOT. */
public sealed interface FilterExpr {
    record And(List<FilterExpr> terms) implements FilterExpr {
    }

    record Or(List<FilterExpr> terms) implements FilterExpr {
    }

    record Not(FilterExpr term) implements FilterExpr {
    }

    /** Matches everything ({@code *} or an empty expression). */
    record Any() implements FilterExpr {
    }

    /** A named filter template, written {@code @name}. */
    record Ref(String name) implements FilterExpr {
    }

    /** One predicate, {@code key:value}. {@code regex} is true for {@code /.../} values. */
    record Atom(String key, String value, boolean regex) implements FilterExpr {
    }
}
