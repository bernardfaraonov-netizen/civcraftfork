package com.civcraft.item;

import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.item.def.ItemDef;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@code /recipes} ({@code /rb}) and {@code /civadmin item give|list|info}. */
final class ItemCommands {

    private static final int MAX_GIVE = 64 * 36;

    private final ItemModule items;
    private final Messages messages;

    ItemCommands(ItemModule items, Messages messages) {
        this.items = items;
        this.messages = messages;
    }

    void registerPlayerCommands(Commands registrar) {
        LiteralArgumentBuilder<CommandSourceStack> recipes = Cmd.literal("recipes")
                .executes(Cmd.player((player, ctx) -> items.openRecipeBook(player)))
                .then(Cmd.arg("query", StringArgumentType.greedyString())
                        .suggests(Cmd.suggest(this::recipeNames))
                        .executes(Cmd.player((player, ctx) ->
                                items.searchRecipes(player, StringArgumentType.getString(ctx, "query")))));
        registrar.register(recipes.build(), messages.plain("items.cmd.recipes"), List.of("rb"));
    }

    private List<String> recipeNames() {
        List<String> names = new ArrayList<>();
        for (var r : items.recipes().all()) {
            if (r.resultId() != null && !names.contains(r.resultId())) names.add(r.resultId());
        }
        return names;
    }

    private List<String> itemIds() {
        List<String> ids = new ArrayList<>();
        for (ItemDef def : items.defs()) ids.add(def.id());
        return ids;
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    LiteralArgumentBuilder<CommandSourceStack> adminNode() {
        return Cmd.literal("item")
                .executes(Cmd.run(ctx -> messages.send(ctx.getSource().getSender(), "items.admin.help")))
                .then(Cmd.literal("give")
                        .then(Cmd.arg("player", StringArgumentType.word()).suggests(Cmd.suggest(ItemCommands::onlineNames))
                                .then(Cmd.arg("id", StringArgumentType.word()).suggests(Cmd.suggest(this::itemIds))
                                        .executes(Cmd.run(ctx -> give(ctx, 1)))
                                        .then(Cmd.arg("amount", IntegerArgumentType.integer(1, MAX_GIVE))
                                                .executes(Cmd.run(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))))
                .then(Cmd.literal("list")
                        .executes(Cmd.run(ctx -> list(ctx.getSource().getSender(), "")))
                        .then(Cmd.arg("filter", StringArgumentType.word())
                                .executes(Cmd.run(ctx -> list(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "filter"))))))
                .then(Cmd.literal("info").executes(Cmd.player((player, ctx) -> info(player))));
    }

    private void give(CommandContext<CommandSourceStack> ctx, int amount) throws CivException {
        String name = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(name);
        CivException.check(target != null, "error.unknown-player", Messages.arg("name", name));
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        CivException.check(items.exists(id), "items.admin.unknown-item", Messages.arg("id", id));
        ItemDef def = items.def(id);
        int maxStack = def != null ? def.stack() : 1;
        int left = amount;
        while (left > 0) {
            int n = Math.min(left, maxStack);
            items.give(target, items.create(id, n));
            left -= n;
        }
        messages.send(ctx.getSource().getSender(), "items.admin.given", Messages.arg("amount", amount),
                Messages.arg("item", items.displayName(id)), Messages.arg("player", target.getName()));
    }

    private void list(CommandSender sender, String filter) {
        String f = filter.toLowerCase(Locale.ROOT);
        List<ItemDef> shown = new ArrayList<>();
        for (ItemDef def : items.defs()) {
            if (f.isEmpty() || def.id().contains(f) || def.category().equals(f)) shown.add(def);
        }
        messages.send(sender, "items.admin.list-header", Messages.arg("count", shown.size()));
        for (ItemDef def : shown) {
            messages.sendRaw(sender, "items.admin.list-entry", Messages.arg("id", def.id()),
                    Messages.arg("name", items.renderer().name(def)));
        }
    }

    private void info(Player player) throws CivException {
        ItemStack hand = player.getInventory().getItemInMainHand();
        String id = ItemData.id(hand);
        CivException.check(id != null || ItemData.soulbound(hand), "items.admin.info-none");
        Map<String, Integer> enchants = ItemData.customEnchants(hand);
        messages.send(player, "items.admin.info", Messages.arg("id", String.valueOf(id)),
                Messages.arg("sharpen", ItemData.sharpen(hand)), Messages.arg("enchants", enchants.toString()),
                Messages.arg("soulbound", items.isSoulbound(hand)));
    }
}
