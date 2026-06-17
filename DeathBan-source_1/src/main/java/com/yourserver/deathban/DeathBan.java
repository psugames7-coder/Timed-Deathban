package com.yourserver.deathban;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * DeathBan — escalating death-ban system.
 *
 * Curve (5 lives, no 2-hour tier):
 *   Death 1: 8 hours
 *   Death 2: 24 hours
 *   Death 3: 48 hours
 *   Death 4: 1 week
 *   Death 5: PERMANENT  -> on this death the player's head drops at the death location
 *
 * To change the curve, edit BAN_DURATIONS_SECONDS and DURATION_LABELS (keep them the same length).
 */
public class DeathBan extends JavaPlugin implements Listener {

    // Ban durations in seconds, indexed by death number (1st death = index 0).
    // -1 == permanent. Curve now starts at 8 hours (2-hour tier removed).
    private static final long[] BAN_DURATIONS_SECONDS = {
        8L * 3600,           // 1st death: 8 hours
        24L * 3600,          // 2nd death: 24 hours
        48L * 3600,          // 3rd death: 48 hours
        7L * 24 * 3600,      // 4th death: 1 week
        -1L                  // 5th death: permanent (head drops)
    };

    private static final String[] DURATION_LABELS = {
        "8 hours",
        "24 hours",
        "48 hours",
        "1 week",
        "permanently"
    };

    // Total number of lives (length of the curve). Used in messages.
    private static final int TOTAL_LIVES = BAN_DURATIONS_SECONDS.length;

    private File deathsFile;
    private FileConfiguration deathsConfig;

    @Override
    public void onEnable() {
        // Ensure the plugin's data folder exists (no embedded config.yml is used).
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        deathsFile = new File(getDataFolder(), "deaths.yml");
        if (!deathsFile.exists()) {
            getDataFolder().mkdirs();
            try {
                deathsFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create deaths.yml: " + e.getMessage());
            }
        }
        deathsConfig = YamlConfiguration.loadConfiguration(deathsFile);

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("DeathBan enabled. Curve: 8h -> 24h -> 48h -> 1w -> PERMA (" + TOTAL_LIVES + " lives). Head drops on final death.");
    }

