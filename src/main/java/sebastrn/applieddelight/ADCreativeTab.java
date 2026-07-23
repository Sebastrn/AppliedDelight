package sebastrn.applieddelight;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ADCreativeTab {

    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AppliedDelight.ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_MODE_TAB =
            CREATIVE_TABS.register(AppliedDelight.ID,
                    () -> CreativeModeTab.builder()
                            .icon(() -> new ItemStack(ADItems.ME_COOKING_POT.get()))
                            .title(Component.translatable("itemGroup." + AppliedDelight.ID))
                            .displayItems((features, output) -> output.accept(ADItems.ME_COOKING_POT.get()))
                            .build());

    private ADCreativeTab() {
    }

    public static void register(IEventBus modEventBus) {
        CREATIVE_TABS.register(modEventBus);
    }
}
