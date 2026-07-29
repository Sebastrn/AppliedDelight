package sebastrn.applieddelight.menu;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import sebastrn.applieddelight.ADAttachments;
import sebastrn.applieddelight.ADBlocks;
import sebastrn.applieddelight.ADMenus;
import sebastrn.applieddelight.blockentity.MECookingPotBlockEntity;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.Comparator;
import java.util.List;

/**
 * POC-A menu for the ME Cooking Pot: a Farmer's-Delight-style slot layout plus a custom recipe list rendered by the
 * screen. Recipe selection is sent with {@link #clickMenuButton} — the button id is an index into the cooking-recipe
 * list that both sides sort identically, so no custom packet is needed.
 */
public class MECookingPotMenu extends AbstractContainerMenu {

    public static final int CLEAR_SELECTION = -2;
    /** Button ids for the two recipe-panel view toggles (see {@link ADAttachments.PotViewSettings}). */
    public static final int TOGGLE_PANEL = -3;
    public static final int TOGGLE_FILTER = -4;
    /** Push the pot's contents back to the network / player, freeing it to take a new recipe. */
    public static final int RETURN_CONTENTS = -5;
    /** Ask the network for the serving containers the waiting meal needs. Never happens automatically. */
    public static final int REQUEST_CONTAINERS = -6;
    /** Cycle the recipe-list name sort: off → A-Z → Z-A → off. */
    public static final int TOGGLE_SORT = -7;
    /** Button-id offset added to a recipe index to signal a shift-click (batch craft). */
    public static final int SHIFT_OFFSET = 100000;

    private static final int INV_START = MECookingPotBlockEntity.OUTPUT_SLOT + 1; // 9
    private static final int INV_END = INV_START + 36;

    public final MECookingPotBlockEntity blockEntity;
    private final ItemStackHandler inventory;
    private final ContainerData data;
    private final Level level;
    private final Player player;
    /** Per-recipe max-craftable counts (network + player inventory), computed server-side and synced to the screen. */
    private final ContainerData availabilityData;
    private final int recipeCount;
    private int availabilityTimer = 0;
    /** The opening player's recipe-panel view preference; authoritative copy lives on the player attachment. */
    private boolean panelOpen;
    private boolean filterCraftableOnly;
    private int sortMode;

