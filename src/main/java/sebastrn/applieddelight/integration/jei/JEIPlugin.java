package sebastrn.applieddelight.integration.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import sebastrn.applieddelight.ADItems;
import sebastrn.applieddelight.AppliedDelight;
import vectorwing.farmersdelight.integration.jei.FDRecipeTypes;

/**
 * JEI integration. The important half is the <em>catalyst</em>: the ME Cooking Pot is registered against Farmer's
 * Delight's own cooking category, so every FD cooking recipe lists this pot as somewhere it can be made — without it,
 * players looking up a meal would never learn the pot can cook it.
 *
 * <p>Loaded only when JEI is present (its {@code @JeiPlugin} scan never touches this class otherwise), so JEI stays an
 * optional dependency.
 */
@JeiPlugin
public class JEIPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(AppliedDelight.ID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        // Reuses FD's own RecipeType constant rather than rebuilding it, so the pot lands in exactly the category FD
        // registers its cooking recipes under.
        registration.addRecipeCatalyst(new ItemStack(ADItems.ME_COOKING_POT.get()), FDRecipeTypes.COOKING);
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(
                new ItemStack(ADItems.ME_COOKING_POT.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("jei.applieddelight.me_cooking_pot.1"),
                Component.translatable("jei.applieddelight.me_cooking_pot.2"),
                Component.translatable("jei.applieddelight.me_cooking_pot.3"));
    }
}
