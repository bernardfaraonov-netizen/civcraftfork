package com.civcraft.science;

import com.civcraft.CivCraft;
import com.civcraft.effect.EffectProvider;
import com.civcraft.effect.EffectSink;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Op;
import com.civcraft.effect.Scope;
import com.civcraft.effect.Stats;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.structure.StructureApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Effects owned by the science module: tech effects, research-related wonder effects (Great Library,
 * Angkor Wat, World University, Space Shuttle, Parthenon science) and the ultra buffs of wonder pairs
 * (spec 03 §5, §7).
 */
final class ScienceEffects implements EffectProvider {

    private record UltraBuff(String id, List<String> wonders, List<EffectSpec> effects) {
    }

    private final CivCraft civ;
    private final ScienceModule science;
    private final Map<String, List<EffectSpec>> wonderEffects = new LinkedHashMap<>();
    private final List<UltraBuff> ultraBuffs = new ArrayList<>();

    ScienceEffects(CivCraft civ, ScienceModule science) {
        this.civ = civ;
        this.science = science;
        ConfigurationSection we = civ.balance().section("science", "wonder-effects");
        for (String key : we.getKeys(false)) wonderEffects.put(key, EffectSpec.parse(we.getList(key), Scope.CIV));
        ConfigurationSection ub = civ.balance().section("science", "ultra-buffs");
        for (String id : ub.getKeys(false)) {
            ConfigurationSection s = ub.getConfigurationSection(id);
            if (s == null) continue;
            ultraBuffs.add(new UltraBuff(id, s.getStringList("wonders"), EffectSpec.parse(s.getList("effects"), Scope.CIV)));
        }
    }

    @Override
    public void contribute(EffectSink sink) {
        WonderIndex wonders = science.wonders();
        double templeBeakers = civ.balance().getDouble("science", "parthenon-beakers-per-temple-level", 50);
        double minPrice = civ.balance().getDouble("science", "min-beaker-price", 5);
        for (Civilization c : civ.state().civs()) {
            sink.civ(c, new Modifier(Stats.BEAKER_PRICE, Op.MIN, minPrice, Scope.CIV, "science:beaker_price_floor"));
            for (String techId : science.state(c).completed()) {
                TechTree.Tech t = science.tree().get(techId);
                if (t == null) continue;
                for (EffectSpec e : t.effects()) sink.civ(c, e.modifier(1, "tech:" + techId));
            }
            boolean parthenon = false;
            for (Town town : civ.state().towns(c)) {
                Set<String> keys = wonders.keysIn(town);
                if (keys.isEmpty()) continue;
                parthenon |= keys.contains("parthenon");
                for (String key : keys) {
                    List<EffectSpec> effects = wonderEffects.get(key);
                    if (effects == null) continue;
                    for (EffectSpec e : effects) e.apply(sink, c, town, 1, "wonder:" + key);
                }
                for (UltraBuff buff : ultraBuffs) {
                    if (!buff.wonders().isEmpty() && keys.containsAll(buff.wonders())) {
                        for (EffectSpec e : buff.effects()) e.apply(sink, c, town, 1, "ultra:" + buff.id());
                    }
                }
            }
            if (parthenon && templeBeakers > 0) {
                String templeType = wonders.type("temple");
                for (Town town : civ.state().towns(c)) {
                    int level = 0;
                    for (StructureApi.Placed temple : wonders.completed(town, templeType)) level = Math.max(level, temple.level());
                    if (level > 0) {
                        sink.town(town, new Modifier(Stats.BEAKERS, Op.ADD, templeBeakers * level, Scope.TOWN,
                                "wonder:parthenon"));
                    }
                }
            }
        }
    }
}