    /**
     * Client constructor (via IContainerFactory): the block position rides across in the buffer, followed by the
     * player's view settings. They travel on the open buffer rather than as synced container data so the screen has
     * them in {@code init()} — the panel changes the GUI's width, and reading it a tick later would resize on open.
     */
    public MECookingPotMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(id, playerInventory, resolve(playerInventory, buf.readBlockPos()),
                new SimpleContainerData(MECookingPotBlockEntity.DATA_SLOTS));
        this.panelOpen = buf.readBoolean();
        this.filterCraftableOnly = buf.readBoolean();
        this.sortMode = buf.readInt();
    }

    public MECookingPotMenu(int id, Inventory playerInventory, MECookingPotBlockEntity blockEntity, ContainerData data) {
        super(ADMenus.ME_COOKING_POT.get(), id);
        this.blockEntity = blockEntity;
        this.inventory = blockEntity.getInventory();
        this.data = data;
        this.level = playerInventory.player.level();
        this.player = playerInventory.player;
        this.recipeCount = sortedRecipes(level).size();
        this.availabilityData = new SimpleContainerData(Math.max(1, recipeCount));
        // Server side this is the real preference; on the client the buffer overwrites it right after construction.
        ADAttachments.PotViewSettings view = this.player.getData(ADAttachments.POT_VIEW);
        this.panelOpen = view.panelOpen();
        this.filterCraftableOnly = view.filterCraftableOnly();
        this.sortMode = view.sortMode();

        // Ingredient slots — 2 rows x 3 columns (Farmer's Delight layout).
        int inputStartX = 30, inputStartY = 17, slot = 18;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new SlotItemHandler(inventory, row * 3 + col, inputStartX + col * slot, inputStartY + row * slot));
            }
        }
        // Meal display (result kept in the pot), serving-container input, finished-output.
        // Meal display: shows the cooked meal but cannot be placed into or taken from (FD's CookingPotMealSlot).
        // To get the meal out you must serve it into the output with a container.
        addSlot(new SlotItemHandler(inventory, MECookingPotBlockEntity.MEAL_DISPLAY_SLOT, 124, 26) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }
        });
        // Serving-container input (bowls/bottles): the player may place them here, or use the request-containers button; the pot never pulls containers on its own.
        addSlot(new SlotItemHandler(inventory, MECookingPotBlockEntity.CONTAINER_SLOT, 92, 55));
        // Output: served meals land here (take them from here); cannot be placed into (FD's CookingPotResultSlot).
        addSlot(new SlotItemHandler(inventory, MECookingPotBlockEntity.OUTPUT_SLOT, 124, 55) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });

        // Player inventory + hotbar.
        int startX = 8, startPlayerInvY = 84;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, 9 + row * 9 + col, startX + col * 18, startPlayerInvY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, startX + col * 18, 142));
        }

        addDataSlots(data);
        addDataSlots(availabilityData);

        // Let the pot draw ingredients from this player's inventory while the GUI is open — the recipe list already
        // counts those items, so cooking has to be able to reach them too.
        if (!level.isClientSide) {
            blockEntity.setCraftingPlayer(playerInventory.player);
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!level.isClientSide) {
            blockEntity.setCraftingPlayer(null);
        }
    }

    /** Recompute per-recipe max-craftable counts server-side (throttled) so the screen can show counts + dim. */
    @Override
    public void broadcastChanges() {
        if (!level.isClientSide && ++availabilityTimer >= 10) {
            availabilityTimer = 0;
            int[] counts = blockEntity.computeMaxCraftable(player);
            for (int i = 0; i < recipeCount && i < counts.length; i++) {
                availabilityData.set(i, counts[i]);
            }
        }
        super.broadcastChanges();
    }

    private static MECookingPotBlockEntity resolve(Inventory inv, BlockPos pos) {
        if (inv.player.level().getBlockEntity(pos) instanceof MECookingPotBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("ME Cooking Pot block entity missing at " + pos);
    }

    /** The Farmer's Delight cooking recipes, in a stable order shared by the screen (client) and this menu (server). */
    public static List<RecipeHolder<CookingPotRecipe>> sortedRecipes(Level level) {
        List<RecipeHolder<CookingPotRecipe>> recipes =
                new java.util.ArrayList<>(level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.COOKING.get()));
        recipes.sort(Comparator.comparing(h -> h.id().toString()));
        return recipes;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == CLEAR_SELECTION) {
            blockEntity.selectRecipe(null, 0);
            return true;
        }
        if (id == TOGGLE_PANEL || id == TOGGLE_FILTER || id == TOGGLE_SORT) {
            if (id == TOGGLE_PANEL) {
                panelOpen = !panelOpen;
            } else if (id == TOGGLE_FILTER) {
                filterCraftableOnly = !filterCraftableOnly;
            } else {
                sortMode = (sortMode + 1) % 3; // off -> A-Z -> Z-A -> off
            }
            // Persist on the player, so every pot they open from now on — this session or after a restart — matches.
            player.setData(ADAttachments.POT_VIEW,
                    new ADAttachments.PotViewSettings(panelOpen, filterCraftableOnly, sortMode));
            return true;
        }
        if (id == RETURN_CONTENTS) {
            blockEntity.returnContents(player);
            return true;
        }
        if (id == REQUEST_CONTAINERS) {
            blockEntity.requestContainers();
            return true;
        }
        boolean shift = id >= SHIFT_OFFSET;
        int index = shift ? id - SHIFT_OFFSET : id;
        List<RecipeHolder<CookingPotRecipe>> recipes = sortedRecipes(level);
        if (index >= 0 && index < recipes.size()) {
            RecipeHolder<CookingPotRecipe> holder = recipes.get(index);
            // Re-clicking the recipe already loaded TOPS UP the current batch instead of restarting it: more
            // ingredients flow into the existing input slots and the cook in progress keeps its elapsed time. A plain
            // click adds one more set; shift-click adds as many as the pot can still take (topUpBatch does the
            // clamping). Switching to a DIFFERENT recipe still clears the pot out first and starts fresh.
            if (holder.id().equals(blockEntity.getSelectedRecipeId())) {
                int request = shift ? MECookingPotBlockEntity.MEAL_CAPACITY : 1;
                return blockEntity.topUpBatch(holder.value(), request) > 0;
            }

            // Switching recipes clears the previous order out first (to the network first, player inventory as fallback). The
            // output is left alone — those are the player's finished servings — and a waiting meal stays put, so the
            // new batch simply cooks once the meal has been served out. No network link is required: an unlinked or
            // flat pot works as a plain cooking pot, sourcing only from the player's inventory.
            blockEntity.returnInputsAndContainer(player);

            // Normal click cooks one; shift-click cooks as many as the pot can actually take in one batch.
            int[] max = blockEntity.computeMaxCraftable(player);
            int ceiling = index < max.length ? max[index] : 0;
            int requested = shift ? ceiling : Math.min(1, ceiling);
            if (requested <= 0) {
                return false;
            }
            // Load what is actually obtainable — a partial batch beats refusing outright.
            int loaded = blockEntity.loadBatch(holder.value(), requested);
            if (loaded <= 0) {
                return false;
            }
            blockEntity.selectRecipe(holder.id(), loaded);
            return true;
        }
        return false;
    }

    /**
     * True when a heat source sits under the pot. Read straight from the block below rather than synced through
     * container data: that is blockstate the client already has, which is exactly how Farmer's Delight's own screen
     * resolves it.
     */
    public boolean isHeated() {
        return blockEntity.isHeated(level, blockEntity.getBlockPos());
    }

    public int getCookProgressScaled() {
        int t = data.get(0), total = data.get(1);
        return total != 0 && t != 0 ? t * 24 / total : 0;
    }

    /** Link state for the status LED / tooltips: 0 = Unlinked, 1 = Offline (linked, not connected), 2 = Linked. */
    public int getLinkState() {
        return data.get(2);
    }

    public boolean isConnected() {
        return data.get(2) == 2;
    }

    public int getEnergyPercent() {
        return data.get(3);
    }

    /** Increments whenever a container request found nothing — the screen flashes the button when it changes. */
    public int getContainerFailureCount() {
        return data.get(4);
    }

    public boolean isPanelOpen() {
        return panelOpen;
    }

    public boolean isFilterCraftableOnly() {
        return filterCraftableOnly;
    }

    /**
     * Applied client-side the moment a toggle is clicked, before the server confirms — the same optimistic update
     * vanilla does, where the client flips its own recipe book and then reports the change.
     */
    public void setPanelOpen(boolean open) {
        this.panelOpen = open;
    }

    public void setFilterCraftableOnly(boolean filter) {
        this.filterCraftableOnly = filter;
    }

    /** 0 = off (default order), 1 = A-Z, 2 = Z-A. Applied within the craftable and non-craftable blocks. */
    public int getSortMode() {
        return sortMode;
    }

    public void setSortMode(int mode) {
        this.sortMode = mode;
    }

    /** Max craftable count of recipe {@code index} from the network + player inventory (0 if none). */
    public int getRecipeCount(int index) {
        return (index >= 0 && index < recipeCount) ? availabilityData.get(index) : 0;
    }

    /** True if recipe {@code index} (in {@link #sortedRecipes}) can be cooked right now from network + inventory. */
    public boolean isRecipeCraftable(int index) {
        return getRecipeCount(index) > 0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // The meal display slot is never movable — the meal must be served into the output via a container.
        if (index == MECookingPotBlockEntity.MEAL_DISPLAY_SLOT) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = ItemStack.EMPTY;
        Slot clicked = slots.get(index);
        if (clicked != null && clicked.hasItem()) {
            ItemStack stack = clicked.getItem();
            copy = stack.copy();
            if (index < INV_START) {
                // From the pot into the player inventory.
                if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
            } else {
                // From the player inventory into the container slot, else the input slots.
                if (!moveItemStackTo(stack, MECookingPotBlockEntity.CONTAINER_SLOT, MECookingPotBlockEntity.CONTAINER_SLOT + 1, false)
                        && !moveItemStackTo(stack, 0, MECookingPotBlockEntity.INPUT_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stack.isEmpty()) {
                clicked.set(ItemStack.EMPTY);
            } else {
                clicked.setChanged();
            }
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity.getLevel() != null
                && blockEntity.getLevel().getBlockState(blockEntity.getBlockPos()).is(ADBlocks.ME_COOKING_POT.get())
                && player.distanceToSqr(blockEntity.getBlockPos().getX() + 0.5, blockEntity.getBlockPos().getY() + 0.5,
                blockEntity.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
