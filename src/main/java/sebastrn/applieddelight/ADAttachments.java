package sebastrn.applieddelight;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Data attached to the player.
 *
 * <p>Whether the recipe panel is open, and whether it is filtered to craftable recipes, are a <em>view preference of the
 * player</em> rather than state of any one pot, so they are stored on the player and saved with them, which is the same
 * scope vanilla gives its recipe book. Vanilla keeps the equivalent pair of booleans (open / filtering) per
 * {@code RecipeBookType} in {@code RecipeBookSettings}, written into the player's NBT; every furnace in a world
 * therefore shares one setting. This mirrors that: every ME Cooking Pot a player opens shows their preference.
 */
public final class ADAttachments {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, AppliedDelight.ID);

    /** The player's recipe-panel view settings, applied to every ME Cooking Pot they open. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PotViewSettings>> POT_VIEW =
            ATTACHMENTS.register("pot_view",
                    () -> AttachmentType.builder(() -> PotViewSettings.DEFAULT)
                            .serialize(PotViewSettings.CODEC)
                            // A UI preference should outlive dying, which otherwise drops attachments.
                            .copyOnDeath()
                            .build());

    private ADAttachments() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    /**
     * Recipe-panel view settings: the panel's open/closed state, its "craftable only" filter and the name-sort mode, 
     * an extension of the two booleans vanilla's {@code RecipeBookSettings} keeps per recipe-book type.
     *
     * <p>The search query is deliberately <em>not</em> here: it is a momentary lookup, not a preference, so it lives on
     * the screen and clears when the GUI closes.
     */
    public record PotViewSettings(boolean panelOpen, boolean filterCraftableOnly, int sortMode) {

        /** Name sort applied within the craftable and non-craftable blocks. Cycles off → A-Z → Z-A → off. */
        public static final int SORT_OFF = 0;
        public static final int SORT_ASC = 1;
        public static final int SORT_DESC = 2;

        /** Panel open, unfiltered, unsorted, what a player who has never touched the toggles sees. */
        public static final PotViewSettings DEFAULT = new PotViewSettings(true, false, SORT_OFF);

        public static final Codec<PotViewSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("panel_open", true).forGetter(PotViewSettings::panelOpen),
                Codec.BOOL.optionalFieldOf("filter_craftable", false).forGetter(PotViewSettings::filterCraftableOnly),
                Codec.INT.optionalFieldOf("sort_mode", SORT_OFF).forGetter(PotViewSettings::sortMode)
        ).apply(instance, PotViewSettings::new));
    }
}
