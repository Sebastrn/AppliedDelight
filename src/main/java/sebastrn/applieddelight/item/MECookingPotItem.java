package sebastrn.applieddelight.item;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.features.IGridLinkableHandler;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.core.localization.GuiText;
import appeng.core.localization.Tooltips;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import sebastrn.applieddelight.AppliedDelight;

import java.util.List;

/**
 * The ME Cooking Pot as an item: carries its battery charge and its Wireless Access Point link across placement, and is
 * chargeable in an AE2 Charger via {@link IAEItemPowerStorage} (the energy capability is registered in
 * {@link AppliedDelight}). Linking to an access point uses AE2's {@link IGridLinkableHandler}, the same mechanism its
 * own wireless terminals use. All persistent item data lives under the vanilla {@code CUSTOM_DATA} component.
 */
public class MECookingPotItem extends BlockItem implements IAEItemPowerStorage {

    public static final IGridLinkableHandler LINKABLE_HANDLER = new LinkableHandler();

    /** Key (within CUSTOM_DATA) for the GlobalPos of the linked Wireless Access Point. Shared with the block entity. */
    public static final String TAG_ACCESS_POINT_POS = "accessPointPos";
    /** Key (within CUSTOM_DATA) for the stored battery energy, in AE units. */
    public static final String TAG_ENERGY = "Energy";
    /**
     * Keys (within CUSTOM_DATA) for a meal still sitting in the pot when it was broken, and the container that meal
     * needs to be served into. Farmer's Delight does the same with its own {@code meal}/{@code container} components:
     * the meal never drops as a loose item, it travels inside the pot. The container has to travel too, or a replaced
     * pot would hold a meal it no longer knows how to serve.
     */
    public static final String TAG_MEAL = "Meal";
    public static final String TAG_MEAL_CONTAINER = "MealContainer";

    private static final int BAR_COLOR = Mth.color(0.2F, 0.6F, 1.0F);

    public MECookingPotItem(Block block, Properties properties) {
        super(block, properties);
    }

    private static double capacity() {
        return AppliedDelight.SERVER_CONFIG.getMeCookingPot().getBatteryCapacity();
    }

    // --- Tooltip ------------------------------------------------------------------------------------------------

    /**
     * Deliberately mirrors AE2's Wireless Terminal: an energy line, then the link state in green/red. It calls AE2's own
     * {@link Tooltips}/{@link GuiText} rather than re-wording them, so the phrasing, the {@code 0.67M/1.6M AE (41.89%)}
     * number formatting and every translation stay identical to AE2's wireless tools.
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        lines.add(Tooltips.energyStorageComponent(getStoredEnergy(stack), capacity()));
        // The item can only know whether a link is saved, not whether a placed pot could reach the network, so it
        // shows Linked (has an access point) or Unlinked, never the Offline middle state.
        lines.add(linkComponent(getLinkedAccessPoint(stack) == null ? 0 : 2));
    }

    /**
     * The link-state line, shared by the item tooltip, Jade and The One Probe so the wording and colour never drift.
     * {@code 0} = Unlinked (no access point saved), {@code 1} = Offline (linked but not currently connected),
     * {@code 2} = Linked (connected). Reuses AE2's own {@link GuiText} strings for the two endpoints.
     */
    public static Component linkComponent(int state) {
        return switch (state) {
            case 2 -> Tooltips.of(GuiText.Linked, Tooltips.GREEN);
            case 1 -> Component.literal("Offline").withStyle(ChatFormatting.YELLOW);
            default -> Tooltips.of(GuiText.Unlinked, Tooltips.RED);
        };
    }

    // --- CUSTOM_DATA helpers ------------------------------------------------------------------------------------

