package me.perch.entityutils;

import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {

    private ReverseDropperModule reverseDropper;

    public String resourceWorld() {
        return getConfig().getString("resource.world", "Resource");
    }

    public String resourceMessage() {
        return getConfig().getString("resource.message",
                "&cThis world is reset monthly, and claims can not be made here. Want to enter the Permanent world? Choose the Overworld by opening the '/rtp' menu.");
    }

    public long resourceCooldownMs() {
        return getConfig().getLong("resource.cooldown_ms", 8000L);
    }

    public String resourceBypassPerm() {
        return getConfig().getString("resource.bypass_permission", "perch.resourceworld.bypass");
    }

    public double resourceMaxHours() {
        return getConfig().getDouble("resource.max_hours", 12.0D);
    }

    @Override
    public void onLoad() {
        ReverseDropperModule.registerFlagOnLoad(this);
    }

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        getServer().getPluginManager().registerEvents(new PlayerInteractEntityListener(this), this);
        getCommand("runcommandall").setExecutor(new RunCommandAllCommand(this));

        reverseDropper = new ReverseDropperModule(this);
        reverseDropper.register();

        getServer().getPluginManager().registerEvents(new ResourceWorldNotifier(this), this);
    }

    @Override
    public void onDisable() { }
}
