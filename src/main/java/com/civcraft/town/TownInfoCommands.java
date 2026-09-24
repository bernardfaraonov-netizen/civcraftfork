package com.civcraft.town;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Durations;
import com.civcraft.economy.BiomeTable;
import com.civcraft.economy.EconomyEngine;
import com.civcraft.economy.EconomyMath;
import com.civcraft.economy.Production;
import com.civcraft.economy.TownValuation;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.StatSheet;
import com.civcraft.effect.Stats;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.resident.CmdKit;
import com.civcraft.resident.Lookup;
import com.civcraft.structure.StructureApi;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Read-only town commands: /t info pages, show, list, top5, survey, happycalc, members, location. */
final class TownInfoCommands {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final List<String> PAGES = List.of("happiness", "growth", "hammers", "beakers", "culture", "upkeep",
            "rates", "structures", "area", "buffs", "help");

    private final CivCraft civ;
    private final TownModule module;

    TownInfoCommands(CivCraft civ, TownModule module) {
        this.civ = civ;
        this.module = module;
    }

    LiteralArgumentBuilder<CommandSourceStack> infoCommand() {
        return lit("info").executes(Cmd.player((p, ctx) -> overview(p, module.selectedTown(p))))
                .then(word("page").suggests(CmdKit.values(this::pageNames))
                        .executes(Cmd.player((p, ctx) -> page(p, module.selectedTown(p), arg(ctx, "page").toLowerCase(Locale.ROOT)))));
    }

    LiteralArgumentBuilder<CommandSourceStack> showCommand() {
        return lit("show").then(word("town").suggests(CmdKit.towns())
                .executes(Cmd.player((p, ctx) -> overview(p, Lookup.town(arg(ctx, "town"))))));
    }

    private List<String> pageNames() {
        Set<String> names = new LinkedHashSet<>(PAGES);
        for (com.civcraft.Module m : civ.modules()) if (m instanceof TownInfoExtension ext) names.addAll(ext.townInfoPages());
        return new ArrayList<>(names);
    }

    /** Whether the viewer may see the treasury and detailed economy of the town. */
    private boolean insider(Player p, Town town) {
        if (p.hasPermission("civcraft.admin")) return true;
        if (town.isOfficial(p.getUniqueId())) return true;
        Civilization c = civ.state().civOf(town);
        return c != null && c.rank(p.getUniqueId()).atLeast(Civilization.Rank.ADVISER);
    }

    void overview(Player p, Town town) {
        Messages m = civ.messages();
        Civilization c = civ.state().civOf(town);
        Production.Figures f = module.production().figures(town);
        String levelName = civ.balance().file("core").getString("town.levels." + town.level() + ".name", String.valueOf(town.level()));
        m.sendRaw(p, "town.info.header", Messages.arg("town", town.name()));
        m.sendRaw(p, "town.info.civ", Messages.arg("civ", c == null ? "-" : c.name()),
                Messages.arg("status", m.plain("town.status." + town.status().name().toLowerCase(Locale.ROOT))),
                Messages.arg("capital", c != null && town.id().equals(c.capitalId()) ? m.plain("town.info.capital-mark") : ""));
        m.sendRaw(p, "town.info.level", Messages.arg("level", town.level()), Messages.arg("name", levelName),
                Messages.arg("culture", civ.culture().level(town)), Messages.number("points", town.culture()));
        m.sendRaw(p, "town.info.residents", Messages.arg("count", town.residents().size()),
                Messages.arg("mayors", names(town.mayors())), Messages.arg("assistants", names(town.assistants())));
        m.sendRaw(p, "town.info.claims", Messages.arg("claims", civ.state().claimCount(town)), Messages.arg("limit", module.claimLimit(town)),
                Messages.arg("slots", module.slots(town)), Messages.money("next", module.nextClaimPrice(town)));
        m.sendRaw(p, "town.info.production", Messages.number("hammers", f.hammers()), Messages.number("beakers", f.beakers()),
                Messages.number("culture", f.culture()), Messages.number("income", f.income()), Messages.number("growth", f.growth()));
        m.sendRaw(p, "town.info.happiness", Messages.number("percent", f.happyPercent()), Messages.arg("state", f.state().name()),
                Messages.number("mult", f.state().multiplier()));
        if (insider(p, town)) {
            m.sendRaw(p, "town.info.treasury", Messages.money("treasury", town.treasury()), Messages.money("upkeep", module.dailyUpkeep(town)));
            if (town.debt() > 0) m.sendRaw(p, "town.info.debt", Messages.money("debt", town.debt()),
                    Messages.arg("since", town.debtSince() == null ? "-" : DATE.format(town.debtSince().atZone(civ.clock().zone()))));
            m.sendRaw(p, "town.info.taxes", Messages.money("flat", town.flatTax()), Messages.arg("rate", Format.percent(town.taxRate())));
        }
        m.sendRaw(p, "town.info.founded", Messages.arg("date", DATE.format(town.founded().atZone(civ.clock().zone()))));
        if (town.disbanding()) m.sendRaw(p, "town.info.burning");
        if (town.convertingHammers()) m.sendRaw(p, "town.info.chammers",
                Messages.arg("time", Durations.format(Duration.between(java.time.Instant.now(), town.chammersUntil()))));
        String motd = module.data(town).motd();
        if (motd != null) m.sendRaw(p, "town.info.motd", Messages.arg("text", motd));
    }

