package com.civcraft.space;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.gui.Items;
import com.civcraft.gui.Menu;
import com.civcraft.model.Civilization;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Space program menu: missions, progress and the «Запуск Шаттла!» button (spec 03 §9). */
final class SpaceMenu extends Menu {

    private final SpaceModule space;
    private final CivCraft civ;

    SpaceMenu(SpaceModule space) {
        super(5, space.civ().messages().component("space.gui.title"));
        this.space = space;
        this.civ = space.civ();
    }

    @Override
    protected void render(Player viewer) {
        Civilization c = civ.state().civOf(viewer);
        if (c == null) {
            set(22, Items.of(Material.BARRIER).name(civ.messages().component("error.not-in-civ")).build());
            return;
        }
        SpaceState st = space.state(c);
        int slot = 10;
        for (SpaceModule.Mission m : space.missions()) {
            if (slot > 16) break;
            List<Component> lore = new ArrayList<>();
            String status = m.number() <= st.completed() ? "space.status.done"
                    : m.number() == st.current() ? "space.status.running" : "space.status.todo";
            lore.add(civ.messages().component(status));
            lore.add(civ.messages().component("space.gui.rocket", Messages.arg("rocket", m.rocket())));
            lore.add(civ.messages().component("space.gui.requirements",
                    Messages.number("hammers", space.requiredHammers(c, m)), Messages.number("beakers", space.requiredBeakers(c, m))));
            lore.add(civ.messages().component("space.gui.components"));
            for (Map.Entry<String, Integer> e : m.components().entrySet()) {
                lore.add(civ.messages().component("space.gui.entry", Messages.arg("amount", e.getValue()),
                        Messages.arg("item", space.itemName(e.getKey()))));
            }
            lore.add(civ.messages().component("space.gui.rewards"));
            for (Map.Entry<String, Integer> e : m.rewards().entrySet()) {
                lore.add(civ.messages().component("space.gui.entry", Messages.arg("amount", e.getValue()),
                        Messages.arg("item", space.itemName(e.getKey()))));
            }
            if (m.number() == st.current()) {
                lore.add(civ.messages().component("space.gui.progress", Messages.number("hammers", st.hammers()),
                        Messages.number("beakers", st.beakers())));
            }
            Material mat = m.number() <= st.completed() ? Material.FIREWORK_STAR
                    : m.number() == st.current() ? Material.FIREWORK_ROCKET : Material.GUNPOWDER;
            set(slot++, Items.of(mat).name(civ.messages().parse("<gold>" + m.number() + ". " + m.name()))
                    .lore(lore).amount(m.number()).glow(m.number() == st.current()).build());
        }
        boolean shuttle = space.hasShuttleInCapital(c);
        SpaceModule.Mission current = space.mission(st.current());
        List<Component> info = new ArrayList<>();
        info.add(civ.messages().component(shuttle ? "space.shuttle-ok" : "space.shuttle-missing"));
        info.add(civ.messages().component("space.gui.completed", Messages.arg("completed", st.completed()),
                Messages.arg("total", space.missionCount())));
        if (current != null) {
            double h = space.requiredHammers(c, current);
            double b = space.requiredBeakers(c, current);
            info.add(civ.messages().component("space.gui.current", Messages.arg("mission", current.name()),
                    Messages.arg("percent", Format.percent(Math.min(h <= 0 ? 1 : st.hammers() / h, b <= 0 ? 1 : st.beakers() / b)))));
        }
        set(4, Items.of(Material.CLOCK).name(civ.messages().component("space.gui.info")).lore(info).build(), e -> {
            try {
                space.progress(viewer);
            } catch (CivException ex) {
                civ.messages().send(viewer, ex.key(), ex.args());
            }
        });
        set(31, Items.of(Material.FIREWORK_ROCKET).name(civ.messages().component("space.gui.launch"))
                .lore(civ.messages().component("space.gui.launch-hint")).glow(shuttle && current == null).build(), e -> {
                    try {
                        space.launch(viewer);
                    } catch (CivException ex) {
                        civ.messages().send(viewer, ex.key(), ex.args());
                    }
                    refresh(viewer);
                });
        set(44, Items.of(Material.BARRIER).name(civ.messages().component("space.gui.close")).build(), e -> viewer.closeInventory());
        fill(Items.filler());
    }
}
