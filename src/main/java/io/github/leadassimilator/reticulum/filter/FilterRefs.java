package io.github.leadassimilator.reticulum.filter;

import java.util.List;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.graph.NodeSettings;

/**
 * Renaming a filter template where it is used: every "@from" in a filter (or "@\"from\"") becomes "@to", whole names
 * only, nothing inside quotes or a regex touched; and every filter a node's settings hold, so a template can be renamed
 * without breaking what uses it.
 */
public final class FilterRefs {
    private FilterRefs() {
    }

    /** The expression with its references to template {@code from} pointing at {@code to}. */
    public static String rename(String expression, String from, String to) {
        StringBuilder out = new StringBuilder(expression.length());
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            // Quoted text and a /regex/ after "name:" are copied as they are (a quote anywhere else is plain text, as
            // the parser reads it; one after '@' is a quoted template name, below).
            if ((c == '"' || c == '/') && i > 0 && expression.charAt(i - 1) == ':') {
                int end = closing(expression, i, c);
                out.append(expression, i, end);
                i = end;
                continue;
            }
            boolean termStart = i == 0 || boundary(expression.charAt(i - 1));
            if (c == '@' && termStart) {
                int nameStart = i + 1;
                int nameEnd;
                String name;
                if (nameStart < n && expression.charAt(nameStart) == '"') {
                    nameEnd = closing(expression, nameStart, '"');
                    name = expression.substring(nameStart + 1, Math.max(nameStart + 1, nameEnd - 1)).replace("\\\"", "\"");
                } else {
                    nameEnd = nameStart;
                    while (nameEnd < n && !boundary(expression.charAt(nameEnd))) {
                        nameEnd++;
                    }
                    name = expression.substring(nameStart, nameEnd);
                }
                out.append('@').append(name.equals(from) ? spelled(to) : expression.substring(nameStart, nameEnd));
                i = nameEnd;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Whether an expression names template {@code name} (whole name, outside quoted text and regexes). */
    public static boolean mentions(@Nullable String expression, String name) {
        return expression != null && !rename(expression, name, name + '\u0001').equals(expression);
    }

    /** Every filter a node's saved settings hold (blank ones too). */
    public static List<String> filters(NodeSettings settings) {
        List<String> out = new java.util.ArrayList<>();
        rename(settings, e -> {
            out.add(e == null ? "" : e);
            return e;
        });
        return out;
    }

    /**
     * A node's saved settings with every filter they hold passed through {@code f}. The one list of settings that hold
     * filters (the engine's own source and sink settings are made from a device's, and never saved).
     */
    public static NodeSettings rename(NodeSettings settings, UnaryOperator<String> f) {
        return switch (settings) {
            case NodeSettings.FilterSettings s -> new NodeSettings.FilterSettings(f.apply(s.expression()));
            case NodeSettings.LevelSettings s -> new NodeSettings.LevelSettings(s.resource(), s.device(), s.faces(), f.apply(s.filter()), s.output(), s.low(), s.high());
            case NodeSettings.KitSettings s -> new NodeSettings.KitSettings(
                    s.lines().stream().map(l -> new NodeSettings.KitLine(f.apply(l.filter()), l.count())).toList(), s.maxKits());
            case NodeSettings.DeviceSettings s -> new NodeSettings.DeviceSettings(
                    s.device(),
                    s.inputs().stream().map(x -> new NodeSettings.Input(x.resource(), x.faces(), x.slots(), limits(x.caps(), f), x.whileOff())).toList(),
                    s.outputs().stream().map(x -> new NodeSettings.Output(x.resource(), x.faces(), x.slots(), limits(x.keep(), f), x.perOperation(),
                            x.interval(), x.priority(), x.comparator())).toList(),
                    s.block());
            default -> settings;
        };
    }

    private static List<NodeSettings.Limit> limits(List<NodeSettings.Limit> limits, UnaryOperator<String> f) {
        return limits.stream().map(l -> new NodeSettings.Limit(l.scope(), l.amount(), f.apply(l.filter()))).toList();
    }

    /** Where a quoted or regex part starting at {@code start} ends (just past its closing mark, or the end). */
    private static int closing(String text, int start, char mark) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == mark) {
                i += 2;
                continue;
            }
            i++;
            if (c == mark) {
                break;
            }
        }
        return i;
    }

    /** What ends a bare name (and so can come before a reference). */
    private static boolean boundary(char c) {
        return Character.isWhitespace(c) || c == '(' || c == ')' || c == '&' || c == '|' || c == '!';
    }

    /** A name as written after '@': bare when it can be, quoted when it holds a space or an operator. */
    private static String spelled(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (boundary(name.charAt(i)) || name.charAt(i) == '"') {
                return "\"" + name.replace("\"", "\\\"") + "\"";
            }
        }
        return name;
    }
}
