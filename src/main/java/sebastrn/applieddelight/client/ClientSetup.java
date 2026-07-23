package sebastrn.applieddelight.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import sebastrn.applieddelight.ADMenus;
import sebastrn.applieddelight.AppliedDelight;

/**
 * Client-side registrations.
 *
 * <p>There is deliberately <em>no</em> block/item colour handler here any more. The pot used to borrow Farmer's
 * Delight's textures and multiply a blue tint over them at {@code tintindex 0}; it now ships its own art
 * ({@code me_cooking_pot_*}), so a tint would only fight the texture — in particular it would wash the purple ME
 * accents on {@code me_cooking_pot_parts} back to blue. The models carry no {@code tintindex} for the same reason.
 */
@EventBusSubscriber(modid = AppliedDelight.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ADMenus.ME_COOKING_POT.get(), MECookingPotScreen::new);
    }
}