    private static CompoundTag readTag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void writeTag(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // --- Battery ------------------------------------------------------------------------------------------------

    public static double getStoredEnergy(ItemStack stack) {
        return readTag(stack).getDouble(TAG_ENERGY);
    }

    public static void setStoredEnergy(ItemStack stack, double energy) {
        CompoundTag tag = readTag(stack);
        tag.putDouble(TAG_ENERGY, Mth.clamp(energy, 0.0, capacity()));
        writeTag(stack, tag);
    }

    // --- Stored meal (carried through break/replace, like Farmer's Delight) ---------------------------------------

    public static ItemStack getStoredMeal(ItemStack stack, HolderLookup.Provider registries) {
        return readStack(stack, registries, TAG_MEAL);
    }

    public static ItemStack getStoredMealContainer(ItemStack stack, HolderLookup.Provider registries) {
        return readStack(stack, registries, TAG_MEAL_CONTAINER);
    }

    public static void setStoredMeal(ItemStack stack, HolderLookup.Provider registries, ItemStack meal, ItemStack container) {
        CompoundTag tag = readTag(stack);
        writeStack(tag, registries, TAG_MEAL, meal);
        writeStack(tag, registries, TAG_MEAL_CONTAINER, container);
        writeTag(stack, tag);
    }

    private static ItemStack readStack(ItemStack stack, HolderLookup.Provider registries, String key) {
        CompoundTag tag = readTag(stack);
        return tag.contains(key) ? ItemStack.parseOptional(registries, tag.getCompound(key)) : ItemStack.EMPTY;
    }

    private static void writeStack(CompoundTag tag, HolderLookup.Provider registries, String key, ItemStack value) {
        if (value.isEmpty()) {
            tag.remove(key);
        } else {
            tag.put(key, value.saveOptional(registries));
        }
    }

    // --- Wireless Access Point link ------------------------------------------------------------------------------

    @Nullable
    public static GlobalPos getLinkedAccessPoint(ItemStack stack) {
        CompoundTag tag = readTag(stack);
        Tag posTag = tag.get(TAG_ACCESS_POINT_POS);
        if (posTag == null) {
            return null;
        }
        return GlobalPos.CODEC.decode(NbtOps.INSTANCE, posTag).result().map(Pair::getFirst).orElse(null);
    }

    public static void setLinkedAccessPoint(ItemStack stack, @Nullable GlobalPos pos) {
        CompoundTag tag = readTag(stack);
        if (pos == null) {
            tag.remove(TAG_ACCESS_POINT_POS);
        } else {
            GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).result()
                    .ifPresent(posTag -> tag.put(TAG_ACCESS_POINT_POS, posTag));
        }
        writeTag(stack, tag);
    }

    // --- IAEItemPowerStorage (battery) --------------------------------------------------------------------------

    @Override
    public double injectAEPower(ItemStack stack, double amount, Actionable mode) {
        double current = getStoredEnergy(stack);
        double room = Math.max(0, capacity() - current);
        double accepted = Math.min(amount, room);
        if (mode == Actionable.MODULATE) {
            setStoredEnergy(stack, current + accepted);
        }
        return amount - accepted; // overflow, unable to be stored
    }

    @Override
    public double extractAEPower(ItemStack stack, double amount, Actionable mode) {
        double current = getStoredEnergy(stack);
        double taken = Math.min(amount, current);
        if (mode == Actionable.MODULATE) {
            setStoredEnergy(stack, current - taken);
        }
        return taken;
    }

    @Override
    public double getAEMaxPower(ItemStack stack) {
        return capacity();
    }

    @Override
    public double getAECurrentPower(ItemStack stack) {
        return getStoredEnergy(stack);
    }

    @Override
    public AccessRestriction getPowerFlow(ItemStack stack) {
        return AccessRestriction.READ_WRITE;
    }

    @Override
    public double getChargeRate(ItemStack stack) {
        return 800.0;
    }

    // --- Battery bar on the item icon ---------------------------------------------------------------------------

    @Override
    public boolean isBarVisible(ItemStack stack) {
        double cur = getStoredEnergy(stack);
        return cur > 0 && cur < capacity();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        double max = capacity();
        return max <= 0 ? 0 : Math.round(13.0F * (float) (getStoredEnergy(stack) / max));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BAR_COLOR;
    }

    // --- Linking to a Wireless Access Point (AE2 IGridLinkableHandler) -------------------------------------------

    private static final class LinkableHandler implements IGridLinkableHandler {
        @Override
        public boolean canLink(ItemStack stack) {
            return stack.getItem() instanceof MECookingPotItem;
        }

        @Override
        public void link(ItemStack itemStack, GlobalPos pos) {
            setLinkedAccessPoint(itemStack, pos);
        }

        @Override
        public void unlink(ItemStack itemStack) {
            setLinkedAccessPoint(itemStack, null);
        }
    }
}
