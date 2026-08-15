# Changelog

All notable changes to **Applied Delight** are listed here.

## 2.0.0

The ME Cooking Pot now exposes every compatible Farmer's Delight cooking-pot recipe to AE2 as an autocrafting
pattern. Each pot accepts one job at a time, while multiple pots can process jobs in parallel.

Serving containers are requested as pattern inputs, item and fluid ingredient substitutions are supported, and
finished meals are inserted directly into the crafting network. Active jobs and partial outputs survive chunk and
server reloads, and the pot retries delivery when the network is unavailable or full.

## 1.1.0

You can now power a placed ME Cooking Pot from energy cables. Run any Forge Energy cable (Mekanism, Flux, and the like) into one of its sides and it charges the battery and keeps it topped, so a stationary pot never runs down; pick it up and it runs on the stored charge as before. The item still charges in an AE2 Charger or any FE charger too.

Added translations for 10 languages: German, Spanish, French, Italian, Korean, Brazilian Portuguese, Russian, Swedish, Simplified Chinese and Traditional Chinese.

## 1.0.2

Gave the guide book a custom look: an illustrated book icon and a themed open-book GUI (fluix cover, leather-bound), replacing the default Patchouli textures.

## 1.0.1

Replaced logo image with a properly sourced asset

## 1.0.0

First release. Adds the **ME Cooking Pot**, a Farmer's Delight cooking pot that draws its ingredients from your
Applied Energistics 2 network.

### Added
- **ME Cooking Pot.** Needs a heat source below it and cooks Farmer's Delight recipes exactly as the vanilla
  Farmer's Delight pot does. Crafted from a Farmer's Delight cooking pot.
- **Recipe panel.** Lists every Farmer's Delight cooking-pot recipe, including ones added by other mods, with a live
  count of how many you could make right now from your inventory and the network together. Search box, A-Z sort and a
  "craftable only" filter; the panel state is remembered per player.
- **Batch cooking.** Click a recipe to load one serving, shift-click to load as many as the ingredients, the meal slot
  and the battery allow. Once loaded the pot owns the batch and needs nothing but heat.
- **Network sourcing.** Ingredients come from your own inventory first, then the network's items, then its fluids, 
  milk and water are pulled from fluid storage when no bucket or bottle is stocked.
- **Serving.** Meals stay in the pot until you supply their container, by hand or with the request button. A second
  button returns everything to the network.
- **Battery and link.** Only network pulls cost power; anything from your own inventory is free. A small idle drain
  runs while the link is live. Both survive breaking the pot.
- **Integrations.** Jade, The One Probe, JEI, EMI, REI and a Patchouli guide book, all optional.
