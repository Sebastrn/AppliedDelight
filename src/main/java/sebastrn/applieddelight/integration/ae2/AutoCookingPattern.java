package sebastrn.applieddelight.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import org.jetbrains.annotations.Nullable;
import sebastrn.applieddelight.ADItems;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AE2 processing pattern backed by a Farmer's Delight cooking-pot recipe. */
public final class AutoCookingPattern implements IPatternDetails {

    private static final String TAG_RECIPE = "AutoCookingRecipe";

    private static final IPatternDetailsDecoder DECODER = new IPatternDetailsDecoder() {
        @Override
        public boolean isEncodedPattern(ItemStack stack) {
            return stack.is(ADItems.ME_COOKING_POT.get()) && readRecipeId(stack.get(DataComponents.CUSTOM_DATA)) != null;
        }

        @Nullable
        @Override
        public IPatternDetails decodePattern(AEItemKey what, Level level) {
            if (what == null || level == null || !what.is(ADItems.ME_COOKING_POT.get())) {
                return null;
            }
            ResourceLocation recipeId = readRecipeId(what.get(DataComponents.CUSTOM_DATA));
            if (recipeId == null) {
                return null;
            }
            return fromRecipeId(recipeId, level);
        }
    };

    private final ResourceLocation recipeId;
    private final AEItemKey definition;
    private final IngredientInput[] ingredientInputs;
    @Nullable
    private final ContainerInput containerInput;
    private final IInput[] inputs;
    private final List<GenericStack> outputs;
    private final ItemStack output;

    private AutoCookingPattern(ResourceLocation recipeId, CookingPotRecipe recipe, Level level) {
        this.recipeId = recipeId;

        ItemStack definitionStack = new ItemStack(ADItems.ME_COOKING_POT.get());
        CompoundTag definitionTag = new CompoundTag();
        definitionTag.putString(TAG_RECIPE, recipeId.toString());
        definitionStack.set(DataComponents.CUSTOM_DATA, CustomData.of(definitionTag));
        this.definition = AEItemKey.of(definitionStack);

        List<Ingredient> ingredients = recipe.getIngredients();
        this.ingredientInputs = new IngredientInput[ingredients.size()];
        List<IInput> allInputs = new ArrayList<>(ingredients.size() + 1);
        for (int i = 0; i < ingredients.size(); i++) {
            IngredientInput input = new IngredientInput(ingredients.get(i));
            if (input.getPossibleInputs().length == 0) {
                throw new IllegalArgumentException("Cooking recipe has an ingredient without AE2-compatible inputs: "
                        + recipeId);
            }
            ingredientInputs[i] = input;
            allInputs.add(input);
        }

        this.output = recipe.getResultItem(level.registryAccess()).copy();
        if (output.isEmpty()) {
            throw new IllegalArgumentException("Cooking recipe has no output: " + recipeId);
        }
        this.outputs = List.of(new GenericStack(AEItemKey.of(output), output.getCount()));

        ItemStack container = recipe.getOutputContainer();
        if (container.isEmpty()) {
            this.containerInput = null;
        } else {
            this.containerInput = new ContainerInput(container, output.getCount());
            allInputs.add(containerInput);
        }
        this.inputs = allInputs.toArray(IInput[]::new);
    }

    public static void registerDecoder() {
        PatternDetailsHelper.registerDecoder(DECODER);
    }

