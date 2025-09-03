package me.perch.entityutils;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.*;

public final class ReverseDropperModule implements Listener, CommandExecutor {

    // === WG flag (optional if registered on real server start) ===
    private static StateFlag REVERSE_DROPPER_FLAG;
    private static final String FLAG_NAME = "perch-reverse-dropper"; // unique

    // ---- Collision tuning (tighter to prevent edge-slips) ----
    private static final double SWEEP_STEP = 0.04;      // denser sweep → fewer misses
    private static final int    MAX_STEPS  = 800;       // safety cap
    private static final double EPS_H      = 0.020;     // horizontal grow (≈2cm)
    private static final double EPS_V      = 0.006;     // vertical grow (keep low to avoid ceilings)
    private static final double LOOK_AHEAD = 0.36;      // look a bit further to catch face-align cases

    // Reflective handle for Paper's precise collision API (Spigot-safe compile)
    private static final Method M_GET_BLOCK_COLLISIONS = resolveGetBlockCollisions();
    private static Method resolveGetBlockCollisions() {
        try {
            // Paper: World#getBlockCollisions(Entity, BoundingBox) → Iterable<BoundingBox>
            return World.class.getMethod("getBlockCollisions", Entity.class, BoundingBox.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void registerFlagOnLoad(JavaPlugin plugin) {
        var registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            StateFlag flag = new StateFlag(FLAG_NAME, false); // default DENY
            registry.register(flag);
            REVERSE_DROPPER_FLAG = flag;
            plugin.getLogger().info("Registered WG flag: " + FLAG_NAME);
        } catch (Throwable ex) {
            Flag<?> existing = registry.get(FLAG_NAME);
            if (existing instanceof StateFlag) {
                REVERSE_DROPPER_FLAG = (StateFlag) existing;
                plugin.getLogger().info("Using existing WG StateFlag: " + FLAG_NAME);
            } else {
                REVERSE_DROPPER_FLAG = null; // use whitelist fallback
                plugin.getLogger().warning("WG flag name conflict on '" + FLAG_NAME + "'. Using whitelist fallback.");
            }
        }
    }

    private final JavaPlugin plugin;

    // fallback region whitelist (used when flag isn’t available/allowed)
    private final Set<String> regionWhitelist = new HashSet<>();

    // region -> start location
    private final Map<String, Location> startByRegion = new HashMap<>();

    public ReverseDropperModule(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("rds") != null) {
            plugin.getCommand("rds").setExecutor(this);
        }
    }

    public void reload() {
        plugin.reloadConfig();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("reverse_dropper");

        regionWhitelist.clear();
        if (root != null) regionWhitelist.addAll(root.getStringList("regions_whitelist"));

        startByRegion.clear();
        ConfigurationSection starts = root != null ? root.getConfigurationSection("starts") : null;
        if (starts != null) {
            for (String regionId : starts.getKeys(false)) {
                Location loc = readLocation(starts.getConfigurationSection(regionId));
                if (loc != null) startByRegion.put(regionId, loc);
            }
        }
    }

    private Location readLocation(ConfigurationSection sec) {
        if (sec == null) return null;
        String world = sec.getString("world");
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(
                w,
                sec.getDouble("x"),
                sec.getDouble("y"),
                sec.getDouble("z"),
                (float) sec.getDouble("yaw"),
                (float) sec.getDouble("pitch")
        );
    }

    private void saveStart(String regionId, Location loc) {
        startByRegion.put(regionId, loc.clone());
        String base = "reverse_dropper.starts." + regionId;
        plugin.getConfig().set(base + ".world", loc.getWorld().getName());
        plugin.getConfig().set(base + ".x", loc.getX());
        plugin.getConfig().set(base + ".y", loc.getY());
        plugin.getConfig().set(base + ".z", loc.getZ());
        plugin.getConfig().set(base + ".yaw", loc.getYaw());
        plugin.getConfig().set(base + ".pitch", loc.getPitch());
        plugin.saveConfig();
    }

    // ===== Listener =====
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        final Location to = e.getTo(), from = e.getFrom();
        if (to == null) return; // catch ALL directions
        if (!isAllowedHere(to)) return;

        final Player p = e.getPlayer();

        try {
            if (collidedDuringMovePrecise(p, from, to)) {
                List<String> regions = regionIdsAt(to);
                String targetRegion = regions.stream().filter(startByRegion::containsKey).findFirst().orElse(null);
                if (targetRegion == null) return;

                Location start = startByRegion.get(targetRegion);
                if (start == null) return;

                p.setVelocity(new Vector(0, 0, 0));
                p.setFallDistance(0f);
                p.teleport(start);
                p.playSound(start, "entity.enderman.teleport", 1f, 1.2f);
                p.spawnParticle(Particle.CLOUD, start.clone().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.01);
            }
        } catch (Throwable ex) {
            // absolutely catch-everything to avoid breaking movement
            plugin.getLogger().warning("[ReverseDropper] Collision check error: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
        }
    }

    /**
     * Sweep the player's actual AABB from -> to, using Paper's real collision shapes if available.
     * - Denser sweep (0.04).
     * - Horizontally grown query box (EPS_H) + small vertical grow (EPS_V) → face/corner contact counts.
     * - Longer look-ahead.
     * - Raytrace fallback for ultra-thin geometry.
     */
    private boolean collidedDuringMovePrecise(Player p, Location from, Location to) {
        final World w = p.getWorld();

        Vector move = to.toVector().subtract(from.toVector());
        double dist = move.length();

        // Player dimensions from current tick
        BoundingBox cur = p.getBoundingBox();
        final double halfX = cur.getWidthX() / 2.0;
        final double halfZ = cur.getWidthZ() / 2.0;
        final double height = cur.getHeight();

        if (dist <= 1.0e-9) {
            // essentially stationary → check occupancy at destination (grown)
            return intersectsCollisions(w, p, boxAt(p, to).expand(EPS_H, EPS_V, EPS_H));
        }

        Vector dir = move.clone().normalize();

        // Dense sweep
        int steps = Math.min(MAX_STEPS, Math.max(1, (int) Math.ceil(dist / SWEEP_STEP)));
        Vector startVec = from.toVector();

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            Vector pos = startVec.clone().add(dir.clone().multiply(dist * t));
            BoundingBox bb = new BoundingBox(
                    pos.getX() - halfX, pos.getY(), pos.getZ() - halfZ,
                    pos.getX() + halfX, pos.getY() + height, pos.getZ() + halfZ
            ).expand(EPS_H, EPS_V, EPS_H);

            if (intersectsCollisions(w, p, bb)) return true;
        }

        // Tiny look-ahead for exact face alignment
        Vector ahead = dir.clone().multiply(LOOK_AHEAD);
        BoundingBox bbAhead = new BoundingBox(
                to.getX() + ahead.getX() - halfX, to.getY(), to.getZ() + ahead.getZ() - halfZ,
                to.getX() + ahead.getX() + halfX, to.getY() + height, to.getZ() + ahead.getZ() + halfZ
        ).expand(EPS_H, EPS_V, EPS_H);
        if (intersectsCollisions(w, p, bbAhead)) return true;

        // Raytrace fallback for ultra-thin shapes if sweep somehow misses
        if (rayHitBetween(w, from, to)) return true;

        return false;
    }

