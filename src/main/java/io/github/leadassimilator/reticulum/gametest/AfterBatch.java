package io.github.leadassimilator.reticulum.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Runs after a batch's tests: a static method taking the {@code ServerLevel} (see {@link GameTests}). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AfterBatch {
    String batch();
}