    @Override
    public void onDisable() {
        if (deathsConfig != null) {
            saveDeaths();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (player.hasPermission("deathban.bypass")) {
            getLogger().info(player.getName() + " died but has deathban.bypass. No ban applied.");
            return;
        }

        UUID uuid = player.getUniqueId();
        String uuidString = uuid.toString();

        int currentDeaths = deathsConfig.getInt("players." + uuidString + ".deaths", 0);
        int newDeathCount = currentDeaths + 1;
        deathsConfig.set("players." + uuidString + ".deaths", newDeathCount);
        deathsConfig.set("players." + uuidString + ".name", player.getName());
        deathsConfig.set("players." + uuidString + ".lastDeath", System.currentTimeMillis());
        saveDeaths();

        int tierIndex = Math.min(newDeathCount - 1, BAN_DURATIONS_SECONDS.length - 1);
        long durationSeconds = BAN_DURATIONS_SECONDS[tierIndex];
        String durationLabel = DURATION_LABELS[tierIndex];
        boolean isPermanent = durationSeconds < 0;

        String deathCause = event.getDeathMessage() != null ? event.getDeathMessage() : "Unknown";

        // --- Drop the player's head on the FINAL (permanent) death ---
        if (isPermanent) {
            dropPlayerHead(player, deathCause);
        }

        String banReason = ChatColor.RED + "" + ChatColor.BOLD + "You died!\n" +
                ChatColor.RESET + ChatColor.GRAY + "Death #" + newDeathCount + " of " + TOTAL_LIVES + "\n" +
                ChatColor.YELLOW + "Banned: " + durationLabel + "\n" +
                ChatColor.GRAY + "Cause: " + deathCause;

        Date expiration = null;
        if (durationSeconds > 0) {
            expiration = Date.from(Instant.now().plus(Duration.ofSeconds(durationSeconds)));
        }

        Bukkit.getBanList(BanList.Type.NAME).addBan(
                player.getName(),
                banReason,
                expiration,
                "DeathBan Plugin"
        );

        // Broadcast — emphasize the final death.
        String broadcast;
        if (isPermanent) {
            broadcast = ChatColor.DARK_RED + "" + ChatColor.BOLD + "[FINAL DEATH] " +
                    ChatColor.RESET + ChatColor.WHITE + player.getName() +
                    ChatColor.DARK_RED + " has fallen for the last time. Their head has dropped.";
        } else {
            broadcast = ChatColor.DARK_RED + "[Death] " + ChatColor.WHITE + player.getName() +
                    ChatColor.GRAY + " has fallen (Death " + newDeathCount + "/" + TOTAL_LIVES + "). " +
                    ChatColor.YELLOW + "Banned for " + durationLabel + ".";
        }
        Bukkit.broadcastMessage(broadcast);

        final String kickMessage = banReason;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                player.kickPlayer(kickMessage);
            }
        }, 60L); // 3-second delay so death animation + drops happen first

        getLogger().info(player.getName() + " died. Count: " + newDeathCount + ". Ban: " + durationLabel +
                (isPermanent ? " (head dropped)" : ""));
    }

    /**
     * Drops a player head item at the player's death location.
     */
    private void dropPlayerHead(Player player, String deathCause) {
        try {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(player);
                meta.setDisplayName(ChatColor.RED + player.getName() + "'s Head");
                meta.setLore(java.util.List.of(
                        ChatColor.GRAY + "Fell for the last time.",
                        ChatColor.DARK_GRAY + deathCause
                ));
                head.setItemMeta(meta);
            }
            Location loc = player.getLocation();
            if (loc.getWorld() != null) {
                loc.getWorld().dropItemNaturally(loc, head);
            }
        } catch (Throwable t) {
            getLogger().warning("Failed to drop head for " + player.getName() + ": " + t.getMessage());
        }
    }

    private void saveDeaths() {
        try {
            deathsConfig.save(deathsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save deaths.yml: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("deathban")) return false;

        if (!sender.hasPermission("deathban.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage:");
            sender.sendMessage(ChatColor.GRAY + "/deathban check <player>");
            sender.sendMessage(ChatColor.GRAY + "/deathban set <player> <count>");
            sender.sendMessage(ChatColor.GRAY + "/deathban reset <player>");
            sender.sendMessage(ChatColor.GRAY + "/deathban revive <player>  (reset + unban)");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("check") && args.length >= 2) {
            String targetName = args[1];
            UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
            int deaths = deathsConfig.getInt("players." + targetUuid + ".deaths", 0);
            sender.sendMessage(ChatColor.YELLOW + targetName + " has died " + deaths + "/" + TOTAL_LIVES + " time(s).");
            if (deaths > 0 && deaths < DURATION_LABELS.length) {
                sender.sendMessage(ChatColor.GRAY + "Next death = " + DURATION_LABELS[deaths]);
            } else if (deaths >= DURATION_LABELS.length) {
                sender.sendMessage(ChatColor.DARK_RED + "Already at final death (permanent).");
            } else {
                sender.sendMessage(ChatColor.GRAY + "Next death = " + DURATION_LABELS[0]);
            }
            return true;
        }

        if (sub.equals("set") && args.length >= 3) {
            String targetName = args[1];
            int count;
            try {
                count = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid number: " + args[2]);
                return true;
            }
            UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
            deathsConfig.set("players." + targetUuid + ".deaths", count);
            deathsConfig.set("players." + targetUuid + ".name", targetName);
            saveDeaths();
            sender.sendMessage(ChatColor.GREEN + "Set " + targetName + "'s death count to " + count + ".");
            return true;
        }

        if (sub.equals("reset") && args.length >= 2) {
            String targetName = args[1];
            UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
            deathsConfig.set("players." + targetUuid, null);
            saveDeaths();
            sender.sendMessage(ChatColor.GREEN + "Reset " + targetName + "'s death count to 0.");
            return true;
        }

        if (sub.equals("revive") && args.length >= 2) {
            String targetName = args[1];
            UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
            deathsConfig.set("players." + targetUuid, null);
            saveDeaths();
            Bukkit.getBanList(BanList.Type.NAME).pardon(targetName);
            sender.sendMessage(ChatColor.GREEN + "Revived " + targetName + ": death count reset and unbanned.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Try /deathban with no args.");
        return true;
    }
}