    private String names(Set<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            Resident r = civ.state().resident(id);
            if (r != null) names.add(r.name());
        }
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    private void page(Player p, Town town, String page) throws CivException {
        Messages m = civ.messages();
        StatSheet sheet = civ.stats().town(town);
        Production.Figures f = module.production().figures(town);
        switch (page) {
            case "help" -> {
                for (Component line : m.lines("town.info.help")) p.sendMessage(line);
            }
            case "happiness" -> {
                m.sendRaw(p, "town.info.page.happiness", Messages.number("h", f.happiness()), Messages.number("u", f.unhappiness()),
                        Messages.number("percent", f.happyPercent()), Messages.arg("state", f.state().name()),
                        Messages.number("mult", f.state().multiplier()));
                for (Production.Line l : f.happinessLines()) line(p, "+", l.source(), l.value());
                for (Production.Line l : f.unhappinessLines()) line(p, "-", l.source(), l.value());
                if (f.distance() > 0) m.sendRaw(p, "town.info.page.distance", Messages.number("distance", f.distance()),
                        Messages.arg("touch", m.plain(f.touchesCapital() ? "town.info.page.touching" : "town.info.page.not-touching")));
            }
            case "growth" -> breakdown(p, sheet, Stats.GROWTH, f.growth());
            case "hammers" -> breakdown(p, sheet, Stats.HAMMERS, f.hammers());
            case "beakers" -> breakdown(p, sheet, Stats.BEAKERS, f.beakers());
            case "culture" -> culture(p, town, f);
            case "upkeep" -> upkeep(p, town);
            case "rates" -> rates(p, town, sheet, f);
            case "structures" -> structures(p, town);
            case "area" -> area(p, town);
            case "buffs" -> buffs(p, sheet);
            default -> {
                for (com.civcraft.Module mod : civ.modules()) {
                    if (!(mod instanceof TownInfoExtension ext)) continue;
                    List<Component> lines = ext.townInfo(page, p, town);
                    if (lines != null) {
                        lines.forEach(p::sendMessage);
                        return;
                    }
                }
                throw new CivException("town.info.unknown-page", Messages.arg("page", page));
            }
        }
    }

    private void line(Player p, String sign, String source, double value) {
        String key = "town.source." + source.replace(':', '.');
        String name = civ.messages().has(key) ? civ.messages().plain(key) : source;
        civ.messages().sendRaw(p, "town.info.page.line", Messages.arg("sign", sign), Messages.arg("source", name), Messages.number("value", value));
    }

    private void breakdown(Player p, StatSheet sheet, String stat, double total) {
        Messages m = civ.messages();
        m.sendRaw(p, "town.info.page.stat-header", Messages.arg("stat", m.plain("town.stat." + stat)), Messages.number("total", total));
        for (Modifier mod : sheet.breakdown(stat)) {
            String value = switch (mod.op()) {
                case ADD -> (mod.value() >= 0 ? "+" : "") + Format.number(mod.value());
                case PERCENT -> Format.signedPercent(mod.value());
                case MULTIPLY -> "×" + Format.number(mod.value());
                case MIN -> "≥" + Format.number(mod.value());
                case MAX -> "≤" + Format.number(mod.value());
            };
            String key = "town.source." + mod.source().replace(':', '.');
            m.sendRaw(p, "town.info.page.modifier", Messages.arg("source", m.has(key) ? m.plain(key) : mod.source()),
                    Messages.arg("value", value));
        }
    }

