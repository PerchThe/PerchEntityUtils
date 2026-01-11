package me.perch.entityutils;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

public class UntameAnimalsListener implements Listener {

    private final Main plugin;

    public UntameAnimalsListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof Tameable tameable)) return;

        if (!tameable.isTamed()) return;

        AnimalTamer owner = tameable.getOwner();
        if (owner == null || !owner.getUniqueId().equals(player.getUniqueId())) return;

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (!isTamingItem(tameable.getType(), itemInHand.getType())) return;

        tameable.setOwner(null);
        tameable.setTamed(false);
        tameable.setTarget(null);

        if (tameable instanceof Sittable sittable) {
            sittable.setSitting(false);
        }

        if (tameable instanceof Wolf wolf) {
            wolf.setAngry(false);
        }

        sendFeedback(player, tameable);
        event.setCancelled(true);
    }

    private boolean isTamingItem(EntityType entityType, Material material) {
        switch (entityType) {
            case WOLF:
                return material == Material.BONE;
            case CAT:
                return material == Material.COD || material == Material.SALMON;
            case PARROT:
                return material == Material.WHEAT_SEEDS || material == Material.PUMPKIN_SEEDS
                        || material == Material.MELON_SEEDS || material == Material.BEETROOT_SEEDS;
            default:
                return false;
        }
    }

    private void sendFeedback(Player player, Tameable animal) {
        Location loc = animal.getLocation();
        String animalName = animal.getType().name().toLowerCase().replace("_", " ");

        String message = ChatColor.GREEN + "You have untamed your " + animalName + ".";
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));

        player.playSound(loc, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        player.spawnParticle(Particle.CLOUD, loc.getX(), loc.getY() + 0.5, loc.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
    }
}