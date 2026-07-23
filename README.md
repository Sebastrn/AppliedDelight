# Applied Delight

A Minecraft mod that bridges Farmer's Delight with Applied Energistics 2.

Applied Delight adds one block, the **ME Cooking Pot** — a Farmer's Delight cooking pot that draws its ingredients
straight from an Applied Energistics 2 network. Place it over a heat source, link it to a Wireless Access Point, and it
cooks any Farmer's Delight recipe using items stored in your ME system.

## What it does

- **Cooks real Farmer's Delight recipes** — every cooking-pot recipe, including ones added by other Farmer's Delight
  add-ons. It needs a heat source below it, exactly like the vanilla pot.
- **Draws from your network** — a recipe panel lists every cooking-pot recipe with a live count of how many you can
  make right now from your inventory and your ME network combined, with search, A–Z sort and a craftable-only filter.
- **Batch cooking** — click a recipe to load one serving, shift-click to load as many as the ingredients, the pot and
  its battery allow. Once loaded, the pot owns everything it needs and just cooks.
- **Ingredients come from you first**, then the network; only network pulls cost power. Milk and water can be drawn
  from the network's fluid storage.
- **Serving on your terms** — meals wait in the pot until you supply their container, by hand or with a button that
  requests it from the network.

## Requirements

- Minecraft 1.21.1 (NeoForge)
- [Farmer's Delight](https://modrinth.com/mod/farmers-delight)
- [Applied Energistics 2](https://modrinth.com/mod/ae2)

## Optional integrations

Jade, The One Probe, JEI, EMI, REI, Patchouli, and AEInfinityBooster — all detected softly; the mod runs fine without
any of them.

## License

MIT — see [LICENSE](LICENSE). Third-party attributions are in [NOTICE.md](NOTICE.md).