    private void culture(Player p, Town town, Production.Figures f) {
        Messages m = civ.messages();
        int level = civ.culture().level(town);
        m.sendRaw(p, "town.info.page.culture", Messages.number("points", town.culture()), Messages.arg("level", level),
                Messages.number("rate", f.culture()), Messages.arg("radius", civ.culture().radius(town)),
                Messages.arg("chunks", civ.culture().chunks(town).size()));
        if (level < civ.culture().maxLevel()) {
            double need = civ.culture().required(town, level + 1) - town.culture();
            String eta = f.culture() <= 0 ? "∞" : Durations.format(Duration.ofSeconds((long) (need / f.culture() * 3600)));
            m.sendRaw(p, "town.info.page.culture-next", Messages.arg("level", level + 1), Messages.number("need", Math.max(0, need)),
                    Messages.arg("eta", eta));
        }
    }

    private void upkeep(Player p, Town town) {
        Messages m = civ.messages();
        EconomyEngine.UpkeepBreakdown u = module.economy().upkeep(town);
        m.sendRaw(p, "town.info.page.upkeep-level", Messages.money("amount", u.level()), Messages.arg("level", town.level()));
        for (Map.Entry<String, Long> e : u.structures().entrySet()) {
            m.sendRaw(p, "town.info.page.upkeep-structure", Messages.arg("name", e.getKey()), Messages.money("amount", e.getValue()));
        }
        m.sendRaw(p, "town.info.page.upkeep-mults", Messages.number("gov", u.government()), Messages.number("war", u.war()),
                Messages.arg("reduction", Format.percent(u.warReduction())), Messages.arg("towns", u.towns()),
                Messages.arg("townfactor", Format.percent(civ.balance().getDouble("core", "town.town-count-upkeep-percent", 0.03)
                        * Math.max(0, u.towns() - 1))));
        m.sendRaw(p, "town.info.page.upkeep-total", Messages.money("amount", u.total()), Messages.money("debt", town.debt()));
    }

    private void rates(Player p, Town town, StatSheet sheet, Production.Figures f) {
        Messages m = civ.messages();
        m.sendRaw(p, "town.info.page.rates", Messages.number("happy", f.state().multiplier()),
                Messages.number("hammers", rate(sheet, Stats.HAMMERS)), Messages.number("beakers", rate(sheet, Stats.BEAKERS)),
                Messages.number("culture", rate(sheet, Stats.CULTURE)), Messages.number("growth", rate(sheet, Stats.GROWTH)),
                Messages.number("upkeep", rate(sheet, Stats.UPKEEP)), Messages.number("cottage", rate(sheet, Stats.COTTAGE)),
                Messages.number("trade", rate(sheet, "trade")),
                Messages.arg("captured", town.isCaptured() ? Format.number(civ.balance().getDouble("economy", "captured-multiplier", 0.5)) : "1"));
    }

    /** Combined multiplier of a stat: (1 + ΣPERCENT) × ΠMULTIPLY. */
    private static double rate(StatSheet sheet, String stat) {
        double percent = 0;
        double mult = 1;
        for (Modifier mod : sheet.breakdown(stat)) {
            if (mod.op() == com.civcraft.effect.Op.PERCENT) percent += mod.value();
            else if (mod.op() == com.civcraft.effect.Op.MULTIPLY) mult *= mod.value();
        }
        return (1 + percent) * mult;
    }

    private void structures(Player p, Town town) throws CivException {
        StructureApi api = civ.apiOrNull(StructureApi.class);
        if (api == null) throw new CivException("town.info.no-structures-module");
        Messages m = civ.messages();
        List<? extends StructureApi.Placed> list = api.of(town);
        m.sendRaw(p, "town.info.page.structures-header", Messages.arg("count", list.size()));
        for (StructureApi.Placed s : list) {
            BlockPos c = s.center();
            m.sendRaw(p, s.complete() ? "town.info.page.structure" : "town.info.page.structure-building",
                    Messages.arg("type", s.type()), Messages.arg("level", s.level()),
                    Messages.arg("progress", Format.percent(s.progress())), Messages.arg("x", c.x()), Messages.arg("y", c.y()),
                    Messages.arg("z", c.z()));
        }
    }

