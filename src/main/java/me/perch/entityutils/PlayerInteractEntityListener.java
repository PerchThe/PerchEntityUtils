package me.perch.entityutils;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;

import java.util.HashMap;
import java.util.UUID;

public class PlayerInteractEntityListener implements Listener {

    private final Main plugin;
    private final HashMap<UUID, Long> lastInteract = new HashMap<>();

    public PlayerInteractEntityListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        FileConfiguration config = plugin.getConfig();
        Material mainHandType = event.getPlayer().getInventory().getItemInMainHand().getType();
        Material triggerItem = Material.valueOf(config.getString("trigger_item", "CLOCK"));
        Material silenceItem = Material.valueOf(config.getString("silence_item", "BELL"));
        Material statueItem = Material.valueOf(config.getString("statue_item", "ARMOR_STAND"));

        if ((mainHandType == triggerItem || mainHandType == silenceItem || mainHandType == statueItem)
                && event.getAction() == Action.RIGHT_CLICK_AIR) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (lastInteract.containsKey(uuid)) {
            long last = lastInteract.get(uuid);
            if (now - last < 250) return;
        }
        lastInteract.put(uuid, now);

        FileConfiguration config = plugin.getConfig();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        Material triggerItem = Material.valueOf(config.getString("trigger_item", "CLOCK"));
        Material silenceItem = Material.valueOf(config.getString("silence_item", "BELL"));
        Material statueItem = Material.valueOf(config.getString("statue_item", "ARMOR_STAND"));

        // AGE LOCK / UNLOCK
        if (itemInHand.getType().equals(triggerItem)) {
            if (!(event.getRightClicked() instanceof Breedable breedable)) return;
            if (breedable.isAdult()) return;

            Location loc = event.getRightClicked().getLocation();
            boolean denied = false;

            if (GriefPrevention.instance != null) {
                Claim claim = GriefPrevention.instance.dataStore.getClaimAt(loc, false, null);
                if (claim != null) {
                    String denyReason = claim.allowBuild(player, loc.getBlock().getType());
                    if (denyReason != null) denied = true;
                }
            }

            if (!denied && event.getRightClicked() instanceof Tameable tameable && tameable.isTamed()) {
                AnimalTamer owner = tameable.getOwner();
                if (owner == null || !owner.getUniqueId().equals(player.getUniqueId())) denied = true;
            }

            if (denied) {
                sendDenyFeedback(player, config, "permission_message", "permission_sound",
                        (float) config.getDouble("sound_volume", 0.2f),
                        (float) config.getDouble("sound_pitch", 1.0f));
                event.setCancelled(true);
                return;
            }

            float volume = (float) config.getDouble("sound_volume", 0.2f);
            float pitch = (float) config.getDouble("sound_pitch", 1.0f);

            if (breedable.getAgeLock()) {
                breedable.setAgeLock(false);
                sendFeedback(player, config, "unlock_message", "unlock_particle", "unlock_sound", loc, volume, pitch);
            } else {
                breedable.setAgeLock(true);
                sendFeedback(player, config, "success_message", "success_particle", "lock_sound", loc, volume, pitch);
            }
            return;
        }

        // SILENCE / UNSILENCE
        if (itemInHand.getType().equals(silenceItem)) {
            if (!(event.getRightClicked() instanceof LivingEntity livingEntity)) return;

            Location loc = livingEntity.getLocation();
            boolean denied = false;

            if (GriefPrevention.instance != null) {
                Claim claim = GriefPrevention.instance.dataStore.getClaimAt(loc, false, null);
                if (claim != null) {
                    String denyReason = claim.allowBuild(player, loc.getBlock().getType());
                    if (denyReason != null) denied = true;
                }
            }

            if (!denied && livingEntity instanceof Tameable tameable && tameable.isTamed()) {
                AnimalTamer owner = tameable.getOwner();
                if (owner == null || !owner.getUniqueId().equals(player.getUniqueId())) denied = true;
            }

            if (denied) {
                sendDenyFeedback(player, config, "silence_permission_message", "silence_permission_sound",
                        (float) config.getDouble("silence_sound_volume", 0.2f),
                        (float) config.getDouble("silence_sound_pitch", 1.0f));
                event.setCancelled(true);
                return;
            }

            float volume = (float) config.getDouble("silence_sound_volume", 0.2f);
            float pitch = (float) config.getDouble("silence_sound_pitch", 1.0f);

            if (livingEntity.isSilent()) {
                livingEntity.setSilent(false);
                sendFeedback(player, config, "unsilence_message", "unsilence_particle", "unsilence_sound", loc, volume, pitch);
            } else {
                livingEntity.setSilent(true);
                sendFeedback(player, config, "silence_message", "silence_particle", "silence_sound", loc, volume, pitch);
            }
            event.setCancelled(true);
            return;
        }

