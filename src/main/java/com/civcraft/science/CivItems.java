package com.civcraft.science;

import com.civcraft.CivCraft;
import com.civcraft.item.ItemApi;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Bridge to custom items for the science family of modules (artifacts, prophets, scrolls, space
 * components, mercury). When the item module ({@link ItemApi}) knows an id it creates the stack and
 * dispatches right clicks; otherwise stacks are built here and tagged with the same PDC key
 * {@code civcraft:item}, so both sides always recognise each other's items.
 */
public final class CivItems implements Listener {

    public static final NamespacedKey ITEM_KEY = new NamespacedKey("civcraft", "item");
    /** Marks a one-shot copy (artifacts found in ruins). */
    public static final NamespacedKey SINGLE_USE_KEY = new NamespacedKey("civcraft", "single_use");

    private record Fallback(Material material, Component name, List<Component> lore, int maxStack) {
    }

    private static CivItems instance;

    private final CivCraft civ;
    private final Map<String, Fallback> fallbacks = new HashMap<>();
    private final Map<String, BiConsumer<Player, PlayerInteractEvent>> localHandlers = new HashMap<>();
    private boolean listening;

    private CivItems(CivCraft civ) {
        this.civ = civ;
    }

    /** Shared instance for the current plugin lifecycle. */
    public static synchronized CivItems get(CivCraft civ) {
        if (instance == null || instance.civ != civ) instance = new CivItems(civ);
        return instance;
    }

    /** Describes how to build the item when the item module does not provide it. */
    public void define(String id, Material material, Component name, List<Component> lore, int maxStack) {
        fallbacks.put(id, new Fallback(material, name, lore, maxStack));
    }

    public boolean known(String id) {
        ItemApi api = civ.apiOrNull(ItemApi.class);
        return fallbacks.containsKey(id) || (api != null && api.exists(id));
    }

    public ItemStack create(String id, int amount) {
        ItemApi api = civ.apiOrNull(ItemApi.class);
        if (api != null && api.exists(id)) return api.create(id, amount);
        Fallback f = fallbacks.get(id);
        if (f == null) throw new IllegalArgumentException("Unknown item " + id);
        ItemStack stack = ItemStack.of(f.material(), Math.max(1, amount));
        stack.setData(DataComponentTypes.ITEM_NAME, f.name());
        if (!f.lore().isEmpty()) {
            stack.setData(DataComponentTypes.LORE, ItemLore.lore(f.lore().stream()
                    .map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList()));
        }
        if (f.maxStack() > 0 && f.maxStack() <= 99) stack.setData(DataComponentTypes.MAX_STACK_SIZE, f.maxStack());
        stack.editPersistentDataContainer(pdc -> pdc.set(ITEM_KEY, PersistentDataType.STRING, id));
        return stack;
    }

    /** Custom item id of the stack or null (reads the shared PDC key directly). */
    public static String id(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return stack.getPersistentDataContainer().get(ITEM_KEY, PersistentDataType.STRING);
    }

    public static boolean isSingleUse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Byte b = stack.getPersistentDataContainer().get(SINGLE_USE_KEY, PersistentDataType.BYTE);
        return b != null && b != 0;
    }

    public static void markSingleUse(ItemStack stack) {
        stack.editPersistentDataContainer(pdc -> pdc.set(SINGLE_USE_KEY, PersistentDataType.BYTE, (byte) 1));
    }

    /** Gives items to the player, dropping what does not fit. */
    public void give(Player player, ItemStack stack) {
        ItemApi api = civ.apiOrNull(ItemApi.class);
        if (api != null) {
            api.give(player, stack);
            return;
        }
        player.getInventory().addItem(stack).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }

    /**
     * Registers a right-click handler. Uses the item module when it is installed (it cancels the event
     * and dispatches by id); otherwise this bridge listens itself.
     */
    public void onUse(String id, BiConsumer<Player, PlayerInteractEvent> handler) {
        ItemApi api = civ.apiOrNull(ItemApi.class);
        if (api != null) {
            try {
                api.onUse(id, handler);
                return;
            } catch (RuntimeException e) {
                civ.logger().log(Level.WARNING, "Item module refused use handler for " + id + "; handling locally", e);
            }
        }
        localHandlers.put(id, handler);
        if (!listening) {
            listening = true;
            civ.listen(this);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String id = id(event.getItem());
        if (id == null) return;
        BiConsumer<Player, PlayerInteractEvent> handler = localHandlers.get(id);
        if (handler == null) return;
        event.setCancelled(true);
        handler.accept(event.getPlayer(), event);
    }
}
