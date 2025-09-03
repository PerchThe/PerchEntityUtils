package me.perch.entityutils;

import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ResourceWorldNotifier implements Listener {
    private final Main plugin;
    private final Map<UUID, Long> lastNotify = new HashMap<>();

    public ResourceWorldNotifier(Main plugin) {
        this.plugin = plugin;
    }

    private boolean inResourceWorld(Player p) {
        return p.getWorld().getName().equalsIgnoreCase(plugin.resourceWorld());
    }

    private boolean underHours(Player p) {
        int ticks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
        double hours = ticks / 20.0 / 3600.0;
        return hours < plugin.resourceMaxHours();
    }

    private void tellOnce(Player p) {
        if (p.hasPermission(plugin.resourceBypassPerm())) return;
        if (!underHours(p)) return;
        long now = System.currentTimeMillis();
        Long last = lastNotify.get(p.getUniqueId());
        if (last == null || now - last > plugin.resourceCooldownMs()) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.resourceMessage()));
            lastNotify.put(p.getUniqueId(), now);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (inResourceWorld(e.getPlayer())) {
            tellOnce(e.getPlayer());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (inResourceWorld(e.getPlayer())) {
            tellOnce(e.getPlayer());
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (inResourceWorld(e.getPlayer())) {
            tellOnce(e.getPlayer());
        }
    }
}
