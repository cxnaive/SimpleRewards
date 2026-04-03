package dev.user.rewards.placeholder;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.manager.WeeklyOnlineManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * PlaceholderAPI 扩展
 * 提供 %simplerewards_xxx% 占位符
 */
public class RewardsExpansion extends PlaceholderExpansion {

    private final SimpleRewardsPlugin plugin;

    public RewardsExpansion(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "simplerewards";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String getRequiredPlugin() {
        return "SimpleRewards";
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        UUID uuid = player.getUniqueId();
        WeeklyOnlineManager wom = plugin.getWeeklyOnlineManager();
        params = params.toLowerCase();

        if (wom == null) {
            return switch (params) {
                case "weekly_online_hours", "total_online_hours" -> "0.0";
                default -> "0";
            };
        }

        boolean online = player.isOnline();

        switch (params) {
            case "weekly_online_minutes":
                return String.valueOf(online ? wom.getWeeklyMinutes(uuid) : wom.getOfflineWeeklyMinutes(uuid));

            case "weekly_online_hours":
                double weekHours = online ? wom.getWeeklyMinutes(uuid) / 60.0 : wom.getOfflineWeeklyMinutes(uuid) / 60.0;
                return String.format("%.1f", weekHours);

            case "total_online_hours":
                return String.format("%.1f", online ? wom.getTotalHours(uuid) : wom.getOfflineTotalHours(uuid));

            default:
                return null;
        }
    }
}
