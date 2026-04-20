package de.minecraftgilde.jailcommandguard;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import net.ess3.api.IEssentials;
import net.ess3.api.events.JailStatusChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class JailCommandGuardPlugin extends JavaPlugin implements Listener {

    private static final long FORCE_JAIL_RESPAWN_WINDOW_MILLIS = 10L * 60_000L;
    private static final long[] POST_RESPAWN_ENFORCEMENT_DELAYS_TICKS = {1L, 5L, 20L};
    private static final long DEATH_FALLBACK_PERIOD_TICKS = 20L;
    private static final int DEATH_FALLBACK_MAX_ATTEMPTS = 600;
    private static final LegacyComponentSerializer LEGACY_AMPERSAND_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private IEssentials essentials;
    private Set<String> allowedCommands = Set.of();
    private String blockedMessage = "§cDu kannst diesen Befehl im Gefängnis nicht benutzen.";
    private boolean hideDisallowedCommands = true;
    private boolean jailTimeReminderEnabled = true;
    private int jailTimeReminderIntervalMinutes = 1;
    private String jailTimeReminderMessage = "&eDu musst noch &6%formatted_time% &eim Gefaengnis bleiben.";
    private boolean respawnDebugLogging = false;
    private ScheduledTask jailTimeReminderTask;
    private final Map<UUID, Long> nextReminderAtMillisByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> forceJailRespawnUntilMillisByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.Location> forcedJailRespawnLocationByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> deathFallbackTaskByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> deathFallbackAttemptsByPlayer = new ConcurrentHashMap<>();

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

        restartJailTimeReminderTask();
        sendJailTimeReminderToAllOnlinePlayers();

        getLogger().info("JailCommandGuard v" + getDescription().getVersion() + " erfolgreich aktiviert.");
    }

    @Override
    public void onDisable() {
        stopJailTimeReminderTask();
        nextReminderAtMillisByPlayer.clear();
        forceJailRespawnUntilMillisByPlayer.clear();
        forcedJailRespawnLocationByPlayer.clear();
        for (final ScheduledTask scheduledTask : deathFallbackTaskByPlayer.values()) {
            if (scheduledTask != null) {
                scheduledTask.cancel();
            }
        }
        deathFallbackTaskByPlayer.clear();
        deathFallbackAttemptsByPlayer.clear();
    }

    private void loadLocalConfig() {
        reloadConfig();

        this.allowedCommands = getConfig().getStringList("allowed-commands")
                .stream()
                .map(this::normalizeLabel)
                .filter(label -> !label.isBlank())
                .collect(Collectors.toCollection(HashSet::new));

        this.blockedMessage = getConfig().getString(
                "blocked-message",
                "&cDu kannst diesen Befehl im Gefängnis nicht benutzen."
        );

        this.hideDisallowedCommands = getConfig().getBoolean("hide-disallowed-commands", true);
        this.jailTimeReminderEnabled = getConfig().getBoolean("jail-time-reminder.enabled", true);
        this.jailTimeReminderIntervalMinutes = Math.max(1, getConfig().getInt("jail-time-reminder.interval-minutes", 1));
        this.respawnDebugLogging = getConfig().getBoolean("debug-respawn.enabled", false);
        this.jailTimeReminderMessage = getConfig().getString(
                "jail-time-reminder.message",
                "&eDu musst noch &6%formatted_time% &eim Gefaengnis bleiben."
        );

        if (respawnDebugLogging) {
            getLogger().info("[RespawnDebug] Debug-Logging ist aktiviert.");
        }
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

    private void stopJailTimeReminderTask() {
        if (jailTimeReminderTask == null) {
            return;
        }

        jailTimeReminderTask.cancel();
        jailTimeReminderTask = null;
    }

    private void restartJailTimeReminderTask() {
        stopJailTimeReminderTask();
        nextReminderAtMillisByPlayer.clear();

        if (!jailTimeReminderEnabled) {
            return;
        }

        this.jailTimeReminderTask = getServer().getGlobalRegionScheduler().runAtFixedRate(
                this,
                task -> {
                    for (final Player onlinePlayer : getServer().getOnlinePlayers()) {
                        onlinePlayer.getScheduler().run(
                                this,
                                playerTask -> checkAndSendJailTimeReminder(onlinePlayer, false),
                                null
                        );
                    }
                },
                1L,
                20L
        );
    }

    private void sendJailTimeReminderToAllOnlinePlayers() {
        if (!jailTimeReminderEnabled) {
            return;
        }

        for (final Player onlinePlayer : getServer().getOnlinePlayers()) {
            onlinePlayer.getScheduler().run(
                    this,
                    task -> checkAndSendJailTimeReminder(onlinePlayer, true),
                    null
            );
        }
    }

    private void checkAndSendJailTimeReminder(final Player player, final boolean forceImmediate) {
        if (!jailTimeReminderEnabled) {
            return;
        }

        final UUID playerId = player.getUniqueId();

        if (player.hasPermission("jailcommandguard.bypass")) {
            nextReminderAtMillisByPlayer.remove(playerId);
            return;
        }

        if (essentials == null) {
            nextReminderAtMillisByPlayer.remove(playerId);
            return;
        }

        final var user = essentials.getUser(player);
        if (user == null || !user.isJailed()) {
            nextReminderAtMillisByPlayer.remove(playerId);
            return;
        }

        final long remainingMillis = getRemainingJailMillis(player);
        if (remainingMillis <= 0) {
            nextReminderAtMillisByPlayer.remove(playerId);
            return;
        }

        if (jailTimeReminderMessage == null || jailTimeReminderMessage.isBlank()) {
            return;
        }

        final long now = System.currentTimeMillis();
        final long nextReminderAt = nextReminderAtMillisByPlayer.getOrDefault(playerId, 0L);
        if (!forceImmediate && now < nextReminderAt) {
            return;
        }

        final long remainingMinutes = Math.max(1L, (remainingMillis + 59_999L) / 60_000L);
        final String formattedMinutes = formatWholeMinutes(remainingMinutes);
        final String message = jailTimeReminderMessage
                .replace("%minutes%", Long.toString(remainingMinutes))
                .replace("%formatted_time%", formattedMinutes);
        player.sendMessage(colorize(message));

        final long intervalMillis = jailTimeReminderIntervalMinutes * 60_000L;
        nextReminderAtMillisByPlayer.put(playerId, now + intervalMillis);
    }

    private String formatWholeMinutes(final long remainingMinutes) {
        if (remainingMinutes == 1L) {
            return "1 Minute";
        }

        return remainingMinutes + " Minuten";
    }

    private long getRemainingJailMillis(final Player player) {
        if (essentials == null) {
            return -1L;
        }

        final var user = essentials.getUser(player);
        if (user == null || !user.isJailed()) {
            return -1L;
        }

        final long jailTimeout = user.getJailTimeout();
        if (jailTimeout <= 0L) {
            return -1L;
        }

        final long now = System.currentTimeMillis();
        final long onlineJailedTime = user.getOnlineJailedTime();
        final long expireTime;

        if (onlineJailedTime > 0L) {
            final long playedTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            final long remainingTicks = Math.max(0L, onlineJailedTime - playedTicks);
            expireTime = now + (remainingTicks * 50L);
        } else {
            expireTime = jailTimeout;
        }

        return Math.max(0L, expireTime - now);
    }

    private void debugRespawn(final String message) {
        if (!respawnDebugLogging) {
            return;
        }

        getLogger().info("[RespawnDebug] " + message);
    }

    private String formatLocation(final org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) {
            return "null";
        }

        return location.getWorld().getName()
                + " "
                + location.getBlockX()
                + " "
                + location.getBlockY()
                + " "
                + location.getBlockZ();
    }

    private boolean isNear(final org.bukkit.Location a, final org.bukkit.Location b, final double maxDistanceBlocks) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        if (!a.getWorld().equals(b.getWorld())) {
            return false;
        }

        return a.distanceSquared(b) <= (maxDistanceBlocks * maxDistanceBlocks);
    }

    private void stopDeathFallbackTask(final UUID playerId) {
        final ScheduledTask existingTask = deathFallbackTaskByPlayer.remove(playerId);
        if (existingTask != null) {
            existingTask.cancel();
        }
        deathFallbackAttemptsByPlayer.remove(playerId);
    }

    private void startDeathFallbackTask(final UUID playerId) {
        stopDeathFallbackTask(playerId);
        deathFallbackAttemptsByPlayer.put(playerId, 0);
        debugRespawn("deathFallback: start for playerId=" + playerId);

        final ScheduledTask fallbackTask = getServer().getGlobalRegionScheduler().runAtFixedRate(
                this,
                task -> {
                    final Player player = getServer().getPlayer(playerId);
                    if (player == null || !player.isOnline()) {
                        debugRespawn("deathFallback: stop, player offline playerId=" + playerId);
                        stopDeathFallbackTask(playerId);
                        task.cancel();
                        return;
                    }

                    final int attempt = deathFallbackAttemptsByPlayer.merge(playerId, 1, Integer::sum);
                    debugRespawn("deathFallback: attempt=" + attempt
                            + ", player=" + player.getName()
                            + ", dead=" + player.isDead()
                            + ", loc=" + formatLocation(player.getLocation()));

                    if (!player.isDead()) {
                        final org.bukkit.Location jailLocation = getBestJailRespawnLocation(player);
                        if (jailLocation != null && isNear(player.getLocation(), jailLocation, 3.0D)) {
                            debugRespawn("deathFallback: player reached jail location, stopping fallback.");
                            stopDeathFallbackTask(playerId);
                            task.cancel();
                            return;
                        }
                    }

                    schedulePlayerEnforcementTask(playerId, 1L);

                    if (attempt >= DEATH_FALLBACK_MAX_ATTEMPTS || !isForcedRespawnWindowActive(playerId)) {
                        debugRespawn("deathFallback: stop after attempt=" + attempt + ", player=" + player.getName());
                        stopDeathFallbackTask(playerId);
                        task.cancel();
                    }
                },
                1L,
                DEATH_FALLBACK_PERIOD_TICKS
        );

        deathFallbackTaskByPlayer.put(playerId, fallbackTask);
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
                player.sendMessage(colorize(blockedMessage));
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

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onJailStatusChange(final JailStatusChangeEvent event) {
        if (event.getAffected() != null && !event.getValue()) {
            final Player affectedPlayer = event.getAffected().getBase();
            if (affectedPlayer != null) {
                final UUID affectedPlayerId = affectedPlayer.getUniqueId();
                forceJailRespawnUntilMillisByPlayer.remove(affectedPlayerId);
                forcedJailRespawnLocationByPlayer.remove(affectedPlayerId);
                stopDeathFallbackTask(affectedPlayerId);
            }
        }

        if (!jailTimeReminderEnabled) {
            return;
        }

        if (!event.getValue()) {
            return;
        }

        if (event.getAffected() == null) {
            return;
        }

        final Player player = event.getAffected().getBase();
        if (player == null || !player.isOnline()) {
            return;
        }

        player.getScheduler().run(
                this,
                task -> checkAndSendJailTimeReminder(player, true),
                null
        );
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        refreshCommands(player);

        if (!isJailed(player)) {
            final UUID playerId = player.getUniqueId();
            forceJailRespawnUntilMillisByPlayer.remove(playerId);
            forcedJailRespawnLocationByPlayer.remove(playerId);
            stopDeathFallbackTask(playerId);
        }

        player.getScheduler().run(
                this,
                task -> checkAndSendJailTimeReminder(player, true),
                null
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        final UUID playerId = player.getUniqueId();
        debugRespawn("onDeath: player=" + player.getName() + ", jailed=" + isJailed(player));

        if (isJailed(player)) {
            forceJailRespawnUntilMillisByPlayer.put(
                    playerId,
                    System.currentTimeMillis() + FORCE_JAIL_RESPAWN_WINDOW_MILLIS
            );
            final org.bukkit.Location forcedLocation = resolveJailLocationFor(player, false);
            if (forcedLocation != null) {
                forcedJailRespawnLocationByPlayer.put(playerId, forcedLocation.clone());
                debugRespawn("onDeath: forced jail location cached=" + formatLocation(forcedLocation));
            } else {
                forcedJailRespawnLocationByPlayer.remove(playerId);
                debugRespawn("onDeath: no jail location could be resolved for cache.");
            }
            startDeathFallbackTask(playerId);
        } else {
            forceJailRespawnUntilMillisByPlayer.remove(playerId);
            forcedJailRespawnLocationByPlayer.remove(playerId);
            stopDeathFallbackTask(playerId);
            debugRespawn("onDeath: player not jailed, forced window cleared.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        debugRespawn("onRespawn(HIGHEST): player=" + player.getName()
                + ", reason=" + event.getRespawnReason()
                + ", bed=" + event.isBedSpawn()
                + ", anchor=" + event.isAnchorSpawn()
                + ", currentRespawn=" + formatLocation(event.getRespawnLocation()));

        if (!shouldForceJailRespawn(player)) {
            debugRespawn("onRespawn(HIGHEST): force=false, no override.");
            return;
        }

        final var jailLocation = getBestJailRespawnLocation(player);
        if (jailLocation != null) {
            event.setRespawnLocation(jailLocation.clone());
            debugRespawn("onRespawn(HIGHEST): respawn overridden to jail=" + formatLocation(jailLocation));
        } else {
            logRespawnFailureState(player, "onRespawn");
        }

        schedulePostRespawnJailEnforcement(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawnMonitor(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        if (!respawnDebugLogging) {
            return;
        }

        debugRespawn("onRespawn(MONITOR): player=" + player.getName()
                + ", finalRespawn=" + formatLocation(event.getRespawnLocation())
                + ", bed=" + event.isBedSpawn()
                + ", anchor=" + event.isAnchorSpawn());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostRespawn(final PlayerPostRespawnEvent event) {
        final Player player = event.getPlayer();
        debugRespawn("onPostRespawn(MONITOR): player=" + player.getName() + ", loc=" + formatLocation(player.getLocation()));
        if (!shouldForceJailRespawn(player)) {
            debugRespawn("onPostRespawn(MONITOR): force=false, no schedule.");
            return;
        }

        schedulePlayerEnforcementTask(player.getUniqueId(), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForcedRespawnTeleport(final PlayerTeleportEvent event) {
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        if (!isForcedRespawnWindowActive(playerId)) {
            return;
        }

        debugRespawn("onForcedRespawnTeleport(HIGHEST): player=" + player.getName()
                + ", cause=" + event.getCause()
                + ", from=" + formatLocation(event.getFrom())
                + ", to=" + formatLocation(event.getTo()));

        final org.bukkit.Location jailLocation = getBestJailRespawnLocation(player);
        if (jailLocation == null) {
            debugRespawn("onForcedRespawnTeleport(HIGHEST): no jail location available.");
            return;
        }

        event.setTo(jailLocation.clone());
        debugRespawn("onForcedRespawnTeleport(HIGHEST): teleport target overridden to jail=" + formatLocation(jailLocation));
    }

    private boolean shouldForceJailRespawn(final Player player) {
        final UUID playerId = player.getUniqueId();
        if (isJailed(player)) {
            debugRespawn("shouldForceJailRespawn: player=" + player.getName() + " is currently jailed.");
            return true;
        }

        final boolean forcedWindow = isForcedRespawnWindowActive(playerId);
        debugRespawn("shouldForceJailRespawn: player=" + player.getName() + ", forcedWindow=" + forcedWindow);
        return forcedWindow;
    }

    private boolean isForcedRespawnWindowActive(final UUID playerId) {
        final long forcedUntilMillis = forceJailRespawnUntilMillisByPlayer.getOrDefault(playerId, 0L);
        if (forcedUntilMillis <= System.currentTimeMillis()) {
            forceJailRespawnUntilMillisByPlayer.remove(playerId);
            forcedJailRespawnLocationByPlayer.remove(playerId);
            return false;
        }

        return true;
    }

    private org.bukkit.Location getBestJailRespawnLocation(final Player player) {
        final org.bukkit.Location liveJailLocation = resolveJailLocationFor(player, true);
        if (liveJailLocation != null) {
            forcedJailRespawnLocationByPlayer.put(player.getUniqueId(), liveJailLocation.clone());
            debugRespawn("getBestJailRespawnLocation: using live jail location=" + formatLocation(liveJailLocation));
            return liveJailLocation;
        }

        final org.bukkit.Location cachedJailLocation = forcedJailRespawnLocationByPlayer.get(player.getUniqueId());
        if (cachedJailLocation != null && cachedJailLocation.getWorld() != null) {
            debugRespawn("getBestJailRespawnLocation: using cached jail location=" + formatLocation(cachedJailLocation));
            return cachedJailLocation.clone();
        }

        debugRespawn("getBestJailRespawnLocation: no location found (live+cache failed).");
        return null;
    }

    private void schedulePostRespawnJailEnforcement(final UUID playerId) {
        debugRespawn("schedulePostRespawnJailEnforcement: playerId=" + playerId);
        for (final long delayTicks : POST_RESPAWN_ENFORCEMENT_DELAYS_TICKS) {
            schedulePlayerEnforcementTask(playerId, delayTicks);
        }
    }

    private void schedulePlayerEnforcementTask(final UUID playerId, final long delayTicks) {
        final Player player = getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            forceJailRespawnUntilMillisByPlayer.remove(playerId);
            forcedJailRespawnLocationByPlayer.remove(playerId);
            return;
        }

        final long safeDelayTicks = Math.max(1L, delayTicks);
        debugRespawn("schedulePlayerEnforcementTask: player=" + player.getName() + ", delayTicks=" + safeDelayTicks);
        final ScheduledTask scheduledTask = player.getScheduler().runDelayed(
                this,
                task -> enforceJailTeleportAfterRespawn(playerId),
                () -> getServer().getGlobalRegionScheduler().runDelayed(
                        this,
                        retryTask -> schedulePlayerEnforcementTask(playerId, 1L),
                        1L
                ),
                safeDelayTicks
        );

        if (scheduledTask == null) {
            debugRespawn("schedulePlayerEnforcementTask: scheduler returned null, retrying via global scheduler.");
            getServer().getGlobalRegionScheduler().runDelayed(
                    this,
                    retryTask -> schedulePlayerEnforcementTask(playerId, 1L),
                    1L
            );
        }
    }

    private void enforceJailTeleportAfterRespawn(final UUID playerId) {
        final Player player = getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            forceJailRespawnUntilMillisByPlayer.remove(playerId);
            forcedJailRespawnLocationByPlayer.remove(playerId);
            return;
        }

        if (!shouldForceJailRespawn(player)) {
            return;
        }

        final var jailLocation = getBestJailRespawnLocation(player);
        if (jailLocation == null) {
            logRespawnFailureState(player, "enforceAfterRespawn");
            return;
        }

        debugRespawn("enforceJailTeleportAfterRespawn: teleportAsync to " + formatLocation(jailLocation));
        player.teleportAsync(jailLocation.clone()).thenAccept(success ->
                debugRespawn("enforceJailTeleportAfterRespawn: teleport result=" + success)
        );
    }

    private void logRespawnFailureState(final Player player, final String phase) {
        final UUID playerId = player.getUniqueId();
        final boolean forcedWindowActive = isForcedRespawnWindowActive(playerId);

        String jailedState = "unknown";
        String jailName = "unknown";
        if (essentials != null) {
            final var user = essentials.getUser(player);
            if (user != null) {
                jailedState = Boolean.toString(user.isJailed());
                jailName = String.valueOf(user.getJail());
            } else {
                jailedState = "user-null";
                jailName = "user-null";
            }
        } else {
            jailedState = "essentials-null";
            jailName = "essentials-null";
        }

        final org.bukkit.Location cachedLocation = forcedJailRespawnLocationByPlayer.get(playerId);
        final String cachedLocationText;
        if (cachedLocation == null || cachedLocation.getWorld() == null) {
            cachedLocationText = "none";
        } else {
            cachedLocationText = cachedLocation.getWorld().getName() + " "
                    + cachedLocation.getBlockX() + " "
                    + cachedLocation.getBlockY() + " "
                    + cachedLocation.getBlockZ();
        }

        getLogger().warning("Respawn-Fallback fehlgeschlagen (" + phase + ") fuer " + player.getName()
                + ": jailed=" + jailedState
                + ", jail=" + jailName
                + ", forcedWindow=" + forcedWindowActive
                + ", cachedLocation=" + cachedLocationText);
        debugRespawn("Respawn-Fallback details(" + phase + "): player=" + player.getName()
                + ", jailed=" + jailedState
                + ", jail=" + jailName
                + ", forcedWindow=" + forcedWindowActive
                + ", cachedLocation=" + cachedLocationText);
    }

    private org.bukkit.Location resolveJailLocationFor(final Player player, final boolean requireJailedState) {
        if (essentials == null) {
            debugRespawn("resolveJailLocationFor: essentials is null.");
            return null;
        }

        final var user = essentials.getUser(player);
        if (user == null) {
            debugRespawn("resolveJailLocationFor: essentials user is null for " + player.getName());
            return null;
        }

        if (requireJailedState && !user.isJailed()) {
            debugRespawn("resolveJailLocationFor: user not jailed (requireJailedState=true) for " + player.getName());
            return null;
        }

        String jailName = user.getJail();
        if (jailName == null || jailName.isBlank()) {
            debugRespawn("resolveJailLocationFor: user jailName empty, trying single-jail fallback.");
            try {
                final var jailNames = essentials.getJails().getList();
                if (jailNames.size() == 1) {
                    jailName = jailNames.iterator().next();
                    debugRespawn("resolveJailLocationFor: single-jail fallback picked '" + jailName + "'.");
                }
            } catch (Exception ignored) {
                debugRespawn("resolveJailLocationFor: getJails().getList() failed.");
                return null;
            }
        }

        if (jailName == null || jailName.isBlank()) {
            debugRespawn("resolveJailLocationFor: jailName still empty after fallback.");
            return null;
        }

        try {
            final var jailLocation = essentials.getJails().getJail(jailName);
            if (jailLocation == null || jailLocation.getWorld() == null) {
                debugRespawn("resolveJailLocationFor: jail '" + jailName + "' returned null/world-null.");
                return null;
            }

            debugRespawn("resolveJailLocationFor: resolved jail '" + jailName + "' -> " + formatLocation(jailLocation));
            return jailLocation.clone();
        } catch (Exception exception) {
            getLogger().warning("Konnte Jail-Location fuer Spieler " + player.getName()
                    + " nicht laden (Jail '" + jailName + "'): " + exception.getMessage());
            debugRespawn("resolveJailLocationFor: exception for jail '" + jailName + "': " + exception.getMessage());
            return null;
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("jailcommandguard.reload")) {
                sender.sendMessage(Component.text("Dafür hast du keine Berechtigung.", NamedTextColor.RED));
                return true;
            }

            loadLocalConfig();
            restartJailTimeReminderTask();
            sendJailTimeReminderToAllOnlinePlayers();

            for (final Player onlinePlayer : getServer().getOnlinePlayers()) {
                refreshCommands(onlinePlayer);
            }

            sender.sendMessage(Component.text("JailCommandGuard wurde neu geladen.", NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text("Verwendung: /" + label + " reload", NamedTextColor.YELLOW));
        return true;
    }

    private Component colorize(final String message) {
        return LEGACY_AMPERSAND_SERIALIZER.deserialize(message == null ? "" : message);
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
