package sebastrn.applieddelight.integration.rei;

import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import me.shedaniel.rei.forge.REIPluginClient;
import me.shedaniel.rei.plugin.client.BuiltinClientPlugin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import sebastrn.applieddelight.ADItems;

/**
 * REI integration: the same info page the JEI plugin attaches, reusing the viewer-agnostic {@code jei.applieddelight.*}
 * keys.
 *
 * <p>Deliberately no workstation registration here. Farmer's Delight ships JEI and EMI plugins but <em>no</em> REI
 * plugin, so there is no REI category for FD cooking to attach the pot to. REI users see FD's recipes through REI's
 * JEI-compatibility layer, which translates FD's JEI plugin, and ours along with it, so the catalyst arrives that way.
 *
 * <p>Discovered only when REI is present (its {@code @REIPluginClient} scan never touches this class otherwise).
 */
@REIPluginClient
public class REIPlugin implements REIClientPlugin {

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        info(ADItems.ME_COOKING_POT.get(),
                "jei.applieddelight.me_cooking_pot.1",
                "jei.applieddelight.me_cooking_pot.2",
                "jei.applieddelight.me_cooking_pot.3");
    }

    private static void info(ItemLike item, String... lineKeys) {
        BuiltinClientPlugin.getInstance().registerInformation(
                EntryIngredients.of(item),
                new ItemStack(item).getHoverName(),
                lines -> {
                    for (String key : lineKeys) {
                        lines.add(Component.translatable(key));
                    }
                    return lines;
                });
    }
}
