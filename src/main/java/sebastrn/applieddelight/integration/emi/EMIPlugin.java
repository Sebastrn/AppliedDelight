package sebastrn.applieddelight.integration.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiInfoRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import sebastrn.applieddelight.ADItems;
import sebastrn.applieddelight.AppliedDelight;
import vectorwing.farmersdelight.integration.emi.FDRecipeCategories;

import java.util.List;

/**
 * EMI integration: registers the ME Cooking Pot as a workstation on Farmer's Delight's cooking category, so FD cooking
 * recipes show it as somewhere they can be made, plus the same info page the JEI plugin attaches. Unlike REI, EMI does
 * not read JEI plugins, so this class is the only route for EMI users. Discovered by EMI's {@code @EmiEntrypoint}
 * scan, so EMI stays optional.
 */
@EmiEntrypoint
public class EMIPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        EmiStack pot = EmiStack.of(ADItems.ME_COOKING_POT.get());
        registry.addWorkstation(FDRecipeCategories.COOKING, pot);

        // Synthetic recipe id: an info page has no data-driven JSON recipe, so EMI wants the path prefixed with '/'
        // (otherwise its dev mode warns the id isn't in the recipe manager and isn't marked synthetic).
        registry.addRecipe(new EmiInfoRecipe(
                List.<EmiIngredient>of(pot),
                List.of(Component.translatable("jei.applieddelight.me_cooking_pot.1"),
                        Component.translatable("jei.applieddelight.me_cooking_pot.2"),
                        Component.translatable("jei.applieddelight.me_cooking_pot.3")),
                ResourceLocation.fromNamespaceAndPath(AppliedDelight.ID, "/info/me_cooking_pot")));
    }
}
