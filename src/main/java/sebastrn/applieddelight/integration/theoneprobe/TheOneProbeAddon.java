package sebastrn.applieddelight.integration.theoneprobe;

import appeng.core.localization.Tooltips;
import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import sebastrn.applieddelight.AppliedDelight;
import sebastrn.applieddelight.block.MECookingPotBlock;
import sebastrn.applieddelight.blockentity.MECookingPotBlockEntity;
import sebastrn.applieddelight.item.MECookingPotItem;

import java.util.function.Function;

/**
 * The One Probe integration: shows the same battery + link lines the Jade plugin does, so both HUD mods report the pot
 * identically.
 *
 * <p>Registered over {@link net.neoforged.fml.InterModComms} only when TOP is actually installed, so nothing in this
 * class is ever loaded otherwise and TOP stays an optional dependency.
 */
public class TheOneProbeAddon {

    /** TOP's mod id — used for the {@code ModList} guard before this class is touched at all. */
    public static final String MOD_ID = "theoneprobe";

    private TheOneProbeAddon() {
    }

    public static void register() {
        net.neoforged.fml.InterModComms.sendTo(MOD_ID, "getTheOneProbe", TopInitializer::new);
    }

    public static class TopInitializer implements Function<ITheOneProbe, Void> {
        @Override
        public Void apply(ITheOneProbe top) {
            if (top != null) {
                top.registerProvider(new PotProvider());
            }
            return null;
        }
    }

    private static class PotProvider implements IProbeInfoProvider {
        private static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(AppliedDelight.ID, "me_cooking_pot");

        @Override
        public ResourceLocation getID() {
            return ID;
        }

        @Override
        public void addProbeInfo(ProbeMode mode, IProbeInfo info, Player player, Level level, BlockState state,
                                 IProbeHitData data) {
            if (!(state.getBlock() instanceof MECookingPotBlock)) return;
            if (!(level.getBlockEntity(data.getPos()) instanceof MECookingPotBlockEntity pot)) return;

            // Same AE2 helpers + shared link line the item tooltip and Jade use, so wording matches everywhere.
            info.mcText(Tooltips.energyStorageComponent(pot.getEnergy(), pot.getMaxEnergy()));
            info.mcText(MECookingPotItem.linkComponent(pot.getLinkState()));
        }
    }
}
