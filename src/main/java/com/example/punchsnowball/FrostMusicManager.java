package com.example.punchsnowball;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.TrialSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Independent per-listener boss/trial sound controller. Does not modify spawners or mobs. */
final class FrostMusicManager implements Listener {
    private enum Track {
        NONE("", "", 0L),
        END("endreborn:the_end", "Now Playing: [The End] by laudividni", 155_610L),
        VANILLA("minecraft:music_disc.pigstep", "Music playback test: Minecraft Pigstep", 148_000L),
        BREEZE("frostmusic:breeze", "Now playing: [Breeze] by TenM", 121_652L),
        CONVICTION("frostmusic:conviction", "Now playing: [Conviction] by FPLester", 343_771L);

        final String sound;
        final String credit;
        final long durationMs;
        Track(String sound, String credit, long durationMs) {
            this.sound = sound; this.credit = credit; this.durationMs = durationMs;
        }
    }

    private record Playback(Track track, long restartAt) { }
    private final JavaPlugin plugin;
    private final Map<UUID, Playback> playing = new HashMap<>();
    private final Map<UUID, Long> manualTestUntil = new HashMap<>();
    private final Map<UUID, Track> manualTestTrack = new HashMap<>();
    private final BukkitTask task;

    FrostMusicManager(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Main-thread only: entity and block-state inspection must not run async.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 40L, 40L);
    }

    private boolean enabled(String key) {
        return plugin.getConfig().getBoolean("music." + key + ".enabled", true);
    }

    private double radius(String key, double defaultValue) {
        return Math.max(8, Math.min(key.equals("end") ? 512 : 96, plugin.getConfig().getDouble("music." + key + ".radius-blocks", defaultValue)));
    }

    private boolean witherNearby(Player player) {
        if (!enabled("wither")) return false;
        double range = radius("wither", 64);
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), range, range, range,
                e -> e.getType() == EntityType.WITHER)) {
            if (entity.isValid() && !entity.isDead() && entity.getLocation().distanceSquared(player.getLocation()) <= range * range)
                return true;
        }
        return false;
    }

    private boolean ominousTrialNearby(Player player) {
        if (!enabled("trial")) return false;
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return false;
        double range = radius("trial", 32);
        Location spot = player.getLocation();
        int cx = spot.getBlockX() >> 4, cz = spot.getBlockZ() >> 4;
        int chunkRadius = (int)Math.ceil(range / 16.0) + 1;
        double rangeSquared = range * range;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!world.isChunkLoaded(x, z)) continue; // Never load new chunks for music.
                Chunk chunk = world.getChunkAt(x, z);
                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state.getBlockData() instanceof TrialSpawner spawner)) continue;
                    if (!spawner.isOminous()) continue;
                    // Exclude inactive and cooldown spawners: music plays during an actual challenge.
                    if (spawner.getTrialSpawnerState() != TrialSpawner.State.ACTIVE &&
                            spawner.getTrialSpawnerState() != TrialSpawner.State.WAITING_FOR_REWARD_EJECTION) continue;
                    double dX = state.getX() + 0.5 - spot.getX();
                    double dY = state.getY() + 0.5 - spot.getY();
                    double dZ = state.getZ() + 0.5 - spot.getZ();
                    if (dX * dX + dY * dY + dZ * dZ <= rangeSquared) return true;
                }
            }
        }
        return false;
    }

    private boolean dragonFightNearby(Player player) {
        if (!enabled("end")) return false;
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) return false;
        // Only the main arena, not distant End cities. Allow the entry platform too.
        double arenaRadius = radius("end", 180);
        double dx = player.getLocation().getX();
        double dz = player.getLocation().getZ();
        if (dx * dx + dz * dz > arenaRadius * arenaRadius) return false;
        // True Ending manages the boss movement. This only checks for a living dragon.
        // Don't force the chunk to load, and don't interfere with entity AI.
        for (Entity entity : world.getEntitiesByClass(org.bukkit.entity.EnderDragon.class)) {
            if (!entity.isValid() || entity.isDead()) continue;
            // True Ending may temporarily disable the real Dragon's AI for scripted moves;
            // that must not interrupt the music. Only ignore its visual mirror entity.
            if (entity.getScoreboardTags().contains("trueEnding_mirrordragon")) continue;
            return true;
        }
        return false;
    }

    private Track target(Player player) {
        // Exactly ONE track per player, including the End boss fight.
        if (dragonFightNearby(player)) return Track.END;
        if (witherNearby(player)) return Track.CONVICTION;
        if (ominousTrialNearby(player)) return Track.BREEZE;
        return Track.NONE;
    }

    private void poll() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Long testUntil = manualTestUntil.get(uuid);
            if (testUntil != null) {
                if (now < testUntil) continue; // Do not restart automatic music during a manual test.
                manualTestUntil.remove(uuid);
                Track manual = manualTestTrack.remove(uuid);
                if (manual != null) stopTrack(player, manual);
            }
            Track desired = target(player);
            Playback current = playing.get(player.getUniqueId());
            if (desired == Track.NONE) {
                if (current != null) stop(player);
            } else if (current == null || current.track() != desired) {
                if (current != null) stop(player);
                start(player, desired, now);
            } else if (now >= current.restartAt()) {
                // Restart ONLY when the file finishes, not when more spawners activate.
                stopTrack(player, desired);
                start(player, desired, now);
            }
        }
    }

    // Adventure audience sounds are played to the listener, NOT from a fixed world position.
    private void playTrack(Player player, Track track) {
        player.playSound(Sound.sound(Key.key(track.sound), Sound.Source.RECORD, 1.0f, 1.0f));
    }

    private void stopTrack(Player player, Track track) {
        player.stopSound(SoundStop.named(Key.key(track.sound)));
    }

    /** Test a resource-pack sound even when no Wither or ominous spawner is present. */
    void test(Player player, String name) {
        Track track = switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "breeze" -> Track.BREEZE;
            case "conviction", "wither" -> Track.CONVICTION;
            case "end", "dragon", "the_end" -> Track.END;
            case "vanilla", "pigstep" -> Track.VANILLA;
            default -> Track.NONE;
        };
        if (track == Track.NONE) {
            player.sendMessage(Component.text("[Frost] Use /frost music test end|breeze|conviction|vanilla", NamedTextColor.YELLOW));
            return;
        }
        // An independent one-shot test. No active encounter required.
        stop(player);
        stopTrack(player, track);
        playTrack(player, track);
        manualTestTrack.put(player.getUniqueId(), track);
        manualTestUntil.put(player.getUniqueId(), System.currentTimeMillis() + 8000);
        player.sendMessage(Component.text("[Frost] Playing test sound " + track.sound
                + " (Jukebox/Note Blocks volume). If silent, check your enabled resource pack.", NamedTextColor.AQUA));
    }

    void stopTest(Player player) {
        stop(player);
        manualTestUntil.remove(player.getUniqueId());
        manualTestTrack.remove(player.getUniqueId());
        stopTrack(player, Track.BREEZE);
        stopTrack(player, Track.CONVICTION);
        stopTrack(player, Track.END);
        stopTrack(player, Track.VANILLA);
        player.sendMessage(Component.text("[Frost] Stopped both test sounds.", NamedTextColor.AQUA));
    }

    void diagnostic(Player player) {
        Track desired = target(player);
        Playback active = playing.get(player.getUniqueId());
        player.sendMessage(Component.text("[Frost] Audio status:", NamedTextColor.AQUA));
        player.sendMessage(Component.text("  End enabled=" + enabled("end") + ", Dragon fight nearby=" + dragonFightNearby(player)
                + ", arena range=" + radius("end", 180), NamedTextColor.GRAY));
        player.sendMessage(Component.text("  Wither enabled=" + enabled("wither") + ", nearby=" + witherNearby(player)
                + ", range=" + radius("wither",64), NamedTextColor.GRAY));
        player.sendMessage(Component.text("  Trial enabled=" + enabled("trial") + ", active ominous nearby=" + ominousTrialNearby(player)
                + ", range=" + radius("trial",32), NamedTextColor.GRAY));
        player.sendMessage(Component.text("  Selected=" + desired.name() + ", playing="
                + (active == null ? "NONE" : active.track().name()), NamedTextColor.GRAY));
        player.sendMessage(Component.text("  Your client must enable the resource pack; this server cannot verify the sound files are loaded.", NamedTextColor.YELLOW));
    }

    private void start(Player player, Track track, long now) {
        playTrack(player, track);
        playing.put(player.getUniqueId(), new Playback(track, now + track.durationMs));
        player.sendMessage(Component.text(track.credit, NamedTextColor.LIGHT_PURPLE));
    }

    private void stop(Player player) {
        Playback current = playing.remove(player.getUniqueId());
        if (current != null) stopTrack(player, current.track());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manualTestTrack.remove(event.getPlayer().getUniqueId());
        manualTestUntil.remove(event.getPlayer().getUniqueId());
        stop(event.getPlayer());
    }

    void shutdown() {
        task.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) stop(player);
        playing.clear();
        manualTestUntil.clear();
        manualTestTrack.clear();
    }
}
