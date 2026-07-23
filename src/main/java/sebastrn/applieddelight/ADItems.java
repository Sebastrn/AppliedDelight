package sebastrn.applieddelight;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import sebastrn.applieddelight.item.MECookingPotItem;

public final class ADItems {

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, AppliedDelight.ID);

    public static final DeferredHolder<Item, MECookingPotItem> ME_COOKING_POT =
            ITEMS.register("me_cooking_pot", () -> new MECookingPotItem(
                    ADBlocks.ME_COOKING_POT.get(), new Item.Properties().stacksTo(1)));

    private ADItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
