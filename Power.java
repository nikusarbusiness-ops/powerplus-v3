package de.power;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Power extends JavaPlugin implements Listener, TabCompleter {

    private static final String P = "§8[§bPower§8] §7";
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static class Request {
        final UUID from; final boolean here; final long time;
        Request(UUID from, boolean here) { this.from = from; this.here = here; this.time = System.currentTimeMillis(); }
    }

    private static class Menu implements InventoryHolder {
        final String type; final List<String> list;
        Menu(String type, List<String> list) { this.type = type; this.list = list; }
        @Override public Inventory getInventory() { return null; }
    }

    private final Map<UUID, Request> requests = new HashMap<>();
    private final Set<UUID> autoAccept = new HashSet<>();
    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID, BukkitTask> warmups = new HashMap<>();
    private final Map<UUID, Location> warmupStart = new HashMap<>();
    private final Map<UUID, Integer> afkLeft = new HashMap<>();
    private final Map<UUID, String> renaming = new ConcurrentHashMap<>();
    private final Map<String, Long> deleteConfirm = new HashMap<>();
    private final Random random = new Random();

    private File dataFile, shopFile;
    private YamlConfiguration data, shop;
    private LuckPermsHook luckPerms;
    private boolean papi = false;
    private boolean dirty = false;
    private int ticks = 0;

    private World afkWorld;
    private int ax1, ay1, az1, ax2, ay2, az2;

    // ============================================================ Start / Stop
    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        shopFile = new File(getDataFolder(), "shop.yml");
        if (!shopFile.exists()) saveResource("shop.yml", false);
        shop = YamlConfiguration.loadConfiguration(shopFile);

        for (String c : List.of("tpa", "tpahere", "tpaccept", "tpdeny", "tpauto", "home", "sethome", "delhome", "homes",
                "rtp", "rtpq", "spawn", "setspawn", "afk", "blitz", "rekord", "news", "anvil", "wb", "ec", "repair",
                "sign", "ranks", "keys", "keyall", "power")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) { pc.setExecutor(this); pc.setTabCompleter(this); }
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try { luckPerms = new LuckPermsHook(); getLogger().info("LuckPerms gefunden."); }
            catch (Throwable t) { getLogger().warning("LuckPerms-Anbindung fehlgeschlagen: " + t.getMessage()); }
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try { new PapiHook(this).register(); papi = true; getLogger().info("PlaceholderAPI gefunden - %power_...% Platzhalter aktiv."); }
            catch (Throwable t) { getLogger().warning("PlaceholderAPI-Anbindung fehlgeschlagen: " + t.getMessage()); }
        }
        refreshAfkArea();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("Power aktiviert.");
    }

    @Override
    public void onDisable() { saveData(); }

    private void saveData() {
        try { data.save(dataFile); dirty = false; }
        catch (IOException e) { getLogger().severe("data.yml konnte nicht gespeichert werden: " + e.getMessage()); }
    }

    private void saveShop() {
        try { shop.save(shopFile); }
        catch (IOException e) { getLogger().severe("shop.yml konnte nicht gespeichert werden: " + e.getMessage()); }
    }

    private void msg(CommandSender s, String m) { s.sendMessage(P + m); }
    private void later(Runnable r) { Bukkit.getScheduler().runTask(this, r); }

    private boolean admin(Player p, String perm) {
        if (p.hasPermission(perm)) return true;
        msg(p, "§cDazu hast du keine Rechte.");
        return false;
    }

    private boolean need(Player p, String perm) {
        if (p.hasPermission(perm)) return true;
        msg(p, "§cDafür brauchst du einen höheren Rang. Siehe §b/ranks§c.");
        return false;
    }

    /** Farbcodes: &c, &l ... und Hex &#RRGGBB */
    public static String color(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore != null) im.setLore(lore);
            it.setItemMeta(im);
        }
        return it;
    }

    private Material mat(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name);
        return (m == null || !m.isItem() || m.isAir()) ? fallback : m;
    }

    private List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        String prefix = "§f";
        StringBuilder line = new StringBuilder();
        int len = 0;
        for (String w : text.split(" ")) {
            int wl = ChatColor.stripColor(w).length();
            if (len > 0 && len + wl > width) {
                out.add(prefix + line);
                prefix = ChatColor.getLastColors(prefix + line);
                if (prefix.isEmpty()) prefix = "§f";
                line = new StringBuilder();
                len = 0;
            }
            if (len > 0) { line.append(' '); len++; }
            line.append(w);
            len += wl;
        }
        if (line.length() > 0) out.add(prefix + line);
        return out;
    }

    private String fmt(int n) { return n == Integer.MAX_VALUE ? "∞" : String.valueOf(n); }

    private String duration(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h + "h " + m + "m";
    }

    // ============================================================ Rang-Werte (LuckPerms)
    /** Hoechster Wert aus: Standard, config-Raenge (Gruppenname), Permission prefix<zahl>, LuckPerms-Meta. */
    private int rankInt(Player p, String section, int def, String permPrefix, String metaKey) {
        int max = def;
        ConfigurationSection ranks = getConfig().getConfigurationSection(section);
        if (ranks != null) {
            for (String rank : ranks.getKeys(false)) {
                if (p.hasPermission("group." + rank)) max = Math.max(max, ranks.getInt(rank));
            }
        }
        for (PermissionAttachmentInfo pai : p.getEffectivePermissions()) {
            String s = pai.getPermission().toLowerCase();
            if (pai.getValue() && s.startsWith(permPrefix)) {
                try { max = Math.max(max, Integer.parseInt(s.substring(permPrefix.length()))); } catch (NumberFormatException ignored) { }
            }
        }
        if (luckPerms != null) {
            Integer meta = luckPerms.meta(p, metaKey);
            if (meta != null) max = Math.max(max, meta);
        }
        return max;
    }

    private int maxHomes(Player p) {
        if (p.hasPermission("power.homes.unlimited")) return Integer.MAX_VALUE;
        return rankInt(p, "homes.ranks", getConfig().getInt("homes.default", 3), "power.homes.", "power.homes");
    }

    private int afkReward(Player p) {
        return rankInt(p, "afk.rewards", getConfig().getInt("afk.reward", 1), "power.afkreward.", "power.afkreward");
    }

    private int keyAllAmount(Player p) {
        return rankInt(p, "keyall.ranks", getConfig().getInt("keyall.amount", 1), "power.keyall.", "power.keyall");
    }

    // ============================================================ Blitze
    public int getBlitze(UUID id) { return data.getInt("blitze." + id, 0); }

    public void addBlitze(UUID id, int amount) {
        data.set("blitze." + id, Math.max(0, getBlitze(id) + amount));
        dirty = true;
    }

    private void earnBlitze(Player p, int amount) {
        UUID id = p.getUniqueId();
        addBlitze(id, amount);
        data.set("stats." + id + ".earned", data.getLong("stats." + id + ".earned", 0) + amount);
        data.set("stats." + id + ".name", p.getName());
    }

    // ============================================================ Haupt-Tick (jede Sekunde)
    private void tick() {
        ticks++;
        afkTick();
        keyAllTick();
        if (ticks % 30 == 0 && dirty) saveData();
    }

    private void actionBar(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
    }

    private void afkTick() {
        int interval = Math.max(5, getConfig().getInt("afk.interval-seconds", 60));
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!inAfkArea(p.getLocation())) { afkLeft.remove(id); continue; }
            int left = afkLeft.getOrDefault(id, interval) - 1;
            data.set("stats." + id + ".afk-seconds", data.getLong("stats." + id + ".afk-seconds", 0) + 1);
            data.set("stats." + id + ".name", p.getName());
            dirty = true;
            if (left <= 0) {
                int amount = afkReward(p);
                earnBlitze(p, amount);
                actionBar(p, "§a⚡ +" + amount + (amount == 1 ? " Blitz" : " Blitze") + "!");
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
                left = interval;
            } else {
                actionBar(p, "§e⚡ Nächster Blitz in §6" + left + "§e..");
            }
            afkLeft.put(id, left);
        }
    }

    private void refreshAfkArea() {
        afkWorld = null;
        Location a = data.getLocation("afk.pos1"), b = data.getLocation("afk.pos2");
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld())) return;
        afkWorld = a.getWorld();
        ax1 = Math.min(a.getBlockX(), b.getBlockX()); ax2 = Math.max(a.getBlockX(), b.getBlockX());
        ay1 = Math.min(a.getBlockY(), b.getBlockY()); ay2 = Math.max(a.getBlockY(), b.getBlockY());
        az1 = Math.min(a.getBlockZ(), b.getBlockZ()); az2 = Math.max(a.getBlockZ(), b.getBlockZ());
    }

    private boolean inAfkArea(Location l) {
        return afkWorld != null && l.getWorld() != null && l.getWorld().equals(afkWorld)
                && l.getBlockX() >= ax1 && l.getBlockX() <= ax2
                && l.getBlockY() >= ay1 && l.getBlockY() <= ay2
                && l.getBlockZ() >= az1 && l.getBlockZ() <= az2;
    }

    // ============================================================ KeyAll
    private long keyAllIntervalMs() { return Math.max(1, getConfig().getInt("keyall.interval-minutes", 60)) * 60_000L; }

    private void keyAllTick() {
        if (!getConfig().getBoolean("keyall.enabled", true)) return;
        long now = System.currentTimeMillis();
        long next = data.getLong("keyall.next", 0);
        if (next == 0) { data.set("keyall.next", now + keyAllIntervalMs()); dirty = true; return; }
        if (now >= next) {
            runKeyAll();
            data.set("keyall.next", now + keyAllIntervalMs());
            dirty = true;
        }
    }

    private void runKeyAll() {
        String key = getConfig().getString("keyall.key", "example");
        List<String> cmds = getConfig().getStringList("keyall.commands");
        String message = getConfig().getString("keyall.message", "&aKeyAll! &7Du hast &e%amount%x %key% &7erhalten.");
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            int amount = keyAllAmount(p);
            for (String c : cmds) {
                String cmd = c.replace("%player%", p.getName()).replace("%key%", key).replace("%amount%", String.valueOf(amount));
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            msg(p, color(message.replace("%amount%", String.valueOf(amount)).replace("%key%", key)));
        }
    }

    private long keyAllRemainingSeconds() {
        return Math.max(0, (data.getLong("keyall.next", System.currentTimeMillis()) - System.currentTimeMillis()) / 1000);
    }

    private String mmss(long s) { return String.format("%02d:%02d", s / 60, s % 60); }

    // ============================================================ Platzhalter (SimpleScore / PlaceholderAPI)
    public String placeholder(OfflinePlayer op, String params) {
        UUID id = op.getUniqueId();
        return switch (params.toLowerCase()) {
            case "blitze" -> String.valueOf(getBlitze(id));
            case "blitze_total" -> String.valueOf(data.getLong("stats." + id + ".earned", 0));
            case "afk_countdown" -> afkLeft.containsKey(id) ? String.valueOf(afkLeft.get(id)) : "-";
            case "afk_minutes" -> String.valueOf(data.getLong("stats." + id + ".afk-seconds", 0) / 60);
            case "keyall_next" -> mmss(keyAllRemainingSeconds());
            case "keyall_next_minutes" -> String.valueOf(keyAllRemainingSeconds() / 60);
            case "record" -> String.valueOf(data.getInt("records.max-online", 0));
            case "homes_used" -> String.valueOf(data.getConfigurationSection("homes." + id) == null ? 0
                    : data.getConfigurationSection("homes." + id).getKeys(false).size());
            case "homes_max" -> op instanceof Player pl ? fmt(maxHomes(pl)) : "?";
            case "news_count" -> String.valueOf(data.getStringList("news").size());
            default -> null;
        };
    }

    // ============================================================ Commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (!(sender instanceof Player p)) {
            if (name.equals("blitz") && args.length >= 1 && List.of("give", "take", "set").contains(args[0].toLowerCase())) blitzAdmin(sender, args);
            else if (name.equals("power") && args.length >= 1 && args[0].equalsIgnoreCase("reload")) { reloadAll(); sender.sendMessage("Power neu geladen."); }
            else if (name.equals("keyall") && args.length >= 1 && args[0].equalsIgnoreCase("now")) { runKeyAll(); sender.sendMessage("KeyAll ausgefuehrt."); }
            else sender.sendMessage("Nur fuer Spieler.");
            return true;
        }

        switch (name) {
            case "tpa" -> {
                if (args.length == 0) { msg(p, "Benutzung: §b/tpa <Spieler>§7, §b/tpa accept§7, §b/tpa deny"); return true; }
                if (args[0].equalsIgnoreCase("accept")) accept(p);
                else if (args[0].equalsIgnoreCase("deny")) deny(p);
                else sendRequest(p, args[0], false);
            }
            case "tpahere" -> {
                if (args.length == 0) { msg(p, "Benutzung: §b/tpahere <Spieler>"); return true; }
                sendRequest(p, args[0], true);
            }
            case "tpaccept" -> accept(p);
            case "tpdeny" -> deny(p);
            case "tpauto" -> {
                if (autoAccept.remove(p.getUniqueId())) msg(p, "Auto-Accept §cdeaktiviert§7.");
                else { autoAccept.add(p.getUniqueId()); msg(p, "Auto-Accept §aaktiviert§7."); }
            }
            case "sethome" -> setHome(p, args.length > 0 ? args[0] : "home");
            case "home" -> {
                if (args.length == 0) openHomesGui(p);
                else home(p, args[0]);
            }
            case "homes" -> openHomesGui(p);
            case "delhome" -> {
                if (args.length == 0) { msg(p, "Benutzung: §b/delhome <name>"); return true; }
                if (homeLoc(p.getUniqueId(), args[0].toLowerCase()) == null) { msg(p, "§cDieses Home gibt es nicht."); return true; }
                data.set(hpath(p.getUniqueId(), args[0].toLowerCase()), null); saveData();
                msg(p, "Home §b" + args[0] + " §7gelöscht.");
            }
            case "rtp" -> openRtpGui(p);
            case "rtpq" -> rtpQueue(p);
            case "setspawn" -> {
                if (!admin(p, "power.setspawn")) return true;
                data.set("spawn", p.getLocation()); saveData();
                p.getWorld().setSpawnLocation(p.getLocation());
                msg(p, "§aSpawn gesetzt.");
            }
            case "spawn" -> {
                Location spawn = data.getLocation("spawn");
                if (spawn == null) spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
                teleport(p, spawn, "§aDu wurdest zum Spawn teleportiert.");
            }
            case "afk" -> afkCommand(p, args);
            case "blitz" -> blitzCommand(p, args);
            case "rekord" -> showRecords(p);
            case "news" -> newsCommand(p, args);
            case "anvil" -> { if (need(p, "power.anvil")) p.openInventory(Bukkit.createInventory(p, InventoryType.ANVIL)); }
            case "wb" -> { if (need(p, "power.wb")) p.openWorkbench(null, true); }
            case "ec" -> { if (need(p, "power.ec")) p.openInventory(p.getEnderChest()); }
            case "repair" -> { if (need(p, "power.repair")) repair(p); }
            case "sign" -> { if (need(p, "power.sign")) sign(p, args); }
            case "ranks" -> openRanks(p);
            case "keys" -> openKeys(p);
            case "keyall" -> {
                if (args.length >= 1 && args[0].equalsIgnoreCase("now")) {
                    if (!admin(p, "power.admin")) return true;
                    runKeyAll(); msg(p, "KeyAll ausgeführt.");
                } else {
                    msg(p, getConfig().getBoolean("keyall.enabled", true)
                            ? "Nächstes KeyAll in §e" + mmss(keyAllRemainingSeconds()) + " §7(alle " + getConfig().getInt("keyall.interval-minutes", 60) + " Min)"
                            : "§cKeyAll ist deaktiviert.");
                }
            }
            case "power" -> {
                if (!admin(p, "power.admin")) return true;
                if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) { reloadAll(); msg(p, "§aPower neu geladen."); }
                else msg(p, "Benutzung: §b/power reload");
            }
            default -> { }
        }
        return true;
    }

    private void reloadAll() {
        reloadConfig();
        shop = YamlConfiguration.loadConfiguration(shopFile);
        refreshAfkArea();
    }

    // ============================================================ TPA
    private void sendRequest(Player p, String targetName, boolean here) {
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null) { msg(p, "§cSpieler nicht gefunden."); return; }
        if (t.equals(p)) { msg(p, "§cDu kannst dich nicht zu dir selbst teleportieren."); return; }
        Request r = new Request(p.getUniqueId(), here);
        if (autoAccept.contains(t.getUniqueId())) {
            msg(p, "§b" + t.getName() + " §7hat Auto-Accept aktiv.");
            execute(r, t);
            return;
        }
        requests.put(t.getUniqueId(), r);
        msg(p, "Anfrage an §b" + t.getName() + " §7gesendet.");
        msg(t, "§b" + p.getName() + " §7möchte " + (here ? "dass du dich zu ihm teleportierst" : "sich zu dir teleportieren") + ".");
        msg(t, "§a/tpa accept §7oder §c/tpa deny §8(" + getConfig().getInt("tpa-timeout-seconds", 60) + "s)");
    }

    private Request validRequest(Player t) {
        Request r = requests.get(t.getUniqueId());
        if (r == null) { msg(t, "§cDu hast keine offene Anfrage."); return null; }
        if (System.currentTimeMillis() - r.time > getConfig().getInt("tpa-timeout-seconds", 60) * 1000L) {
            requests.remove(t.getUniqueId());
            msg(t, "§cDie Anfrage ist abgelaufen."); return null;
        }
        return r;
    }

    private void accept(Player t) {
        Request r = validRequest(t);
        if (r == null) return;
        requests.remove(t.getUniqueId());
        execute(r, t);
    }

    private void execute(Request r, Player target) {
        Player from = Bukkit.getPlayer(r.from);
        if (from == null) { msg(target, "§cDer Spieler ist nicht mehr online."); return; }
        msg(target, "Anfrage angenommen.");
        if (r.here) teleport(target, from.getLocation(), "§aTeleportiert zu §b" + from.getName() + "§a.");
        else teleport(from, target.getLocation(), "§aTeleportiert zu §b" + target.getName() + "§a.");
    }

    private void deny(Player t) {
        Request r = validRequest(t);
        if (r == null) return;
        requests.remove(t.getUniqueId());
        msg(t, "Anfrage abgelehnt.");
        Player from = Bukkit.getPlayer(r.from);
        if (from != null) msg(from, "§b" + t.getName() + " §chat deine Anfrage abgelehnt.");
    }

    // ============================================================ Homes
    private String hpath(UUID id, String name) { return "homes." + id + "." + name; }

    /** Alte Homes wurden direkt als Location gespeichert. */
    private void normalize(UUID id, String name) {
        Object o = data.get(hpath(id, name));
        if (o instanceof Location l) {
            data.set(hpath(id, name), null);
            data.set(hpath(id, name) + ".loc", l);
        }
    }

    private Location homeLoc(UUID id, String name) {
        Object o = data.get(hpath(id, name));
        if (o instanceof Location l) return l;
        return data.getLocation(hpath(id, name) + ".loc");
    }

    private void putHome(UUID id, String name, Location l) {
        String icon = data.getString(hpath(id, name) + ".icon");
        data.set(hpath(id, name), null);
        data.set(hpath(id, name) + ".loc", l);
        if (icon != null) data.set(hpath(id, name) + ".icon", icon);
    }

    private Set<String> homeNames(Player p) {
        ConfigurationSection sec = data.getConfigurationSection("homes." + p.getUniqueId());
        return sec == null ? new TreeSet<>() : new TreeSet<>(sec.getKeys(false));
    }

    private boolean validHomeName(String n) { return n.matches("[a-z0-9_\\-]{1,16}"); }

    private void setHome(Player p, String name) {
        name = name.toLowerCase();
        if (!validHomeName(name)) { msg(p, "§cUngültiger Name (a-z, 0-9, _ - ; max 16 Zeichen)."); return; }
        Set<String> homes = homeNames(p);
        if (!homes.contains(name) && homes.size() >= maxHomes(p)) {
            msg(p, "§cDu hast dein Limit von §b" + fmt(maxHomes(p)) + " §cHomes erreicht. Hol dir einen höheren Rang!");
            return;
        }
        putHome(p.getUniqueId(), name, p.getLocation());
        saveData();
        msg(p, "Home §b" + name + " §7gesetzt §8(" + homeNames(p).size() + "/" + fmt(maxHomes(p)) + ")");
    }

    private void home(Player p, String name) {
        Location l = homeLoc(p.getUniqueId(), name.toLowerCase());
        if (l == null || l.getWorld() == null) { msg(p, "§cDieses Home gibt es nicht. Nutze §b/homes§c."); return; }
        teleport(p, l, "§aTeleportiert zu Home §b" + name + "§a.");
    }

    private void openHomesGui(Player p) {
        UUID id = p.getUniqueId();
        List<String> names = new ArrayList<>(homeNames(p));
        int total = names.size();
        int max = maxHomes(p);
        if (names.size() > 45) names = new ArrayList<>(names.subList(0, 45));
        int slots = Math.max(1, Math.min(45, Math.max(names.size(), Math.min(max, 45))));
        int rows = (slots + 8) / 9;
        Inventory inv = Bukkit.createInventory(new Menu("HOMES", names), rows * 9, "§8Homes §7(" + total + "/" + fmt(max) + ")");
        for (int i = 0; i < slots; i++) {
            if (i < names.size()) {
                String n = names.get(i);
                Location l = homeLoc(id, n);
                String world = (l == null || l.getWorld() == null) ? "?" : l.getWorld().getName();
                String xyz = l == null ? "?" : l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
                Material icon = mat(data.getString(hpath(id, n) + ".icon"), Material.RED_BED);
                inv.setItem(i, item(icon, "§b" + n, List.of("§7Welt: §f" + world, "§7Position: §f" + xyz, "",
                        "§aLinksklick: §7Teleportieren", "§eRechtsklick: §7Einstellungen")));
            } else {
                inv.setItem(i, item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7Freier Home-Slot",
                        List.of("§7Setze ein Home mit", "§b/sethome <name>")));
            }
        }
        p.openInventory(inv);
    }

    private void clickHomes(Player p, Menu m, int slot, ClickType ct) {
        if (slot < 0 || slot >= m.list.size()) {
            ItemStack cur = p.getOpenInventory().getTopInventory().getItem(slot);
            if (cur != null && cur.getType() != Material.AIR) { p.closeInventory(); msg(p, "Setze ein Home mit §b/sethome <name>§7."); }
            return;
        }
        String name = m.list.get(slot);
        if (ct == ClickType.RIGHT || ct == ClickType.SHIFT_RIGHT) {
            later(() -> openHomeSettings(p, name));
        } else {
            Location l = homeLoc(p.getUniqueId(), name);
            p.closeInventory();
            if (l == null || l.getWorld() == null) { msg(p, "§cDieses Home ist nicht mehr gültig."); return; }
            teleport(p, l, "§aTeleportiert zu Home §b" + name + "§a.");
        }
    }

    private void openHomeSettings(Player p, String name) {
        UUID id = p.getUniqueId();
        Location l = homeLoc(id, name);
        if (l == null) { openHomesGui(p); return; }
        Inventory inv = Bukkit.createInventory(new Menu("HOME_SET", List.of(name)), 27, "§8Home: §b" + name);
        Material icon = mat(data.getString(hpath(id, name) + ".icon"), Material.RED_BED);
        String world = l.getWorld() == null ? "?" : l.getWorld().getName();
        inv.setItem(4, item(icon, "§b" + name, List.of("§7Welt: §f" + world, "§7Position: §f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ())));
        inv.setItem(10, item(Material.ENDER_PEARL, "§aTeleportieren", List.of("§7Zu diesem Home teleportieren.")));
        inv.setItem(11, item(Material.COMPASS, "§eNeu setzen", List.of("§7Setzt das Home auf", "§7deine aktuelle Position.")));
        inv.setItem(12, item(Material.ITEM_FRAME, "§dIcon ändern", List.of("§7Halte ein Item in der Hand", "§7und klicke hier.")));
        inv.setItem(13, item(Material.NAME_TAG, "§6Umbenennen", List.of("§7Neuen Namen im Chat eingeben.")));
        inv.setItem(15, item(Material.TNT, "§cLöschen", List.of("§7Zweimal klicken zum Bestätigen.")));
        inv.setItem(22, item(Material.ARROW, "§7Zurück", List.of("§7Zur Home-Liste.")));
        p.openInventory(inv);
    }

    private void clickHomeSet(Player p, Menu m, int slot) {
        UUID id = p.getUniqueId();
        String name = m.list.get(0);
        switch (slot) {
            case 10 -> {
                Location l = homeLoc(id, name);
                p.closeInventory();
                if (l == null || l.getWorld() == null) { msg(p, "§cDieses Home ist nicht mehr gültig."); return; }
                teleport(p, l, "§aTeleportiert zu Home §b" + name + "§a.");
            }
            case 11 -> {
                putHome(id, name, p.getLocation()); saveData();
                msg(p, "Home §b" + name + " §7neu gesetzt.");
                later(() -> openHomeSettings(p, name));
            }
            case 12 -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir() || !hand.getType().isItem()) { msg(p, "§cHalte ein Item in der Hand."); return; }
                normalize(id, name);
                data.set(hpath(id, name) + ".icon", hand.getType().name()); saveData();
                msg(p, "Icon geändert.");
                later(() -> openHomeSettings(p, name));
            }
            case 13 -> {
                p.closeInventory();
                renaming.put(id, name);
                msg(p, "Schreibe den neuen Namen in den Chat §8(oder §7abbrechen§8).");
            }
            case 15 -> {
                String key = id + ":" + name;
                Long t = deleteConfirm.get(key);
                if (t != null && System.currentTimeMillis() - t < 5000) {
                    deleteConfirm.remove(key);
                    data.set(hpath(id, name), null); saveData();
                    msg(p, "Home §b" + name + " §7gelöscht.");
                    later(() -> openHomesGui(p));
                } else {
                    deleteConfirm.put(key, System.currentTimeMillis());
                    msg(p, "§cKlicke innerhalb von 5 Sekunden erneut, um §b" + name + " §czu löschen.");
                }
            }
            case 22 -> later(() -> openHomesGui(p));
            default -> { }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        String old = renaming.get(id);
        if (old == null) return;
        e.setCancelled(true);
        renaming.remove(id);
        Player p = e.getPlayer();
        String text = e.getMessage().trim();
        later(() -> doRename(p, old, text));
    }

    private void doRename(Player p, String old, String input) {
        UUID id = p.getUniqueId();
        if (input.equalsIgnoreCase("abbrechen")) { msg(p, "Umbenennen abgebrochen."); return; }
        String n = input.toLowerCase();
        if (!validHomeName(n)) { msg(p, "§cUngültiger Name (a-z, 0-9, _ - ; max 16 Zeichen)."); return; }
        if (homeLoc(id, n) != null) { msg(p, "§cEin Home mit diesem Namen gibt es schon."); return; }
        Location l = homeLoc(id, old);
        if (l == null) { msg(p, "§cDas Home gibt es nicht mehr."); return; }
        String icon = data.getString(hpath(id, old) + ".icon");
        data.set(hpath(id, old), null);
        putHome(id, n, l);
        if (icon != null) data.set(hpath(id, n) + ".icon", icon);
        saveData();
        msg(p, "Home umbenannt zu §b" + n + "§7.");
        openHomeSettings(p, n);
    }

    // ============================================================ RTP
    private void openRtpGui(Player p) {
        Inventory inv = Bukkit.createInventory(new Menu("RTP", List.of()), 27, "§8Random Teleport");
        FileConfiguration c = getConfig();
        inv.setItem(11, item(Material.GRASS_BLOCK, "§aOverworld", List.of("§7Klicke, um zu einer", "§7zufälligen Position weit weg", "§7zu reisen.")));
        boolean nl = c.getBoolean("rtp.nether-locked", true) && !p.hasPermission("power.rtp.nether");
        boolean el = c.getBoolean("rtp.end-locked", true) && !p.hasPermission("power.rtp.end");
        inv.setItem(13, nl ? item(Material.BARRIER, "§cNether §8(§4gesperrt§8)", List.of("§7Der Nether ist derzeit gesperrt."))
                : item(Material.NETHERRACK, "§cNether", List.of("§7Klicke, um zu teleportieren.")));
        inv.setItem(15, el ? item(Material.BARRIER, "§5End §8(§4gesperrt§8)", List.of("§7Das End ist derzeit gesperrt."))
                : item(Material.END_STONE, "§5End", List.of("§7Klicke, um zu teleportieren.")));
        p.openInventory(inv);
    }

    private void clickRtp(Player p, int slot) {
        FileConfiguration c = getConfig();
        switch (slot) {
            case 11 -> { p.closeInventory(); rtp(p, c.getString("rtp.world", "world"), c.getInt("rtp.min-radius", 10000), c.getInt("rtp.max-radius", 140000)); }
            case 13 -> {
                if (c.getBoolean("rtp.nether-locked", true) && !p.hasPermission("power.rtp.nether")) { msg(p, "§cDer Nether ist gesperrt."); return; }
                p.closeInventory();
                rtp(p, c.getString("rtp.nether-world", "world_nether"), c.getInt("rtp.nether-min-radius", 500), c.getInt("rtp.nether-max-radius", 10000));
            }
            case 15 -> {
                if (c.getBoolean("rtp.end-locked", true) && !p.hasPermission("power.rtp.end")) { msg(p, "§cDas End ist gesperrt."); return; }
                p.closeInventory();
                rtp(p, c.getString("rtp.end-world", "world_the_end"), c.getInt("rtp.end-min-radius", 0), c.getInt("rtp.end-max-radius", 80));
            }
            default -> { }
        }
    }

    private void rtp(Player p, String worldName, int minR, int maxR) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) { msg(p, "§cWelt §b" + worldName + " §cexistiert nicht."); return; }
        msg(p, "Suche einen sicheren Ort...");
        Location l = randomLocation(w, minR, maxR);
        if (l == null) { msg(p, "§cKein sicherer Ort gefunden, versuche es nochmal."); return; }
        teleport(p, l, "§aDu wurdest zu einer zufälligen Position teleportiert.");
    }

    /** Zufaelliger Punkt im Quadrat um die Weltmitte, mindestens minR entfernt, begrenzt durch den Worldborder. */
    private Location randomLocation(World w, int minR, int maxR) {
        WorldBorder b = w.getWorldBorder();
        double max = Math.min(maxR, b.getSize() / 2 - 20);
        double min = Math.min(minR, max * 0.8);
        Location c = b.getCenter();
        for (int i = 0; i < 40; i++) {
            double dx = (random.nextDouble() * 2 - 1) * max;
            double dz = (random.nextDouble() * 2 - 1) * max;
            if (Math.max(Math.abs(dx), Math.abs(dz)) < min) continue;
            int x = (int) (c.getX() + dx);
            int z = (int) (c.getZ() + dz);
            int y;
            if (w.getEnvironment() == World.Environment.NETHER) {
                y = -1;
                for (int yy = 100; yy > 32; yy--) {
                    Block ground = w.getBlockAt(x, yy - 1, z);
                    if (ground.getType().isSolid() && w.getBlockAt(x, yy, z).isEmpty() && w.getBlockAt(x, yy + 1, z).isEmpty()
                            && ground.getType() != Material.BEDROCK) { y = yy; break; }
                }
                if (y < 0) continue;
            } else {
                y = w.getHighestBlockYAt(x, z) + 1;
            }
            Block ground = w.getBlockAt(x, y - 1, z);
            Material m = ground.getType();
            if (!m.isSolid() || ground.isLiquid() || m == Material.CACTUS || m == Material.MAGMA_BLOCK
                    || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.FIRE) continue;
            return new Location(w, x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 0f);
        }
        return null;
    }

    private void rtpQueue(Player p) {
        UUID id = p.getUniqueId();
        if (queue.remove(id)) { msg(p, "Du hast die Queue §cverlassen§7."); return; }
        queue.add(id);
        msg(p, "Du bist in der §bRTP-Queue§7. Warte auf einen anderen Spieler... §8(/rtpq zum Verlassen)");
        if (queue.size() >= 2) {
            Iterator<UUID> it = queue.iterator();
            Player a = Bukkit.getPlayer(it.next()); it.remove();
            Player b = Bukkit.getPlayer(it.next()); it.remove();
            if (a == null || b == null) { if (a != null) queue.add(a.getUniqueId()); if (b != null) queue.add(b.getUniqueId()); return; }
            World w = Bukkit.getWorld(getConfig().getString("rtp.world", "world"));
            Location l = w == null ? null : randomLocation(w, getConfig().getInt("rtp.min-radius", 10000), getConfig().getInt("rtp.max-radius", 140000));
            if (l == null) { msg(a, "§cKein sicherer Ort gefunden."); msg(b, "§cKein sicherer Ort gefunden."); return; }
            Location l2 = l.clone().add(1, 0, 0);
            msg(a, "Partner gefunden: §b" + b.getName());
            msg(b, "Partner gefunden: §b" + a.getName());
            teleport(a, l, "§aDu wurdest mit §b" + b.getName() + " §ateleportiert.");
            teleport(b, l2, "§aDu wurdest mit §b" + a.getName() + " §ateleportiert.");
        }
    }

    // ============================================================ AFK
    private void afkCommand(Player p, String[] args) {
        if (args.length == 0) {
            Location l = data.getLocation("afk.spawn");
            if (l == null || l.getWorld() == null) { msg(p, "§cDie AFK-Area wurde noch nicht eingerichtet."); return; }
            teleport(p, l, "§aDu bist jetzt in der AFK-Area. Hier bekommst du Blitze ⚡");
            return;
        }
        switch (args[0].toLowerCase()) {
            case "set" -> {
                if (!admin(p, "power.afk.admin")) return;
                data.set("afk.spawn", p.getLocation()); saveData();
                msg(p, "§aAFK-Spawn gesetzt.");
            }
            case "areaset" -> {
                if (!admin(p, "power.afk.admin")) return;
                if (args.length < 2 || !(args[1].equalsIgnoreCase("pos1") || args[1].equalsIgnoreCase("pos2"))) {
                    msg(p, "Benutzung: §b/afk areaset pos1 §7und §b/afk areaset pos2"); return;
                }
                String which = args[1].toLowerCase();
                data.set("afk." + which, p.getLocation().getBlock().getLocation()); saveData();
                refreshAfkArea();
                msg(p, "§a" + which + " gesetzt." + (afkWorld != null ? " §7Die AFK-Area ist aktiv." : " §7Setze noch die andere Ecke."));
            }
            case "info" -> {
                msg(p, "AFK-Spawn: " + (data.getLocation("afk.spawn") != null ? "§agesetzt" : "§cfehlt")
                        + " §8| §7Area: " + (afkWorld != null ? "§aaktiv" : "§cfehlt")
                        + " §8| §7Deine Belohnung: §e" + afkReward(p) + " ⚡ §7pro " + getConfig().getInt("afk.interval-seconds", 60) + "s");
            }
            default -> msg(p, "Benutzung: §b/afk§7, §b/afk set§7, §b/afk areaset pos1|pos2§7, §b/afk info");
        }
    }

    // ============================================================ Blitz-Shop
    private void blitzCommand(Player p, String[] args) {
        if (args.length == 0) { openShop(p); return; }
        switch (args[0].toLowerCase()) {
            case "balance", "kontostand" -> msg(p, "Du hast §e" + getBlitze(p.getUniqueId()) + " ⚡ Blitze§7.");
            case "give", "take", "set" -> { if (admin(p, "power.blitz.admin")) blitzAdmin(p, args); }
            case "shop" -> { if (admin(p, "power.blitz.admin")) shopAdmin(p, args); }
            default -> msg(p, "Benutzung: §b/blitz§7, §b/blitz balance");
        }
    }

    private void blitzAdmin(CommandSender s, String[] args) {
        if (args.length < 3) { msg(s, "Benutzung: §b/blitz " + args[0] + " <Spieler> <Anzahl>"); return; }
        OfflinePlayer t = Bukkit.getPlayerExact(args[1]);
        if (t == null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
            if (!op.hasPlayedBefore()) { msg(s, "§cSpieler nicht gefunden."); return; }
            t = op;
        }
        int amt;
        try { amt = Integer.parseInt(args[2]); } catch (NumberFormatException e) { msg(s, "§cUngültige Zahl."); return; }
        if (amt < 0) { msg(s, "§cDie Zahl muss positiv sein."); return; }
        UUID id = t.getUniqueId();
        switch (args[0].toLowerCase()) {
            case "give" -> addBlitze(id, amt);
            case "take" -> addBlitze(id, -amt);
            default -> { data.set("blitze." + id, amt); dirty = true; }
        }
        msg(s, "§b" + args[1] + " §7hat jetzt §e" + getBlitze(id) + " ⚡ Blitze§7.");
        if (t instanceof Player tp) msg(tp, "Dein Blitz-Kontostand: §e" + getBlitze(id) + " ⚡");
    }

    private void shopAdmin(Player p, String[] args) {
        if (args.length < 2) {
            msg(p, "§b/blitz shop §7list | add <id> <preis> <material> | remove <id> | price <id> <preis> | name <id> <text> | material <id> <material> | lore <id> <text> | cmd <id> <command> | clearcmds <id>");
            return;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("list")) {
            ConfigurationSection items = shop.getConfigurationSection("items");
            msg(p, "Shop-Items: §b" + (items == null || items.getKeys(false).isEmpty() ? "keine" : String.join("§7, §b", items.getKeys(false))));
            return;
        }
        if (args.length < 3) { msg(p, "§cEs fehlt die ID."); return; }
        String id = args[2].toLowerCase();
        String path = "items." + id;
        if (sub.equals("add")) {
            if (args.length < 5) { msg(p, "Benutzung: §b/blitz shop add <id> <preis> <material>"); return; }
            if (!id.matches("[a-z0-9_\\-]{1,24}")) { msg(p, "§cUngültige ID."); return; }
            int price;
            try { price = Integer.parseInt(args[3]); } catch (NumberFormatException e) { msg(p, "§cUngültiger Preis."); return; }
            Material m = Material.matchMaterial(args[4]);
            if (m == null || !m.isItem()) { msg(p, "§cUnbekanntes Material."); return; }
            shop.set(path + ".name", "&f" + id);
            shop.set(path + ".material", m.name());
            shop.set(path + ".price", price);
            saveShop();
            msg(p, "Item §b" + id + " §7erstellt. Setze jetzt §b/blitz shop cmd " + id + " <command>§7.");
            return;
        }
        if (!shop.contains(path)) { msg(p, "§cDieses Item gibt es nicht."); return; }
        String rest = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "";
        switch (sub) {
            case "remove" -> { shop.set(path, null); msg(p, "Item entfernt."); }
            case "price" -> {
                try { shop.set(path + ".price", Integer.parseInt(rest)); msg(p, "Preis gesetzt."); }
                catch (NumberFormatException e) { msg(p, "§cUngültiger Preis."); return; }
            }
            case "name" -> { shop.set(path + ".name", rest); msg(p, "Name gesetzt: " + color(rest)); }
            case "material" -> {
                Material m = Material.matchMaterial(rest);
                if (m == null || !m.isItem()) { msg(p, "§cUnbekanntes Material."); return; }
                shop.set(path + ".material", m.name()); msg(p, "Material gesetzt.");
            }
            case "lore" -> {
                List<String> l = new ArrayList<>(shop.getStringList(path + ".lore"));
                l.add(rest); shop.set(path + ".lore", l); msg(p, "Lore-Zeile hinzugefügt.");
            }
            case "cmd" -> {
                if (rest.isEmpty()) { msg(p, "§cBitte einen Command angeben (ohne /). Platzhalter: %player%"); return; }
                List<String> l = new ArrayList<>(shop.getStringList(path + ".commands"));
                l.add(rest.startsWith("/") ? rest.substring(1) : rest); shop.set(path + ".commands", l);
                msg(p, "Command hinzugefügt: §f" + rest);
            }
            case "clearcmds" -> { shop.set(path + ".commands", new ArrayList<String>()); msg(p, "Commands geleert."); }
            default -> { msg(p, "§cUnbekannter Unterbefehl."); return; }
        }
        saveShop();
    }

    private void openShop(Player p) {
        ConfigurationSection items = shop.getConfigurationSection("items");
        List<String> ids = items == null ? new ArrayList<>() : new ArrayList<>(items.getKeys(false));
        if (ids.size() > 45) ids = new ArrayList<>(ids.subList(0, 45));
        int rows = Math.min(6, Math.max(2, (ids.size() + 8) / 9 + 1));
        int balance = getBlitze(p.getUniqueId());
        Inventory inv = Bukkit.createInventory(new Menu("SHOP", ids), rows * 9, "§8Blitz-Shop §7(§e" + balance + " ⚡§7)");
        for (int i = 0; i < ids.size(); i++) {
            String path = "items." + ids.get(i);
            int price = shop.getInt(path + ".price", 1);
            List<String> lore = new ArrayList<>();
            for (String l : shop.getStringList(path + ".lore")) lore.add(color(l));
            lore.add("");
            lore.add("§7Preis: " + (balance >= price ? "§e" : "§c") + price + " ⚡");
            lore.add(balance >= price ? "§aKlicke zum Kaufen" : "§cNicht genug Blitze");
            inv.setItem(i, item(mat(shop.getString(path + ".material"), Material.PAPER), color(shop.getString(path + ".name", ids.get(i))), lore));
        }
        inv.setItem(rows * 9 - 5, item(Material.LIGHTNING_ROD, "§e⚡ Deine Blitze: §6" + balance,
                List.of("§7Sammle Blitze in der §b/afk §7Area.")));
        p.openInventory(inv);
    }

    private void clickShop(Player p, Menu m, int slot) {
        if (slot < 0 || slot >= m.list.size()) return;
        String id = m.list.get(slot);
        String path = "items." + id;
        if (!shop.contains(path)) return;
        int price = shop.getInt(path + ".price", 1);
        UUID uid = p.getUniqueId();
        if (getBlitze(uid) < price) { msg(p, "§cDu hast nicht genug Blitze!"); return; }
        addBlitze(uid, -price);
        for (String c : shop.getStringList(path + ".commands")) {
            String cmd = c.replace("%player%", p.getName()).replace("%uuid%", uid.toString());
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
        msg(p, "§aGekauft: §f" + color(shop.getString(path + ".name", id)) + " §7für §e" + price + " ⚡");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        later(() -> openShop(p));
    }

    // ============================================================ Rekorde
    private Map.Entry<String, Long> top(String stat) {
        String bestName = "-";
        long best = 0;
        ConfigurationSection s = data.getConfigurationSection("stats");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                long v = s.getLong(k + "." + stat, 0);
                if (v > best) { best = v; bestName = s.getString(k + ".name", "?"); }
            }
        }
        return new AbstractMap.SimpleEntry<>(bestName, best);
    }

    private void showRecords(Player p) {
        Map.Entry<String, Long> earn = top("earned");
        Map.Entry<String, Long> afk = top("afk-seconds");
        p.sendMessage("§8§m              §r §b§lRekorde §8§m              ");
        p.sendMessage("§7Spieler-Rekord (gleichzeitig online): §e" + data.getInt("records.max-online", 0)
                + " §8(" + data.getString("records.max-online-date", "-") + ")");
        p.sendMessage("§7Aktuell online: §e" + Bukkit.getOnlinePlayers().size());
        p.sendMessage("§7Registrierte Spieler: §e" + Bukkit.getOfflinePlayers().length);
        p.sendMessage("§7Meiste Blitze verdient: §e" + earn.getKey() + " §8(§6" + earn.getValue() + " ⚡§8)");
        p.sendMessage("§7Längste AFK-Zeit: §e" + afk.getKey() + " §8(§6" + duration(afk.getValue()) + "§8)");
        p.sendMessage("§7Deine Blitze verdient: §e" + data.getLong("stats." + p.getUniqueId() + ".earned", 0) + " ⚡ §8| §7Deine AFK-Zeit: §e"
                + duration(data.getLong("stats." + p.getUniqueId() + ".afk-seconds", 0)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        data.set("stats." + p.getUniqueId() + ".name", p.getName());
        int online = Bukkit.getOnlinePlayers().size();
        if (online > data.getInt("records.max-online", 0)) {
            data.set("records.max-online", online);
            data.set("records.max-online-date", LocalDate.now().format(DATE));
            if (online >= 3) Bukkit.broadcastMessage(P + "§6Neuer Spieler-Rekord: §e" + online + " Spieler §6gleichzeitig online!");
        }
        dirty = true;
    }

    // ============================================================ News
    private void newsCommand(Player p, String[] args) {
        if (args.length == 0) { openNews(p); return; }
        switch (args[0].toLowerCase()) {
            case "add" -> {
                if (!admin(p, "power.news.admin")) return;
                if (args.length < 2) { msg(p, "Benutzung: §b/news add <Text> §7(Farben: &c, &l, &#ff8800)"); return; }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                List<String> l = new ArrayList<>(data.getStringList("news"));
                l.add(LocalDate.now().format(DATE) + "|" + text);
                data.set("news", l); saveData();
                Bukkit.broadcastMessage(P + "§6§lNEU §8» " + color(text));
            }
            case "remove" -> {
                if (!admin(p, "power.news.admin")) return;
                List<String> l = new ArrayList<>(data.getStringList("news"));
                int nr;
                try { nr = Integer.parseInt(args.length > 1 ? args[1] : ""); } catch (NumberFormatException e) { msg(p, "Benutzung: §b/news remove <Nummer> §7(Nummer wie im GUI)"); return; }
                int idx = l.size() - nr;
                if (nr < 1 || idx < 0 || idx >= l.size()) { msg(p, "§cDiese Nummer gibt es nicht."); return; }
                l.remove(idx); data.set("news", l); saveData();
                msg(p, "News #" + nr + " entfernt.");
            }
            default -> openNews(p);
        }
    }

    private void openNews(Player p) {
        List<String> all = new ArrayList<>(data.getStringList("news"));
        Collections.reverse(all);
        if (all.size() > 45) all = new ArrayList<>(all.subList(0, 45));
        int rows = Math.max(1, (all.size() + 8) / 9);
        Inventory inv = Bukkit.createInventory(new Menu("NEWS", List.of()), rows * 9, "§8News & Features");
        if (all.isEmpty()) {
            inv.setItem(4, item(Material.BARRIER, "§cNoch keine News", List.of("§7Schau später nochmal vorbei!")));
        }
        for (int i = 0; i < all.size(); i++) {
            String[] parts = all.get(i).split("\\|", 2);
            String date = parts.length > 1 ? parts[0] : "";
            String text = parts.length > 1 ? parts[1] : parts[0];
            List<String> lore = new ArrayList<>(wrap(color(text), 34));
            lore.add("");
            lore.add("§8" + date);
            inv.setItem(i, item(i == 0 ? Material.NETHER_STAR : Material.PAPER,
                    (i == 0 ? "§e§lNEU §8#" : "§6Feature §8#") + (i + 1), lore));
        }
        p.openInventory(inv);
    }

    // ============================================================ Ranks / Keys GUIs
    private void openRanks(Player p) {
        ConfigurationSection rs = getConfig().getConfigurationSection("ranks");
        List<String> ids = rs == null ? new ArrayList<>() : new ArrayList<>(rs.getKeys(false));
        if (ids.size() > 45) ids = new ArrayList<>(ids.subList(0, 45));
        int rows = Math.max(1, (ids.size() + 8) / 9);
        Inventory inv = Bukkit.createInventory(new Menu("RANKS", List.of()), rows * 9, "§8Ränge & Vorteile");
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String path = "ranks." + id;
            List<String> lore = new ArrayList<>();
            int homes = id.equals("default") ? getConfig().getInt("homes.default", 3) : getConfig().getInt("homes.ranks." + id, -1);
            int afk = id.equals("default") ? getConfig().getInt("afk.reward", 1) : getConfig().getInt("afk.rewards." + id, -1);
            int ka = id.equals("default") ? getConfig().getInt("keyall.amount", 1) : getConfig().getInt("keyall.ranks." + id, -1);
            if (homes >= 0) lore.add("§7Homes: §f" + homes);
            if (afk >= 0) lore.add("§7Blitze pro AFK-Intervall: §f" + afk);
            if (ka >= 0) lore.add("§7KeyAll-Keys: §f" + ka);
            for (String perk : getConfig().getStringList(path + ".perks")) lore.add(color(perk));
            if (!id.equals("default") && p.hasPermission("group." + id)) { lore.add(""); lore.add("§a✔ Dein Rang"); }
            inv.setItem(i, item(mat(getConfig().getString(path + ".material"), Material.PAPER), color(getConfig().getString(path + ".name", id)), lore));
        }
        p.openInventory(inv);
    }

    private int keyAmount(Player p, String keyId) {
        if (!papi) return -1;
        try {
            String raw = PapiHook.parse(p, getConfig().getString("keys.placeholder", "%excellentcrates_keys_{id}%").replace("{id}", keyId));
            return (int) Double.parseDouble(raw.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private void openKeys(Player p) {
        ConfigurationSection ks = getConfig().getConfigurationSection("keys.list");
        List<String> ids = ks == null ? new ArrayList<>() : new ArrayList<>(ks.getKeys(false));
        if (ids.size() > 45) ids = new ArrayList<>(ids.subList(0, 45));
        int rows = Math.min(6, Math.max(2, (ids.size() + 8) / 9 + 1));
        Inventory inv = Bukkit.createInventory(new Menu("KEYS", List.of()), rows * 9, "§8Deine Keys");
        int total = 0, owned = 0;
        boolean unknown = false;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int amount = keyAmount(p, id);
            if (amount < 0) unknown = true;
            else { total += amount; if (amount > 0) owned++; }
            String path = "keys.list." + id;
            inv.setItem(i, item(mat(getConfig().getString(path + ".material"), Material.TRIPWIRE_HOOK),
                    color(getConfig().getString(path + ".name", id)),
                    List.of("§7Du besitzt: " + (amount < 0 ? "§c?" : "§e" + amount))));
        }
        List<String> sum = new ArrayList<>();
        sum.add("§7Keys von §e" + owned + "§7/§e" + ids.size() + " §7Key-Arten");
        sum.add("§7Insgesamt: §e" + total + " Keys");
        if (unknown) sum.add("§cPlaceholderAPI/ExcellentCrates nicht gefunden.");
        inv.setItem(rows * 9 - 5, item(Material.CHEST, "§6Zusammenfassung", sum));
        p.openInventory(inv);
    }

    // ============================================================ Repair / Sign
    private void repair(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir() || !(it.getItemMeta() instanceof Damageable d)) { msg(p, "§cHalte ein reparierbares Item in der Hand."); return; }
        if (d.getDamage() == 0) { msg(p, "§cDas Item ist bereits voll repariert."); return; }
        String today = LocalDate.now().toString();
        String path = "repair." + p.getUniqueId();
        if (!p.hasPermission("power.repair.unlimited") && today.equals(data.getString(path))) {
            msg(p, "§cDu hast heute schon repariert. Komm morgen wieder!"); return;
        }
        d.setDamage(0);
        it.setItemMeta(d);
        data.set(path, today); saveData();
        msg(p, "§aItem repariert. §7(1x pro Tag)");
    }

    private void sign(Player p, String[] args) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) { msg(p, "§cHalte ein Item in der Hand."); return; }
        ItemMeta im = it.getItemMeta();
        if (im == null) { msg(p, "§cDieses Item kann nicht signiert werden."); return; }
        List<String> lore = im.hasLore() && im.getLore() != null ? new ArrayList<>(im.getLore()) : new ArrayList<>();
        for (String l : lore) if (l.contains("Signiert von")) { msg(p, "§cDieses Item ist bereits signiert."); return; }
        String extra = args.length > 0 ? " §8- §7" + color(String.join(" ", args)) : "";
        lore.add("");
        lore.add("§7Signiert von §b" + p.getName() + " §7am §f" + LocalDate.now().format(DATE) + extra);
        im.setLore(lore);
        it.setItemMeta(im);
        msg(p, "§aItem signiert.");
    }

    // ============================================================ Inventar-Klicks
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        switch (m.type) {
            case "RTP" -> clickRtp(p, slot);
            case "HOMES" -> clickHomes(p, m, slot, e.getClick());
            case "HOME_SET" -> clickHomeSet(p, m, slot);
            case "SHOP" -> clickShop(p, m, slot);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ============================================================ Teleport mit Delay
    private void teleport(Player p, Location dest, String doneMsg) {
        int delay = getConfig().getInt("teleport-delay", 3);
        cancelWarmup(p.getUniqueId());
        if (delay <= 0 || p.hasPermission("power.bypass")) {
            p.teleport(dest); msg(p, doneMsg); return;
        }
        msg(p, "Teleport in §b" + delay + "s§7 - nicht bewegen!");
        warmupStart.put(p.getUniqueId(), p.getLocation());
        warmups.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(this, () -> {
            warmups.remove(p.getUniqueId()); warmupStart.remove(p.getUniqueId());
            if (p.isOnline()) { p.teleport(dest); msg(p, doneMsg); }
        }, delay * 20L));
    }

    private void cancelWarmup(UUID id) {
        BukkitTask t = warmups.remove(id);
        if (t != null) t.cancel();
        warmupStart.remove(id);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location start = warmupStart.get(e.getPlayer().getUniqueId());
        if (start == null || e.getTo() == null) return;
        if (start.getBlockX() != e.getTo().getBlockX() || start.getBlockY() != e.getTo().getBlockY()
                || start.getBlockZ() != e.getTo().getBlockZ()) {
            cancelWarmup(e.getPlayer().getUniqueId());
            msg(e.getPlayer(), "§cTeleport abgebrochen, du hast dich bewegt.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        queue.remove(id); requests.remove(id); cancelWarmup(id); afkLeft.remove(id); renaming.remove(id);
    }

    // ============================================================ Tab-Complete
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if (!(s instanceof Player p)) return List.of();
        String n = cmd.getName().toLowerCase();
        String a = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            switch (n) {
                case "tpa", "tpahere" -> {
                    if (n.equals("tpa")) { out.add("accept"); out.add("deny"); }
                    Bukkit.getOnlinePlayers().forEach(o -> { if (!o.equals(p)) out.add(o.getName()); });
                }
                case "home", "delhome" -> out.addAll(homeNames(p));
                case "afk" -> { if (p.hasPermission("power.afk.admin")) out.addAll(List.of("set", "areaset", "info")); }
                case "blitz" -> {
                    out.addAll(List.of("balance"));
                    if (p.hasPermission("power.blitz.admin")) out.addAll(List.of("give", "take", "set", "shop"));
                }
                case "news" -> { if (p.hasPermission("power.news.admin")) out.addAll(List.of("add", "remove")); }
                case "power" -> out.add("reload");
                case "keyall" -> { if (p.hasPermission("power.admin")) out.add("now"); }
                default -> { }
            }
        } else if (args.length == 2) {
            if (n.equals("afk") && args[0].equalsIgnoreCase("areaset")) out.addAll(List.of("pos1", "pos2"));
            else if (n.equals("blitz") && args[0].equalsIgnoreCase("shop") && p.hasPermission("power.blitz.admin"))
                out.addAll(List.of("list", "add", "remove", "price", "name", "material", "lore", "cmd", "clearcmds"));
            else if (n.equals("blitz") && List.of("give", "take", "set").contains(args[0].toLowerCase()))
                Bukkit.getOnlinePlayers().forEach(o -> out.add(o.getName()));
        } else if (args.length == 3 && n.equals("blitz") && args[0].equalsIgnoreCase("shop")) {
            ConfigurationSection items = shop.getConfigurationSection("items");
            if (items != null) out.addAll(items.getKeys(false));
        }
        return out.stream().filter(x -> x.toLowerCase().startsWith(a)).collect(Collectors.toList());
    }
}