    private BoundingBox boxAt(Player p, Location loc) {
        BoundingBox cur = p.getBoundingBox();
        double halfX = cur.getWidthX() / 2.0;
        double halfZ = cur.getWidthZ() / 2.0;
        double height = cur.getHeight();
        return new BoundingBox(
                loc.getX() - halfX, loc.getY(), loc.getZ() - halfZ,
                loc.getX() + halfX, loc.getY() + height, loc.getZ() + halfZ
        );
    }

    /**
     * Returns true if the given AABB intersects any **real** block collision boxes.
     * Paper (via reflection) → exact, multi-shape aware (end rods, panes, fences, stairs, open trapdoors, etc.)
     * Fallback → coarse check using passability and block bounding boxes if available.
     */
    @SuppressWarnings("unchecked")
    private boolean intersectsCollisions(World w, Player p, BoundingBox playerBox) {
        // Try Paper exact collisions via reflection
        if (M_GET_BLOCK_COLLISIONS != null) {
            try {
                Object it = M_GET_BLOCK_COLLISIONS.invoke(w, p, playerBox);
                if (it instanceof Iterable<?>) {
                    for (Object ignoredBox : (Iterable<?>) it) {
                        // first collision → we’re done
                        return true;
                    }
                }
                return false;
            } catch (Throwable ignored) {
                // fall through to coarse fallback
            }
        }

        // --- Fallback path (non-Paper): coarse but conservative ---
        int minX = (int) Math.floor(playerBox.getMinX());
        int maxX = (int) Math.floor(playerBox.getMaxX());
        int minY = (int) Math.floor(playerBox.getMinY());
        int maxY = (int) Math.floor(playerBox.getMaxY());
        int minZ = (int) Math.floor(playerBox.getMinZ());
        int maxZ = (int) Math.floor(playerBox.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.isPassable()) continue; // open trapdoors/doors/gates → passable
                    try {
                        BoundingBox bb = b.getBoundingBox(); // may not exist on some servers
                        if (bb != null && bb.overlaps(playerBox)) return true;
                    } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
                        BoundingBox cell = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
                        if (cell.overlaps(playerBox)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Raytrace a few lines between from → to to catch ultra-thin shapes if the sweep missed.
     * Uses center and two shoulder-height offsets; ignores fluids and passable blocks.
     */
    private boolean rayHitBetween(World w, Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        double dist = delta.length();
        if (dist <= 1.0e-9) return false;

        Vector dir = delta.clone().normalize();

        // Sample 3 rays at different heights/offsets (approx torso/center)
        Location c = from.clone();
        Location up = from.clone().add(0, 1.0, 0);
        Location upLeft = from.clone().add(0.25, 1.0, 0.25);

        return rayHits(w, c, dir, dist) || rayHits(w, up, dir, dist) || rayHits(w, upLeft, dir, dist);
    }

    private boolean rayHits(World w, Location start, Vector dir, double dist) {
        try {
            RayTraceResult res = w.rayTraceBlocks(start, dir, dist, FluidCollisionMode.NEVER, true);
            return res != null;
        } catch (NoSuchMethodError ignored) {
            // Older API without rayTraceBlocks → no ray support
            return false;
        }
    }

    // ===== Allow check: WG flag if available, else whitelist =====
    private boolean isAllowedHere(Location loc) {
        RegionManager rm = regionManager(loc);
        if (rm == null) return false;
        ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(loc));

        // Prefer WG flag if present
        if (REVERSE_DROPPER_FLAG != null && set.testState(null, REVERSE_DROPPER_FLAG)) return true;

        // Whitelist fallback
        for (ProtectedRegion r : set) if (regionWhitelist.contains(r.getId())) return true;
        return false;
    }

    // ===== WG helpers =====
    private RegionManager regionManager(Location loc) {
        World world = loc.getWorld();
        if (world == null) return null;
        return WorldGuard.getInstance().getPlatform()
                .getRegionContainer()
                .get(BukkitAdapter.adapt(world));
    }

    private List<String> regionIdsAt(Location loc) {
        List<String> res = new ArrayList<>();
        RegionManager rm = regionManager(loc);
        if (rm == null) return res;
        ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(loc));
        for (ProtectedRegion r : set) res.add(r.getId());
        // Highest WG priority first
        res.sort((a, b) -> Integer.compare(rm.getRegion(b).getPriority(), rm.getRegion(a).getPriority()));
        return res;
    }

    // ===== command =====
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
        Player p = (Player) sender;
        if (!p.hasPermission("perch.rds.admin")) { p.sendMessage("§cNo permission."); return true; }

        if (args.length >= 1 && args[0].equalsIgnoreCase("setstart")) {
            String regionId = (args.length >= 2) ? args[1] : null;
            if (regionId == null) {
                List<String> here = regionIdsAt(p.getLocation());
                if (here.isEmpty()) { p.sendMessage("§cStand inside a WG region or specify an ID."); return true; }
                regionId = here.get(0);
            }
            saveStart(regionId, p.getLocation());
            p.sendMessage("§aReverseDropper start set for §e" + regionId + "§a.");
            return true;
        }

        p.sendMessage("§eUsage: /" + label + " setstart [regionId]");
        return true;
    }
}
