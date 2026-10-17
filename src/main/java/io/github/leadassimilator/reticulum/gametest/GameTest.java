package io.github.leadassimilator.reticulum.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A game test: a static method taking a {@code GameTestHelper}. Minecraft 1.21.5 dropped its own annotation for test
 * functions and instances registered as data; {@link GameTests} registers each method marked with this one.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {
    /** The structure the test runs in (reticulum:{@code template}). */
    String template();

    int timeoutTicks() default 100;

    /** Tests in a batch share its {@link BeforeBatch} and {@link AfterBatch} methods. */
    String batch() default "";
}
