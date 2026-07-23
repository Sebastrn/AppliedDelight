package sebastrn.applieddelight;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import sebastrn.applieddelight.menu.MECookingPotMenu;

public final class ADMenus {

    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, AppliedDelight.ID);

    /**
     * The ME Cooking Pot menu (POC-A: custom recipe list). Uses {@link IMenuTypeExtension#create} so the block position
     * rides across on open and the client can resolve the same block entity.
     */
    public static final DeferredHolder<MenuType<?>, MenuType<MECookingPotMenu>> ME_COOKING_POT =
            MENUS.register("me_cooking_pot",
                    () -> IMenuTypeExtension.create((IContainerFactory<MECookingPotMenu>) MECookingPotMenu::new));

    private ADMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
