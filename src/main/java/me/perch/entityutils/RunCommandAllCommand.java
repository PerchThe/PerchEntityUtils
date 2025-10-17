package me.perch.entityutils;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RunCommandAllCommand implements CommandExecutor {

    private final Main plugin;

    public RunCommandAllCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("perchentityutils.runcommandall")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§cUsage: /runcommandall <command with $player>");
            return true;
        }

        // Join all arguments to form the command template
        String baseCommand = String.join(" ", args);

        for (Player player : Bukkit.getOnlinePlayers()) {
            String cmd = baseCommand.replace("$player", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }

        sender.sendMessage("§aCommand executed for all online players.");
        return true;
    }
}