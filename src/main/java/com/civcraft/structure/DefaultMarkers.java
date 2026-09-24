package com.civcraft.structure;

import com.civcraft.CivCraft;
import com.civcraft.structure.component.MarkerHandler;
import com.civcraft.structure.component.StructureComponent;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Built-in marker handlers: chests, barrels, signs, doors, control blocks, the town hall tech board and item frames.
 * Point markers without a block ({@code /respawn}, {@code /revive}, {@code /warroom}, {@code /towerfire},
 * {@code /tradeoutpost}) only exist as components. Other modules may replace any handler.
 */
final class DefaultMarkers {

    private DefaultMarkers() {
    }

    static void register(StructureModule module, CivCraft civ) {
        module.registerMarkerHandler(new ContainerHandler("chest", Material.CHEST, civ));
        module.registerMarkerHandler(new ContainerHandler("barrel", Material.BARREL, civ));
        module.registerMarkerHandler(new SignHandler("sign", module, civ));
        module.registerMarkerHandler(new SignHandler("gui", module, civ));
        module.registerMarkerHandler(new SignHandler("techname", module, civ));
        module.registerMarkerHandler(new SignHandler("techdata", module, civ));
        module.registerMarkerHandler(new DoorHandler());
        module.registerMarkerHandler(new ControlHandler(module));
        module.registerMarkerHandler(new SimpleBlockHandler("techbar", Material.WHITE_WOOL));
        module.registerMarkerHandler(new ItemFrameHandler(civ));
    }

    private record ContainerHandler(String type, Material material, CivCraft civ) implements MarkerHandler {
        @Override
        public void build(Structure s, StructureComponent c, Block block) {
            if (block.getType() == material || block.getState(false) instanceof Container) return;
            BlockData data = material.createBlockData();
            if (data instanceof Directional d && d.getFaces().contains(c.facing())) d.setFacing(c.facing());
            block.setBlockData(data, false);
            if (block.getState(false) instanceof Container container) {
                container.customName(Component.text(s.typeDef().name()));
                container.update(true, false);
            }
        }
    }

    private record SimpleBlockHandler(String type, Material material) implements MarkerHandler {
        @Override
        public void build(Structure s, StructureComponent c, Block block) {
            if (block.getType().isAir()) block.setType(material, false);
        }
    }

    private static final class SignHandler implements MarkerHandler {
        private final String type;
        private final StructureModule module;
        private final CivCraft civ;

        SignHandler(String type, StructureModule module, CivCraft civ) {
            this.type = type;
            this.module = module;
            this.civ = civ;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public void build(Structure s, StructureComponent c, Block block) {
            if (Tag.ALL_SIGNS.isTagged(block.getType())) return;
            BlockFace facing = c.facing() == null ? BlockFace.SOUTH : c.facing();
            Block behind = block.getRelative(facing.getOppositeFace());
            BlockData data;
            if (behind.getType().isSolid() && isCardinal(facing)) {
                data = Material.OAK_WALL_SIGN.createBlockData();
                if (data instanceof Directional d) d.setFacing(facing);
            } else {
                data = Material.OAK_SIGN.createBlockData();
                if (data instanceof Rotatable r) r.setRotation(isCardinal(facing) ? facing : BlockFace.SOUTH);
            }
            block.setBlockData(data, false);
            BlockState state = block.getState(false);
            if (state instanceof Sign sign) {
                var side = sign.getSide(Side.FRONT);
                side.line(0, civ.messages().parse("<dark_blue>[" + s.typeDef().name() + "]"));
                if (!type.equals("techname") && !type.equals("techdata")) {
                    String arg = c.arg(type).toLowerCase(Locale.ROOT);
                    String value = null;
                    int colon = arg.indexOf(':');
                    if (colon > 0) {
                        value = arg.substring(colon + 1);
                        arg = arg.substring(0, colon);
                    }
                    side.line(1, text("structure.sign." + arg, arg));
                    if (value != null) side.line(2, text("structure.sign-value." + value, value));
                    side.line(3, civ.messages().component("structure.sign.click"));
                }
                sign.setWaxed(true);
                sign.update(true, false);
            }
        }

        @Override
        public boolean interact(Structure s, StructureComponent c, Player player, PlayerEvent event) {
            if (!s.isActive()) {
                civ.messages().actionBar(player, "structure.error.not-active");
                return true;
            }
            return module.behavior(s.type()).openGui(s, player);
        }

        private Component text(String key, String fallback) {
            return civ.messages().has(key) ? civ.messages().component(key) : Component.text(fallback);
        }

        private static boolean isCardinal(BlockFace f) {
            return f == BlockFace.NORTH || f == BlockFace.SOUTH || f == BlockFace.EAST || f == BlockFace.WEST;
        }
    }

