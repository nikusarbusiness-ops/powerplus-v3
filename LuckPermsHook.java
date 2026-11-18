package de.power;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.entity.Player;

/** Wird nur geladen, wenn LuckPerms installiert ist. */
public class LuckPermsHook {

    private final LuckPerms api = LuckPermsProvider.get();

    /** Liest eine LuckPerms-Meta (Zahl) oder null. */
    public Integer meta(Player p, String key) {
        try {
            String v = api.getPlayerAdapter(Player.class).getMetaData(p).getMetaValue(key);
            return v == null ? null : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
