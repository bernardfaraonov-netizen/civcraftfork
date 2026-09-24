package com.civcraft.structure.command;

import static com.civcraft.core.text.Messages.arg;
import static com.civcraft.core.text.Messages.money;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.util.Durations;
import com.civcraft.gui.Items;
import com.civcraft.gui.PagedMenu;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.structure.StructureModule;
import com.civcraft.structure.type.Category;
import com.civcraft.structure.type.StructureType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /build} menu (spec 01 §19.4): buildable types with price, hammers, time, upkeep and the reason when a type
 * is not available. Filters by category; the theme button cycles the style used for the next placement.
 */
final class BuildMenu extends PagedMenu<StructureType> {

    private enum Filter { ALL, BUILDINGS, IMPROVEMENTS, WONDERS, MILITARY }

    private final StructureModule module;
    private final CivCraft civ;
    private final Town town;
    private String theme;
    private Filter filter = Filter.ALL;

    BuildMenu(StructureModule module, CivCraft civ, Town town, String theme) {
        super(civ.messages().component("structure.menu.title", arg("town", town.name())));
        this.module = module;
        this.civ = civ;
        this.town = town;
        this.theme = theme;
    }

    @Override
    protected List<StructureType> entries(Player viewer) {
        Civilization c = civ.state().civOf(town);
        List<StructureType> list = new ArrayList<>();
        for (StructureType t : module.types().sorted()) {
            if (t.warOnly()) continue;
            if (t.nation() != null && (c == null || !module.types().nationMatches(t.nation(), c.nation()))) continue;
            if (c != null && c.nation() != null && module.types().replacementFor(t.id(), c.nation()) != null) continue;
            if (!matches(t.category())) continue;
            list.add(t);
        }
        return list;
    }

    private boolean matches(Category category) {
        return switch (filter) {
            case ALL -> true;
            case BUILDINGS -> category == Category.BUILDING || category == Category.MAIN;
            case IMPROVEMENTS -> category == Category.IMPROVEMENT;
            case WONDERS -> category.isWonder();
            case MILITARY -> category == Category.DEFENSE || category == Category.SHIP || category.isCustom();
        };
    }

    @Override
    protected ItemStack icon(Player viewer, StructureType t) {
        String reason = null;
        try {
            module.checkAvailable(town, t);
        } catch (CivException e) {
            reason = civ.messages().plain(e.key(), e.args());
        }
        List<Component> lore = new ArrayList<>();
        lore.add(civ.messages().component("structure.menu.category", arg("category", civ.messages().plain("structure.category." + t.category().key())),
                arg("era", t.era())));
        if (!t.category().isCustom()) {
            long price = module.price(town, t);
            double hammers = module.requiredHammers(town, t);
            double rate = module.buildRate(town);
            long eta = rate <= 0 ? -1 : (long) Math.ceil(hammers / rate * 3600);
            String etaText = eta < 0 ? civ.messages().plain("structure.eta-never") : Durations.format(Duration.ofSeconds(eta));
            lore.add(civ.messages().component("structure.menu.cost", money("cost", price)));
            lore.add(civ.messages().component("structure.menu.hammers", arg("hammers", Format.number(Math.ceil(hammers))), arg("eta", etaText)));
            lore.add(civ.messages().component("structure.menu.size", arg("x", t.chunksX()), arg("z", t.chunksZ())));
        } else {
            lore.add(civ.messages().component("structure.menu.cost-segment", money("cost", t.cost())));
        }
        lore.add(civ.messages().component("structure.menu.upkeep", money("upkeep", t.upkeep())));
        if (t.slot()) lore.add(civ.messages().component("structure.menu.slot", arg("used", module.slotsUsed(town)), arg("max", module.slots(town))));
        if (t.limit() > 0) lore.add(civ.messages().component("structure.menu.limit", arg("count", module.countInTown(town, t)), arg("limit", t.limit())));
        lore.add(Component.empty());
        lore.add(reason == null ? civ.messages().component("structure.menu.available")
                : civ.messages().component("structure.menu.unavailable", arg("reason", reason)));
        return Items.of(reason == null ? t.icon() : Material.GRAY_DYE)
                .name(civ.messages().component(reason == null ? "structure.menu.name" : "structure.menu.name-locked", arg("name", t.name())))
                .lore(lore)
                .glow(t.isWonder() && reason == null)
                .build();
    }

    @Override
    protected Consumer<InventoryClickEvent> action(Player viewer, StructureType t) {
        return e -> {
            viewer.closeInventory();
            try {
                module.placement().begin(viewer, t, null, 0, theme, List.of());
            } catch (CivException ex) {
                civ.messages().send(viewer, ex.key(), ex.args());
            }
        };
    }

    @Override
    protected void renderFooter(Player viewer) {
        Map<String, String> themes = module.themes();
        set(49, Items.of(Material.PAINTING)
                .name(civ.messages().component("structure.menu.theme", arg("theme", themes.getOrDefault(theme, theme))))
                .lore(civ.messages().component("structure.menu.theme-hint"))
                .build(), e -> {
            List<String> ids = new ArrayList<>(themes.keySet());
            int i = ids.indexOf(theme);
            theme = ids.get((i + 1) % ids.size());
            refresh(viewer);
        });
        set(47, Items.of(Material.HOPPER)
                .name(civ.messages().component("structure.menu.filter", arg("filter", civ.messages().plain("structure.menu.filter-" + filter.name().toLowerCase()))))
                .lore(civ.messages().component("structure.menu.filter-hint"))
                .build(), e -> {
            filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
            refresh(viewer);
        });
        set(51, Items.of(Material.CLOCK)
                .name(civ.messages().component("structure.menu.rate", arg("rate", Format.number(module.buildRate(town)))))
                .build());
    }
}
