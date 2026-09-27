package io.github.leadassimilator.reticulum.filter;

/** A filter that can't be parsed or compiled. {@code position} is the character offset, or -1. */
public class FilterException extends Exception {
    private final int position;

    public FilterException(String message, int position) {
        super(position >= 0 ? message + " (at " + position + ")" : message);
        this.position = position;
    }

    public int position() {
        return position;
    }
}
