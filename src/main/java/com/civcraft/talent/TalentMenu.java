package com.civcraft.talent;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.gui.Items;
import com.civcraft.gui.Menu;
import com.civcraft.model.Civilization;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Talent tree: levels 1–9 are columns (header in row 4, choices in rows 1–3), level 10 sits in row 5.
 */
final class TalentMenu extends Menu {

    private final TalentModule talents;
    private final CivCraft civ;

    TalentMenu(TalentModule talents) {
        super(6, talents.civ().messages().component("talent.gui.title"));
        this.talents = talents;
        this.civ = talents.civ();
    }

    @Override
    protected void render(Player viewer) {
        Civilization c = civ.state().civOf(viewer);
        if (c == null) {
            set(22, Items.of(Material.BARRIER).name(civ.messages().component("error.not-in-civ")).build());
            return;
        }
        TalentState st = talents.state(c);
        int available = talents.availableLevel(c);
        for (TalentModule.Level level : talents.levels().values()) {
            int l = level.level();
            int headerSlot;
            int[] choiceSlots;
            if (l <= 9) {
                headerSlot = 27 + (l - 1);
                choiceSlots = new int[]{l - 1, 9 + (l - 1), 18 + (l - 1)};
            } else if (l == 10) {
                headerSlot = 36;
                choiceSlots = new int[]{37, 38, 39};
            } else {
                continue;
            }
            Integer chosen = st.choices().get(l);
            String status = chosen != null ? "chosen" : l == available ? "available"
                    : l < st.maxLevelReached() ? "lost" : "locked";
            set(headerSlot, Items.of(level.icon()).name(civ.messages().component("talent.gui.level",
                            Messages.arg("level", l), Messages.arg("theme", level.theme())))
                    .lore(civ.messages().component("talent.gui.status." + status)).amount(l).build());
            for (int i = 0; i < choiceSlots.length; i++) {
                TalentModule.Talent t = level.choices().get(i + 1);
                if (t == null) continue;
                boolean isChosen = chosen != null && chosen == t.choice();
                Material mat = isChosen ? Material.LIME_DYE : l == available ? Material.YELLOW_DYE
                        : chosen != null || l < st.maxLevelReached() ? Material.GRAY_DYE : Material.RED_DYE;
                NamedTextColor color = isChosen ? NamedTextColor.GREEN : l == available ? NamedTextColor.YELLOW : NamedTextColor.GRAY;
                List<Component> lore = new ArrayList<>();
                for (String line : t.description()) lore.add(civ.messages().parse("<gray>" + line));
                if (l == available) lore.add(civ.messages().component("talent.gui.click"));
                int choice = t.choice();
                set(choiceSlots[i], Items.of(mat).name(Component.text(l + "." + choice + " " + t.name(), color))
                        .lore(lore).glow(isChosen).build(), e -> {
                            if (l != available) return;
                            viewer.closeInventory();
                            try {
                                talents.confirmChoice(viewer, choice);
                            } catch (CivException ex) {
                                civ.messages().send(viewer, ex.key(), ex.args());
                            }
                        });
            }
        }
        for (int s = 45; s < 54; s++) set(s, Items.filler());
        set(49, Items.of(Material.BOOK).name(civ.messages().component("talent.gui.info-title"))
                .lore(civ.messages().component("talent.gui.info", Messages.arg("count", st.choices().size()),
                        Messages.arg("capital", talents.capitalLevel(c))))
                .build());
        set(53, Items.of(Material.BARRIER).name(civ.messages().component("talent.gui.close")).build(),
                e -> viewer.closeInventory());
    }
}
