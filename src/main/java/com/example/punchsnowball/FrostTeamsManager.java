package com.example.punchsnowball;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Team persistence, shared storage, safe member warps and main-scoreboard nametags. */
public final class FrostTeamsManager implements Listener {
    private final PunchSnowballPlugin plugin;
    private final File file;
    private final Map<UUID, TeamData> teams = new LinkedHashMap<>();
    private final Map<UUID, UUID> membership = new HashMap<>();
    private final Map<UUID, TeleportRequest> tpaRequests = new HashMap<>();
    private final Scoreboard scoreboard;

    private static final class TeamData {
        UUID id;
        String name;
        ChatColor color;
        UUID leader;
        final Set<UUID> members = new HashSet<>();
        final Map<String, Location> homes = new HashMap<>();
        final Map<Integer, Location> warps = new HashMap<>();
        TeamChest chest;
    }
    private record TeleportRequest(UUID source, UUID target, long until) {}

    private static final class TeamChest implements InventoryHolder {
        final UUID teamId;
        final Inventory inventory;
        TeamChest(UUID id, String label) {
            teamId = id;
            inventory = Bukkit.createInventory(this, 54, Component.text("Team Chest: " + label));
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    public FrostTeamsManager(PunchSnowballPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
        this.scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTask(plugin, this::applyAllNames);
    }

    private boolean setting(String key) { return plugin.getConfig().getBoolean("teams." + key, true); }
    private int maxWarps() { return Math.max(0, Math.min(9, plugin.getConfig().getInt("teams.max-warps", 3))); }
    private int maxHomes() { return Math.max(1, Math.min(20, plugin.getConfig().getInt("teams.max-homes", 3))); }
    private String prefix() { return ChatColor.AQUA + "[Frost Teams] " + ChatColor.RESET; }
    private void say(CommandSender sender, String text) { sender.sendMessage(prefix() + text); }
    private TeamData teamOf(UUID playerId) { return teams.get(membership.get(playerId)); }
    private TeamData named(String name) { for (TeamData t : teams.values()) if (t.name.equalsIgnoreCase(name)) return t; return null; }
    private static String cleanName(String s) { return s.trim(); }
    private static boolean validName(String name) { return name.matches("[A-Za-z0-9_-]{1,16}"); }
    private static String scoreName(UUID id) { return "ft_" + id.toString().replace("-", "").substring(0, 12); }

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("teams");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection node = root.getConfigurationSection(key);
                if (node == null) continue;
                String name = node.getString("name", "Team");
                if (!validName(name) || named(name) != null) continue;
                TeamData data = new TeamData();
                data.id = id;
                data.name = name;
                ChatColor parsed = ChatColor.getByChar(node.getString("color", "b"));
                data.color = parsed != null && parsed.isColor() ? parsed : ChatColor.AQUA;
                try { data.leader = UUID.fromString(node.getString("leader", "")); }
                catch (Exception ignored) { }
                for (String member : node.getStringList("members")) {
                    try {
                        UUID memberId = UUID.fromString(member);
                        if (!membership.containsKey(memberId)) {
                            data.members.add(memberId);
                            membership.put(memberId, data.id);
                        }
                    } catch (IllegalArgumentException ignored) { }
                }
                if (data.leader != null) {
                    UUID previous = membership.get(data.leader);
                    if (previous == null || previous.equals(id)) {
                        data.members.add(data.leader); membership.put(data.leader, id);
                    } else data.leader = null;
                }
                ConfigurationSection homeSection = node.getConfigurationSection("homes");
                if (homeSection != null) for (String home : homeSection.getKeys(false)) {
                    Location loc = homeSection.getLocation(home);
                    if (loc != null && loc.getWorld() != null) data.homes.put(home.toLowerCase(Locale.ROOT), loc);
                }
                ConfigurationSection warpSection = node.getConfigurationSection("warps");
                if (warpSection != null) for (String slot : warpSection.getKeys(false)) {
                    try {
                        int number = Integer.parseInt(slot);
                        Location loc = warpSection.getLocation(slot);
                        if (loc != null && loc.getWorld() != null && number > 0 && number <= 9) data.warps.put(number, loc);
                    } catch (NumberFormatException ignored) { }
                }
                data.chest = new TeamChest(id, name);
                List<?> contents = node.getList("chest", List.of());
                for (int i=0; i<Math.min(54, contents.size()); i++) {
                    if (contents.get(i) instanceof ItemStack stack) data.chest.inventory.setItem(i, stack);
                }
                teams.put(id, data);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid team ID in teams.yml: " + key);
            }
        }
        applyAllNames();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (TeamData t : teams.values()) {
            String p = "teams." + t.id + ".";
            yaml.set(p+"name", t.name);
            yaml.set(p+"color", String.valueOf(t.color.getChar()));
            yaml.set(p+"leader", t.leader == null ? null : t.leader.toString());
            yaml.set(p+"members", t.members.stream().map(UUID::toString).sorted().toList());
            t.homes.forEach((name,loc)->yaml.set(p+"homes." + name, loc));
            t.warps.forEach((n,loc)->yaml.set(p+"warps." + n, loc));
            yaml.set(p+"chest", Arrays.asList(t.chest.inventory.getContents()));
        }
        try {
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            File temp = new File(file.getParentFile(), "teams.yml.tmp");
            yaml.save(temp);
            try { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save teams.yml: " + exception.getMessage());
        }
    }