    private static final class DoorHandler implements MarkerHandler {
        @Override
        public String type() {
            return "door";
        }

        @Override
        public void build(Structure s, StructureComponent c, Block block) {
            if (Tag.DOORS.isTagged(block.getType())) return;
            Block upper = block.getRelative(BlockFace.UP);
            if (!upper.getType().isAir()) return;
            BlockFace facing = c.facing() == null ? BlockFace.SOUTH : c.facing();
            for (Bisected.Half half : Bisected.Half.values()) {
                BlockData data = Material.SPRUCE_DOOR.createBlockData();
                if (data instanceof Door door) {
                    if (door.getFaces().contains(facing)) door.setFacing(facing);
                    door.setHalf(half);
                }
                (half == Bisected.Half.BOTTOM ? block : upper).setBlockData(data, false);
            }
        }
    }

    private record ControlHandler(StructureModule module) implements MarkerHandler {
        @Override
        public String type() {
            return "control";
        }

        @Override
        public void build(Structure s, StructureComponent c, Block block) {
            Block below = block.getRelative(BlockFace.DOWN);
            if (below.getType().isAir()) below.setType(module.settings().controlBase(), false);
            for (ControlPoint cp : s.controlPoints()) {
                if (cp.pos().equals(c.pos())) {
                    module.controlPoints().showBlock(s, cp);
                    return;
                }
            }
            if (block.getType().isAir()) block.setType(module.settings().controlBlock(), false);
        }
    }

    private static final class ItemFrameHandler implements MarkerHandler {
        private final NamespacedKey key;

        ItemFrameHandler(CivCraft civ) {
            this.key = new NamespacedKey(civ.plugin(), "structure_frame");
        }

        @Override
        public String type() {
            return "itemframe";
        }

        private String tag(Structure s, StructureComponent c) {
            return s.id() + ":" + c.index();
        }

        private ItemFrame find(Structure s, StructureComponent c, Block block) {
            String tag = tag(s, c);
            for (Entity e : block.getWorld().getNearbyEntities(block.getLocation().add(0.5, 0.5, 0.5), 0.8, 0.8, 0.8)) {
                if (e instanceof ItemFrame f && tag.equals(f.getPersistentDataContainer().get(key, PersistentDataType.STRING))) return f;
            }
            return null;
        }

        @Override
        public void build(Structure s, StructureComponent c, Block block) {
            if (find(s, c, block) != null) return;
            BlockFace facing = c.facing() == null ? BlockFace.SOUTH : c.facing();
            if (!block.getRelative(facing.getOppositeFace()).getType().isSolid()) return;
            Location at = block.getLocation();
            try {
                block.getWorld().spawn(at, ItemFrame.class, f -> {
                    f.setFacingDirection(facing, true);
                    f.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag(s, c));
                });
            } catch (IllegalArgumentException ignored) {
                // No room for a frame at this position (template changed); the component still works without it.
            }
        }

        @Override
        public void remove(Structure s, StructureComponent c) {
            Block block = c.block();
            if (block == null) return;
            ItemFrame f = find(s, c, block);
            if (f != null) f.remove();
        }
    }
}
