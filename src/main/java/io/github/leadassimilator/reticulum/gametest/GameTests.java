package io.github.leadassimilator.reticulum.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers the {@link GameTest} methods of the test classes as Minecraft's test functions and test instances
 * (reticulum:{@code class}/{@code method}, lowercase). A batch with {@link BeforeBatch} or {@link AfterBatch} methods
 * becomes a test environment whose setup and teardown run them; other tests share an empty one. Only in dev and game
 * test runs, when NeoForge runs game tests at all.
 */
public final class GameTests {
    /** The test classes; the integrations' ones are left out of builds without them. */
    private static final List<String> CLASSES = List.of("EngineTests", "FilterTests", "SafetyTests", "TopologyTests", "WebApiTests",
            "BenchmarkTests", "Ae2Tests", "MekanismTests");

    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, Reticulum.MODID);
    private static final DeferredRegister<MapCodec<? extends TestEnvironmentDefinition>> ENVIRONMENT_TYPES =
            DeferredRegister.create(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, Reticulum.MODID);

    private record Test(Identifier id, GameTest settings) {
    }

    private static final List<Test> TESTS = new ArrayList<>();
    private static final Map<String, List<Method>> BEFORE = new LinkedHashMap<>();
    private static final Map<String, List<Method>> AFTER = new LinkedHashMap<>();

    private GameTests() {
    }

    public static void init(IEventBus modBus) {
        if (!GameTestHooks.isGametestEnabled()) {
            return;
        }
        for (String name : CLASSES) {
            Class<?> type;
            try {
                type = Class.forName(GameTests.class.getPackageName() + "." + name);
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                GameTest settings = method.getAnnotation(GameTest.class);
                if (settings != null) {
                    String path = (name + "/" + method.getName()).toLowerCase(Locale.ROOT);
                    TESTS.add(new Test(id(path), settings));
                    FUNCTIONS.register(path, () -> helper -> invoke(method, helper));
                }
                BeforeBatch before = method.getAnnotation(BeforeBatch.class);
                if (before != null) {
                    BEFORE.computeIfAbsent(before.batch(), k -> new ArrayList<>()).add(method);
                }
                AfterBatch after = method.getAnnotation(AfterBatch.class);
                if (after != null) {
                    AFTER.computeIfAbsent(after.batch(), k -> new ArrayList<>()).add(method);
                }
            }
        }
        ENVIRONMENT_TYPES.register("batch", () -> Batch.CODEC);
        FUNCTIONS.register(modBus);
        ENVIRONMENT_TYPES.register(modBus);
        modBus.addListener(GameTests::register);
    }

    private static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition> plain = event.registerEnvironment(id("plain"));
        Map<String, Holder<TestEnvironmentDefinition>> batches = new LinkedHashMap<>();
        for (Test test : TESTS) {
            String batch = test.settings().batch();
            Holder<TestEnvironmentDefinition> environment = batch.isEmpty() ? plain
                    : batches.computeIfAbsent(batch, b -> event.registerEnvironment(id("batch/" + b), new Batch(b)));
            event.registerTest(test.id(), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, test.id()),
                    new TestData<>(environment, id(test.settings().template()), test.settings().timeoutTicks(), 0, true)));
        }
    }

    private static void invoke(Method method, Object argument) {
        try {
            method.invoke(null, argument);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Reticulum.MODID, path);
    }

    /** A batch's environment: its {@link BeforeBatch} methods set it up, its {@link AfterBatch} methods tear it down. */
    private record Batch(String name) implements TestEnvironmentDefinition {
        static final MapCodec<Batch> CODEC = Codec.STRING.fieldOf("batch").xmap(Batch::new, Batch::name);

        @Override
        public void setup(ServerLevel level) {
            BEFORE.getOrDefault(name, List.of()).forEach(method -> invoke(method, level));
        }

        @Override
        public void teardown(ServerLevel level) {
            AFTER.getOrDefault(name, List.of()).forEach(method -> invoke(method, level));
        }

        @Override
        public MapCodec<Batch> codec() {
            return CODEC;
        }
    }
}
