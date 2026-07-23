package sebastrn.applieddelight.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import sebastrn.applieddelight.ADAttachments;
import sebastrn.applieddelight.menu.MECookingPotMenu;
import org.lwjgl.glfw.GLFW;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * POC-A screen: FD-style pot slots with a compact status header (link LED + battery bar) and a collapsible recipe panel
 * (recipe-book-style toggle button, à la FD) holding a scrollable recipe grid + a "craftable only" checkbox.
 */
public class MECookingPotScreen extends AbstractContainerScreen<MECookingPotMenu> {

    private static final ResourceLocation FD_GUI =
            ResourceLocation.fromNamespaceAndPath("farmersdelight", "textures/gui/cooking_pot.png");
    private static final WidgetSprites TOGGLE_SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("recipe_book/button"),
            ResourceLocation.withDefaultNamespace("recipe_book/button"));

    private static final int POT_GUI_WIDTH = 176;
    private static final int POT_GUI_HEIGHT = 166;
    private static final int PANEL_DARK = 0xFF373737;
    /** Recipe-list backdrop — deliberately lighter than PANEL_DARK so the list reads as its own area. */
    private static final int PANEL_LIGHT = 0xFF8B8B8B;
    private static final int SELECTED = 0xFF55FF55;
    private static final int BORDER = 0xFF232323;
    /** Label colour on the pot's light GUI background (same grey vanilla uses for container titles). */
    private static final int LABEL_TEXT = 0x404040;

    private static final int GRID_X = 180;
    /** Grid start, pushed down to make room for the search row between the title and the list. */
    private static final int GRID_Y = 35;
    /** Panel chrome above the grid: the dark panel's top edge, the "Recipes" title, and the search row. */
    private static final int PANEL_TOP = 4;
    private static final int TITLE_Y = 6;
    private static final int SEARCH_Y = 17;
    private static final int SEARCH_H = 14;
    private static final int GRID_COLS = 4;
    private static final int GRID_ROWS = 6;
    private static final int CELL = 18;
    private static final int GRID_RIGHT = GRID_X + GRID_COLS * CELL;
    private static final int TRACK_H = GRID_ROWS * CELL;
    private static final int SCROLLBAR_X = GRID_RIGHT + 1;
    private static final int SCROLLBAR_W = 5;
    private static final int PANEL_RIGHT = SCROLLBAR_X + SCROLLBAR_W + 3;
    private static final int OPEN_WIDTH = PANEL_RIGHT + 1;

    // Search row: a text field filling the row with the sort toggle squared off at its right edge.
    private static final int SORT_SIZE = SEARCH_H;
    private static final int SORT_X = SCROLLBAR_X + SCROLLBAR_W - SORT_SIZE;
    private static final int SEARCH_X = GRID_X;
    private static final int SEARCH_W = SORT_X - 2 - SEARCH_X;
    /** Recessed field shades — the inverse of the button bevel, so a field never reads as a button. */
    private static final int FIELD_FACE = 0xFF2B2B2B;
    private static final int FIELD_SHADE = 0xFF1F1F1F;
    private static final int FIELD_LIT = 0xFF6E6E6E;

    // Status row: the free band directly above the player inventory (slots span x 8..170, starting at y 84).
    // The band is only ~11px tall, so the labels render small (0.66 scale, ~5px) and the LED/bar are 3px, all optically
    // centred on STATUS_CENTER_Y.
    private static final float LABEL_SCALE = 0.66F;
    private static final int STATUS_CENTER_Y = 77;
    private static final int STATUS_TEXT_Y = STATUS_CENTER_Y - 2;
    private static final int WIDGET_Y = STATUS_CENTER_Y - 1;
    private static final int LED_X = 9, LED_SIZE = 3;
    private static final int LINK_TEXT_X = LED_X + LED_SIZE + 3;
    private static final String BATTERY_LABEL = "Battery";
    private static final int BAT_W = 28, BAT_H = 3;
    private static final int BAT_X = 170 - 1 - BAT_W;
    // Filter checkbox (compact, custom).
    private static final int CHECK_X = GRID_X - 1, CHECK_Y = GRID_Y + TRACK_H + 5, CHECK_SIZE = 9;
    // Action buttons sit beside the slots they act on: "request containers" left of the container slot, "send to
    // network" right of the output slot. They are 12px (the 18px slot at 1.5:1) and are *vertically centred* on the
    // slot row rather than boxed to it — the slot visual boxes run y 54..72, centre 63, so a 12px button starts at 57.
    // A 3px gap keeps them off the slot borders: the container slot's box starts at x 91, the output slot's ends at 141.
    private static final int BTN_SIZE = 14;   // same square the sort toggle uses, so all three buttons match
    private static final int BTN_ROW_Y = 56;  // slot boxes run y 54..72, centre 63, so a 14px button starts at 56
    private static final int CONTAINER_X = 74, CONTAINER_Y = BTN_ROW_Y, CONTAINER_W = BTN_SIZE, CONTAINER_H = BTN_SIZE;
    private static final int RETURN_X = 144, RETURN_Y = BTN_ROW_Y, RETURN_W = BTN_SIZE, RETURN_H = BTN_SIZE;
    /** Raised-bevel shades, matching vanilla's button lighting: lit top/left, shaded bottom/right. */
    private static final int BTN_FACE = 0xFF8B8B8B;
    private static final int BTN_LIT = 0xFFC6C6C6;
    private static final int BTN_SHADE = 0xFF555555;
    private static final int BTN_FACE_FAIL = 0xFFB55050;
    /**
     * Farmer's Delight's lit heat-source sprite. The background texture bakes in a dim version; FD blits the lit one
     * over it from u=176,v=0 whenever the pot is heated. Same rectangle FD's own screen uses, so it lines up exactly.
     */
    private static final int HEAT_ICON_X = 47, HEAT_ICON_Y = 55, HEAT_ICON_W = 17, HEAT_ICON_H = 15;
    /** How long the container button stays red after a request found nothing, in milliseconds. */
    private static final long FAIL_FLASH_MS = 1000L;

    private int lastContainerFailureCount = -1;
    private long failFlashUntil = 0L;

    private List<RecipeHolder<CookingPotRecipe>> recipes = new ArrayList<>();
    /** Display names, resolved once — rebuildVisible() sorts every frame and must not re-resolve item names. */
    private String[] recipeNames = new String[0];
    private final List<Integer> visible = new ArrayList<>();
    private int scrollRow = 0;
    private int selectedIndex = -1;
    private boolean draggingScrollbar = false;
    /** Search field, present only while the panel is open. The query is screen-local and clears with the GUI. */
    private EditBox searchBox;
    private String searchQuery = "";

    public MECookingPotScreen(MECookingPotMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageHeight = POT_GUI_HEIGHT;
    }

    @Override
    protected void init() {
        this.imageWidth = menu.isPanelOpen() ? OPEN_WIDTH : POT_GUI_WIDTH;
        super.init();
        this.inventoryLabelY = this.imageHeight - 94;
        if (minecraft != null && minecraft.level != null) {
            recipes = MECookingPotMenu.sortedRecipes(minecraft.level);
            recipeNames = new String[recipes.size()];
            for (int i = 0; i < recipes.size(); i++) {
                recipeNames[i] = recipes.get(i).value()
                        .getResultItem(minecraft.level.registryAccess()).getHoverName().getString();
            }
        }

        // The search field exists only while the panel does. It is focused on creation, which covers both cases the
        // spec asks for: opening the panel, and opening the pot with the panel already open (init runs either way).
        if (menu.isPanelOpen()) {
            searchBox = new EditBox(font, leftPos + SEARCH_X + 3, topPos + SEARCH_Y + 3, SEARCH_W - 6, 8,
                    Component.literal("Search"));
            searchBox.setBordered(false);          // we draw a recessed frame ourselves, to match the panel
            searchBox.setMaxLength(48);
            searchBox.setValue(searchQuery);
            searchBox.setResponder(text -> {
                searchQuery = text;
                scrollRow = 0;                     // a new query invalidates where you were scrolled to
            });
            addRenderableWidget(searchBox);
            setInitialFocus(searchBox);
        } else {
            searchBox = null;
        }

        // Recipe-panel show/hide button (recipe-book style). The sprite is natively 20x18; 1.5:1 (13x12) keeps it a
        // corner affordance without shrinking it to illegibility the way 2:1 did. Same 5px inset from the corner.
        ImageButton toggle = new ImageButton(leftPos + POT_GUI_WIDTH - 18, topPos + 5, 13, 12, TOGGLE_SPRITES, b -> {
            menu.setPanelOpen(!menu.isPanelOpen());
            sendViewToggle(MECookingPotMenu.TOGGLE_PANEL);
            rebuildWidgets();
        });
        toggle.setTooltip(Tooltip.create(Component.literal(menu.isPanelOpen() ? "Hide recipes" : "Show recipes")));
        addRenderableWidget(toggle);
    }

    /** Tell the server to flip the same view setting, which persists it on the player. */
    private void sendViewToggle(int buttonId) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        }
    }

    /**
     * Filter, then order. Filters (search text, "craftable only") decide <em>what</em> is listed; ordering is then
     * always the same two-level rule: <strong>craftable first, unconditionally</strong> — no search or sort setting
     * turns that off — and the chosen name sort applied <em>within</em> each of the two blocks. The two blocks render
     * as one continuous grid with no divider, so the seam is only where the ordering happens to change.
     *
     * <p>Craftable counts refresh a couple of times a second, so the list re-orders live as stock changes. That is
     * intended: the list always tells the truth about what you can make right now.
     */
    private void rebuildVisible() {
        visible.clear();
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < recipes.size(); i++) {
            if (menu.isFilterCraftableOnly() && menu.getRecipeCount(i) <= 0) continue;
            if (!query.isEmpty() && !recipeName(i).toLowerCase(Locale.ROOT).contains(query)) continue;
            visible.add(i);
        }
        int sort = menu.getSortMode();
        visible.sort((a, b) -> {
            // Craftable ahead of non-craftable, always.
            int byCraftable = Boolean.compare(menu.getRecipeCount(b) > 0, menu.getRecipeCount(a) > 0);
            if (byCraftable != 0) return byCraftable;
            return switch (sort) {
                case ADAttachments.PotViewSettings.SORT_ASC -> recipeName(a).compareToIgnoreCase(recipeName(b));
                case ADAttachments.PotViewSettings.SORT_DESC -> recipeName(b).compareToIgnoreCase(recipeName(a));
                default -> Integer.compare(a, b); // off: the canonical order the menu already agrees on
            };
        });
        scrollRow = Math.max(0, Math.min(maxScrollRow(), scrollRow));
    }

    private String recipeName(int index) {
        return index >= 0 && index < recipeNames.length && recipeNames[index] != null ? recipeNames[index] : "";
    }

    private int totalRows() {
        return (visible.size() + GRID_COLS - 1) / GRID_COLS;
    }

    private int maxScrollRow() {
        return Math.max(0, totalRows() - GRID_ROWS);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        rebuildVisible();
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);

        // Status-row tooltips — the whole LED+label / label+bar cluster is hoverable, not just the swatch.
        int linkW = LINK_TEXT_X - LED_X + scaledWidth(linkLabel()) + 1;
        int batLabelX = batteryLabelX();
        if (inRegion(mouseX, mouseY, LED_X - 1, STATUS_TEXT_Y, linkW, 8)) {
            String linkMsg = switch (menu.getLinkState()) {
                case 2 -> "Linked to a network";
                case 1 -> "Linked, but offline — no power or out of range";
                default -> "Not linked";
            };
            g.renderTooltip(font, Component.literal(linkMsg), mouseX, mouseY);
        } else if (inRegion(mouseX, mouseY, batLabelX, STATUS_TEXT_Y, BAT_X + BAT_W + 1 - batLabelX, 8)) {
            g.renderTooltip(font, Component.literal("Battery: " + menu.getEnergyPercent() + "%"), mouseX, mouseY);
        } else if (overContainerButton(mouseX, mouseY)) {
            g.renderComponentTooltip(font, List.of(
                    Component.literal("Request serving containers"),
                    Component.literal("Brings in as many as the waiting meal needs")
                            .withStyle(s -> s.withColor(0xAAAAAA)),
                    Component.literal("You can also place containers in by hand")
                            .withStyle(s -> s.withColor(0xAAAAAA))), mouseX, mouseY);
        } else if (overReturnButton(mouseX, mouseY)) {
            g.renderComponentTooltip(font, List.of(
                    Component.literal("Send contents to the network"),
                    Component.literal("Ingredients, containers and finished servings — the meal stays in the pot")
                            .withStyle(s -> s.withColor(0xAAAAAA))), mouseX, mouseY);
        } else if (overSortButton(mouseX, mouseY)) {
            String mode = switch (menu.getSortMode()) {
                case ADAttachments.PotViewSettings.SORT_ASC -> "Sort: A-Z";
                case ADAttachments.PotViewSettings.SORT_DESC -> "Sort: Z-A";
                default -> "Sort: default order";
            };
            g.renderComponentTooltip(font, List.of(
                    Component.literal(mode),
                    Component.literal("Click to cycle. Craftable recipes always come first")
                            .withStyle(s -> s.withColor(0xAAAAAA))), mouseX, mouseY);
        } else if (inRegion(mouseX, mouseY, HEAT_ICON_X, HEAT_ICON_Y, HEAT_ICON_W, HEAT_ICON_H)) {
            // Farmer's Delight's own keys, so the wording and every translation match the vanilla pot.
            g.renderTooltip(font, Component.translatable(menu.isHeated()
                    ? "container.farmersdelight.cooking_pot.heated"
                    : "container.farmersdelight.cooking_pot.not_heated"), mouseX, mouseY);
        }

        if (menu.isPanelOpen()) {
            int hovered = recipeAt(mouseX, mouseY);
            if (hovered >= 0) {
                RecipeHolder<CookingPotRecipe> holder = recipes.get(hovered);
                List<Component> lines = new ArrayList<>();
                lines.add(holder.value().getResultItem(minecraft.level.registryAccess()).getHoverName());
                int count = menu.getRecipeCount(hovered);
                if (count <= 0) {
                    lines.add(Component.literal("Not enough ingredients").withStyle(s -> s.withColor(0xDD7777)));
                } else if (hasShiftDown()) {
                    // Shift is what mouseClicked reads for a batch, so say what that click will actually cook.
                    int batch = Math.min(64, count);
                    lines.add(Component.literal("Shift-click: cook " + batch
                                    + (batch >= 64 ? " (a full stack)" : " (all available)"))
                            .withStyle(s -> s.withColor(0xFFD37F)));
                } else {
                    lines.add(Component.literal("Can make: " + count).withStyle(s -> s.withColor(0x77DD77)));
                    lines.add(Component.literal("Shift-click to cook a stack").withStyle(s -> s.withColor(0x888888)));
                }
                for (Ingredient ing : holder.value().getIngredients()) {
                    ItemStack[] items = ing.getItems();
                    if (items.length > 0) {
                        lines.add(Component.literal(" • ").append(items[0].getHoverName()).withStyle(s -> s.withColor(0xAAAAAA)));
                    }
                }
                g.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        g.blit(FD_GUI, x, y, 0, 0, POT_GUI_WIDTH, POT_GUI_HEIGHT);

        renderStatusHeader(g, x, y);
        renderContainerButton(g, x, y);
        renderReturnButton(g, x, y);

        // Lit heat indicator, exactly as Farmer's Delight draws it — without this the pot never showed the burning
        // log, so there was no way to tell a heated pot from an unheated one at a glance.
        if (menu.isHeated()) {
            g.blit(FD_GUI, x + HEAT_ICON_X, y + HEAT_ICON_Y, 176, 0, HEAT_ICON_W, HEAT_ICON_H);
        }

        int progress = menu.getCookProgressScaled();
        if (progress > 0) {
            g.blit(FD_GUI, x + 89, y + 25, 176, 15, progress + 1, 17);
        }

        if (menu.isPanelOpen()) {
            // Outer panel (dark) — wraps the title, the search row, the list and the filter row.
            g.fill(x + GRID_X - 4, y + PANEL_TOP, x + PANEL_RIGHT, y + GRID_Y + TRACK_H + 20, PANEL_DARK);
            // Inner list backdrop (light) — separates the scrollable recipes from the rows above and below.
            g.fill(x + GRID_X - 2, y + GRID_Y - 2, x + SCROLLBAR_X + SCROLLBAR_W + 1, y + GRID_Y + TRACK_H + 2, PANEL_LIGHT);
            drawRecessedField(g, x + SEARCH_X, y + SEARCH_Y, SEARCH_W, SEARCH_H);
            renderSortButton(g, x, y);
            renderRecipeGrid(g);
            renderScrollbar(g);
            renderCheckbox(g, x, y);
            g.drawString(font, Component.translatable("gui.applieddelight.recipes"), x + GRID_X, y + TITLE_Y, 0xE0E0E0, false);
        }
    }

    private String linkLabel() {
        return switch (menu.getLinkState()) {
            case 2 -> "Linked";
            case 1 -> "Offline";
            default -> "Unlinked";
        };
    }

    /** Width a label occupies once drawn at {@link #LABEL_SCALE}. */
    private int scaledWidth(String text) {
        return Math.round(font.width(text) * LABEL_SCALE);
    }

    private int batteryLabelX() {
        return BAT_X - 4 - scaledWidth(BATTERY_LABEL);
    }

    /**
     * Small "send the pot's contents back" button. The pot refuses a new recipe while anything is loaded, so this is
     * the one-click way to free it without dragging every slot out by hand.
     */
    /**
     * A raised button face with the same depth cue vanilla's widgets (and the recipe-book toggle) have: a dark outline,
     * a lit top/left edge and a shaded bottom/right edge. Without this the flat grey squares did not read as clickable.
     */
    private void drawBeveledButton(GuiGraphics g, int bx, int by, int w, int h, int face) {
        g.fill(bx, by, bx + w, by + h, BORDER);
        g.fill(bx + 1, by + 1, bx + w - 1, by + h - 1, face);
        g.fill(bx + 1, by + 1, bx + w - 1, by + 2, BTN_LIT);          // top highlight
        g.fill(bx + 1, by + 1, bx + 2, by + h - 1, BTN_LIT);          // left highlight
        g.fill(bx + 1, by + h - 2, bx + w - 1, by + h - 1, BTN_SHADE); // bottom shadow
        g.fill(bx + w - 2, by + 1, bx + w - 1, by + h - 1, BTN_SHADE); // right shadow
    }

    /**
     * A recessed field frame — the button bevel inverted (shadow top/left, highlight bottom/right) over a dark face.
     * Buttons pop out, fields sink in, so the search box can never be mistaken for something clickable.
     */
    private void drawRecessedField(GuiGraphics g, int bx, int by, int w, int h) {
        g.fill(bx, by, bx + w, by + h, BORDER);
        g.fill(bx + 1, by + 1, bx + w - 1, by + h - 1, FIELD_FACE);
        g.fill(bx + 1, by + 1, bx + w - 1, by + 2, FIELD_SHADE);
        g.fill(bx + 1, by + 1, bx + 2, by + h - 1, FIELD_SHADE);
        g.fill(bx + 1, by + h - 2, bx + w - 1, by + h - 1, FIELD_LIT);
        g.fill(bx + w - 2, by + 1, bx + w - 1, by + h - 1, FIELD_LIT);
    }

    /**
     * The 2px chevron arrow shared by the sort toggle and the send-to-network button, so the two can never drift
     * apart. Drawn about (cx, cy): a 2px shaft with a three-step head, sized for a 14px button.
     *
     * <p>{@code stretch} lengthens it by that many pixels at <em>each</em> end, so the arrow grows 2x that overall
     * while staying centred. The sort toggle passes 0; the send-to-network button passes 1, making its arrow 2px
     * taller. At 14px the button's clean interior is rows 2..11, so 1 is the most that fits.
     */
    private void drawArrow(GuiGraphics g, int cx, int cy, boolean up, int ink, int stretch) {
        if (up) {
            g.fill(cx - 1, cy - 3 - stretch, cx + 1, cy + 4 + stretch, ink);
            for (int i = 0; i < 3; i++) {
                g.fill(cx - 1 - i, cy - 3 - stretch + i, cx + 1 + i, cy - 2 - stretch + i, ink);
            }
        } else {
            g.fill(cx - 1, cy - 4 - stretch, cx + 1, cy + 3 + stretch, ink);
            for (int i = 0; i < 3; i++) {
                g.fill(cx - 1 - i, cy + 3 + stretch - i, cx + 1 + i, cy + 4 + stretch - i, ink);
            }
        }
    }

    /** The name-sort toggle: bars when off, an up arrow for A-Z, a down arrow for Z-A. */
    private void renderSortButton(GuiGraphics g, int x, int y) {
        int bx = x + SORT_X, by = y + SORT_Y();
        drawBeveledButton(g, bx, by, SORT_SIZE, SORT_SIZE, BTN_FACE);
        int cx = bx + SORT_SIZE / 2, cy = by + SORT_SIZE / 2, ink = 0xFF3B3B3B;
        switch (menu.getSortMode()) {
            case ADAttachments.PotViewSettings.SORT_ASC -> drawArrow(g, cx, cy, true, ink, 0);
            case ADAttachments.PotViewSettings.SORT_DESC -> drawArrow(g, cx, cy, false, ink, 0);
            default -> {
                g.fill(cx - 3, cy - 3, cx + 3, cy - 2, ink);
                g.fill(cx - 3, cy - 1, cx + 3, cy, ink);
                g.fill(cx - 3, cy + 1, cx + 3, cy + 2, ink);
            }
        }
    }

    private static int SORT_Y() {
        return SEARCH_Y;
    }

    private boolean overSortButton(double mouseX, double mouseY) {
        return menu.isPanelOpen()
                && inRegion((int) mouseX, (int) mouseY, SORT_X, SORT_Y(), SORT_SIZE, SORT_SIZE);
    }

    private void renderReturnButton(GuiGraphics g, int x, int y) {
        int bx = x + RETURN_X, by = y + RETURN_Y;
        drawBeveledButton(g, bx, by, RETURN_W, RETURN_H, BTN_FACE);
        // Contents going back out to storage — the sort toggle's arrow, stretched 2px taller so it fills the button
        // rather than floating in it (the sort toggle shares a row with the search field and wants the shorter one).
        int cx = bx + RETURN_W / 2, cy = by + RETURN_H / 2;
        drawArrow(g, cx, cy, true, 0xFF3B3B3B, 1);
    }

    private boolean overReturnButton(double mouseX, double mouseY) {
        return inRegion((int) mouseX, (int) mouseY, RETURN_X, RETURN_Y, RETURN_W, RETURN_H);
    }

    /**
     * "Request serving containers" button. The pot never fetches containers on its own, so this is how a waiting meal
     * gets bottled without hand-carrying containers. Flashes red for a moment when the network had none, which is the
     * only feedback available — the client cannot see network contents itself.
     */
    private void renderContainerButton(GuiGraphics g, int x, int y) {
        // The server bumps a counter on a failed request; noticing it change is how the client knows to flash.
        int failures = menu.getContainerFailureCount();
        if (lastContainerFailureCount >= 0 && failures != lastContainerFailureCount) {
            failFlashUntil = Util.getMillis() + FAIL_FLASH_MS;
        }
        lastContainerFailureCount = failures;
        boolean failing = Util.getMillis() < failFlashUntil;

        int bx = x + CONTAINER_X, by = y + CONTAINER_Y;
        drawBeveledButton(g, bx, by, CONTAINER_W, CONTAINER_H, failing ? BTN_FACE_FAIL : BTN_FACE);
        // Containers coming in: the sort toggle's 2px arrow language, shortened so the cup fits beneath it. The cup
        // stays a light 1px U — at this size 2px walls crowd the arrow, and the open U reads well.
        int cx = bx + CONTAINER_W / 2, cy = by + CONTAINER_H / 2;
        int ink = failing ? 0xFF3A1A1A : 0xFF3B3B3B;
        g.fill(cx - 1, cy - 5, cx + 1, cy - 1, ink);                  // shaft, 2px wide
        for (int i = 0; i < 3; i++) {
            g.fill(cx - 3 + i, cy - 1 + i, cx + 3 - i, cy + i, ink);  // head, narrowing downward
        }
        g.fill(cx - 4, cy + 2, cx - 3, cy + 5, ink);                  // cup left wall
        g.fill(cx + 3, cy + 2, cx + 4, cy + 5, ink);                  // cup right wall
        g.fill(cx - 4, cy + 4, cx + 4, cy + 5, ink);                  // cup base
    }

    private boolean overContainerButton(double mouseX, double mouseY) {
        return inRegion((int) mouseX, (int) mouseY, CONTAINER_X, CONTAINER_Y, CONTAINER_W, CONTAINER_H);
    }

    /** Draw a status label smaller than the standard font, so the 11px band does not feel packed. */
    private void drawSmallLabel(GuiGraphics g, String text, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(LABEL_SCALE, LABEL_SCALE, 1.0F);
        g.drawString(font, text, 0, 0, LABEL_TEXT, false);
        g.pose().popPose();
    }

    /** Status row above the player inventory: link LED + label on the left, battery label + bar on the right. */
    private void renderStatusHeader(GuiGraphics g, int x, int y) {
        // Link LED (green connected, yellow linked-but-offline, red unlinked) + label, at the inventory's left edge.
        int lx = x + LED_X, ly = y + WIDGET_Y;
        int ledColor = switch (menu.getLinkState()) {
            case 2 -> 0xFF3FD03F;   // green — connected
            case 1 -> 0xFFD0C03F;   // yellow — linked but offline
            default -> 0xFFD03F3F;  // red — unlinked
        };
        g.fill(lx - 1, ly - 1, lx + LED_SIZE + 1, ly + LED_SIZE + 1, BORDER);
        g.fill(lx, ly, lx + LED_SIZE, ly + LED_SIZE, ledColor);
        drawSmallLabel(g, linkLabel(), x + LINK_TEXT_X, y + STATUS_TEXT_Y);

        // Battery label + bar, right-aligned to the player inventory's right edge.
        int bx = x + BAT_X, by = y + WIDGET_Y;
        int pct = menu.getEnergyPercent();
        drawSmallLabel(g, BATTERY_LABEL, x + batteryLabelX(), y + STATUS_TEXT_Y);
        g.fill(bx - 1, by - 1, bx + BAT_W + 1, by + BAT_H + 1, BORDER);
        g.fill(bx, by, bx + BAT_W, by + BAT_H, 0xFF555555);
        int fill = BAT_W * pct / 100;
        int color = pct > 60 ? 0xFF3FD03F     // green
                : pct > 35 ? 0xFFD0C03F       // yellow
                : pct > 15 ? 0xFFD08A3F       // orange
                : 0xFFD03F3F;                 // red
        g.fill(bx, by, bx + fill, by + BAT_H, color);
    }

    private void renderCheckbox(GuiGraphics g, int x, int y) {
        boolean filtering = menu.isFilterCraftableOnly();
        int cx = x + CHECK_X, cy = y + CHECK_Y;
        g.fill(cx, cy, cx + CHECK_SIZE, cy + CHECK_SIZE, BORDER);
        g.fill(cx + 1, cy + 1, cx + CHECK_SIZE - 1, cy + CHECK_SIZE - 1, filtering ? 0xFF3F9F3F : 0xFF6E6E6E);
        if (filtering) {
            g.fill(cx + 3, cy + 3, cx + CHECK_SIZE - 3, cy + CHECK_SIZE - 3, 0xFFFFFFFF);
        }
        g.drawString(font, "Craftable", cx + CHECK_SIZE + 3, cy + 1, 0xE0E0E0, false);
    }

    private void renderRecipeGrid(GuiGraphics g) {
        int start = scrollRow * GRID_COLS;
        for (int cell = 0; cell < GRID_COLS * GRID_ROWS; cell++) {
            int pos = start + cell;
            if (pos >= visible.size()) break;
            int index = visible.get(pos);
            int col = cell % GRID_COLS, row = cell / GRID_COLS;
            int cx = leftPos + GRID_X + col * CELL, cy = topPos + GRID_Y + row * CELL;
            if (index == selectedIndex) {
                g.fill(cx - 1, cy - 1, cx + 17, cy + 17, SELECTED);
            }
            ItemStack result = recipes.get(index).value().getResultItem(minecraft.level.registryAccess());
            g.renderItem(result, cx, cy);
            int count = menu.getRecipeCount(index);
            if (count > 0) {
                ItemStack display = result.copy();
                display.setCount(Math.min(count, 99));
                g.renderItemDecorations(font, display, cx, cy);
            } else {
                g.renderItemDecorations(font, result, cx, cy);
                g.fill(cx, cy, cx + 16, cy + 16, 0x99CC2020);
            }
        }
    }

    private void renderScrollbar(GuiGraphics g) {
        int tx = leftPos + SCROLLBAR_X, ty = topPos + GRID_Y;
        g.fill(tx, ty, tx + SCROLLBAR_W, ty + TRACK_H, 0xFF1A1A1A);
        int rows = Math.max(totalRows(), 1);
        int thumbH = Math.min(TRACK_H, Math.max(10, TRACK_H * GRID_ROWS / rows));
        int maxScroll = maxScrollRow();
        int thumbY = ty + (maxScroll == 0 ? 0 : (TRACK_H - thumbH) * scrollRow / maxScroll);
        int color = maxScroll == 0 ? 0xFF555555 : 0xFFB0B0B0;
        g.fill(tx, thumbY, tx + SCROLLBAR_W, thumbY + thumbH, color);
    }

    private boolean inRegion(int mx, int my, int x, int y, int w, int h) {
        return mx >= leftPos + x && mx < leftPos + x + w && my >= topPos + y && my < topPos + y + h;
    }

    private boolean overCheckbox(int mx, int my) {
        int w = CHECK_SIZE + 4 + font.width("Craftable");
        return menu.isPanelOpen() && inRegion(mx, my, CHECK_X, CHECK_Y, w, CHECK_SIZE);
    }

    private int recipeAt(int mouseX, int mouseY) {
        if (!menu.isPanelOpen()) return -1;
        int relX = mouseX - (leftPos + GRID_X), relY = mouseY - (topPos + GRID_Y);
        if (relX < 0 || relY < 0 || relX >= GRID_COLS * CELL || relY >= GRID_ROWS * CELL) return -1;
        int pos = (scrollRow + relY / CELL) * GRID_COLS + relX / CELL;
        return pos < visible.size() ? visible.get(pos) : -1;
    }

    private boolean overScrollbar(double mouseX, double mouseY) {
        if (!menu.isPanelOpen()) return false;
        int tx = leftPos + SCROLLBAR_X, ty = topPos + GRID_Y;
        return mouseX >= tx && mouseX < tx + SCROLLBAR_W && mouseY >= ty && mouseY < ty + TRACK_H;
    }

    private void scrollToMouse(double mouseY) {
        int ty = topPos + GRID_Y;
        int maxScroll = maxScrollRow();
        if (maxScroll <= 0) return;
        double frac = (mouseY - ty) / (double) TRACK_H;
        scrollRow = Math.max(0, Math.min(maxScroll, (int) Math.round(frac * maxScroll)));
    }

    /**
     * A focused search box must swallow typed keys <em>before</em> {@link AbstractContainerScreen} sees them, or the
     * inventory key ("e" by default) closes the whole GUI mid-word. Escape is deliberately let through so it still
     * closes the screen.
     */
    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (searchBox != null && searchBox.isFocused() && key != GLFW.GLFW_KEY_ESCAPE
                && (searchBox.keyPressed(key, scan, mods) || searchBox.canConsumeInput())) {
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (searchBox != null && searchBox.isFocused() && searchBox.charTyped(c, mods)) {
            return true;
        }
        return super.charTyped(c, mods);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (overSortButton(mouseX, mouseY)) {
            // Optimistic client flip, then report — the same pattern the other view toggles use.
            menu.setSortMode((menu.getSortMode() + 1) % 3);
            sendViewToggle(MECookingPotMenu.TOGGLE_SORT);
            scrollRow = 0;
            return true;
        }
        if (overContainerButton(mouseX, mouseY)) {
            sendViewToggle(MECookingPotMenu.REQUEST_CONTAINERS);
            return true;
        }
        if (overReturnButton(mouseX, mouseY)) {
            sendViewToggle(MECookingPotMenu.RETURN_CONTENTS);
            return true;
        }
        if (overCheckbox((int) mouseX, (int) mouseY)) {
            menu.setFilterCraftableOnly(!menu.isFilterCraftableOnly());
            sendViewToggle(MECookingPotMenu.TOGGLE_FILTER);
            scrollRow = 0;
            return true;
        }
        if (overScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            scrollToMouse(mouseY);
            return true;
        }
        int index = recipeAt((int) mouseX, (int) mouseY);
        if (index >= 0 && minecraft != null && minecraft.gameMode != null) {
            selectedIndex = index;
            int id = hasShiftDown() ? index + MECookingPotMenu.SHIFT_OFFSET : index;
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollToMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (menu.isPanelOpen()) {
            int relX = (int) mouseX - (leftPos + GRID_X), relY = (int) mouseY - (topPos + GRID_Y);
            if (relX >= 0 && relY >= 0 && relX < GRID_COLS * CELL + SCROLLBAR_W + 2 && relY < GRID_ROWS * CELL) {
                scrollRow = Math.max(0, Math.min(maxScrollRow(), scrollRow - (int) Math.signum(dy)));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // y=6, not 16: the ingredient slots start at y=17, so the old position ran the title straight through them.
        g.drawString(font, title, 8, 6, LABEL_TEXT, false);
    }
}
