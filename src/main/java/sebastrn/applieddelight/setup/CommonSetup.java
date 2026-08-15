package sebastrn.applieddelight.setup;

import appeng.api.features.GridLinkables;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import sebastrn.applieddelight.ADItems;
import sebastrn.applieddelight.integration.ae2.AutoCookingPattern;
import sebastrn.applieddelight.item.MECookingPotItem;

public final class CommonSetup {

    private CommonSetup() {
    }

    public static void onCommonSetup(FMLCommonSetupEvent event) {
        // Register the ME Cooking Pot item as linkable at a Wireless Access Point's GUI, exactly like AE2's own
        // wireless terminals. Linking stores the access point's GlobalPos on the stack; placing the pot copies it
        // to the block entity.
        event.enqueueWork(() -> {
            GridLinkables.register(ADItems.ME_COOKING_POT.get(), MECookingPotItem.LINKABLE_HANDLER);
            AutoCookingPattern.registerDecoder();
        });
    }
}