    public void shutdown() {
        save();
        for (TeamData team : teams.values()) {
            Team score = scoreboard.getTeam(scoreName(team.id));
            if (score != null) score.unregister();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setDisplayName(player.getName());
            player.setPlayerListName(player.getName());
        }
    }

    private void updateName(Player player) {
        TeamData data = teamOf(player.getUniqueId());
        if (data == null) {
            player.setDisplayName(player.getName());
            player.setPlayerListName(player.getName());
            return;
        }
        Team team = scoreboard.getTeam(scoreName(data.id));
        if (team == null) team = scoreboard.registerNewTeam(scoreName(data.id));
        team.setColor(data.color);
        team.setPrefix(data.color + "[" + data.name + "] " + ChatColor.RESET);
        team.addEntry(player.getName());
        player.setDisplayName(data.color + player.getName() + ChatColor.RESET);
        player.setPlayerListName(data.color + player.getName());
    }
    private void applyAllNames() {
        for (TeamData data : teams.values()) {
            Team team = scoreboard.getTeam(scoreName(data.id));
            if (team == null) team = scoreboard.registerNewTeam(scoreName(data.id));
            team.setColor(data.color);
            team.setPrefix(data.color + "[" + data.name + "] " + ChatColor.RESET);
        }
        for (Player player : Bukkit.getOnlinePlayers()) updateName(player);
    }
    private void refreshTeam(TeamData data) {
        Team scoreboardTeam = scoreboard.getTeam(scoreName(data.id));
        if (scoreboardTeam != null) scoreboardTeam.setPrefix(data.color + "[" + data.name + "] " + ChatColor.RESET);
        for (UUID id : data.members) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) updateName(player);
        }
    }
    private void removeMembership(UUID id) {
        TeamData current = teamOf(id);
        if (current == null) return;
        current.members.remove(id);
        membership.remove(id);
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        Team entryTeam = scoreboard.getTeam(scoreName(current.id));
        if (entryTeam != null && offline.getName() != null) entryTeam.removeEntry(offline.getName());
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            if (current.chest.inventory.getViewers().contains(player)) player.closeInventory();
            updateName(player);
        }
        if (id.equals(current.leader)) current.leader = null;
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> updateName(event.getPlayer()));
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        tpaRequests.remove(event.getPlayer().getUniqueId());
        tpaRequests.entrySet().removeIf(e -> e.getValue().source.equals(event.getPlayer().getUniqueId()));
        save(); // Chest contents and locations remain durable across crashes/restarts.
    }
    @EventHandler public void onChestClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof TeamChest) save();
    }

    private boolean requireTeam(CommandSender sender, Player player) {
        if (teamOf(player.getUniqueId()) != null) return true;
        say(sender, ChatColor.RED + "You are not on a team."); return false;
    }
    private boolean requireLeader(CommandSender sender, TeamData data, UUID player) {
        if (data.leader != null && data.leader.equals(player)) return true;
        say(sender, ChatColor.RED + "Only your team's leader can do this."); return false;
    }
    private void teleportPlayer(Player player, Location location) {
        if (location == null || location.getWorld() == null) {
            say(player, ChatColor.RED + "That destination is unavailable."); return;
        }
        // Paper's asynchronous teleport avoids expensive synchronous chunk loads.
        player.teleportAsync(location.clone()).thenAccept(success -> {
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) say(player, success ? ChatColor.GREEN + "Teleported." : ChatColor.RED + "Teleport failed.");
            });
        });
    }

    public boolean teamCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { say(sender, ChatColor.RED + "Team commands require an in-game player."); return true; }
        if (!sender.hasPermission("frost.team")) { say(sender, ChatColor.RED + "No permission to use team commands."); return true; }
        if (args.length == 0) {
            say(sender, ChatColor.YELLOW + "/frost team tpa <member> | tpaccept | tpdeny");
            say(sender, ChatColor.YELLOW + "/frost team sethome <name> | home <name>");
            say(sender, ChatColor.YELLOW + "/frost team setwarp <1-3> | warp <1-3> | echest | info");
            return true;
        }
        TeamData team = teamOf(player.getUniqueId());
        if (!requireTeam(sender,player)) return true;
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> {
                say(sender, ChatColor.GOLD + "Team " + team.color + team.name + ChatColor.GRAY + " ("+team.members.size()+" members)");
                say(sender, ChatColor.GRAY + "Leader: " + (team.leader == null ? "Not assigned" : Bukkit.getOfflinePlayer(team.leader).getName()));
                say(sender, ChatColor.GRAY + "Homes: " + (team.homes.isEmpty()? "none" : String.join(", ",team.homes.keySet())));
                say(sender, ChatColor.GRAY + "Warps: " + (team.warps.isEmpty()? "none" : team.warps.keySet()));
            }
            case "tpa" -> {
                if (!setting("tpa-enabled")) { say(sender, ChatColor.RED+"Team teleport requests are disabled."); break; }
                if (args.length < 2) { say(sender,ChatColor.RED+"Usage: /frost team tpa <teammate>"); break; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null || target.equals(player) || !team.members.contains(target.getUniqueId())) {
                    say(sender, ChatColor.RED + "Target must be another online team member."); break;
                }
                tpaRequests.put(target.getUniqueId(), new TeleportRequest(player.getUniqueId(),target.getUniqueId(),System.currentTimeMillis()+60_000L));
                say(target, ChatColor.YELLOW + player.getName() + " wants to teleport to you. /frost team tpaccept or /frost team tpdeny (60s)");
                say(sender,ChatColor.GREEN+"Teleport request sent to "+target.getName()+".");
            }
            case "tpaccept", "tpdeny" -> {
                TeleportRequest req = tpaRequests.remove(player.getUniqueId());
                if (req == null || req.until < System.currentTimeMillis()) { say(sender,ChatColor.RED+"No pending team teleport request."); break; }
                Player from = Bukkit.getPlayer(req.source);
                if (from == null || !team.members.contains(from.getUniqueId())) { say(sender,ChatColor.RED+"Request is no longer valid."); break; }
                if (sub.equals("tpdeny")) { say(from,ChatColor.RED + player.getName()+" declined your request."); break; }
                teleportPlayer(from, player.getLocation());
                say(sender,ChatColor.GREEN + "Accepted " + from.getName() + "'s request.");
            }
            case "sethome" -> {
                if (!setting("homes-enabled")) { say(sender,ChatColor.RED+"Team homes are disabled."); break; }
                if (!requireLeader(sender,team,player.getUniqueId())) break;
                if (args.length < 2 || !validName(args[1])) { say(sender,ChatColor.RED+"Usage: /frost team sethome <name> (1-16 letters/numbers/underscores)"); break; }
                String name = args[1].toLowerCase(Locale.ROOT);
                if (!team.homes.containsKey(name) && team.homes.size() >= maxHomes()) { say(sender,ChatColor.RED+"Team home limit reached."); break; }
                team.homes.put(name,player.getLocation().clone()); save();
                say(sender,ChatColor.GREEN+"Saved team home '"+name+"'.");
            }
            case "home" -> {
                if (!setting("homes-enabled")) { say(sender,ChatColor.RED+"Team homes are disabled."); break; }
                if (args.length < 2) { say(sender,ChatColor.YELLOW+"Homes: "+team.homes.keySet()); break; }
                Location loc = team.homes.get(args[1].toLowerCase(Locale.ROOT));
                if (loc == null) { say(sender,ChatColor.RED+"Team home not found."); break; }
                teleportPlayer(player,loc);
            }
            case "setwarp" -> {
                if (!setting("warps-enabled")) { say(sender,ChatColor.RED+"Team warps are disabled."); break; }
                if (!requireLeader(sender,team,player.getUniqueId())) break;
                int slot = getWarpSlot(sender,args); if (slot < 1) break;
                team.warps.put(slot,player.getLocation().clone()); save();
                say(sender,ChatColor.GREEN+"Set team warp " + slot + ".");
            }
            case "warp" -> {
                if (!setting("warps-enabled")) { say(sender,ChatColor.RED+"Team warps are disabled."); break; }
                int slot = getWarpSlot(sender,args); if (slot < 1) break;
                Location loc = team.warps.get(slot);
                if (loc == null) { say(sender,ChatColor.RED+"Warp " + slot + " has not been set by the leader."); break; }
                teleportPlayer(player,loc);
            }
            case "echest" -> {
                if (!setting("echests-enabled")) { say(sender,ChatColor.RED+"Shared team chests are disabled."); break; }
                player.openInventory(team.chest.inventory);
            }
            default -> say(sender, ChatColor.RED+"Unknown team command. Use /frost team for help.");
        }
        return true;
    }

    private int getWarpSlot(CommandSender sender, String[] args) {
        int n;
        try { n = Integer.parseInt(args.length < 2 ? "" : args[1]); }
        catch (NumberFormatException ignored) { say(sender,ChatColor.RED+"Use a warp number from 1 to "+maxWarps()+"."); return -1; }
        if (n < 1 || n > maxWarps()) { say(sender,ChatColor.RED+"Warp number must be between 1 and "+maxWarps()+"."); return -1; }
        return n;
    }

    public boolean adminCommand(CommandSender sender,String[] args) {
        if (!sender.hasPermission("frost.adminteam")) { say(sender,ChatColor.RED+"Only administrators can manage teams."); return true; }
        // Exact requested command hierarchy: /frost adminteam team ...
        if (args.length < 1 || !args[0].equalsIgnoreCase("team") || args.length < 2) {
            say(sender,ChatColor.YELLOW+"/frost adminteam team create|player|rename|echest see|color|leader set|members|remove|disband"); return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (args.length < 3 || !validName(args[2])) { say(sender,ChatColor.RED+"Usage: /frost adminteam team create <name> (1-16 chars)"); break; }
                if (named(args[2]) != null) { say(sender,ChatColor.RED+"That team exists."); break; }
                TeamData data = new TeamData(); data.id=UUID.randomUUID(); data.name=cleanName(args[2]); data.color=ChatColor.AQUA;
                data.chest=new TeamChest(data.id,data.name); teams.put(data.id,data);
                applyAllNames(); save(); say(sender,ChatColor.GREEN+"Created team '"+data.name+"'.");
            }
            case "player" -> {
                if (args.length < 4) { say(sender,ChatColor.RED+"Usage: /frost adminteam team player <team> <player>"); break; }
                TeamData data=named(args[2]);
                if (data == null) { say(sender,ChatColor.RED+"Unknown team."); break; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[3]);
                if (!target.isOnline() && !target.hasPlayedBefore()) { say(sender,ChatColor.RED+"Player has not joined this server before."); break; }
                if (membership.get(target.getUniqueId()) != null) removeMembership(target.getUniqueId());
                data.members.add(target.getUniqueId()); membership.put(target.getUniqueId(),data.id);
                if (data.leader == null) data.leader=target.getUniqueId();
                refreshTeam(data); save();
                say(sender,ChatColor.GREEN+"Added "+target.getName()+" to "+data.name+".");
            }
            case "rename" -> {
                if (args.length < 4 || !validName(args[3])) { say(sender,ChatColor.RED+"Usage: /frost adminteam team rename <old> <new>"); break; }
                TeamData data=named(args[2]);
                if (data == null || named(args[3]) != null) { say(sender,ChatColor.RED+"Team missing or new name already used."); break; }
                data.name=cleanName(args[3]); refreshTeam(data); save(); say(sender,ChatColor.GREEN+"Renamed team to "+data.name+".");
            }
            case "echest" -> {
                if (args.length < 4 || !args[2].equalsIgnoreCase("see")) { say(sender,ChatColor.RED+"Usage: /frost adminteam team echest see <team>"); break; }
                TeamData data=named(args[3]);
                if (data == null) { say(sender,ChatColor.RED+"Unknown team."); break; }
                if (sender instanceof Player player) player.openInventory(data.chest.inventory);
                else say(sender,ChatColor.RED+"Open the chest in-game (player only).");
            }
            case "color" -> {
                if (args.length < 4) { say(sender,ChatColor.RED+"Usage: /frost adminteam team color <team> <color>"); break; }
                TeamData data=named(args[2]);
                ChatColor color;
                try { color=ChatColor.valueOf(args[3].toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) { say(sender,ChatColor.RED+"Invalid color. Try aqua, blue, red, green, gold or yellow."); break; }
                if (data == null || !color.isColor()) { say(sender,ChatColor.RED+"Invalid team or color."); break; }
                data.color=color; refreshTeam(data); save(); say(sender,ChatColor.GREEN+"Updated team color: "+color+data.name);
            }
            case "leader" -> {
                if (args.length < 4 || !args[2].equalsIgnoreCase("set")) { say(sender,ChatColor.RED+"Usage: /frost adminteam team leader set <player>"); break; }
                OfflinePlayer p=Bukkit.getOfflinePlayer(args[3]); TeamData data=teamOf(p.getUniqueId());
                if (data == null) { say(sender,ChatColor.RED+"That player is not on a team."); break; }
                data.leader=p.getUniqueId(); save(); say(sender,ChatColor.GREEN+p.getName()+" is the leader of "+data.name+".");
            }
            case "members" -> {
                if (args.length < 3) { say(sender,ChatColor.RED+"Usage: /frost adminteam team members <team>"); break; }
                TeamData data=named(args[2]); if (data==null) { say(sender,ChatColor.RED+"Unknown team."); break; }
                say(sender,data.color+data.name+ChatColor.GRAY+" members: "+ data.members.stream()
                        .map(id->String.valueOf(Bukkit.getOfflinePlayer(id).getName())+(id.equals(data.leader)?" [leader]":""))
                        .sorted().collect(Collectors.joining(", ")));
            }
            case "remove" -> {
                if (args.length < 3) { say(sender,ChatColor.RED+"Usage: /frost adminteam team remove <player>"); break; }
                OfflinePlayer p=Bukkit.getOfflinePlayer(args[2]);
                if (teamOf(p.getUniqueId()) == null) { say(sender,ChatColor.RED+"Player not on a team."); break; }
                removeMembership(p.getUniqueId()); save(); say(sender,ChatColor.GREEN+"Removed player from team.");
            }
            case "disband" -> {
                if (args.length < 3) { say(sender,ChatColor.RED+"Usage: /frost adminteam team disband <team>"); break; }
                TeamData data=named(args[2]); if (data==null) { say(sender,ChatColor.RED+"Unknown team."); break; }
                for (org.bukkit.entity.HumanEntity viewer : List.copyOf(data.chest.inventory.getViewers())) viewer.closeInventory();
                for (UUID member : List.copyOf(data.members)) removeMembership(member);
                Team board=scoreboard.getTeam(scoreName(data.id)); if(board!=null) board.unregister();
                teams.remove(data.id); save(); say(sender,ChatColor.GREEN+"Team disbanded.");
            }
            default -> say(sender,ChatColor.RED+"Unknown admin team command.");
        }
        return true;
    }

    public List<String> tabTeam(CommandSender sender, String[] args) {
        if (args.length == 1) return Arrays.asList("tpa","tpaccept","tpdeny","sethome","home","setwarp","warp","echest","info");
        if (!(sender instanceof Player player)) return List.of();
        TeamData data = teamOf(player.getUniqueId());
        if (data == null) return List.of();
        if (args.length == 2) {
            return switch(args[0].toLowerCase(Locale.ROOT)) {
                case "tpa" -> data.members.stream().filter(id->!id.equals(player.getUniqueId())).map(Bukkit::getPlayer)
                        .filter(p->p!=null).map(Player::getName).toList();
                case "home" -> List.copyOf(data.homes.keySet());
                case "warp","setwarp" -> java.util.stream.IntStream.rangeClosed(1,maxWarps()).mapToObj(String::valueOf).toList();
                default -> List.of();
            };
        }
        return List.of();
    }
    public List<String> tabAdmin(String[] args) {
        if (args.length == 1) return List.of("team");
        if (!args[0].equalsIgnoreCase("team")) return List.of();
        if (args.length == 2) return List.of("create","player","rename","echest","color","leader","members","remove","disband");
        if (args.length == 3) {
            if (args[1].equalsIgnoreCase("leader")) return List.of("set");
            if (args[1].equalsIgnoreCase("echest")) return List.of("see");
            if (List.of("player","rename","color","members","disband").contains(args[1].toLowerCase(Locale.ROOT))) return teams.values().stream().map(t->t.name).toList();
            if (List.of("remove").contains(args[1].toLowerCase(Locale.ROOT))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 4) {
            if (args[1].equalsIgnoreCase("echest")) return teams.values().stream().map(t->t.name).toList();
            if (args[1].equalsIgnoreCase("color")) return Arrays.stream(ChatColor.values()).filter(ChatColor::isColor).map(c->c.name().toLowerCase(Locale.ROOT)).toList();
            if (args[1].equalsIgnoreCase("player") || args[1].equalsIgnoreCase("leader")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
