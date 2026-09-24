package com.civcraft.science;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.gui.Items;
import com.civcraft.gui.Menu;
import com.civcraft.model.Civilization;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

/** Tech tree GUI: one page per branch, states, costs, requirements and unlocks (spec 03 §3). */
final class TechTreeMenu extends Menu {

    private final ScienceModule science;
    private final CivCraft civ;
    private String branch;

    TechTreeMenu(ScienceModule science, String branch) {
        super(6, science.civ().messages().component("science.gui.title"));
        this.science = science;
        this.civ = science.civ();
        this.branch = branch;
    }

    private enum State { RESEARCHED, CURRENT, QUEUED, AVAILABLE, LOCKED, PROVINCE }

    @Override
    protected void render(Player viewer) {
        Civilization c = civ.state().civOf(viewer);
        if (c == null) {
            set(22, Items.of(Material.BARRIER).name(civ.messages().component("error.not-in-civ")).build());
            return;
        }
        ResearchState st = science.state(c);
        List<TechTree.Tech> techs = science.tree().branch(branch);
        for (int i = 0; i < techs.size() && i < 45; i++) {
            TechTree.Tech t = techs.get(i);
            State state = state(c, st, t);
            set(i, icon(c, st, t, state), e -> click(viewer, c, t, state, e));
        }
        int slot = 45;
        for (TechTree.Branch b : science.tree().branches()) {
            if (slot > 49) break;
            boolean selected = b.id().equals(branch);
            set(slot++, Items.of(b.icon()).name(Component.text(b.name(), selected ? NamedTextColor.GOLD : NamedTextColor.YELLOW))
                    .lore(civ.messages().component(selected ? "science.gui.branch-selected" : "science.gui.branch-click"))
                    .glow(selected).build(), e -> {
                        branch = b.id();
                        refresh(viewer);
                    });
        }
        for (int s = slot; s < 54; s++) set(s, Items.filler());
        TechTree.Tech current = science.tree().get(st.current());
        List<Component> info = new ArrayList<>();
        info.add(civ.messages().component("science.gui.info-era", Messages.arg("era",
                Component.text(science.eraName(st.era()), science.tree().era(st.era()).color()))));
        info.add(civ.messages().component("science.gui.info-rate", Messages.number("rate", st.civRate())));
        info.add(civ.messages().component("science.gui.info-stored", Messages.number("stored", st.storedBeakers())));
        if (current != null) {
            info.add(civ.messages().component("science.gui.info-current", Messages.arg("tech", current.name()),
                    Messages.arg("percent", Format.percent(science.progress(c)))));
        }
        for (String q : st.queue()) {
            info.add(civ.messages().component("science.gui.info-queued", Messages.arg("tech", science.techName(q))));
        }
        set(52, Items.of(Material.CLOCK).name(civ.messages().component("science.gui.info-title"))
                .lore(info).build());
        set(53, Items.of(Material.BARRIER).name(civ.messages().component("science.gui.close")).build(),
                e -> viewer.closeInventory());
    }

    private State state(Civilization c, ResearchState st, TechTree.Tech t) {
        if (st.completed().contains(t.id())) return State.RESEARCHED;
        if (t.id().equals(st.current())) return State.CURRENT;
        if (st.queue().contains(t.id())) return State.QUEUED;
        if (!st.completed().containsAll(t.requires())) return State.LOCKED;
        if (!science.available(c).contains(t)) return State.PROVINCE;
        return State.AVAILABLE;
    }

    private org.bukkit.inventory.ItemStack icon(Civilization c, ResearchState st, TechTree.Tech t, State state) {
        NamedTextColor color = switch (state) {
            case RESEARCHED -> NamedTextColor.GREEN;
            case CURRENT -> NamedTextColor.GOLD;
            case QUEUED -> NamedTextColor.AQUA;
            case AVAILABLE -> NamedTextColor.WHITE;
            case LOCKED, PROVINCE -> NamedTextColor.RED;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(civ.messages().component("science.gui.state." + state.name().toLowerCase()));
        lore.add(civ.messages().component("science.gui.era", Messages.arg("era",
                Component.text(science.eraName(t.era()), science.tree().era(t.era()).color()))));
        if (state != State.RESEARCHED) {
            ScienceModule.Cost cost = science.cost(c, t);
            lore.add(civ.messages().component("science.gui.cost", Messages.money("coins", cost.coinsCents()),
                    Messages.number("beakers", cost.beakers())));
            double done = st.progress(t.id());
            if (done > 0) {
                lore.add(civ.messages().component("science.gui.progress", Messages.number("done", done),
                        Messages.arg("percent", Format.percent(cost.beakers() <= 0 ? 1 : done / cost.beakers()))));
            }
        }
        if (!t.requires().isEmpty()) {
            lore.add(civ.messages().component("science.gui.requires"));
            for (String r : t.requires()) {
                lore.add(civ.messages().component(st.completed().contains(r) ? "science.gui.req-ok" : "science.gui.req-missing",
                        Messages.arg("tech", science.techName(r))));
            }
        }
        for (String line : t.description()) lore.add(civ.messages().parse("<gray>" + line));
        for (Map.Entry<String, List<String>> e : t.unlocks().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String key = "science.gui.unlocks." + e.getKey();
            String label = civ.messages().has(key) ? civ.messages().raw(key) : e.getKey();
            lore.add(civ.messages().component("science.gui.unlocks-line", Messages.arg("category", label),
                    Messages.arg("ids", String.join(", ", e.getValue()))));
        }
        switch (state) {
            case AVAILABLE -> lore.add(civ.messages().component(st.current() == null
                    ? "science.gui.click-start" : "science.gui.click-queue"));
            case QUEUED -> lore.add(civ.messages().component("science.gui.click-unqueue"));
            case LOCKED -> lore.add(civ.messages().component("science.gui.click-queue"));
            default -> {
            }
        }
        return Items.of(t.icon()).name(Component.text(t.name(), color)).lore(lore)
                .glow(state == State.RESEARCHED || state == State.CURRENT).build();
    }

    private void click(Player viewer, Civilization c, TechTree.Tech t, State state, InventoryClickEvent e) {
        try {
            if (state == State.QUEUED && e.getClick() == ClickType.SHIFT_RIGHT) {
                ResearchCommands.queueRemove(civ, science, viewer, t);
            } else if (state == State.AVAILABLE && e.getClick() == ClickType.LEFT && science.state(c).current() == null) {
                CivPerms.check(civ, viewer, c, CivPerms.RESEARCH);
                science.checkCanResearch(c, t, true);
                ScienceModule.Cost cost = science.cost(c, t);
                viewer.closeInventory();
                Prompts.confirm(viewer, civ.messages().component("science.gui.confirm-title"),
                        List.of(civ.messages().component("science.gui.confirm-body", Messages.arg("tech", t.name()),
                                Messages.money("coins", cost.coinsCents()), Messages.number("beakers", cost.beakers()))),
                        civ.messages().component("science.gui.yes"), civ.messages().component("science.gui.no"),
                        p -> {
                            try {
                                ResearchCommands.on(civ, science, p, t);
                            } catch (CivException ex) {
                                civ.messages().send(p, ex.key(), ex.args());
                            }
                        });
                return;
            } else if (state == State.AVAILABLE || state == State.LOCKED) {
                ResearchCommands.queueAdd(civ, science, viewer, t);
            } else {
                return;
            }
        } catch (CivException ex) {
            civ.messages().send(viewer, ex.key(), ex.args());
        }
        refresh(viewer);
    }
}
