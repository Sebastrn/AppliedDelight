package sebastrn.applieddelight.blockentity;

import appeng.api.config.Actionable;
import appeng.api.config.PowerUnit;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.blockentity.networking.WirelessAccessPointBlockEntity;
import appeng.me.helpers.MachineSource;
import appeng.util.Platform;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.Nullable;
import sebastrn.applieddelight.ADBlockEntities;
import sebastrn.applieddelight.AppliedDelight;
import sebastrn.applieddelight.block.MECookingPotBlock;
import sebastrn.applieddelight.config.ServerConfig;
import sebastrn.applieddelight.integration.ae2.AutoCookingPattern;
import sebastrn.applieddelight.item.MECookingPotItem;
import sebastrn.applieddelight.menu.MECookingPotMenu;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.HeatableBlockEntity;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModParticleTypes;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;
import vectorwing.farmersdelight.common.utility.ItemUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The ME Cooking Pot block entity. It reproduces Farmer's Delight's cooking-pot mechanic (heat + timer + result/
 * container slots) on its own inventory, but when a recipe is selected it pulls the missing ingredients from a linked
 * ME network, paying for that network access out of the pot's battery. It does not extend Farmer's Delight; it only
 * reads FD's {@link CookingPotRecipe}s and implements FD's {@link HeatableBlockEntity} heat check.
 */
public class MECookingPotBlockEntity extends BlockEntity implements MenuProvider, HeatableBlockEntity, ICraftingProvider {

    public static final int INPUT_SLOTS = 6;
    public static final int MEAL_DISPLAY_SLOT = 6;
    public static final int CONTAINER_SLOT = 7;
    public static final int OUTPUT_SLOT = 8;
    public static final int INVENTORY_SIZE = OUTPUT_SLOT + 1;

    private static final int NETWORK_REFRESH_INTERVAL = 20;
    /** Upper bound shown for a recipe's craftable count in the list. */
    public static final int MAX_CRAFT_DISPLAY = 999;
    /**
     * How many servings the meal slot holds, regardless of the meal item's own stack size, Farmer's Delight's rule.
     * The meal is the pot's contents, not a stack in a box, so 64 Hot Cocoa fit even though the item stacks to 16.
     */
    public static final int MEAL_CAPACITY = 64;

    private enum BoosterCard {NONE, INFINITY, DIMENSION}

    private static final ResourceLocation INFINITY_CARD_ID = ResourceLocation.fromNamespaceAndPath("aeinfinitybooster", "infinity_card");
    private static final ResourceLocation DIMENSION_CARD_ID = ResourceLocation.fromNamespaceAndPath("aeinfinitybooster", "dimension_card");

    private final ItemStackHandler inventory = new ItemStackHandler(INVENTORY_SIZE) {
        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            return slot == MEAL_DISPLAY_SLOT ? Math.max(64, stack.getMaxStackSize()) : super.getStackLimit(slot, stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private int cookTime;
    private int cookTimeTotal;
    private ItemStack mealContainerStack = ItemStack.EMPTY;
    /** Per input slot: true if its item was conjured from a network fluid (so its empty-bucket remainder is voided). */
    private final boolean[] fluidSourced = new boolean[INPUT_SLOTS];
    @Nullable
    private ResourceLocation selectedRecipeId;

    /** Battery, in AE units. Carried in/out via the item on place/break; drained when pulling from the network. */
    private double energy;

    /**
     * Forge-Energy view of the pot's battery for a <em>placed</em> pot, so cables (Mekanism, Flux, any FE source) can
     * charge it in the world, the block-level counterpart of the item's AE2 {@code PoweredItemCapabilities} bridge, and
     * mirroring it exactly: incoming FE is converted to AE and reported back in FE via AE2's own {@link PowerUnit}, so
     * cable-charging behaves like charging the item in an AE2 or FE charger. Receive-only: a cable tops the battery up
     * but can never siphon it back out. Registered against
     * {@link net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage#BLOCK} in {@link AppliedDelight}. Exposing it
     * is also what makes cables visually connect to the block.
     */
    private final IEnergyStorage energyStorage = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            double offeredAE = PowerUnit.FE.convertTo(PowerUnit.AE, maxReceive);
            double room = Math.max(0.0, getMaxEnergy() - energy);
            double acceptedAE = Math.min(offeredAE, room);
            double overflowAE = offeredAE - acceptedAE;
            if (!simulate && acceptedAE > 0) {
                energy += acceptedAE;
                setChanged();
            }
            return maxReceive - (int) PowerUnit.AE.convertTo(PowerUnit.FE, overflowAE);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, PowerUnit.AE.convertTo(PowerUnit.FE, energy));
        }

