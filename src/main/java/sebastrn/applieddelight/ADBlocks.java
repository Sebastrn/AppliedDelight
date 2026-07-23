package sebastrn.applieddelight;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import sebastrn.applieddelight.block.MECookingPotBlock;

public final class ADBlocks {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, AppliedDelight.ID);

    public static final DeferredHolder<Block, MECookingPotBlock> ME_COOKING_POT =
            BLOCKS.register("me_cooking_pot", () -> new MECookingPotBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).sound(SoundType.LANTERN).strength(0.5f).noOcclusion()));

    private ADBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