    private void area(Player p, Town town) {
        Messages m = civ.messages();
        EconomyEngine eco = module.economy();
        BiomeTable.Values sum = BiomeTable.Values.ZERO;
        Map<String, Integer> counts = new LinkedHashMap<>();
        int unknown = 0;
        for (ChunkKey chunk : civ.culture().chunks(town)) {
            String b = eco.biomeCache().biome(chunk);
            if (b == null) {
                unknown++;
                continue;
            }
            sum = sum.plus(eco.biomes().values(b));
            counts.merge(b.replace("minecraft:", ""), 1, Integer::sum);
        }
        m.sendRaw(p, "town.info.page.area", Messages.arg("chunks", civ.culture().chunks(town).size()),
                Messages.number("hammers", sum.hammers()), Messages.number("growth", sum.growth()),
                Messages.number("happiness", sum.happiness()), Messages.number("beakers", sum.beakers()));
        List<String> parts = new ArrayList<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> parts.add(e.getKey() + " ×" + e.getValue()));
        if (!parts.isEmpty()) m.sendRaw(p, "town.info.page.area-biomes", Messages.arg("list", String.join(", ", parts)));
        if (unknown > 0) m.sendRaw(p, "town.info.page.area-scanning", Messages.arg("count", unknown));
    }

    private void buffs(Player p, StatSheet sheet) {
        Messages m = civ.messages();
        m.sendRaw(p, "town.info.page.buffs-header");
        Map<String, List<String>> bySource = new LinkedHashMap<>();
        for (Map.Entry<String, List<Modifier>> e : sheet.all().entrySet()) {
            for (Modifier mod : e.getValue()) {
                String v = switch (mod.op()) {
                    case ADD -> (mod.value() >= 0 ? "+" : "") + Format.number(mod.value());
                    case PERCENT -> Format.signedPercent(mod.value());
                    case MULTIPLY -> "×" + Format.number(mod.value());
                    case MIN -> "≥" + Format.number(mod.value());
                    case MAX -> "≤" + Format.number(mod.value());
                };
                bySource.computeIfAbsent(mod.source(), k -> new ArrayList<>()).add(e.getKey() + " " + v);
            }
        }
        for (Map.Entry<String, List<String>> e : bySource.entrySet()) {
            String key = "town.source." + e.getKey().replace(':', '.');
            m.sendRaw(p, "town.info.page.buff", Messages.arg("source", m.has(key) ? m.plain(key) : e.getKey()),
                    Messages.arg("list", String.join(", ", e.getValue())));
        }
    }

    void list(CommandSender sender) {
        List<Town> towns = new ArrayList<>(civ.state().towns());
        towns.sort(Comparator.comparing(Town::name, String.CASE_INSENSITIVE_ORDER));
        List<String> names = new ArrayList<>();
        for (Town t : towns) {
            Civilization c = civ.state().civOf(t);
            names.add(t.name() + (c == null || c.tag() == null ? "" : " [" + c.tag() + "]"));
        }
        civ.messages().send(sender, "town.list", Messages.arg("count", towns.size()), Messages.arg("list", String.join(", ", names)));
    }

    /** Town score (spec §5.3 tops): structures (via TownValuation) + residents, claims, culture chunks, treasury. */
    double score(Town town) {
        double score = 0;
        for (com.civcraft.Module m : civ.modules()) if (m instanceof TownValuation v) score += v.structuresScore(town);
        var s = civ.balance().section("town", "score");
        score += s.getDouble("per-resident", 5000) * town.residents().size();
        score += s.getDouble("per-claim", 200) * civ.state().claimCount(town);
        score += s.getDouble("per-culture-chunk", 100) * civ.culture().chunks(town).size();
        score += town.treasury() / 100.0 / Math.max(1, s.getDouble("coins-per-point", 5));
        return score;
    }

    void top(CommandSender sender, int n) {
        List<Town> towns = new ArrayList<>(civ.state().towns());
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Town t : towns) scores.put(t.id(), score(t));
        towns.sort(Comparator.comparingDouble((Town t) -> scores.get(t.id())).reversed());
        civ.messages().send(sender, "town.top.header");
        for (int i = 0; i < Math.min(n, towns.size()); i++) {
            Town t = towns.get(i);
            civ.messages().sendRaw(sender, "town.top.entry", Messages.arg("place", i + 1), Messages.arg("town", t.name()),
                    Messages.number("score", Math.floor(scores.get(t.id()))));
        }
    }

    void survey(Player p, int level) {
        Messages m = civ.messages();
        EconomyEngine eco = module.economy();
        BlockPos center = BlockPos.of(p.getLocation());
        List<ChunkKey> chunks = civ.culture().preview(center, level);
        BiomeTable.Values sum = BiomeTable.Values.ZERO;
        int unknown = 0;
        for (ChunkKey chunk : chunks) {
            String b = eco.biomeCache().biome(chunk);
            if (b == null) unknown++;
            else sum = sum.plus(eco.biomes().values(b));
        }
        m.sendRaw(p, "town.survey.header", Messages.arg("level", level), Messages.arg("chunks", chunks.size()));
        m.sendRaw(p, "town.info.page.area", Messages.arg("chunks", chunks.size()), Messages.number("hammers", sum.hammers()),
                Messages.number("growth", sum.growth()), Messages.number("happiness", sum.happiness()), Messages.number("beakers", sum.beakers()));
        if (unknown > 0) m.sendRaw(p, "town.survey.scanning", Messages.arg("count", unknown));
        double nearest = Double.MAX_VALUE;
        Town near = null;
        for (Town t : civ.state().towns()) {
            if (t.center() == null || !Objects.equals(t.center().world(), center.world())) continue;
            double d = Production.xzDistance(t.center(), center);
            if (d < nearest) {
                nearest = d;
                near = t;
            }
        }
        if (near != null) m.sendRaw(p, "town.survey.nearest", Messages.arg("town", near.name()), Messages.number("distance", nearest),
                Messages.number("min", civ.balance().getDouble("core", "town.settler-min-distance", 150)));
    }

    void happycalc(CommandSender sender, String hText, String uText) throws CivException {
        double h;
        double u;
        try {
            h = Double.parseDouble(hText.replace(',', '.'));
            u = Double.parseDouble(uText.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new CivException("error.invalid-number", Messages.arg("min", 0), Messages.arg("max", 2000));
        }
        double max = civ.balance().getDouble("town", "happycalc-max", 2000);
        if (!Double.isFinite(h) || !Double.isFinite(u) || h < 0 || u < 0 || h > max || u > max) {
            throw new CivException("error.invalid-number", Messages.arg("min", 0), Messages.arg("max", Format.number(max)));
        }
        double percent = EconomyMath.happyPercent(h, u);
        Production.State state = module.production().stateFor(percent);
        civ.messages().send(sender, "town.happycalc", Messages.number("h", h), Messages.number("u", u),
                Messages.number("percent", percent), Messages.arg("state", state.name()), Messages.number("mult", state.multiplier()));
    }

    void members(Player p, String townName) throws CivException {
        Town town;
        if (townName == null) {
            town = civ.state().townOf(p);
            if (town == null) throw new CivException("error.not-in-town");
        } else {
            town = Lookup.town(townName);
            Town own = civ.state().townOf(p);
            Civilization c = civ.state().civOf(town);
            boolean ownTown = own != null && own.id().equals(town.id());
            if (!ownTown && (c == null || !c.isLeader(p.getUniqueId())) && !p.hasPermission("civcraft.admin")) {
                throw new CivException("town.members.denied");
            }
        }
        Component list = Component.empty();
        int count = 0;
        for (UUID id : town.residents()) {
            Resident r = civ.state().resident(id);
            if (r == null) continue;
            boolean online = Bukkit.getPlayer(id) != null;
            if (count++ > 0) list = list.append(Component.text(", "));
            list = list.append(civ.messages().component(online ? "town.members.online" : "town.members.offline",
                    Messages.arg("name", r.name())));
        }
        civ.messages().send(p, "town.members.list", Messages.arg("town", town.name()), Messages.arg("count", count));
        p.sendMessage(list);
    }

    void location(Player p) throws CivException {
        Town town = module.selectedTown(p);
        if (town.center() == null) throw new CivException("town.location.none");
        BlockPos c = town.center();
        civ.messages().send(p, "town.location", Messages.arg("town", town.name()), Messages.arg("world", c.world()),
                Messages.arg("x", c.x()), Messages.arg("y", c.y()), Messages.arg("z", c.z()));
    }

    void warnings(Player p) throws CivException {
        Town town = module.selectedTown(p);
        civ.messages().send(p, "town.warning.show", Messages.arg("town", town.name()), Messages.arg("count", town.warnings()),
                Messages.arg("limit", civ.balance().getInt("core", "town.warnings-to-disband", 3)));
    }

    void motd(Player p) throws CivException {
        Town town = module.selectedTown(p);
        String motd = module.data(town).motd();
        if (motd == null) throw new CivException("town.motd.none");
        civ.messages().send(p, "town.info.motd", Messages.arg("text", motd));
    }
}
