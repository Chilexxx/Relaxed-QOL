package com.relaxedqol.client.util;

import com.relaxedqol.client.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.Set;

public final class EntityUtil {
    public static final int PLAYER_COLOR = 0xFF3B82F6;
    public static final int FRIEND_COLOR = 0xFF22D3EE;
    public static final int MOB_COLOR = 0xFFEF4444;
    public static final int ANIMAL_COLOR = 0xFF22C55E;
    public static final int ITEM_COLOR = 0xFFFACC15;
    public static final int OTHER_COLOR = 0xFFFFFFFF;

    private EntityUtil() {}

    public static boolean isFriend(Entity entity) {
        return entity instanceof Player player && FriendManager.isFriend(player.getName().getString());
    }

    /**
     * Any passive, non-hostile creature: cows, sheep, but also squid, dolphins, bats, villagers, golems and the rest
     * (these are NOT instanceof Animal, which is why squid never showed up before).
     */
    public static boolean isAnimal(Entity entity) {
        return entity instanceof Mob && !(entity instanceof Enemy);
    }

    /** Registry id of an entity's type, e.g. "minecraft:squid". */
    public static String typeId(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? "" : key.toString();
    }

    /** Whether an entity matches the player/mob/animal/item filters used by ESP and Tracers. */
    public static boolean matches(Entity entity, boolean players, boolean mobs, boolean animals, boolean items) {
        return matches(entity, players, mobs, animals, items, null);
    }

    /**
     * Like {@link #matches} but, when {@code animalTypes} is non-empty, only animals of those exact entity types
     * count (so you can pick e.g. only squid and cows for your radar / tracers).
     */
    public static boolean matches(Entity entity, boolean players, boolean mobs, boolean animals, boolean items, Set<String> onlyTypes) {
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player || entity == mc.getCameraEntity() || !entity.isAlive()) return false;
        if (entity instanceof Player) return players;
        // Picked types: when the list is not empty, exactly the picked mobs / animals / items show, whatever
        // the Mobs / Animals / Items switches say. Before, the list only applied to animals and only while the
        // Animals switch was on, so picking e.g. "zombie" or picking with Animals off did nothing at all.
        if (onlyTypes != null && !onlyTypes.isEmpty()) return onlyTypes.contains(typeId(entity));
        if (entity instanceof Enemy) return mobs;
        if (entity instanceof ItemEntity) return items;
        if (isAnimal(entity)) return animals;
        return false;
    }

    /**
     * True when the entity is out in the open: at most {@code margin} blocks below the top solid block of its
     * column (leaves ignored). Entities deep in caves are not. Always true in the Nether (its "top" is the roof).
     */
    public static boolean onSurface(Entity entity, int margin) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return true;
        if (com.relaxedqol.client.util.Dims.ceiling()) return true;
        int x = net.minecraft.util.Mth.floor(entity.getX());
        int z = net.minecraft.util.Mth.floor(entity.getZ());
        int top = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // A column the client has no heightmap for yet reports the world bottom: don't hide anything then
        if (top <= mc.level.getMinY()) return true;
        return entity.getY() >= top - margin;
    }

    /** The same for a block position (x, y, z): at most {@code margin} blocks under the top of its column. */
    public static boolean onSurfaceAt(int x, int y, int z, int margin) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return true;
        if (com.relaxedqol.client.util.Dims.ceiling()) return true;
        int top = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (top <= mc.level.getMinY()) return true;
        return y >= top - margin;
    }

    public static int color(Entity entity) {
        if (entity instanceof Player) return isFriend(entity) ? FRIEND_COLOR : PLAYER_COLOR;
        if (entity instanceof Enemy) return MOB_COLOR;
        if (isAnimal(entity)) return ANIMAL_COLOR;
        if (entity instanceof ItemEntity) return ITEM_COLOR;
        return OTHER_COLOR;
    }

    /** Living, attackable and not a friend. */
    public static boolean isValidTarget(Entity entity, boolean players, boolean mobs, boolean animals) {
        Minecraft mc = Minecraft.getInstance();
        if (!(entity instanceof LivingEntity living) || entity == mc.player) return false;
        if (!living.isAlive() || living.getHealth() <= 0) return false;
        if (entity instanceof Player player) {
            return players && !player.isSpectator() && !player.isCreative() && !isFriend(player);
        }
        if (entity instanceof Enemy) return mobs;
        if (entity instanceof Animal) return animals;
        return false;
    }

    /**
     * A player-entity that is not a real player: no valid Minecraft name (NPC plugins use blank, coloured or
     * random names), no player-list entry at all, or a version-2 UUID (what Citizens and most NPC plugins use;
     * real players have version 4, or 3 on offline-mode servers), or a hidden nametag (scoreboard team set to
     * never show it - how NPC plugins hide the name). Being missing from the VISIBLE tab is not used:
     * tab-layout plugins hide real players' own entries.
     */
    public static boolean isNpc(net.minecraft.world.entity.player.Player player) {
        String name = player.getGameProfile().name();
        if (!com.relaxedqol.client.util.Names.isPlayerName(name)) return true; // Bedrock ".name" players are real
        if (player.getUUID().version() == 2) return true;
        // No visible name: NPC plugins hide the real nametag with a scoreboard team (and float a hologram instead)
        net.minecraft.world.scores.PlayerTeam team = player.getTeam();
        if (team != null) {
            net.minecraft.world.scores.Team.Visibility tag = team.getNameTagVisibility();
            if (tag == net.minecraft.world.scores.Team.Visibility.NEVER) return true;
            Minecraft self = Minecraft.getInstance();
            if (tag == net.minecraft.world.scores.Team.Visibility.HIDE_FOR_OTHER_TEAMS && self.player != null
                && self.player.getTeam() != team) return true;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && mc.getConnection().getPlayerInfo(player.getUUID()) == null;
    }
}
