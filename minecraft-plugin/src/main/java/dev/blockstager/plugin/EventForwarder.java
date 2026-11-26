package dev.blockstager.plugin;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.Locale;

/**
 * Escucha eventos públicos de Bukkit/Paper API y los reenvía a Blockstager Studio
 * como mensajes {@code minecraft.event} y {@code skin.set}.
 */
public final class EventForwarder implements Listener {

    private final BlockstagerPlugin plugin;
    private volatile String targetPlayer;
    private volatile boolean syncSkinOnJoin;

    public EventForwarder(BlockstagerPlugin plugin, String targetPlayer, boolean syncSkinOnJoin) {
        this.plugin = plugin;
        this.targetPlayer = normalizeTarget(targetPlayer);
        this.syncSkinOnJoin = syncSkinOnJoin;
    }

    public void updateSettings(String targetPlayer, boolean syncSkinOnJoin) {
        this.targetPlayer = normalizeTarget(targetPlayer);
        this.syncSkinOnJoin = syncSkinOnJoin;
    }

    private static String normalizeTarget(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean matchesTarget(Player player) {
        String filter = this.targetPlayer;
        if (filter.isEmpty()) {
            return true;
        }
        return player.getName().equalsIgnoreCase(filter);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!matchesTarget(player)) {
            return;
        }
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            return;
        }

        PlayerTextures textures = player.getPlayerProfile().getTextures();
        URL skinUrlObj = textures.getSkin();
        String skinUrl = skinUrlObj != null ? skinUrlObj.toString() : null;
        String skinModel = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM ? "slim" : "classic";

        JsonObject data = new JsonObject();
        if (skinUrl != null) {
            data.addProperty("skinUrl", skinUrl);
        }
        data.addProperty("skinModel", skinModel);
        bridge.sendMinecraftEvent("player.join", player.getName(), data);

        if (syncSkinOnJoin && skinUrl != null) {
            bridge.sendSkinSet(player.getName(), skinUrl, skinModel);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!matchesTarget(player)) {
            return;
        }
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            return;
        }

        double finalHealth = Math.max(0.0, player.getHealth() - event.getFinalDamage());
        JsonObject data = new JsonObject();
        data.addProperty("damage", event.getDamage());
        data.addProperty("finalDamage", event.getFinalDamage());
        data.addProperty("cause", event.getCause().name().toLowerCase(Locale.ROOT));
        data.addProperty("finalHealth", finalHealth);
        bridge.sendMinecraftEvent("player.damage", player.getName(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!matchesTarget(player)) {
            return;
        }
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            return;
        }

        EntityDamageEvent lastDamage = player.getLastDamageCause();
        String cause = lastDamage != null
                ? lastDamage.getCause().name().toLowerCase(Locale.ROOT)
                : "unknown";

        JsonObject data = new JsonObject();
        data.addProperty("cause", cause);
        data.addProperty("world", player.getWorld().getName());
        bridge.sendMinecraftEvent("player.death", player.getName(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAdvancementDone(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!matchesTarget(player)) {
            return;
        }
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("key", event.getAdvancement().getKey().toString());
        bridge.sendMinecraftEvent("player.advancement", player.getName(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!matchesTarget(player)) {
            return;
        }
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            return;
        }

        EquipmentSlot hand = event.getHand();
        String handName = hand != null ? hand.name().toLowerCase(Locale.ROOT) : "unknown";
        JsonObject data = new JsonObject();
        data.addProperty("hand", handName);
        bridge.sendMinecraftEvent("player.totem_pop", player.getName(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!matchesTarget(player)) {
            return;
        }
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("material", event.getItem().getType().name());
        bridge.sendMinecraftEvent("player.item_consume", player.getName(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!matchesTarget(player)) {
            return;
        }
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("fromWorld", event.getFrom().getName());
        data.addProperty("toWorld", player.getWorld().getName());
        data.addProperty("toEnvironment", player.getWorld().getEnvironment().name());
        bridge.sendMinecraftEvent("player.dimension_change", player.getName(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!matchesTarget(player)) {
            return;
        }
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            return;
        }

        int slot = event.getNewSlot();
        ItemStack held = player.getInventory().getItem(slot);
        boolean isEmpty = held == null || held.getType().isAir();
        String material = isEmpty ? "AIR" : held.getType().name();
        String itemKey = isEmpty ? "minecraft:air" : held.getType().getKey().toString();

        JsonObject data = new JsonObject();
        data.addProperty("slot", slot);
        data.addProperty("material", material);
        data.addProperty("item", material);
        data.addProperty("itemKey", itemKey);
        bridge.sendMinecraftEvent("player.held_item_change", player.getName(), data);
    }
}
