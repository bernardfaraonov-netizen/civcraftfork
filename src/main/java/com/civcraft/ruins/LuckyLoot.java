package com.civcraft.ruins;

import com.civcraft.pve.ItemSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/**
 * Lucky block loot (spec 04 §15.2): every category is rolled independently and a category that hits
 * yields one random entry. Rare categories (3.82 % and below) give at most one item per block; bonus
 * scrolls are rolled rarest first and at most one scroll drops. Single-use artifacts are an extra 5 %
 * roll. Entries may list {@code choices}: one of them is picked with the entry's amount/enchantments.
 */
final class LuckyLoot {

    private record Category(double chance, boolean rare, List<ItemSpec> entries) {
    }

    private final List<Category> common = new ArrayList<>();
    private final List<Category> rare = new ArrayList<>();
    private final List<ItemSpec> scrolls;
    private final double artifactChance;
    private final List<String> artifacts;
    private final List<ItemSpec> extras;

    LuckyLoot(ConfigurationSection cfg, Logger log) {
        for (Map<?, ?> m : cfg.getMapList("categories")) {
            Object c = m.get("chance");
            double chance = c instanceof Number n ? n.doubleValue() : 0;
            boolean isRare = Boolean.TRUE.equals(m.get("rare"));
            List<ItemSpec> entries = ItemSpec.parseList(m.get("entries") instanceof List<?> l ? l : List.of(), log,
                    "ruins.yml lucky.categories");
            if (entries.isEmpty() || chance <= 0) continue;
            (isRare ? rare : common).add(new Category(chance, isRare, entries));
        }
        rare.sort(Comparator.comparingDouble(Category::chance));
        scrolls = new ArrayList<>(ItemSpec.parseList(cfg.getList("bonus-scrolls"), log, "ruins.yml lucky.bonus-scrolls"));
        scrolls.sort(Comparator.comparingDouble(ItemSpec::chance));
        artifactChance = cfg.getDouble("single-use-artifacts.chance", 5);
        artifacts = cfg.getStringList("single-use-artifacts.items");
        extras = ItemSpec.parseList(cfg.getList("extras"), log, "ruins.yml lucky.extras");
    }

    List<ItemStack> roll() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<ItemStack> out = new ArrayList<>();
        for (Category c : common) {
            if (rnd.nextDouble(100) < c.chance()) add(out, build(c.entries().get(rnd.nextInt(c.entries().size()))));
        }
        for (Category c : rare) {
            if (rnd.nextDouble(100) < c.chance()) {
                add(out, build(c.entries().get(rnd.nextInt(c.entries().size()))));
                break;
            }
        }
        for (ItemSpec s : scrolls) {
            if (s.roll()) {
                add(out, build(s));
                break;
            }
        }
        if (!artifacts.isEmpty() && rnd.nextDouble(100) < artifactChance) {
            add(out, ItemSpec.create(artifacts.get(rnd.nextInt(artifacts.size())), 1));
        }
        for (ItemSpec s : extras) if (s.roll()) add(out, build(s));
        return out;
    }

    private static ItemStack build(ItemSpec spec) {
        Object choices = spec.extra().get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            String pick = String.valueOf(list.get(ThreadLocalRandom.current().nextInt(list.size())));
            ItemSpec chosen = new ItemSpec(pick, spec.chance(), spec.min(), spec.max(), spec.enchants(), spec.potion(),
                    spec.name(), spec.unbreakable(), spec.extra());
            return chosen.build();
        }
        return spec.build();
    }

    private static void add(List<ItemStack> out, ItemStack s) {
        if (s != null && s.getAmount() > 0) out.add(s);
    }
}
