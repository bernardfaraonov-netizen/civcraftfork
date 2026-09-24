package com.civcraft.religion.artifact;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.gui.Items;
import com.civcraft.gui.PagedMenu;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** Artifact catalogue with requirements and prices; click to buy with religion points. */
final class ArtifactMenu extends PagedMenu<ArtifactModule.Artifact> {

    private final ArtifactModule module;
    private final CivCraft civ;

    ArtifactMenu(ArtifactModule module) {
        super(module.civ().messages().component("artifacts.gui.title"));
        this.module = module;
        this.civ = module.civ();
    }

    @Override
    protected List<ArtifactModule.Artifact> entries(Player viewer) {
        return List.copyOf(module.artifacts().values());
    }

    @Override
    protected ItemStack icon(Player viewer, ArtifactModule.Artifact a) {
        Civilization c = civ.state().civOf(viewer);
        Town town = civ.state().townOf(viewer);
        List<Component> lore = new ArrayList<>();
        for (String line : a.description()) lore.add(civ.messages().parse("<gray>" + line));
        lore.add(module.requirementText(a));
        boolean ok = false;
        if (c != null && town != null) {
            try {
                module.checkCanProduce(c, town, a.id());
                ok = true;
            } catch (CivException e) {
                lore.add(civ.messages().component(e.key(), e.args()));
            }
        }
        lore.add(civ.messages().component("artifacts.gui.price", Messages.money("coins", module.coinPrice(a.id(), town)),
                Messages.number("hammers", a.hammers())));
        double religion = module.religionPrice(a.id());
        lore.add(religion < 0 ? civ.messages().component("artifacts.gui.no-religion")
                : civ.messages().component("artifacts.gui.religion-price", Messages.number("points", religion)));
        lore.add(civ.messages().component(a.passive() ? "artifacts.gui.passive" : "artifacts.gui.active",
                Messages.arg("duration", com.civcraft.core.util.Durations.format(java.time.Duration.ofSeconds(a.duration()))),
                Messages.arg("cooldown", com.civcraft.core.util.Durations.format(java.time.Duration.ofSeconds(a.cooldown()))),
                Messages.arg("uses", civ.messages().plain(a.single() ? "artifacts.gui.single" : "artifacts.gui.infinite"))));
        if (religion >= 0) lore.add(civ.messages().component("artifacts.gui.click"));
        return Items.of(a.material()).name(civ.messages().parse((ok ? "<green>" : "<red>") + a.name())).lore(lore).build();
    }

    @Override
    protected Consumer<InventoryClickEvent> action(Player viewer, ArtifactModule.Artifact a) {
        return e -> {
            double price = module.religionPrice(a.id());
            if (price < 0) return;
            viewer.closeInventory();
            Prompts.confirm(viewer, civ.messages().component("artifacts.gui.confirm-title"),
                    List.of(civ.messages().component("artifacts.gui.confirm-body", Messages.arg("name", a.name()),
                            Messages.arg("points", Format.number(price)))),
                    civ.messages().component("artifacts.gui.yes"), civ.messages().component("artifacts.gui.no"), p -> {
                        try {
                            module.buy(p, a.id());
                        } catch (CivException ex) {
                            civ.messages().send(p, ex.key(), ex.args());
                        }
                    });
        };
    }
}