        @Override
        public int getMaxEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, PowerUnit.AE.convertTo(PowerUnit.FE, getMaxEnergy()));
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    // --- Network link state (mirrors Applied Cooking's KitchenStationBlockEntity) ---
    @Nullable
    private GlobalPos accessPointPos;
    /** The access point the pot was linked at. Used only to find the grid, reach is judged per access point. */
    @Nullable
    private IWirelessAccessPoint linkedAccessPoint;
    /** The access point currently covering this pot, chosen from the grid each refresh. Null when out of reach. */
    @Nullable
    private IWirelessAccessPoint accessPoint;
    @Nullable
    private MEStorage meStorage;
    @Nullable
    private IGrid grid;
    @Nullable
    private IGrid craftingProviderGrid;
    private List<IPatternDetails> autoCookingPatterns = List.of();
    private int autoCookingPatternFingerprint = Integer.MIN_VALUE;
    private int ticksSinceNetworkRefresh = NETWORK_REFRESH_INTERVAL;

    private boolean autoCrafting;
    private ItemStack autoCraftingOutput = ItemStack.EMPTY;

    /** Container-data slots synced to the menu: cookTime, cookTimeTotal, link state, energy%, containerRequestFailures. */
    public static final int DATA_SLOTS = 5;
    /** Bumped whenever a container request finds nothing, so the screen can flash the button red. */
    private int containerRequestFailures;
    /** Remaining meals to cook for the selected recipe (0 = idle). Set by clicking a recipe (1) or shift-clicking (batch). */
    private int craftTarget = 0;
    /**
     * The player whose pot GUI is currently open, if any. Ingredients may be drawn from their inventory as well as from
     * the network (the recipe list counts both, so cooking has to honour both). Deliberately transient and tied to the
     * open menu: the pot must never reach into the inventory of someone who has walked away.
     */
    @Nullable
    private Player craftingPlayer;

    private final RecipeManager.CachedCheck<RecipeWrapper, CookingPotRecipe> quickCheck =
            RecipeManager.createCheck(ModRecipeTypes.COOKING.get());

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int i) {
            return switch (i) {
                case 0 -> cookTime;
                case 1 -> cookTimeTotal;
                case 2 -> getLinkState();
                case 3 -> energyPercent();
                case 4 -> containerRequestFailures;
                default -> 0;
            };
        }

        @Override
        public void set(int i, int v) {
            switch (i) {
                case 0 -> cookTime = v;
                case 1 -> cookTimeTotal = v;
            }
        }

        @Override
        public int getCount() {
            return DATA_SLOTS;
        }
    };

    public MECookingPotBlockEntity(BlockPos pos, BlockState state) {
        super(ADBlockEntities.ME_COOKING_POT.get(), pos, state);
    }

    private static ServerConfig.MECookingPot cfg() {
        return AppliedDelight.SERVER_CONFIG.getMeCookingPot();
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    /** The meal currently accumulated in the pot (may be more than a stack, up to {@link #MEAL_CAPACITY}). */
    public ItemStack getMeal() {
        return inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
    }

    public ContainerData getData() {
        return data;
    }

    public boolean isConnected() {
        return grid != null && meStorage != null;
    }

    public boolean isAutoCrafting() {
        return autoCrafting;
    }

    // ------------------------------------------------------------------------------------------------------------
    //  AE2 autocrafting provider
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return autoCookingPatterns;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolders) {
        if (level == null || level.isClientSide || craftingProviderGrid == null || craftingProviderGrid != grid
                || isBusy()) {
            return false;
        }

        AutoCookingPattern pattern = null;
        for (IPatternDetails available : autoCookingPatterns) {
            if (available.equals(patternDetails) && available instanceof AutoCookingPattern autoPattern) {
                pattern = autoPattern;
                break;
            }
        }
        if (pattern == null) {
            return false;
        }

        AutoCookingPattern.AcceptedInputs accepted = pattern.acceptInputs(inputHolders);
        if (accepted == null || accepted.ingredients().length > INPUT_SLOTS) {
            return false;
        }

        double cost = cfg().getDrainPerIngredient() * accepted.ingredients().length;
        if (energy < cost) {
            return false;
        }

        // AE2 retains ownership until every input has been validated above.
        Arrays.fill(fluidSourced, false);
        for (int i = 0; i < accepted.ingredients().length; i++) {
            inventory.setStackInSlot(i, accepted.ingredients()[i]);
            fluidSourced[i] = accepted.fluidSourced()[i];
        }
        if (!accepted.container().isEmpty()) {
            inventory.setStackInSlot(CONTAINER_SLOT, accepted.container());
        }

        energy = Math.max(0, energy - cost);
        cookTime = 0;
        cookTimeTotal = 0;
        mealContainerStack = ItemStack.EMPTY;
        selectedRecipeId = pattern.recipeId();
        craftTarget = 1;
        autoCraftingOutput = pattern.output();
        autoCrafting = true;
        setChanged();
        return true;
    }

    @Override
    public boolean isBusy() {
        if (autoCrafting || !isConnected()) {
            return true;
        }
        if (selectedRecipeId != null || craftTarget > 0 || cookTime > 0) {
            return true;
        }
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The pot's link state, for the status LED and HUD tooltips: 0 = no access point saved (Unlinked), 1 = linked but
     * not currently connected, no power, out of range, or the network is down (Offline), 2 = actively connected
     * (Linked). This distinguishes "I linked it but it can't reach/power the network" from "it was never linked",
     * which a plain connected/not boolean conflated.
     */
    public int getLinkState() {
        if (accessPointPos == null) return 0;
        return isConnected() ? 2 : 1;
    }

    public double getEnergy() {
        return energy;
    }

    public double getMaxEnergy() {
        return cfg().getBatteryCapacity();
    }

    /** Receive-only Forge-Energy view of the battery, exposed to cables on a placed pot. See {@link #energyStorage}. */
    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    private int energyPercent() {
        double max = getMaxEnergy();
        return max <= 0 ? 0 : Mth.clamp((int) Math.round(100.0 * energy / max), 0, 100);
    }

    @Nullable
    public ResourceLocation getSelectedRecipeId() {
        return selectedRecipeId;
    }

    /**
     * Select a recipe and how many to cook. Clicking a recipe passes 1; shift-clicking passes a batch.
     *
     * <p>A target of 0 <em>clears</em> the selection rather than storing it. Shift-clicking a recipe nothing can supply
     * computes a batch of 0, and keeping the id in that state would leave the pot armed but inert, {@code active} is
     * false, so it would silently ignore ingredients loaded into it by hand.
     */
    public void selectRecipe(@Nullable ResourceLocation id, int target) {
        if (autoCrafting) return;
        boolean cleared = id == null || target <= 0;
        this.selectedRecipeId = cleared ? null : id;
        this.craftTarget = cleared ? 0 : target;
        setChanged();
    }

    /** Set while a player has this pot's menu open; see {@link #craftingPlayer}. */
    public void setCraftingPlayer(@Nullable Player player) {
        this.craftingPlayer = player;
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Ticking
    // ------------------------------------------------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, MECookingPotBlockEntity be) {
        if (++be.ticksSinceNetworkRefresh >= NETWORK_REFRESH_INTERVAL) {
            be.ticksSinceNetworkRefresh = 0;
            be.resolveAccessPoint();
            be.refreshAutoCookingPatterns();
        }
        be.refreshConnection();
        be.updateConnectedState();
        be.cookingTick();
    }

    /**
     * Client-side particle tick, mirroring Farmer's Delight's cooking pot: while heated, the pot emits bubble-pop
     * particles and rising steam, whether or not anything is actually cooking. The steam is Farmer's Delight's own
     * registered particle, reused here since we hard-depend on the mod.
     */
    public static void animationTick(Level level, BlockPos pos, BlockState state, MECookingPotBlockEntity be) {
        if (!be.isHeated(level, pos)) return;
        RandomSource random = level.random;
        if (random.nextFloat() < 0.2F) {
            double x = pos.getX() + 0.5D + (random.nextDouble() * 0.6D - 0.3D);
            double y = pos.getY() + 0.7D;
            double z = pos.getZ() + 0.5D + (random.nextDouble() * 0.6D - 0.3D);
            level.addParticle(ParticleTypes.BUBBLE_POP, x, y, z, 0.0D, 0.0D, 0.0D);
        }
        if (random.nextFloat() < 0.05F) {
            double x = pos.getX() + 0.5D + (random.nextDouble() * 0.4D - 0.2D);
            double y = pos.getY() + 0.5D;
            double z = pos.getZ() + 0.5D + (random.nextDouble() * 0.4D - 0.2D);
            double motionY = random.nextBoolean() ? 0.015D : 0.005D;
            level.addParticle(ModParticleTypes.STEAM.get(), x, y, z, 0.0D, motionY, 0.0D);
        }
    }

    /**
     * Per-recipe maximum craftable count from the network <em>and</em> the given player's inventory combined, indexed
     * by {@link MECookingPotMenu#sortedRecipes}. Used by the menu to drive the recipe list (counts + dimming) and
     * shift-click batch sizing. Capped at {@value #MAX_CRAFT_DISPLAY} for display.
     */
    public int[] computeMaxCraftable(Player player) {
        List<RecipeHolder<CookingPotRecipe>> recipes = level == null
                ? List.of() : MECookingPotMenu.sortedRecipes(level);
        int[] result = new int[recipes.size()];

        // Availability is tracked split by source, because the two are priced differently: items in the player's own
        // inventory are always free and reachable, while network items need a live link and cost battery. So an
        // unlinked or flat pot still shows, and cooks, whatever the player is carrying, like a plain cooking pot.
        List<ItemStack> reps = new ArrayList<>();
        List<Long> netCounts = new ArrayList<>();
        List<Long> playerCounts = new ArrayList<>();
        List<Long> inputCounts = new ArrayList<>();
        boolean networkUsable = meStorage != null && isConnected();
        if (networkUsable) {
            for (Object2LongMap.Entry<AEKey> entry : meStorage.getAvailableStacks()) {
                if (entry.getLongValue() > 0 && entry.getKey() instanceof AEItemKey ik) {
                    addRep(reps, netCounts, playerCounts, inputCounts, ik.toStack(1), entry.getLongValue(), 0, 0);
                }
            }
        }
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) {
                addRep(reps, netCounts, playerCounts, inputCounts, stack, 0, stack.getCount(), 0);
            }
        }
        // Items already sitting in the pot's own input slots count too. On a recipe click the pot returns them (to the
        // network first, inventory as fallback) and re-pulls them into the batch, so they are as available to a craft as anything in your bag.
        // This is what a partially hand-loaded pot needs. A fluid-conjured bucket is not real returnable stock, so it is
        // skipped. The one recipe these are NOT counted for is the one currently loaded (below): its input IS its
        // committed batch, which a re-click tops up rather than returns.
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack inSlot = inventory.getStackInSlot(i);
            if (!inSlot.isEmpty() && !fluidSourced[i]) {
                addRep(reps, netCounts, playerCounts, inputCounts, inSlot, 0, 0, inSlot.getCount());
            }
        }
        // Network fluids (for milk/water ingredients that can also be satisfied from fluid storage).
        Map<Fluid, Long> networkFluids = new HashMap<>();
        if (networkUsable) {
            for (Object2LongMap.Entry<AEKey> entry : meStorage.getAvailableStacks()) {
                if (entry.getLongValue() > 0 && entry.getKey() instanceof AEFluidKey fk) {
                    networkFluids.merge(fk.getFluid(), entry.getLongValue(), Long::sum);
                }
            }
        }

        double drain = cfg().getDrainPerIngredient();
        for (int r = 0; r < recipes.size(); r++) {
            CookingPotRecipe recipe = recipes.get(r).value();
            List<Ingredient> ingredients = recipe.getIngredients();
            // The loaded recipe's input is its committed batch (a re-click tops it up, not returns it), so it does not
            // count its own input slots as available stock; every other recipe does.
            boolean rSelected = selectedRecipeId != null && recipes.get(r).id().equals(selectedRecipeId);

            // Group identical ingredients. A recipe listing cocoa beans twice consumes TWO beans per craft, so its
            // bean pool supports total/2 crafts, taking an independent minimum per ingredient double-counts it.
            Map<String, Integer> multiplicity = new HashMap<>();
            Map<String, Ingredient> distinct = new LinkedHashMap<>();
            for (Ingredient ingredient : ingredients) {
                String key = signature(ingredient);
                multiplicity.merge(key, 1, Integer::sum);
                distinct.putIfAbsent(key, ingredient);
            }

            // Two ceilings per recipe: how many the player can make alone (free), and how many player+network can make.
            long playerMax = Long.MAX_VALUE;
            long combinedMax = Long.MAX_VALUE;
            for (Map.Entry<String, Ingredient> entry : distinct.entrySet()) {
                Ingredient ingredient = entry.getValue();
                int copies = multiplicity.get(entry.getKey());
                // Availability is per ITEM VARIANT, never summed across them: an input slot holds one kind of item, so
                // a batch is served by whichever single variant goes furthest, min(what we have of it / how many the
                // recipe needs, its own stack size).
                long playerBest = 0;
                long combinedBest = 0;
                for (int i = 0; i < reps.size(); i++) {
                    if (ingredient.test(reps.get(i))) {
                        int cap = reps.get(i).getMaxStackSize();
                        // Input-slot items are free (player-side), counted for every recipe but the loaded one.
                        long free = playerCounts.get(i) + (rSelected ? 0 : inputCounts.get(i));
                        playerBest = Math.max(playerBest, Math.min(free / copies, cap));
                        combinedBest = Math.max(combinedBest,
                                Math.min((netCounts.get(i) + free) / copies, cap));
                    }
                }
                // A network fluid can serve this ingredient too (conjured bucket, so one per batch), network only.
                if (fluidBucketsFor(ingredient, networkFluids) >= copies) {
                    combinedBest = Math.max(combinedBest, 1);
                }
                playerMax = Math.min(playerMax, playerBest);
                combinedMax = Math.min(combinedMax, combinedBest);
                if (combinedMax <= 0) break;
            }

            long room = mealRoom(recipe);
            playerMax = Math.min(playerMax, room);
            combinedMax = Math.min(combinedMax, room);

            // Player crafts are free; crafts beyond those need the network and are limited by the battery. So an
            // unlinked pot shows playerMax, and a linked-but-flat pot still shows what the player alone can supply.
            long display;
            if (!networkUsable) {
                display = playerMax;
            } else if (drain <= 0) {
                display = combinedMax;
            } else {
                long budget = (long) (energy / (drain * ingredients.size()));
                display = Math.min(combinedMax, playerMax + budget);
            }
            result[r] = (int) Math.max(0, Math.min(display, MAX_CRAFT_DISPLAY));
        }
        return result;
    }

    /**
     * Fold a stack into the availability list by item + components, keeping its network, player-inventory and
     * input-slot counts separate (they are priced/handled differently: network costs battery, player is free, input
     * items are free but excluded for the recipe currently loaded). The player's inventory spreads one item across
     * several slots and the network lists it once, so without merging a per-variant total would only see whichever
     * copy came first.
     */
    private static void addRep(List<ItemStack> reps, List<Long> netCounts, List<Long> playerCounts,
                               List<Long> inputCounts, ItemStack stack, long net, long player, long input) {
        for (int i = 0; i < reps.size(); i++) {
            if (ItemStack.isSameItemSameComponents(reps.get(i), stack)) {
                netCounts.set(i, netCounts.get(i) + net);
                playerCounts.set(i, playerCounts.get(i) + player);
                inputCounts.set(i, inputCounts.get(i) + input);
                return;
            }
        }
        reps.add(stack.copyWithCount(1));
        netCounts.add(net);
        playerCounts.add(player);
        inputCounts.add(input);
    }

    /**
     * Stable identity for an ingredient, used to spot the same ingredient listed twice in one recipe.
     * {@link Ingredient} has no dependable {@code equals}, so this compares the set of items it accepts.
     */
    private static String signature(Ingredient ingredient) {
        List<String> ids = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        java.util.Collections.sort(ids);
        return String.join(",", ids);
    }

    /**
     * How many more servings will fit in the meal slot. The pot holds up to {@link #MEAL_CAPACITY} meals regardless of
     * the meal item's own stack size (Farmer's Delight's rule, it's the pot's contents, not a stack in a box), so a
     * pot can hold 64 Hot Cocoa even though the item stacks to 16.
     *
     * <p>The output slot is deliberately NOT part of this: meals only reach it when the player supplies containers, so
     * it has no bearing on how much the pot can cook.
     */
    private long mealRoom(CookingPotRecipe recipe) {
        if (level == null) return 0;
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) return 0;
        ItemStack stored = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
        // A meal of a different kind blocks cooking until it is served out, but it does not shrink the room this
        // recipe will eventually have, the batch simply waits.
        int used = ItemStack.isSameItem(stored, result) ? stored.getCount() : 0;
        return Math.max(0, MEAL_CAPACITY - used) / Math.max(1, result.getCount());
    }

    /** Buckets-worth of network fluid available for {@code ingredient}, if it accepts a filled bucket (milk/water). */
    private static long fluidBucketsFor(Ingredient ingredient, Map<Fluid, Long> networkFluids) {
        for (ItemStack candidate : ingredient.getItems()) {
            FluidStack contained = FluidUtil.getFluidContained(candidate).orElse(FluidStack.EMPTY);
            if (contained.getAmount() >= AEFluidKey.AMOUNT_BUCKET) {
                return networkFluids.getOrDefault(contained.getFluid(), 0L) / AEFluidKey.AMOUNT_BUCKET;
            }
        }
        return 0;
    }

    private void updateConnectedState() {
        if (level == null) return;
        BlockState state = level.getBlockState(worldPosition);
        BlockState updated = state;
        boolean connected = isConnected();
        if (updated.hasProperty(MECookingPotBlock.CONNECTED) && updated.getValue(MECookingPotBlock.CONNECTED) != connected) {
            updated = updated.setValue(MECookingPotBlock.CONNECTED, connected);
        }
        // Carry "has a meal" to nearby clients so the ambient boil sound can pick FD's soup vs water variant; the
        // inventory isn't synced, so the blockstate is how the client learns this. Flips only on empty<->present.
        boolean hasMeal = !getMeal().isEmpty();
        if (updated.hasProperty(MECookingPotBlock.HAS_MEAL) && updated.getValue(MECookingPotBlock.HAS_MEAL) != hasMeal) {
            updated = updated.setValue(MECookingPotBlock.HAS_MEAL, hasMeal);
        }
        if (updated != state) {
            level.setBlockAndUpdate(worldPosition, updated);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Cooking (Farmer's Delight logic, reproduced)
    // ------------------------------------------------------------------------------------------------------------

    private void cookingTick() {
        if (level == null || level.isClientSide) return;

        boolean heated = isHeated(level, worldPosition);
        boolean changed = false;

        // Drop a standing order the moment its ingredients no longer match, the player pulled or swapped items out of
        // the slots, so revert to plain cooking-pot behaviour (cook whatever is actually there) instead of latching on
        // the stale order. This never fires during normal cooking, nor while a full batch waits for meal-slot room, nor
        // while an unheated pot waits for heat: in all of those the loaded ingredients still match the selected recipe.
        if (selectedRecipeId != null && !autoCrafting) {
            Optional<RecipeHolder<?>> holder = level.getRecipeManager().byKey(selectedRecipeId);
            boolean stillMatches = holder.isPresent()
                    && holder.get().value() instanceof CookingPotRecipe cooking
                    && cooking.matches(new RecipeWrapper(inventory), level);
            if (!stillMatches) {
                selectRecipe(null, 0);
            }
        }

        RecipeHolder<CookingPotRecipe> recipe = resolveTargetRecipe();
        // A hand-loaded pot (no explicit selection) cooks freely, FD-style; a selected recipe cooks until its craft
        // target is met (1 for a click, up to a stack for a shift-click).
        boolean active = recipe != null && (selectedRecipeId == null || craftTarget > 0);
        if (heated && active) {
            // Cooking never touches the network or the player: the batch already owns everything it needs.
            if (canCook(recipe.value())) {
                if (processCooking(recipe.value())) {
                    changed = true;
                    if (selectedRecipeId != null && craftTarget > 0) {
                        craftTarget--;
                        // Batch finished: drop back to plain Farmer's-Delight behaviour. Without this the spent
                        // selection stays latched and the pot ignores ingredients loaded into it by hand.
                        if (craftTarget == 0) {
                            selectedRecipeId = null;
                        }
                    }
                }
            } else {
                cookTime = Mth.clamp(cookTime - 2, 0, cookTimeTotal);
            }
        } else if (cookTime > 0) {
            cookTime = Mth.clamp(cookTime - 2, 0, cookTimeTotal);
        }

        // Serving is exactly Farmer's Delight's: a meal needing no container moves out on its own, otherwise it waits
        // for containers the PLAYER supplies, by hand or via the request button. The pot never fetches them itself.
        ItemStack meal = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
        if (!meal.isEmpty()) {
            if (!mealHasContainer(meal)) {
                moveMealToOutput();
                changed = true;
            } else if (!inventory.getStackInSlot(CONTAINER_SLOT).isEmpty()) {
                useStoredContainersOnMeal();
                changed = true;
            }
        }

        if (exportAutoCraftingOutput()) {
            changed = true;
        }

        if (changed) {
            setChanged();
        }
    }

    /** Inserts the active job's output and keeps any rejected remainder for the next tick. */
    private boolean exportAutoCraftingOutput() {
        if (!autoCrafting || autoCraftingOutput.isEmpty() || meStorage == null || accessPoint == null || !isConnected()) {
            return false;
        }

        ItemStack output = inventory.getStackInSlot(OUTPUT_SLOT);
        if (output.isEmpty() || !ItemStack.isSameItemSameComponents(output, autoCraftingOutput)) {
            return false;
        }

        int offered = Math.min(output.getCount(), autoCraftingOutput.getCount());
        AEItemKey key = AEItemKey.of(autoCraftingOutput);
        if (key == null || offered <= 0) {
            return false;
        }
        long inserted = meStorage.insert(key, offered, Actionable.MODULATE, new MachineSource(accessPoint));
        if (inserted <= 0) {
            return false;
        }

        output.shrink((int) inserted);
        autoCraftingOutput.shrink((int) inserted);
        if (autoCraftingOutput.isEmpty()) {
            autoCrafting = false;
            autoCraftingOutput = ItemStack.EMPTY;
        }
        return true;
    }

    @Nullable
    private RecipeHolder<CookingPotRecipe> resolveTargetRecipe() {
        if (level == null) return null;
        if (selectedRecipeId != null) {
            Optional<RecipeHolder<?>> holder = level.getRecipeManager().byKey(selectedRecipeId);
            if (holder.isPresent() && holder.get().value() instanceof CookingPotRecipe cooking) {
                @SuppressWarnings("unchecked")
                RecipeHolder<CookingPotRecipe> typed = (RecipeHolder<CookingPotRecipe>) holder.get();
                return typed;
            }
            return null;
        }
        // No explicit selection: behave like a plain FD pot and cook whatever the input slots already match.
        return hasInput() ? quickCheck.getRecipeFor(new RecipeWrapper(inventory), level).orElse(null) : null;
    }

    private boolean hasInput() {
        for (int i = 0; i < INPUT_SLOTS; i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Load a whole batch of {@code requested} crafts into the pot in one go, then hand ownership to the pot: from here
     * the cook needs only heat, touching neither the network nor the player again.
     *
     * <p>Per ingredient we first choose the <em>item variant</em> that stacks highest among those actually obtainable
     * (milk bottle over milk bucket, since the batch has to fit in one slot), then fill it player-inventory-first and
     * network-second. Only the network-sourced part costs battery. Duplicated ingredients get their own slot each, 
     * Farmer's Delight's matcher requires the number of occupied slots to equal the ingredient count, so merging two
     * cocoa entries into one slot would stop the recipe matching.
     *
     * @return the number of crafts actually loaded (may be fewer than requested), or 0 if nothing was taken.
     */
    public int loadBatch(CookingPotRecipe recipe, int requested) {
        if (level == null || autoCrafting || requested <= 0 || !canAcceptBatch()) return 0;

        List<Ingredient> ingredients = recipe.getIngredients();
        int count = ingredients.size();
        boolean networkUsable = meStorage != null && accessPoint != null && isConnected();
        NonNullList<ItemStack> playerItems = craftingPlayer == null ? null : craftingPlayer.getInventory().items;

        KeyCounter snapshot = new KeyCounter();
        if (networkUsable) {
            snapshot.addAll(meStorage.getAvailableStacks());
        }
        int[] reserved = playerItems == null ? null : new int[playerItems.size()];

        ItemStack[] chosenItem = new ItemStack[count];   // the variant to use, count 1
        int[] fromPlayer = new int[count];
        int[] fromNetwork = new int[count];
        boolean[] fromFluid = new boolean[count];

        int batch = requested;
        for (int n = 0; n < count && batch > 0; n++) {
            Ingredient ingredient = ingredients.get(n);

            // Pick the variant that goes furthest: min(how many we can get, how many fit in the slot). Stack size
            // alone is not enough, 1 milk bottle beats nothing, but 8 milk buckets beat 1 milk bottle.
            ItemStack best = ItemStack.EMPTY;
            long bestScore = 0;
            int bestPlayer = 0;
            long bestNetwork = 0;
            for (ItemStack candidate : ingredient.getItems()) {
                int inPlayer = 0;
                if (playerItems != null) {
                    for (int slot = 0; slot < playerItems.size(); slot++) {
                        ItemStack stack = playerItems.get(slot);
                        if (ItemStack.isSameItem(stack, candidate)) {
                            inPlayer += Math.max(0, stack.getCount() - reserved[slot]);
                        }
                    }
                }
                long inNetwork = 0;
                AEItemKey key = AEItemKey.of(candidate);
                if (networkUsable && key != null) {
                    inNetwork = snapshot.get(key);
                }
                if (inPlayer + inNetwork <= 0) continue;
                long score = Math.min(inPlayer + inNetwork, candidate.getMaxStackSize());
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate.copyWithCount(1);
                    bestPlayer = inPlayer;
                    bestNetwork = inNetwork;
                }
            }

            if (!best.isEmpty()) {
                int take = (int) Math.min(batch, Math.min(bestPlayer + bestNetwork, best.getMaxStackSize()));
                if (take <= 0) return 0;
                batch = Math.min(batch, take);
                chosenItem[n] = best;
                fromPlayer[n] = Math.min(bestPlayer, batch);
                fromNetwork[n] = batch - fromPlayer[n];
                AEItemKey key = AEItemKey.of(best);
                if (key != null && fromNetwork[n] > 0) {
                    snapshot.remove(key, fromNetwork[n]);
                }
                if (playerItems != null && fromPlayer[n] > 0) {
                    int left = fromPlayer[n];
                    for (int slot = 0; slot < playerItems.size() && left > 0; slot++) {
                        ItemStack stack = playerItems.get(slot);
                        if (ItemStack.isSameItem(stack, best)) {
                            int usable = Math.min(left, Math.max(0, stack.getCount() - reserved[slot]));
                            reserved[slot] += usable;
                            left -= usable;
                        }
                    }
                }
                continue;
            }

            // No item variant anywhere, fall back to network fluid, conjuring a bucket. Buckets do not stack, so a
            // fluid-sourced ingredient pins the whole batch to a single craft.
            boolean found = false;
            if (networkUsable) {
                for (ItemStack candidate : ingredient.getItems()) {
                    FluidStack contained = FluidUtil.getFluidContained(candidate).orElse(FluidStack.EMPTY);
                    if (contained.getAmount() >= AEFluidKey.AMOUNT_BUCKET) {
                        AEFluidKey fluidKey = AEFluidKey.of(contained.getFluid());
                        if (snapshot.get(fluidKey) >= AEFluidKey.AMOUNT_BUCKET) {
                            snapshot.remove(fluidKey, AEFluidKey.AMOUNT_BUCKET);
                            chosenItem[n] = candidate.copyWithCount(1);
                            fromFluid[n] = true;
                            batch = 1;
                            found = true;
                            break;
                        }
                    }
                }
            }
            if (!found) return 0; // an ingredient is unavailable from every source, take nothing
        }

        if (batch <= 0) return 0;

        // Containers are deliberately NOT fetched here. Like Farmer's Delight, meals stay in the pot until the player
        // supplies containers, by hand, or with the "request containers" button.
        // Battery: only the network-sourced portion is charged for.
        int networkItems = 0;
        for (int n = 0; n < count; n++) {
            networkItems += fromFluid[n] ? 1 : Math.min(fromNetwork[n], batch);
        }
        double drain = cfg().getDrainPerIngredient();
        if (drain > 0 && energy < drain * networkItems) {
            int affordable = (int) (energy / drain);
            if (affordable <= 0) return 0;
            batch = Math.max(1, batch * affordable / Math.max(1, networkItems));
        }

        // Commit.
        IActionSource source = networkUsable ? new MachineSource(accessPoint) : null;
        for (int n = 0; n < count; n++) {
            ItemStack variant = chosenItem[n];
            if (variant == null || variant.isEmpty()) return 0;
            if (fromFluid[n]) {
                AEFluidKey fluidKey = AEFluidKey.of(FluidUtil.getFluidContained(variant).orElse(FluidStack.EMPTY).getFluid());
                long got = meStorage.extract(fluidKey, AEFluidKey.AMOUNT_BUCKET, Actionable.MODULATE, source);
                if (got >= AEFluidKey.AMOUNT_BUCKET) {
                    inventory.setStackInSlot(n, variant.copyWithCount(1));
                    fluidSourced[n] = true;
                    energy = Math.max(0, energy - drain);
                }
                continue;
            }
            int wantPlayer = Math.min(fromPlayer[n], batch);
            int wantNetwork = batch - wantPlayer;
            int got = 0;
            if (wantPlayer > 0 && playerItems != null) {
                int left = wantPlayer;
                for (int slot = 0; slot < playerItems.size() && left > 0; slot++) {
                    ItemStack stack = playerItems.get(slot);
                    if (ItemStack.isSameItem(stack, variant)) {
                        ItemStack taken = stack.split(Math.min(left, stack.getCount()));
                        left -= taken.getCount();
                        got += taken.getCount();
                    }
                }
            }
            if (wantNetwork > 0 && networkUsable) {
                AEItemKey key = AEItemKey.of(variant);
                long extracted = key == null ? 0 : meStorage.extract(key, wantNetwork, Actionable.MODULATE, source);
                got += (int) extracted;
                energy = Math.max(0, energy - drain * extracted);
            }
            inventory.setStackInSlot(n, got > 0 ? variant.copyWithCount(got) : ItemStack.EMPTY);
        }

        // Remember what this meal will need to be served into, so the request button knows what to ask for, but ONLY
        // when the meal slot is not already holding a DIFFERENT meal. That waiting meal still needs its OWN container
        // (hot cocoa wants a glass bottle even if you have just ordered a bowl recipe); overwriting this would make the
        // request button fetch the wrong container, and would let that wrong container serve the waiting meal. Once the
        // waiting meal has been served out and this batch actually cooks, processCooking sets the container for real.
        ItemStack waiting = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
        if (waiting.isEmpty() || ItemStack.isSameItem(waiting, recipe.getResultItem(level.registryAccess()))) {
            mealContainerStack = recipe.getOutputContainer();
        }
        setChanged();
        return batch;
    }

    /**
     * Add up to {@code additional} more sets of the recipe already loaded and cooking, WITHOUT restarting it: the
     * elapsed cook time and the current selection are left untouched, and the extra ingredients simply stack onto the
     * existing input slots. This is what makes re-clicking the recipe that is already cooking "top the pot up" the way
     * the Farmer's Delight cooking pot does, instead of returning the batch and starting over.
     *
     * <p>Each occupied input slot keeps the exact item variant already in it, a slot holds one kind of item, so milk
     * bottles cannot be topped up with milk buckets. Sourcing is player-inventory-first, network-second, and only the
     * network part costs battery. The amount added is the smallest of: the room left in each occupied slot, the
     * meal-slot room left once the meals the current batch will still produce are counted, how much of each ingredient
     * the player + network can still supply, and what the battery can afford.
     *
     * @return the number of sets actually added (0 if none could be).
     */
    public int topUpBatch(CookingPotRecipe recipe, int additional) {
        if (level == null || autoCrafting || additional <= 0 || selectedRecipeId == null) return 0;

        List<Ingredient> ingredients = recipe.getIngredients();
        int count = ingredients.size();
        if (count <= 0 || count > INPUT_SLOTS) return 0;

        // The loaded batch lives in slots 0..count-1, one ingredient per slot; reuse whatever variant is already there.
        ItemStack[] slotItem = new ItemStack[count];
        for (int n = 0; n < count; n++) {
            ItemStack inSlot = inventory.getStackInSlot(n);
            // An empty slot means this isn't the batch we think it is; a fluid-conjured bucket can't stack. Refuse both.
            if (inSlot.isEmpty() || fluidSourced[n]) return 0;
            slotItem[n] = inSlot;
        }

        // (1) Slot room: no occupied slot may exceed its stack size.
        long cap = additional;
        for (int n = 0; n < count; n++) {
            cap = Math.min(cap, slotItem[n].getMaxStackSize() - slotItem[n].getCount());
        }
        // (2) Meal-slot room, minus the meals the uncooked part of the current batch will itself produce.
        cap = Math.min(cap, mealRoom(recipe) - craftTarget);
        if (cap <= 0) return 0;

        // (3) Availability of the exact items already loaded. An ingredient listed twice occupies two slots, so it
        //     needs `copies` per set, count player + network stock of each distinct item and divide.
        boolean networkUsable = meStorage != null && accessPoint != null && isConnected();
        NonNullList<ItemStack> playerItems = craftingPlayer == null ? null : craftingPlayer.getInventory().items;
        KeyCounter snapshot = new KeyCounter();
        if (networkUsable) snapshot.addAll(meStorage.getAvailableStacks());

        List<ItemStack> distinct = new ArrayList<>();
        List<Integer> copies = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            int idx = -1;
            for (int d = 0; d < distinct.size(); d++) {
                if (ItemStack.isSameItemSameComponents(distinct.get(d), slotItem[n])) {
                    idx = d;
                    break;
                }
            }
            if (idx < 0) {
                distinct.add(slotItem[n].copyWithCount(1));
                copies.add(1);
            } else {
                copies.set(idx, copies.get(idx) + 1);
            }
        }
        long[] playerAvail = new long[distinct.size()];
        for (int d = 0; d < distinct.size(); d++) {
            ItemStack item = distinct.get(d);
            long inPlayer = 0;
            if (playerItems != null) {
                for (ItemStack s : playerItems) {
                    if (ItemStack.isSameItem(s, item)) inPlayer += s.getCount();
                }
            }
            long inNetwork = 0;
            AEItemKey key = AEItemKey.of(item);
            if (networkUsable && key != null) inNetwork = snapshot.get(key);
            playerAvail[d] = inPlayer;
            cap = Math.min(cap, (inPlayer + inNetwork) / copies.get(d));
        }
        if (cap <= 0) return 0;

        // (4) Battery pays only for the network portion, and the player's stock is spent first. The network draw is not
        //     linear in the batch size (the player covers the first sets for free), so find the largest feasible size
        //     by stepping down, the counts here are tiny (<= 64 sets, <= 6 ingredients).
        double drain = cfg().getDrainPerIngredient();
        if (networkUsable && drain > 0) {
            int feasible = 0;
            for (int k = (int) cap; k >= 1; k--) {
                long networkItems = 0;
                for (int d = 0; d < distinct.size(); d++) {
                    long need = (long) copies.get(d) * k;
                    networkItems += Math.max(0, need - playerAvail[d]);
                }
                if (energy >= drain * networkItems) {
                    feasible = k;
                    break;
                }
            }
            cap = feasible;
        }
        if (cap <= 0) return 0;

        // Commit: for each distinct item pull `copies*cap` (player first, then network), then grow every occupied slot.
        IActionSource source = networkUsable ? new MachineSource(accessPoint) : null;
        for (int d = 0; d < distinct.size(); d++) {
            ItemStack item = distinct.get(d);
            int need = copies.get(d) * (int) cap;
            int fromPlayer = (int) Math.min(need, playerAvail[d]);
            int fromNetwork = need - fromPlayer;
            if (fromPlayer > 0 && playerItems != null) {
                int left = fromPlayer;
                for (int slot = 0; slot < playerItems.size() && left > 0; slot++) {
                    ItemStack s = playerItems.get(slot);
                    if (ItemStack.isSameItem(s, item)) {
                        left -= s.split(Math.min(left, s.getCount())).getCount();
                    }
                }
            }
            if (fromNetwork > 0 && networkUsable) {
                AEItemKey key = AEItemKey.of(item);
                long got = key == null ? 0 : meStorage.extract(key, fromNetwork, Actionable.MODULATE, source);
                energy = Math.max(0, energy - drain * got);
            }
        }
        for (int n = 0; n < count; n++) {
            ItemStack s = inventory.getStackInSlot(n);
            s.grow((int) cap);
            inventory.setStackInSlot(n, s);
        }
        craftTarget += (int) cap;
        setChanged();
        return (int) cap;
    }

    /**
     * Request serving containers from the network for the meal currently in the pot, the player's explicit action,
     * never automatic. Tops the container slot up to whatever is actually needed: the fewest of the container's stack
     * size, the number of meals waiting, and what the network holds.
     *
     * @return true if any container arrived; false means nothing was available and the screen should say so.
     */
    public boolean requestContainers() {
        if (autoCrafting) return false;
        ItemStack required = mealContainerStack;
        ItemStack meal = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
        if (required.isEmpty() || meal.isEmpty() || meStorage == null || accessPoint == null || !isConnected()) {
            return noContainersAvailable();
        }
        ItemStack held = inventory.getStackInSlot(CONTAINER_SLOT);
        if (!held.isEmpty() && !ItemStack.isSameItem(held, required)) {
            return noContainersAvailable(); // the slot holds something else; leave the player's item alone
        }
        int wanted = Math.min(required.getMaxStackSize(), meal.getCount()) - held.getCount();
        if (wanted <= 0) {
            return true; // already holding everything this meal needs
        }
        double drain = cfg().getDrainPerIngredient();
        if (drain > 0) {
            wanted = (int) Math.min(wanted, energy / drain);
            if (wanted <= 0) return noContainersAvailable();
        }
        AEItemKey key = AEItemKey.of(required);
        if (key == null) return noContainersAvailable();
        long extracted = meStorage.extract(key, wanted, Actionable.MODULATE, new MachineSource(accessPoint));
        if (extracted <= 0) {
            return noContainersAvailable();
        }
        energy = Math.max(0, energy - drain * extracted);
        inventory.setStackInSlot(CONTAINER_SLOT, required.copyWithCount(held.getCount() + (int) extracted));
        setChanged();
        return true;
    }

    private boolean noContainersAvailable() {
        containerRequestFailures++;
        setChanged();
        return false;
    }

    /**
     * True when the ingredient and container slots are clear, the precondition for loading a batch. A waiting meal or
     * a finished serving in the output does NOT block a new order; the batch simply sits until the meal is served out.
     */
    public boolean canAcceptBatch() {
        if (autoCrafting) return false;
        for (int i = 0; i < INPUT_SLOTS; i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) return false;
        }
        return inventory.getStackInSlot(CONTAINER_SLOT).isEmpty();
    }

    /**
     * The "send to network" button: push everything, ingredients, containers <em>and</em> the finished output, back
     * out, network first (the pot is a network device and the player can pull it all straight back out). The meal slot
     * is deliberately untouched: a meal has not paid for its container yet, so releasing it as items would hand out
     * free servings.
     */
    public void returnContents(@Nullable Player player) {
        if (autoCrafting) return;
        for (int i = 0; i < INPUT_SLOTS; i++) {
            releaseSlot(i, player, false);
        }
        releaseSlot(CONTAINER_SLOT, player, false);
        releaseSlot(OUTPUT_SLOT, player, false);
        selectRecipe(null, 0);
        cookTime = 0;
        setChanged();
    }

    /**
     * Clear the ingredients and serving containers back out before a recipe switch, leaving the output alone, the
     * player's finished servings stay theirs. Items go to the <em>network first</em>, matching the send-to-network
     * button: the pot is a storage device, the ingredients came from the network, and a shift-clicked batch can easily
     * be more than a player's inventory can absorb, sending it at the player first would litter the floor. Your
     * inventory is the fallback when the network is unreachable (so an unlinked pot still hands everything back), and
     * the floor is the last resort, so nothing is ever destroyed.
     */
    public void returnInputsAndContainer(@Nullable Player player) {
        if (autoCrafting) return;
        for (int i = 0; i < INPUT_SLOTS; i++) {
            releaseSlot(i, player, false);
        }
        releaseSlot(CONTAINER_SLOT, player, false);
        selectRecipe(null, 0);
        cookTime = 0;
        setChanged();
    }

    /**
     * Send one slot's contents outward. {@code playerFirst} chooses the order: true tries the player's inventory
     * before the network, false the network before the player. Both current callers (the recipe switch and the
     * send-to-network button) pass false, so ingredients go to the network first with the player's inventory as the
     * fallback. Either way the floor is the last resort, so nothing is ever destroyed.
     */
    private void releaseSlot(int slot, @Nullable Player player, boolean playerFirst) {
        // A bucket conjured from a network fluid was never a real item; the bucket is voided, but the fluid inside it
        // really did come out of the network, so it goes back.
        if (slot < INPUT_SLOTS && fluidSourced[slot]) {
            returnFluidSourcedSlot(slot);
            return;
        }
        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack.isEmpty()) return;
        if (playerFirst) {
            giveToPlayer(stack, player);
            giveToNetwork(stack);
        } else {
            giveToNetwork(stack);
            giveToPlayer(stack, player);
        }
        if (!stack.isEmpty() && level != null) {
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.7,
                    worldPosition.getZ() + 0.5, stack);
        }
        inventory.setStackInSlot(slot, ItemStack.EMPTY);
    }

    /**
     * Empty a slot holding a bucket conjured from network fluid, putting the FLUID back into the network. The bucket
     * itself is never dropped or inserted, it was never a real item, and minting one would hand out a free container, 
     * but the fluid it stands for was genuinely extracted, so destroying it would lose the player's milk/water.
     *
     * <p>If the network is unreachable the fluid is lost: dropping a filled bucket instead would mint exactly the
     * container this avoids, which a player could farm by repeatedly loading a fluid batch and breaking the pot offline.
     */
    private void returnFluidSourcedSlot(int slot) {
        ItemStack stack = inventory.getStackInSlot(slot);
        if (!stack.isEmpty() && meStorage != null && accessPoint != null && isConnected()) {
            FluidStack contained = FluidUtil.getFluidContained(stack).orElse(FluidStack.EMPTY);
            if (contained.getAmount() >= AEFluidKey.AMOUNT_BUCKET) {
                long amount = (long) AEFluidKey.AMOUNT_BUCKET * stack.getCount();
                meStorage.insert(AEFluidKey.of(contained.getFluid()), amount,
                        Actionable.MODULATE, new MachineSource(accessPoint));
            }
        }
        inventory.setStackInSlot(slot, ItemStack.EMPTY);
        fluidSourced[slot] = false;
    }

    /** Insert as much of {@code stack} into the network as it will take (shrinking it), if the pot is linked. */
    private void giveToNetwork(ItemStack stack) {
        if (!stack.isEmpty() && meStorage != null && accessPoint != null && isConnected()) {
            long inserted = meStorage.insert(AEItemKey.of(stack), stack.getCount(),
                    Actionable.MODULATE, new MachineSource(accessPoint));
            stack.shrink((int) inserted);
        }
    }

    /** Put as much of {@code stack} into the player's inventory as fits (shrinking it). */
    private void giveToPlayer(ItemStack stack, @Nullable Player player) {
        if (!stack.isEmpty() && player != null) {
            player.getInventory().add(stack);
        }
    }

    private boolean canCook(CookingPotRecipe recipe) {
        if (level == null || !hasInput()) return false;
        // The inputs must actually make this recipe. A selected recipe is resolved by id, not by matching the slots,
        // so without this the pot would keep cooking a "ghost" of the selected recipe as long as any one input slot
        // stayed occupied, producing a full meal even after ingredients were pulled out. Farmer's Delight gets this
        // for free by re-fetching a matching recipe every tick; we check the selected one directly.
        if (!recipe.matches(new RecipeWrapper(inventory), level)) return false;
        ItemStack result = recipe.assemble(new RecipeWrapper(inventory), level.registryAccess());
        if (result.isEmpty()) return false;
        ItemStack stored = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
        if (stored.isEmpty()) return true;
        // A meal of a different kind blocks cooking until it is served out, the loaded ingredients simply wait.
        if (!ItemStack.isSameItem(stored, result)) return false;
        return stored.getCount() + result.getCount() <= Math.max(MEAL_CAPACITY, stored.getMaxStackSize());
    }

    private boolean processCooking(CookingPotRecipe recipe) {
        if (level == null) return false;
        ++cookTime;
        cookTimeTotal = recipe.getCookTime();
        if (cookTime < cookTimeTotal) return false;

        cookTime = 0;
        mealContainerStack = recipe.getOutputContainer();
        ItemStack result = recipe.assemble(new RecipeWrapper(inventory), level.registryAccess());
        ItemStack stored = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
        if (stored.isEmpty()) {
            inventory.setStackInSlot(MEAL_DISPLAY_SLOT, result.copy());
        } else if (ItemStack.isSameItem(stored, result)) {
            stored.grow(result.getCount());
        }

        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack slot = inventory.getStackInSlot(i);
            // A fluid-sourced ingredient (milk/water conjured from a network fluid) leaves no real bucket, so its
            // empty-bucket remainder is voided; a real item ingredient returns its remainder (matching Farmer's
            // Delight's own override list for buckets/bowls/bottles that don't carry a crafting remainder themselves).
            if (!fluidSourced[i]) {
                if (slot.hasCraftingRemainingItem()) {
                    returnOrDrop(slot.getCraftingRemainingItem());
                } else if (CookingPotBlockEntity.INGREDIENT_REMAINDER_OVERRIDES.containsKey(slot.getItem())) {
                    returnOrDrop(CookingPotBlockEntity.INGREDIENT_REMAINDER_OVERRIDES.get(slot.getItem()).getDefaultInstance());
                }
            }
            if (!slot.isEmpty()) {
                slot.shrink(1);
            }
            if (inventory.getStackInSlot(i).isEmpty()) {
                fluidSourced[i] = false;
            }
        }
        return true;
    }

    /**
     * Dispose of a crafting remainder (the empty bucket a milk bucket leaves behind). Farmer's Delight drops these on
     * the floor; we prefer to put them back in the network, which the {@code returnRemaindersToNetwork} config can
     * turn off for packs that want FD's behaviour.
     */
    private void returnOrDrop(ItemStack remainder) {
        if (remainder.isEmpty() || level == null) return;
        if (cfg().returnRemaindersToNetwork() && meStorage != null && accessPoint != null) {
            long inserted = meStorage.insert(AEItemKey.of(remainder), remainder.getCount(),
                    Actionable.MODULATE, new MachineSource(accessPoint));
            remainder.shrink((int) inserted);
        }
        if (!remainder.isEmpty()) {
            ejectIngredientRemainder(remainder);
        }
    }

    /**
     * Drop a crafting remainder the way Farmer's Delight does, nudged out the pot's left side at a fixed position and
     * velocity, so a batch's remainders land together in one spot instead of scattering. Mirrors
     * {@code CookingPotBlockEntity#ejectIngredientRemainder}, using our own {@code FACING} property.
     */
    private void ejectIngredientRemainder(ItemStack remainderStack) {
        if (level == null || remainderStack.isEmpty()) return;
        Direction direction = getBlockState().getValue(MECookingPotBlock.FACING).getCounterClockWise();
        double x = worldPosition.getX() + 0.5 + (direction.getStepX() * 0.25);
        double y = worldPosition.getY() + 0.7;
        double z = worldPosition.getZ() + 0.5 + (direction.getStepZ() * 0.25);
        ItemUtils.spawnItemEntity(level, remainderStack, x, y, z,
                direction.getStepX() * 0.08F, 0.25F, direction.getStepZ() * 0.08F);
    }

    private boolean mealHasContainer(ItemStack meal) {
        return !mealContainerStack.isEmpty() || meal.hasCraftingRemainingItem();
    }

    private boolean isContainerValid(ItemStack container) {
        if (container.isEmpty()) return false;
        return !mealContainerStack.isEmpty() && ItemStack.isSameItem(mealContainerStack, container);
    }

    private void moveMealToOutput() {
        ItemStack meal = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
        ItemStack output = inventory.getStackInSlot(OUTPUT_SLOT);
        int moved = Math.min(meal.getCount(), meal.getMaxStackSize() - output.getCount());
        if (output.isEmpty()) {
            inventory.setStackInSlot(OUTPUT_SLOT, meal.split(moved));
        } else if (ItemStack.isSameItem(meal, output)) {
            meal.shrink(moved);
            output.grow(moved);
        }
    }

    private void useStoredContainersOnMeal() {
        ItemStack meal = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
        ItemStack container = inventory.getStackInSlot(CONTAINER_SLOT);
        ItemStack output = inventory.getStackInSlot(OUTPUT_SLOT);
        if (isContainerValid(container) && output.getCount() < output.getMaxStackSize()) {
            int smaller = Math.min(meal.getCount(), container.getCount());
            int moved = Math.min(smaller, meal.getMaxStackSize() - output.getCount());
            if (output.isEmpty()) {
                container.shrink(moved);
                inventory.setStackInSlot(OUTPUT_SLOT, meal.split(moved));
            } else if (ItemStack.isSameItem(output, meal)) {
                meal.shrink(moved);
                container.shrink(moved);
                output.grow(moved);
            }
        }
    }

    /**
     * Farmer's Delight's hand-serve: right-clicking the pot with the meal's correct serving container takes one
     * serving straight into the player's hand, consuming one container. Returns the served meal, or empty if the held
     * item isn't the right container or there is no meal waiting. Mirrors {@code CookingPotBlockEntity#useHeldItemOnMeal}.
     */
    public ItemStack useHeldItemOnMeal(ItemStack container) {
        if (autoCrafting) return ItemStack.EMPTY;
        ItemStack meal = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
        if (isContainerValid(container) && !meal.isEmpty()) {
            container.shrink(1);
            setChanged();
            return meal.split(1);
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Network resolution + booster-aware reach (mirrors Applied Cooking, adds range/dimension gating)
    // ------------------------------------------------------------------------------------------------------------

    private void resolveAccessPoint() {
        linkedAccessPoint = null;
        if (!(level instanceof ServerLevel serverLevel) || accessPointPos == null) return;

        ServerLevel linkedLevel = serverLevel.getServer().getLevel(accessPointPos.dimension());
        if (linkedLevel == null) return;

        if (Platform.getTickingBlockEntity(linkedLevel, accessPointPos.pos()) instanceof IWirelessAccessPoint found) {
            linkedAccessPoint = found;
        }
    }

    private void refreshAutoCookingPatterns() {
        if (level == null || level.isClientSide) return;

        List<RecipeHolder<CookingPotRecipe>> recipes = MECookingPotMenu.sortedRecipes(level);
        int fingerprint = 1;
        for (RecipeHolder<CookingPotRecipe> holder : recipes) {
            fingerprint = 31 * fingerprint + holder.id().hashCode();
            fingerprint = 31 * fingerprint + holder.value().hashCode();
        }
        if (fingerprint == autoCookingPatternFingerprint) {
            return;
        }

        List<IPatternDetails> rebuilt = new ArrayList<>(recipes.size());
        for (RecipeHolder<CookingPotRecipe> holder : recipes) {
            if (holder.value().getIngredients().size() > INPUT_SLOTS) {
                continue;
            }
            AutoCookingPattern pattern = AutoCookingPattern.fromRecipe(holder, level);
            if (pattern != null) {
                rebuilt.add(pattern);
            }
        }
        autoCookingPatterns = List.copyOf(rebuilt);
        autoCookingPatternFingerprint = fingerprint;
        if (craftingProviderGrid != null) {
            craftingProviderGrid.getCraftingService().refreshGlobalCraftingProvider(this);
        }
    }

    private void refreshConnection() {
        IGrid liveGrid = null;
        IWirelessAccessPoint liveAccessPoint = null;
        MEStorage liveStorage = null;

        grid = null;
        meStorage = null;
        accessPoint = null;

        if (linkedAccessPoint != null && energy > 0) {
            // The link only identifies which NETWORK the pot belongs to, exactly how AE2's wireless terminal treats it.
            // Reach is then judged against every access point on that grid, so building a nearer access point just works
            // without re-linking the pot.
            liveGrid = linkedAccessPoint.getGrid();
            if (liveGrid != null) {
                liveAccessPoint = selectReachableAccessPoint(liveGrid);
            }

            if (liveAccessPoint != null) {
                // Keeping the link open is not free: pay the per-tick idle cost, or drop offline until recharged.
                double idle = cfg().getIdleDrainPerTick();
                if (idle > 0 && energy < idle) {
                    energy = 0;
                    liveGrid = null;
                    liveAccessPoint = null;
                } else {
                    energy -= idle;
                    liveStorage = liveGrid.getStorageService().getInventory();
                }
            } else {
                liveGrid = null;
            }
        }

        grid = liveGrid;
        accessPoint = liveAccessPoint;
        meStorage = liveStorage;
        setCraftingProviderGrid(liveGrid);
    }

    private void setCraftingProviderGrid(@Nullable IGrid newGrid) {
        if (craftingProviderGrid == newGrid) {
            return;
        }
        if (craftingProviderGrid != null) {
            craftingProviderGrid.getCraftingService().removeGlobalCraftingProvider(this);
        }
        craftingProviderGrid = newGrid;
        if (craftingProviderGrid != null) {
            craftingProviderGrid.getCraftingService().addGlobalCraftingProvider(this);
        }
    }

    /**
     * Pick an access point on {@code grid} that covers this pot, mirroring how AE2's wireless terminal chooses one, 
     * except measured from the pot instead of from a player. Reach is entirely the network's business: an access
     * point's own {@link IWirelessAccessPoint#getRange()} already includes any Wireless Boosters installed in it, so
     * upgrading the access point is how a player extends coverage. There are deliberately no config knobs.
     *
     * <p>This is written out rather than delegated because AE2's own check
     * ({@code WirelessTerminalMenuHost#getAccessPointSignal}) is protected and measured from {@code getPlayer()}, a
     * placed block has no API to hand the question to, and no mixin would help, since our block never runs that code.
     *
     * @return the nearest covering access point, or null if none reaches this pot.
     */
    @Nullable
    private IWirelessAccessPoint selectReachableAccessPoint(IGrid grid) {
        if (level == null) return null;

        // AEInfinityBooster's cards work from ANY access point on the network, not just the linked one, that is how
        // their own mixin reads them, so we match it. Detected softly by item id; no dependency on the mod.
        BoosterCard card = detectBoosterCard(grid);

        IWirelessAccessPoint best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (WirelessAccessPointBlockEntity wap : grid.getMachines(WirelessAccessPointBlockEntity.class)) {
            if (!wap.isActive()) continue;
            boolean sameDimension = wap.getLocation().getLevel() == this.level;
            // A Dimension card reaches across worlds; anything else has to be in this one.
            if (!sameDimension) {
                if (card == BoosterCard.DIMENSION) {
                    return wap;
                }
                continue;
            }
            if (card != BoosterCard.NONE) {
                return wap; // either card lifts the range limit within the dimension
            }
            BlockPos pos = wap.getLocation().getPos();
            double dx = (pos.getX() + 0.5) - (worldPosition.getX() + 0.5);
            double dy = (pos.getY() + 0.5) - (worldPosition.getY() + 0.5);
            double dz = (pos.getZ() + 0.5) - (worldPosition.getZ() + 0.5);
            double distanceSq = dx * dx + dy * dy + dz * dz;
            double range = wap.getRange();
            if (distanceSq <= range * range && distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = wap;
            }
        }
        return best;
    }

    /**
     * Softly detect an AEInfinityBooster card in <em>any</em> access point on the grid (no hard dependency, the cards
     * are matched by item id). Scanning the whole grid rather than just the linked access point is what
     * AEInfinityBooster's own mixin does, so a card placed in a different access point still counts.
     */
    private BoosterCard detectBoosterCard(IGrid grid) {
        BoosterCard found = BoosterCard.NONE;
        for (WirelessAccessPointBlockEntity wap : grid.getMachines(WirelessAccessPointBlockEntity.class)) {
            ItemStack slot = wap.getInternalInventory().getStackInSlot(0);
            if (slot.isEmpty()) continue;
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem());
            if (DIMENSION_CARD_ID.equals(id)) {
                return BoosterCard.DIMENSION; // the strongest card wins outright
            }
            if (INFINITY_CARD_ID.equals(id)) {
                found = BoosterCard.INFINITY;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Item <-> block-entity data transfer + drops
    // ------------------------------------------------------------------------------------------------------------

    public void applyDataFromItem(ItemStack stack) {
        accessPointPos = MECookingPotItem.getLinkedAccessPoint(stack);
        energy = MECookingPotItem.getStoredEnergy(stack);
        if (level != null) {
            // A meal broken with the pot rides inside the item and comes back when it is placed, exactly as in
            // Farmer's Delight, it never dropped as a loose item, because it has not paid for its container.
            ItemStack meal = MECookingPotItem.getStoredMeal(stack, level.registryAccess());
            if (!meal.isEmpty()) {
                inventory.setStackInSlot(MEAL_DISPLAY_SLOT, meal);
                mealContainerStack = MECookingPotItem.getStoredMealContainer(stack, level.registryAccess());
            }
        }
        setChanged();
    }

    public void saveToItem(ItemStack stack) {
        MECookingPotItem.setLinkedAccessPoint(stack, accessPointPos);
        MECookingPotItem.setStoredEnergy(stack, energy);
        if (level != null) {
            MECookingPotItem.setStoredMeal(stack, level.registryAccess(),
                    inventory.getStackInSlot(MEAL_DISPLAY_SLOT), mealContainerStack);
        }
    }

    /** Feed the link + battery into the dropped pot on ANY break, via a {@code copy_components} loot function. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        ItemStack carrier = new ItemStack(sebastrn.applieddelight.ADItems.ME_COOKING_POT.get());
        saveToItem(carrier);
        CustomData data = carrier.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            components.set(DataComponents.CUSTOM_DATA, data);
        }
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        CustomData data = input.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            ItemStack carrier = new ItemStack(sebastrn.applieddelight.ADItems.ME_COOKING_POT.get());
            carrier.set(DataComponents.CUSTOM_DATA, data);
            applyDataFromItem(carrier);
        }
    }

    public void dropContents(Level level, BlockPos pos) {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            // Matches Farmer's Delight's own getDroppableInventory(), which skips the meal slot: a meal has not paid
            // for its serving container, so dropping it would hand out free servings.
            if (i == MEAL_DISPLAY_SLOT) {
                continue;
            }
            // A bucket conjured from a network fluid isn't a real item, never drop it, but put its FLUID back into
            // the network rather than destroying it along with the pot.
            if (i < INPUT_SLOTS && fluidSourced[i]) {
                returnFluidSourcedSlot(i);
                continue;
            }
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            }
        }
    }

    @Override
    public void setRemoved() {
        setCraftingProviderGrid(null);
        super.setRemoved();
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Menu
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.applieddelight.me_cooking_pot");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return new MECookingPotMenu(id, playerInventory, this, data);
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Persistence
    // ------------------------------------------------------------------------------------------------------------

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        cookTime = tag.getInt("CookTime");
        cookTimeTotal = tag.getInt("CookTimeTotal");
        mealContainerStack = ItemStack.parseOptional(registries, tag.getCompound("Container"));
        energy = tag.getDouble("Energy");
        craftTarget = tag.getInt("CraftTarget");
        autoCraftingOutput = ItemStack.parseOptional(registries, tag.getCompound("AutoCraftingOutput"));
        autoCrafting = tag.getBoolean("AutoCrafting") && !autoCraftingOutput.isEmpty();
        int fluidMask = tag.getInt("FluidSourced");
        for (int i = 0; i < INPUT_SLOTS; i++) {
            fluidSourced[i] = (fluidMask & (1 << i)) != 0;
        }
        selectedRecipeId = tag.contains("SelectedRecipe", Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString("SelectedRecipe")) : null;
        if (tag.contains("AccessPointPos")) {
            accessPointPos = GlobalPos.CODEC.decode(NbtOps.INSTANCE, tag.get("AccessPointPos"))
                    .result().map(Pair::getFirst).orElse(null);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.putInt("CookTime", cookTime);
        tag.putInt("CookTimeTotal", cookTimeTotal);
        tag.put("Container", mealContainerStack.saveOptional(registries));
        tag.putDouble("Energy", energy);
        tag.putInt("CraftTarget", craftTarget);
        tag.putBoolean("AutoCrafting", autoCrafting);
        tag.put("AutoCraftingOutput", autoCraftingOutput.saveOptional(registries));
        int fluidMask = 0;
        for (int i = 0; i < INPUT_SLOTS; i++) {
            if (fluidSourced[i]) fluidMask |= (1 << i);
        }
        tag.putInt("FluidSourced", fluidMask);
        if (selectedRecipeId != null) {
            tag.putString("SelectedRecipe", selectedRecipeId.toString());
        }
        if (accessPointPos != null) {
            GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, accessPointPos).result()
                    .ifPresent(t -> tag.put("AccessPointPos", t));
        }
    }
}
