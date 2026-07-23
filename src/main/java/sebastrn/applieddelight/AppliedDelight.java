package sebastrn.applieddelight;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import sebastrn.applieddelight.integration.theoneprobe.TheOneProbeAddon;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import sebastrn.applieddelight.config.ServerConfig;
import sebastrn.applieddelight.item.MECookingPotItem;
import sebastrn.applieddelight.setup.CommonSetup;

import appeng.items.tools.powered.powersink.PoweredItemCapabilities;

/**
 * Applied Delight — cook Farmer's Delight meals straight from an Applied Energistics 2 network.
 *
 * <p>The mod adds a single block, the <em>ME Cooking Pot</em>: a Farmer's-Delight-style cooking pot that still needs a
 * heat source below it to cook, but draws its ingredients from an ME network it links to wirelessly (a Wireless Access
 * Point), paying for that network access out of its own battery. It deliberately does not extend or modify Farmer's
 * Delight — it merely reads FD's cooking recipes and reacts to FD's heat-source tags.
 */
@Mod(AppliedDelight.ID)
public final class AppliedDelight {
    public static final String ID = "applieddelight";
    public static final ServerConfig SERVER_CONFIG = new ServerConfig();

    public AppliedDelight(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, SERVER_CONFIG.getSpec());

        ADBlocks.register(modEventBus);
        ADItems.register(modEventBus);
        ADBlockEntities.register(modEventBus);
        ADMenus.register(modEventBus);
        ADAttachments.register(modEventBus);
        ADCreativeTab.register(modEventBus);

        modEventBus.addListener(CommonSetup::onCommonSetup);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::enqueueIMC);
    }

    /**
     * Hand our probe provider to The One Probe, if it is installed. The {@link ModList} check comes first so the addon
     * class — and with it every TOP type it references — is never loaded when TOP is absent, keeping TOP optional.
     */
    private void enqueueIMC(InterModEnqueueEvent event) {
        if (ModList.get().isLoaded(TheOneProbeAddon.MOD_ID)) {
            TheOneProbeAddon.register();
        }
    }

    /**
     * Expose the ME Cooking Pot item's battery as a Forge energy store so it charges in an AE2 Charger (or any FE
     * charger), reusing AE2's own {@link PoweredItemCapabilities} bridge. The item itself implements
     * {@link appeng.api.implementations.items.IAEItemPowerStorage}.
     */
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        MECookingPotItem potItem = ADItems.ME_COOKING_POT.get();
        event.registerItem(
                Capabilities.EnergyStorage.ITEM,
                (stack, ctx) -> new PoweredItemCapabilities(stack, potItem),
                potItem);
    }
}
