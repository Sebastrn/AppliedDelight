package sebastrn.applieddelight;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import sebastrn.applieddelight.blockentity.MECookingPotBlockEntity;

public final class ADBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> REGISTRY =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AppliedDelight.ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MECookingPotBlockEntity>> ME_COOKING_POT =
            REGISTRY.register("me_cooking_pot", () -> BlockEntityType.Builder
                    .of(MECookingPotBlockEntity::new, ADBlocks.ME_COOKING_POT.get()).build(null));

    private ADBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        REGISTRY.register(modEventBus);
    }
}
