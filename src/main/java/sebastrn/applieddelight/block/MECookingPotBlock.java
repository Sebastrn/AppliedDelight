package sebastrn.applieddelight.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import org.jetbrains.annotations.Nullable;
import sebastrn.applieddelight.ADAttachments;
import sebastrn.applieddelight.ADBlockEntities;
import sebastrn.applieddelight.blockentity.MECookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.state.CookingPotSupport;
import vectorwing.farmersdelight.common.registry.ModSounds;
import vectorwing.farmersdelight.common.tag.ModTags;

/**
 * A Farmer's-Delight-style cooking pot that draws its ingredients from a linked ME network. It is a plain
 * {@link BaseEntityBlock} — it does not extend Farmer's Delight, it only mirrors its shape and heat behaviour.
 */
public class MECookingPotBlock extends BaseEntityBlock {

    public static final MapCodec<MECookingPotBlock> CODEC = simpleCodec(MECookingPotBlock::new);

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** True while linked to an active, in-range, powered network. Drives the block model. */
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");
    /**
     * True while a meal sits in the pot. Not used by the model (the blockstate JSON keys only on facing) — it exists
     * purely to carry the "has a meal" fact to nearby clients, since the block entity's inventory is not synced. It is
     * the client-visible mirror of {@code !getMeal().isEmpty()}, and lets {@link #animateTick} pick Farmer's Delight's
     * soup-boil sound over the water-boil one exactly as FD does.
     */
    public static final BooleanProperty HAS_MEAL = BooleanProperty.create("has_meal");
    /**
     * Farmer's Delight's cooking-pot support, reused wholesale for parity: {@code NONE} plain, {@code TRAY} a grate and
     * legs added automatically when the pot stands on a {@code TRAY_HEAT_SOURCES} block, {@code HANDLE} a hanging bail
     * you get by placing the pot against a block's underside — or by sneak + right-clicking it with an empty hand.
     */
    public static final EnumProperty<CookingPotSupport> SUPPORT =
            EnumProperty.create("support", CookingPotSupport.class);

    /** Matches Farmer's Delight's cooking pot exactly, and the pot body in our own model. */
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 10, 14);
    /** The tray's grate and legs hang below the block, so only the collision shape grows (as in Farmer's Delight). */
    private static final VoxelShape SHAPE_WITH_TRAY = Shapes.or(SHAPE, Block.box(0, -1, 0, 16, 0, 16));

    public MECookingPotBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CONNECTED, false)
                .setValue(HAS_MEAL, false)
                .setValue(SUPPORT, CookingPotSupport.NONE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CONNECTED, HAS_MEAL, SUPPORT);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        // Placed against a block's underside, the pot hangs by its handle; otherwise it picks up a tray if it has
        // landed on a heat source that wants one. Mirrors Farmer's Delight.
        if (context.getClickedFace() == Direction.DOWN) {
            return state.setValue(SUPPORT, CookingPotSupport.HANDLE);
        }
        return state.setValue(SUPPORT, trayStateFor(context.getLevel(), context.getClickedPos()));
    }

    /** A pot standing on a {@code TRAY_HEAT_SOURCES} block gains the grate; anything else leaves it plain. */
    private static CookingPotSupport trayStateFor(LevelAccessor level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(ModTags.Blocks.TRAY_HEAT_SOURCES)
                ? CookingPotSupport.TRAY
                : CookingPotSupport.NONE;
    }

    /** Re-evaluate the tray whenever the block below changes — but never override a handle the player chose. */
    @Override
    protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level,
                                     BlockPos currentPos, BlockPos facingPos) {
        if (facing.getAxis() == Direction.Axis.Y && state.getValue(SUPPORT) != CookingPotSupport.HANDLE) {
            return state.setValue(SUPPORT, trayStateFor(level, currentPos));
        }
        return state;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(SUPPORT) == CookingPotSupport.TRAY ? SHAPE_WITH_TRAY : SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MECookingPotBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, ADBlockEntities.ME_COOKING_POT.get(), MECookingPotBlockEntity::animationTick);
        }
        return createTickerHelper(type, ADBlockEntities.ME_COOKING_POT.get(), MECookingPotBlockEntity::serverTick);
    }

    /**
     * Client-side ambience while heated, mirroring the Farmer's Delight cooking pot: an occasional boil sound. The
     * bubble/steam particles are emitted by the block entity's {@link MECookingPotBlockEntity#animationTick}.
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof MECookingPotBlockEntity be && be.isHeated(level, pos)
                && random.nextInt(10) == 0) {
            // FD picks the sound purely on "is there a meal in the pot"; HAS_MEAL is that fact, synced to the client
            // via the blockstate (the inventory itself is not synced).
            SoundEvent boil = state.getValue(HAS_MEAL)
                    ? ModSounds.BLOCK_COOKING_POT_BOIL_SOUP.get()
                    : ModSounds.BLOCK_COOKING_POT_BOIL.get();
            level.playLocalSound(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, boil,
                    SoundSource.BLOCKS, 0.5F, random.nextFloat() * 0.2F + 0.9F, false);
        }
    }

    /**
     * Right-clicking with the meal's correct serving container takes one serving straight to hand, exactly like the
     * Farmer's Delight cooking pot. Anything else falls through to opening the GUI (see {@link #useWithoutItem}).
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack heldStack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        // Sneak + empty hand toggles the hanging handle on and off, exactly as Farmer's Delight does. Checked first,
        // because with an empty hand the serve below would do nothing anyway and the GUI would open instead.
        if (heldStack.isEmpty() && player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                level.setBlockAndUpdate(pos, state.setValue(SUPPORT,
                        state.getValue(SUPPORT) == CookingPotSupport.HANDLE
                                ? trayStateFor(level, pos)
                                : CookingPotSupport.HANDLE));
                level.playSound(null, pos, SoundEvents.LANTERN_PLACE, SoundSource.BLOCKS, 0.7F, 1.0F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MECookingPotBlockEntity be) {
            ItemStack serving = be.useHeldItemOnMeal(heldStack);
            if (!serving.isEmpty()) {
                if (!player.getInventory().add(serving)) {
                    player.drop(serving, false);
                }
                level.playSound(null, pos, ModSounds.BLOCK_FOOD_TAKE_PORTION.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
                return ItemInteractionResult.SUCCESS;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MECookingPotBlockEntity be) {
            // The player's recipe-panel preference rides across with the position so the screen opens at the right size.
            ADAttachments.PotViewSettings view = player.getData(ADAttachments.POT_VIEW);
            player.openMenu(be, buf -> {
                buf.writeBlockPos(pos);
                buf.writeBoolean(view.panelOpen());
                buf.writeBoolean(view.filterCraftableOnly());
                buf.writeInt(view.sortMode());
            });
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MECookingPotBlockEntity be) {
            be.applyDataFromItem(stack);
        }
    }

    /**
     * The pot itself drops via the loot table (which copies its {@code custom_data} so link + battery survive ANY
     * break). Here we only spill the stored slot contents when the block is actually removed.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof MECookingPotBlockEntity be) {
            be.dropContents(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof MECookingPotBlockEntity be) {
            be.saveToItem(stack);
        }
        return stack;
    }
}
