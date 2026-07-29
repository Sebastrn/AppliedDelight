package sebastrn.applieddelight.integration;

import appeng.core.localization.Tooltips;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import sebastrn.applieddelight.AppliedDelight;
import sebastrn.applieddelight.block.MECookingPotBlock;
import sebastrn.applieddelight.blockentity.MECookingPotBlockEntity;
import sebastrn.applieddelight.item.MECookingPotItem;

/**
 * Shows the placed pot's battery and link state in Jade's HUD, matching what the item tooltip shows in the inventory —
 * the same pair of lines AE2's Wireless Terminal uses, via AE2's own {@link Tooltips}/{@code GuiText}.
 *
 * <p>Jade discovers this class by scanning for {@link WailaPlugin}; nothing in the mod references it. Jade is therefore
 * a soft dependency (compileOnly), and none of this loads when Jade is absent.
 *
 * <p>The values travel through {@link IServerDataProvider} because the pot's energy and link are server-side state —
 * the block entity does not sync them to the client on its own, so reading the client copy here would show nothing.
 */
@WailaPlugin
public class JadeIntegration implements IWailaPlugin {

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(AppliedDelight.ID, "me_cooking_pot");

    private static final String TAG_LINK_STATE = "LinkState";
    private static final String TAG_ENERGY = "Energy";
    private static final String TAG_MAX_ENERGY = "MaxEnergy";

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(PotProvider.INSTANCE, MECookingPotBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(PotProvider.INSTANCE, MECookingPotBlock.class);
    }

    private enum PotProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            if (accessor.getBlockEntity() instanceof MECookingPotBlockEntity pot) {
                tag.putInt(TAG_LINK_STATE, pot.getLinkState());
                tag.putDouble(TAG_ENERGY, pot.getEnergy());
                tag.putDouble(TAG_MAX_ENERGY, pot.getMaxEnergy());
            }
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag tag = accessor.getServerData();
            // Absent until the server's first reply, so the HUD stays empty rather than briefly showing 0/0.
            if (!tag.contains(TAG_MAX_ENERGY)) {
                return;
            }
            tooltip.add(Tooltips.energyStorageComponent(tag.getDouble(TAG_ENERGY), tag.getDouble(TAG_MAX_ENERGY)));
            tooltip.add(MECookingPotItem.linkComponent(tag.getInt(TAG_LINK_STATE)));
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }
    }
}
