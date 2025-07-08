package me.perch.entityutils;

import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.getServer().getPluginManager().registerEvents(new PlayerInteractEntityListener(this), this);
        this.getCommand("runcommandall").setExecutor(new RunCommandAllCommand(this));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
