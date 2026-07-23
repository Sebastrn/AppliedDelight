package sebastrn.applieddelight.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config for the ME Cooking Pot. All values are POC defaults and expected to be tuned once the battery / heat
 * balance is settled.
 */
public class ServerConfig {
    private final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
    private final ModConfigSpec spec;

    private final MECookingPot meCookingPot;

    public ServerConfig() {
        meCookingPot = new MECookingPot();
        spec = builder.build();
    }

    public ModConfigSpec getSpec() {
        return spec;
    }

    public MECookingPot getMeCookingPot() {
        return meCookingPot;
    }

    public class MECookingPot {
        private final ModConfigSpec.DoubleValue batteryCapacity;
        private final ModConfigSpec.DoubleValue drainPerIngredient;
        private final ModConfigSpec.DoubleValue idleDrainPerTick;
        private final ModConfigSpec.BooleanValue showAllRecipes;
        private final ModConfigSpec.BooleanValue returnRemaindersToNetwork;

        public MECookingPot() {
            builder.push("meCookingPot");

            batteryCapacity = builder
                    .comment("Battery capacity of the ME Cooking Pot, in AE units. Charge it in an AE2 Charger (or any FE",
                            "charger). The battery pays for network access, not for the heat that cooks the food.")
                    .defineInRange("batteryCapacity", 200_000.0, 0.0, Double.MAX_VALUE);

            drainPerIngredient = builder
                    .comment("AE drained from the pot's battery for each ingredient pulled from the ME network.",
                            "Set to 0 to make pulling free (heat is then the only cost of cooking).")
                    .defineInRange("drainPerIngredient", 20.0, 0.0, Double.MAX_VALUE);

            idleDrainPerTick = builder
                    .comment("AE drained from the pot's battery every tick while it holds a live link to the network,",
                            "even when idle — keeping the connection open is not free. When the battery can't pay, the",
                            "pot drops offline until it is recharged. Set to 0 to make an open link free.")
                    .defineInRange("idleDrainPerTick", 1.0, 0.0, Double.MAX_VALUE);

            showAllRecipes = builder
                    .comment("If true, the recipe list shows every Farmer's Delight cooking recipe. If false, only",
                            "recipes the player has unlocked are shown.")
                    .define("showAllRecipes", true);

            returnRemaindersToNetwork = builder
                    .comment("What to do with the empty container a cooked ingredient leaves behind (a milk bucket's",
                            "empty bucket, for example). If true they go back into the network; if false they drop on",
                            "the floor, which is what the vanilla Farmer's Delight pot does.")
                    .define("returnRemaindersToNetwork", true);

            builder.pop();
        }

        public double getBatteryCapacity() {
            return batteryCapacity.get();
        }

        public double getDrainPerIngredient() {
            return drainPerIngredient.get();
        }

        public double getIdleDrainPerTick() {
            return idleDrainPerTick.get();
        }


        public boolean getShowAllRecipes() {
            return showAllRecipes.get();
        }

        public boolean returnRemaindersToNetwork() {
            return returnRemaindersToNetwork.get();
        }
    }
}
