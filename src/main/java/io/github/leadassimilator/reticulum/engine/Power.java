package io.github.leadassimilator.reticulum.engine;

import io.github.leadassimilator.reticulum.ReticulumConfig;

/**
 * What running a network costs, in FE. Free by default; a server can switch on the suggested balanced values with
 * one preset, or set each cost itself.
 *
 * @param base         per tick for every running network
 * @param perConnector per tick for each connector
 * @param wirelessTier1 per tick for each wireless connector on a tier 1 antenna
 * @param wirelessTier2 per tick for each wireless connector on a tier 2 or 3 antenna
 * @param perBlock     per tick per block of distance between a wireless connector and the controller
 * @param crossDimension per tick for each wireless connector in another dimension
 * @param perOperation for each item operation that moves something (continuous flows never pay it)
 */
public record Power(double base, double perConnector, double wirelessTier1, double wirelessTier2, double perBlock, double crossDimension,
        double perOperation) {
    public static final Power FREE = new Power(0, 0, 0, 0, 0, 0, 0);
    public static final Power BALANCED = new Power(10, 2, 8, 32, 0.05, 256, 1);

    public enum Preset {
        FREE, BALANCED, CUSTOM
    }

    public static Power current() {
        return switch (ReticulumConfig.POWER_PRESET.get()) {
            case FREE -> FREE;
            case BALANCED -> BALANCED;
            case CUSTOM -> new Power(ReticulumConfig.COST_BASE.get(), ReticulumConfig.COST_PER_CONNECTOR.get(),
                    ReticulumConfig.COST_WIRELESS_TIER_1.get(), ReticulumConfig.COST_WIRELESS_TIER_2.get(), ReticulumConfig.COST_WIRELESS_PER_BLOCK.get(),
                    ReticulumConfig.COST_CROSS_DIMENSION.get(), ReticulumConfig.COST_PER_OPERATION.get());
        };
    }

    /** Whether anything costs power at all; when not, the buffer and power UI are hidden. */
    public boolean enabled() {
        return base > 0 || perConnector > 0 || wirelessTier1 > 0 || wirelessTier2 > 0 || perBlock > 0 || crossDimension > 0 || perOperation > 0;
    }
}