        // STATUE: FREEZE / UNFREEZE, OR SNEAK-ROTATE
        if (itemInHand.getType().equals(statueItem)) {
            if (!(event.getRightClicked() instanceof LivingEntity living)) return;
            if (living instanceof Player || living.getType() == EntityType.ARMOR_STAND) return;

            // Disallow hostiles/bosses
            if (living instanceof Monster
                    || living.getType() == EntityType.SLIME
                    || living.getType() == EntityType.MAGMA_CUBE
                    || living.getType() == EntityType.WARDEN
                    || living.getType() == EntityType.WITHER
                    || living.getType() == EntityType.ENDER_DRAGON) {
                sendDenyFeedback(player, config, "statue_hostile_message", "statue_permission_sound",
                        (float) config.getDouble("statue_sound_volume", 0.2f),
                        (float) config.getDouble("statue_sound_pitch", 1.0f));
                event.setCancelled(true);
                return;
            }

            // Disallow villagers (use your separate feature instead)
            if (living instanceof Villager) {
                String msgKey = player.isSneaking() ? "villager_statue_sneak_message" : "statue_villager_message";
                sendDenyFeedback(player, config, msgKey, "statue_permission_sound",
                        (float) config.getDouble("statue_sound_volume", 0.2f),
                        (float) config.getDouble("statue_sound_pitch", 1.0f));
                event.setCancelled(true);
                return;
            }

            Location loc = living.getLocation();
            boolean denied = false;

            if (GriefPrevention.instance != null) {
                Claim claim = GriefPrevention.instance.dataStore.getClaimAt(loc, false, null);
                if (claim != null) {
                    String denyReason = claim.allowBuild(player, loc.getBlock().getType());
                    if (denyReason != null) denied = true;
                }
            }

            if (!denied && living instanceof Tameable tameable && tameable.isTamed()) {
                AnimalTamer owner = tameable.getOwner();
                if (owner == null || !owner.getUniqueId().equals(player.getUniqueId())) denied = true;
            }

            if (denied) {
                sendDenyFeedback(player, config, "statue_permission_message", "statue_permission_sound",
                        (float) config.getDouble("statue_sound_volume", 0.2f),
                        (float) config.getDouble("statue_sound_pitch", 1.0f));
                event.setCancelled(true);
                return;
            }

            float volume = (float) config.getDouble("statue_sound_volume", 0.2f);
            float pitch = (float) config.getDouble("statue_sound_pitch", 1.0f);

            // SNEAK = ROTATE
            if (player.isSneaking()) {
                double inc = config.getDouble("statue_rotate_increment_degrees", 22.5d);
                boolean onlyWhenFrozen = config.getBoolean("statue_rotate_only_when_frozen", false);

                boolean ai;
                try {
                    ai = living.hasAI();
                } catch (Throwable t) {
                    ai = true;
                }

                if (!onlyWhenFrozen || !ai) {
                    Location l = living.getLocation();
                    float newYaw = (float) ((l.getYaw() + inc) % 360.0);
                    l.setYaw(newYaw);
                    living.teleport(l);
                    sendFeedback(player, config, "statue_rotate_message", "statue_rotate_particle", "statue_rotate_sound", l, volume, pitch);
                } else {
                    sendDenyFeedback(player, config, "statue_rotate_need_freeze_message", "statue_permission_sound", volume, pitch);
                }
                event.setCancelled(true);
                return;
            }

            // NORMAL CLICK = TOGGLE FREEZE
            boolean ai;
            try {
                ai = living.hasAI();
            } catch (Throwable t) {
                ai = true;
            }

            if (ai) {
                living.setAI(false);
                try { living.setGravity(false); } catch (Throwable ignored) {}
                sendFeedback(player, config, "statue_message", "statue_particle", "statue_sound", loc, volume, pitch);
            } else {
                living.setAI(true);
                try { living.setGravity(true); } catch (Throwable ignored) {}
                sendFeedback(player, config, "unstatue_message", "unstatue_particle", "unstatue_sound", loc, volume, pitch);
            }
            event.setCancelled(true);
        }
    }

    private void sendFeedback(Player player, FileConfiguration config, String messageKey, String particleKey, String soundKey, Location loc, float volume, float pitch) {
        String message = config.getString(messageKey, "");
        String particleName = config.getString(particleKey, "VILLAGER_HAPPY");
        String soundName = config.getString(soundKey, "ENTITY_EXPERIENCE_ORB_PICKUP");
        if (!message.isEmpty()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.translateAlternateColorCodes('&', message)));
        }
        try {
            Particle particle = Particle.valueOf(particleName);
            player.spawnParticle(particle, loc.getX(), loc.getY() + 0.75, loc.getZ(), 5, 0.5, 0.25, 0.5);
        } catch (Exception ignored) {}
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception ignored) {}
    }

    private void sendDenyFeedback(Player player, FileConfiguration config, String messageKey, String soundKey, float volume, float pitch) {
        String message = config.getString(messageKey, "You don't have permission!");
        String soundName = config.getString(soundKey, "BLOCK_NOTE_BLOCK_BASS");
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.translateAlternateColorCodes('&', message)));
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception ignored) {}
    }
}
