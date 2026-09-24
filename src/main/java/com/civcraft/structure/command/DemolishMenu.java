package com.civcraft.structure.command;

import static com.civcraft.core.text.Messages.arg;

import com.civcraft.CivCraft;
import com.civcraft.gui.Items;
import com.civcraft.gui.PagedMenu;
import com.civcraft.model.Town;
import com.civcraft.structure.Structure;
import com.civcraft.structure.StructureModule;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** {@code /build demolish}: the town's demolishable structures; a click asks for confirmation (spec 02 §1.7). */
final class DemolishMenu extends PagedMenu<Structure> {

    private final StructureModule module;
    private final CivCraft civ;
    private final Town town;
    private final BuildCommand command;

    DemolishMenu(StructureModule module, CivCraft civ, Town town, BuildCommand command) {
        super(civ.messages().component("structure.demolish.menu", arg("town", town.name())));
        this.module = module;
        this.civ = civ;
        this.town = town;
        this.command = command;
    }

    @Override
    protected List<Structure> entries(Player viewer) {
        List<Structure> list = new ArrayList<>();
        for (Structure s : module.structures(town)) {
            if (!s.removed() && s.typeDef().demolishable()) list.add(s);
        }
        return list;
    }

    @Override
    protected ItemStack icon(Player viewer, Structure s) {
        String state = s.isBuilding() ? civ.messages().plain("structure.state.building", arg("percent", (int) (s.progress() * 100)))
                : s.isDestroyed() ? civ.messages().plain("structure.state.destroyed")
                : civ.messages().plain("structure.state.complete");
        return Items.of(s.typeDef().icon())
                .name(civ.messages().component("structure.menu.name", arg("name", s.typeDef().name())))
                .lore(civ.messages().component("structure.demolish.lore", module.coords(s), arg("state", state)))
                .build();
    }

    @Override
    protected Consumer<InventoryClickEvent> action(Player viewer, Structure s) {
        return e -> {
            viewer.closeInventory();
            command.confirmDemolish(viewer, s);
        };
    }
}
