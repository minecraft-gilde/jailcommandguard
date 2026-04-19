package de.minecraftgilde.jailcommandguard;

import net.ess3.api.IEssentials;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class JailCommandGuardPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private IEssentials essentials;
    private Set<String> allowedCommands = Set.of();
    private String blockedMessage = "§cDu kannst diesen Befehl im Gefängnis nicht benutzen.";
    private boolean hideDisallowedCommands = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLocalConfig();

        final Plugin plugin = getServer().getPluginManager().getPlugin("Essentials");
        if (!(plugin instanceof IEssentials ess)) {
            getLogger().severe("Essentials/EssentialsX wurde nicht gefunden oder ist API-seitig inkompatibel. Deaktiviere JailCommandGuard.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.essentials = ess;

        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("jailcommandguard"), "Command jailcommandguard fehlt in plugin.yml")
                .setExecutor(this);
        Objects.requireNonNull(getCommand("jailcommandguard"), "Command jailcommandguard fehlt in plugin.yml")
                .setTabCompleter(this);

        getLogger().info("JailCommandGuard aktiviert.");
    }

    private void loadLocalConfig() {
        reloadConfig();

        this.allowedCommands = getConfig().getStringList("allowed-commands")
                .stream()
                .map(this::normalizeLabel)
                .filter(label -> !label.isBlank())
                .collect(Collectors.toCollection(HashSet::new));

        this.blockedMessage = ChatColor.translateAlternateColorCodes(
                '&',
                getConfig().getString(
                        "blocked-message",
                        "&cDu kannst diesen Befehl im Gefängnis nicht benutzen."
                )
        );

        this.hideDisallowedCommands = getConfig().getBoolean("hide-disallowed-commands", true);
    }

    private boolean isJailed(final Player player) {
        if (essentials == null) {
            return false;
        }

        final var user = essentials.getUser(player);
        return user != null && user.isJailed();
    }

    private String normalizeLabel(final String input) {
        if (input == null) {
            return "";
        }

        String label = input.trim().toLowerCase(Locale.ROOT);

        if (label.startsWith("/")) {
            label = label.substring(1);
        }

        final int firstSpace = label.indexOf(' ');
        if (firstSpace >= 0) {
            label = label.substring(0, firstSpace);
        }

        final int namespaceIndex = label.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex < label.length() - 1) {
            label = label.substring(namespaceIndex + 1);
        }

        return label;
    }

    private boolean isAllowed(final String rawLabel) {
        return allowedCommands.contains(normalizeLabel(rawLabel));
    }

    private void refreshCommands(final Player player) {
        if (!hideDisallowedCommands) {
            return;
        }

        try {
            player.updateCommands();
        } catch (Throwable ignored) {
            // Falls der Server/Fork updateCommands anders handhabt, soll das Plugin trotzdem weiterlaufen.
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(final PlayerCommandPreprocessEvent event) {
        final Player player = event.getPlayer();

        if (!isJailed(player)) {
            return;
        }

        if (player.hasPermission("jailcommandguard.bypass")) {
            return;
        }

        final String message = event.getMessage();
        if (!message.startsWith("/")) {
            return;
        }

        if (!isAllowed(message)) {
            event.setCancelled(true);

            if (blockedMessage != null && !blockedMessage.isBlank()) {
                player.sendMessage(blockedMessage);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommandTreeSend(final PlayerCommandSendEvent event) {
        if (!hideDisallowedCommands) {
            return;
        }

        final Player player = event.getPlayer();
        if (!isJailed(player)) {
            return;
        }

        if (player.hasPermission("jailcommandguard.bypass")) {
            return;
        }

        event.getCommands().removeIf(command -> !isAllowed(command));
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        refreshCommands(event.getPlayer());
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("jailcommandguard.reload")) {
                sender.sendMessage(ChatColor.RED + "Dafür hast du keine Berechtigung.");
                return true;
            }

            loadLocalConfig();

            for (final Player onlinePlayer : getServer().getOnlinePlayers()) {
                refreshCommands(onlinePlayer);
            }

            sender.sendMessage(ChatColor.GREEN + "JailCommandGuard wurde neu geladen.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Verwendung: /" + label + " reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (args.length == 1) {
            final List<String> values = new ArrayList<>();
            if (sender.hasPermission("jailcommandguard.reload")) {
                values.add("reload");
            }
            return values;
        }

        return List.of();
    }
}
