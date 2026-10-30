package com.example.punchsnowball;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.NamespacedKey;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Server-wide grace period. All Bukkit use runs on the main thread. */
final class FrostGraceManager implements Listener {
    private static final long DURATION_MILLIS = 30L * 60 * 1000;
    private final JavaPlugin plugin;
    private final File stateFile;
    private final Set<UUID> receivedFood = new HashSet<>();
    private long startedAt;
    private long endsAt;
    private BossBar bossBar;
    private BukkitTask updateTask;

    FrostGraceManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "grace.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (isActive()) {
            createBar();
            for (Player player : Bukkit.getOnlinePlayers()) {
                bossBar.addPlayer(player);
                giveFoodOnce(player);
            }
            scheduleUpdate();
            updateBar();
        } else if (endsAt != 0L) {
            clearState();
        }
    }

    boolean isActive() { return endsAt > System.currentTimeMillis(); }
    long secondsLeft() { return isActive() ? (endsAt - System.currentTimeMillis() + 999) / 1000 : 0; }

    void start(CommandSender sender) {
        if (isActive()) {
            sender.sendMessage(ChatColor.YELLOW + "[Frost] A grace period is already running (" + format(secondsLeft()) + " remaining). Use /frost grace stop to end it.");
            return;
        }
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        startedAt = System.currentTimeMillis();
        endsAt = startedAt + DURATION_MILLIS;
        receivedFood.clear();
        persist();
        createBar();
        for (Player player : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(player);
            giveFoodOnce(player);
            showStart(player);
        }
        Bukkit.broadcastMessage(ChatColor.AQUA + "[Frost] GRACE PERIOD STARTED! " + ChatColor.WHITE + "PvP is disabled for 30 minutes. Every player receives 16 cooked beef.");
        updateBar();
        scheduleUpdate();
        sender.sendMessage(ChatColor.GREEN + "[Frost] Started the 30-minute grace period.");
    }

    void stop(CommandSender sender) {
        if (!isActive()) {
            sender.sendMessage(ChatColor.YELLOW + "[Frost] No grace period is active.");
            return;
        }
        finish(false);
        sender.sendMessage(ChatColor.GREEN + "[Frost] Grace period stopped.");
    }

    void sendStatus(CommandSender sender) {
        sender.sendMessage(isActive()
            ? ChatColor.AQUA + "[Frost] Grace period: " + ChatColor.WHITE + format(secondsLeft()) + " remaining. PvP is disabled."
            : ChatColor.GRAY + "[Frost] No grace period is active. PvP follows the normal server rules.");
    }

    private void showStart(Player player) {
        player.sendTitle(ChatColor.AQUA + "GRACE PERIOD", ChatColor.WHITE + "30 minutes • PvP disabled", 10, 80, 20);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.9f);
    }

    private void giveFoodOnce(Player player) {
        if (!isActive() || !receivedFood.add(player.getUniqueId())) return;
        ItemStack food = new ItemStack(Material.COOKED_BEEF, 16);
        for (ItemStack leftover : player.getInventory().addItem(food).values()) {
            // Don't silently delete food when a player has a full inventory.
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.sendMessage(ChatColor.AQUA + "[Frost] You received " + ChatColor.GOLD + "16 cooked beef" + ChatColor.AQUA + " for the grace period!");
        persist();
    }

    private void createBar() {
        if (bossBar != null) return;
        bossBar = Bukkit.createBossBar(ChatColor.AQUA + "❄ Grace Period", BarColor.BLUE, BarStyle.SEGMENTED_20);
        bossBar.setVisible(true);
    }

    private void updateBar() {
        if (!isActive()) {
            if (endsAt != 0L) finish(true);
            return;
        }
        createBar();
        long remaining = secondsLeft();
        bossBar.setTitle(ChatColor.AQUA + "❄ GRACE PERIOD " + ChatColor.WHITE + "| " + format(remaining) + ChatColor.GRAY + " | PvP Disabled");
        bossBar.setProgress(Math.min(1.0, Math.max(0.0, (endsAt - System.currentTimeMillis()) / (double) DURATION_MILLIS)));
    }

    private void scheduleUpdate() {
        if (updateTask != null) updateTask.cancel();
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateBar, 20L, 20L);
    }

    private void finish(boolean elapsed) {
        clearState();
        String notice = elapsed ? "Grace period ended! PvP is enabled again." : "Grace period ended early by an administrator.";
        Bukkit.broadcastMessage(ChatColor.GOLD + "[Frost] " + ChatColor.WHITE + notice);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(ChatColor.GOLD + "GRACE ENDED", ChatColor.YELLOW + "Normal PvP rules restored", 10, 60, 15);
        }
    }

    private void clearState() {
        startedAt = 0;
        endsAt = 0;
        receivedFood.clear();
        if (updateTask != null) { updateTask.cancel(); updateTask = null; }
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        persist();
    }

    void shutdown() {
        // Do not clear the end time: grace resumes with the correct countdown after a restart.
        if (updateTask != null) { updateTask.cancel(); updateTask = null; }
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        if (endsAt != 0) persist();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        createBar();
        bossBar.addPlayer(player);
        giveFoodOnce(player);
        player.sendMessage(ChatColor.AQUA + "[Frost] Grace period active: " + ChatColor.WHITE + format(secondsLeft()) + " remaining; PvP disabled.");
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (bossBar != null) bossBar.removePlayer(event.getPlayer());
    }

    /** Cancel player-owned direct, ranged, pet, summon and explosion damage. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void preventPvp(EntityDamageByEntityEvent event) {
        if (!isActive() || !(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker != null && !attacker.equals(victim)) event.setCancelled(true);
    }

    /** Disable hostile player-thrown potions during grace, including non-damaging debuffs. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void preventPotionPvp(PotionSplashEvent event) {
        if (!isActive() || !(event.getPotion().getShooter() instanceof Player shooter)) return;
        for (org.bukkit.entity.LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player player && !player.equals(shooter)) {
                event.setIntensity(player, 0.0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void preventLingeringPvp(AreaEffectCloudApplyEvent event) {
        if (!isActive()) return;
        Player source = null;
        if (event.getEntity().getSource() instanceof Player player) source = player;
        if (source == null && event.getEntity().getOwnerUniqueId() != null) {
            source = Bukkit.getPlayer(event.getEntity().getOwnerUniqueId());
        }
        if (source == null) return;
        final Player owner = source;
        event.getAffectedEntities().removeIf(entity -> entity instanceof Player player && !player.equals(owner));
    }

    private Player resolvePlayer(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player p) return p;
            if (source instanceof Entity shooter) return resolvePlayer(shooter);
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() != null) return resolvePlayer(tnt.getSource());
        if (entity instanceof AreaEffectCloud cloud) {
            if (cloud.getSource() instanceof Player p) return p;
            if (cloud.getOwnerUniqueId() != null) return Bukkit.getPlayer(cloud.getOwnerUniqueId());
        }
        if (entity instanceof Tameable tame && tame.getOwner() instanceof Player p) return p;
        // Frostbound Horn creatures are player-owned even though vanilla AI attacks directly.
        if (entity instanceof Mob mob) {
            String id = mob.getPersistentDataContainer().get(new NamespacedKey("frostpvp", "summoner"), PersistentDataType.STRING);
            if (id != null) {
                try { return Bukkit.getPlayer(UUID.fromString(id)); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }

    private void load() {
        if (!stateFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(stateFile);
        startedAt = data.getLong("started-at", 0L);
        endsAt = data.getLong("ends-at", 0L);
        for (String id : data.getStringList("received-food")) {
            try { receivedFood.add(UUID.fromString(id)); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private void persist() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("started-at", startedAt);
        data.set("ends-at", endsAt);
        data.set("received-food", receivedFood.stream().map(UUID::toString).toList());
        try { data.save(stateFile); }
        catch (IOException ex) { plugin.getLogger().severe("Unable to save grace.yml: " + ex.getMessage()); }
    }

    private static String format(long seconds) {
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
    }
}
