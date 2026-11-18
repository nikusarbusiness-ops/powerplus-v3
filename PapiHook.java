package de.power;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** Wird nur geladen, wenn PlaceholderAPI installiert ist. Fuer SimpleScore, TAB, Hologramme usw. */
public class PapiHook extends PlaceholderExpansion {

    private final Power plugin;

    public PapiHook(Power plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "power"; }
    @Override public String getAuthor() { return "Power"; }
    @Override public String getVersion() { return "1.0.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        return plugin.placeholder(player, params);
    }

    public static String parse(Player p, String text) {
        return PlaceholderAPI.setPlaceholders(p, text);
    }
}
