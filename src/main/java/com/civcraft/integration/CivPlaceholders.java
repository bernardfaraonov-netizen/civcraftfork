package com.civcraft.integration;

import com.civcraft.CivCraft;
import com.civcraft.core.util.Money;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion {@code %civcraft_<key>%}: balance, town, town_level, culture, civ, civ_tag,
 * civ_government, rank, debt, camp. Values are read on the calling thread; PlaceholderAPI calls from
 * the main thread for scoreboards/chat, and the fields read are simple immutable snapshots.
 */
final class CivPlaceholders extends PlaceholderExpansion {

    private final CivCraft civ;

    CivPlaceholders(CivCraft civ) {
        this.civ = civ;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "civcraft";
    }

    @Override
    public @NotNull String getAuthor() {
        return "CivCraft";
    }

    @Override
    public @NotNull String getVersion() {
        return civ.plugin().getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        Resident r = civ.state().resident(player.getUniqueId());
        if (r == null) return "";
        Town town = civ.state().townOf(r);
        Civilization c = civ.state().civOf(town);
        return switch (params.toLowerCase()) {
            case "balance" -> Money.format(r.balance());
            case "debt" -> Money.format(r.debt());
            case "town" -> town == null ? "" : town.name();
            case "town_level" -> town == null ? "" : String.valueOf(town.level());
            case "culture" -> town == null ? "" : String.valueOf((long) town.culture());
            case "civ" -> c == null ? "" : c.name();
            case "civ_tag" -> c == null || c.tag() == null ? "" : c.tag();
            case "civ_government" -> c == null ? "" : c.government();
            case "rank" -> c == null ? "" : c.rank(r.uuid()).name().toLowerCase();
            case "camp" -> {
                var camp = civ.state().camp(r.campId());
                yield camp == null ? "" : camp.name();
            }
            default -> null;
        };
    }
}