    @Nullable
    public static AutoCookingPattern fromRecipe(RecipeHolder<CookingPotRecipe> holder, Level level) {
        try {
            return new AutoCookingPattern(holder.id(), holder.value(), level);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private static AutoCookingPattern fromRecipeId(ResourceLocation recipeId, Level level) {
        var holder = level.getRecipeManager().byKey(recipeId);
        if (holder.isEmpty() || !(holder.get().value() instanceof CookingPotRecipe)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        RecipeHolder<CookingPotRecipe> cookingHolder = (RecipeHolder<CookingPotRecipe>) holder.get();
        return fromRecipe(cookingHolder, level);
    }

    @Nullable
    private static ResourceLocation readRecipeId(@Nullable CustomData customData) {
        if (customData == null) {
            return null;
        }
        CompoundTag tag = customData.copyTag();
        return tag.contains(TAG_RECIPE) ? ResourceLocation.tryParse(tag.getString(TAG_RECIPE)) : null;
    }

    public ResourceLocation recipeId() {
        return recipeId;
    }

    public ItemStack output() {
        return output.copy();
    }

    /** Validates AE2 inputs without mutating them and maps them to the pot's slots. */
    @Nullable
    public AcceptedInputs acceptInputs(KeyCounter[] inputHolders) {
        if (inputHolders == null || inputHolders.length != inputs.length) {
            return null;
        }

        ItemStack[] ingredients = new ItemStack[ingredientInputs.length];
        boolean[] fluidSourced = new boolean[ingredientInputs.length];
        for (int i = 0; i < ingredientInputs.length; i++) {
            AcceptedIngredient accepted = ingredientInputs[i].accept(inputHolders[i]);
            if (accepted == null) {
                return null;
            }
            ingredients[i] = accepted.stack();
            fluidSourced[i] = accepted.fluidSourced();
        }

        ItemStack container = ItemStack.EMPTY;
        if (containerInput != null) {
            container = containerInput.accept(inputHolders[inputHolders.length - 1]);
            if (container.isEmpty()) {
                return null;
            }
        }
        return new AcceptedInputs(ingredients, fluidSourced, container);
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
    }

    @Override
    public IInput[] getInputs() {
        return inputs;
    }

    @Override
    public List<GenericStack> getOutputs() {
        return outputs;
    }

    @Override
    public boolean supportsPushInputsToExternalInventory() {
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AutoCookingPattern pattern && definition.equals(pattern.definition);
    }

    @Override
    public int hashCode() {
        return definition.hashCode();
    }

    public record AcceptedInputs(ItemStack[] ingredients, boolean[] fluidSourced, ItemStack container) {
    }

    private record AcceptedIngredient(ItemStack stack, boolean fluidSourced) {
    }

    private static final class IngredientInput implements IInput {
        private final Ingredient ingredient;
        private final GenericStack[] possibleInputs;
        private final Map<AEFluidKey, ItemStack> fluidContainers;

        private IngredientInput(Ingredient ingredient) {
            this.ingredient = ingredient;
            Map<AEKey, GenericStack> possible = new LinkedHashMap<>();
            this.fluidContainers = new LinkedHashMap<>();

            for (ItemStack candidate : ingredient.getItems()) {
                AEItemKey itemKey = AEItemKey.of(candidate);
                if (itemKey != null) {
                    possible.putIfAbsent(itemKey, new GenericStack(itemKey, 1));
                }

                FluidStack contained = FluidUtil.getFluidContained(candidate).orElse(FluidStack.EMPTY);
                if (contained.getAmount() >= AEFluidKey.AMOUNT_BUCKET) {
                    AEFluidKey fluidKey = AEFluidKey.of(contained);
                    if (fluidKey != null) {
                        possible.putIfAbsent(fluidKey, new GenericStack(fluidKey, AEFluidKey.AMOUNT_BUCKET));
                        fluidContainers.putIfAbsent(fluidKey, candidate.copyWithCount(1));
                    }
                }
            }
            this.possibleInputs = possible.values().toArray(GenericStack[]::new);
        }

        @Nullable
        private AcceptedIngredient accept(KeyCounter holder) {
            if (holder == null || holder.size() != 1) {
                return null;
            }
            var entry = holder.getFirstEntry();
            if (entry == null || !isValid(entry.getKey(), null)) {
                return null;
            }
            if (entry.getKey() instanceof AEItemKey itemKey && entry.getLongValue() == 1) {
                return new AcceptedIngredient(itemKey.toStack(1), false);
            }
            if (entry.getKey() instanceof AEFluidKey fluidKey
                    && entry.getLongValue() == AEFluidKey.AMOUNT_BUCKET) {
                ItemStack container = fluidContainers.get(fluidKey);
                return container == null ? null : new AcceptedIngredient(container.copy(), true);
            }
            return null;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            if (input instanceof AEItemKey itemKey) {
                return itemKey.matches(ingredient);
            }
            return input instanceof AEFluidKey fluidKey && fluidContainers.containsKey(fluidKey);
        }

        @Nullable
        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private static final class ContainerInput implements IInput {
        private final AEItemKey key;
        private final GenericStack[] possibleInputs;
        private final int count;

        private ContainerInput(ItemStack container, int count) {
            this.key = AEItemKey.of(container);
            this.possibleInputs = new GenericStack[]{new GenericStack(key, 1)};
            this.count = count;
        }

        private ItemStack accept(KeyCounter holder) {
            if (holder == null || holder.size() != 1) {
                return ItemStack.EMPTY;
            }
            var entry = holder.getFirstEntry();
            if (entry == null || !key.equals(entry.getKey()) || entry.getLongValue() != count) {
                return ItemStack.EMPTY;
            }
            return key.toStack(count);
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return count;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return key.equals(input);
        }

        @Nullable
        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
