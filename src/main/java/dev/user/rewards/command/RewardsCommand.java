package dev.user.rewards.command;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.gui.CustomRewardAdminGUI;
import dev.user.rewards.gui.CustomRewardPlayerGUI;
import dev.user.rewards.gui.WeeklyOnlineGUI;
import dev.user.rewards.manager.CustomRewardManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /rewards 命令执行器
 * <ul>
 *   /rewards          → 打开每周在线奖励 GUI
 *   /rewards online   → 同上（别名）
 *   /rewards custom   → 打开自定义奖励 GUI
 *   /rewards custom &lt;奖励名&gt; → 直接领取指定奖励
 *   /rewards admin    → 打开管理员奖励管理 GUI
 *   /rewards lookup &lt;player&gt; → 查询玩家在线时长数据
 *   /rewards reload   → 重载配置
 * </ul>
 */
public class RewardsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("online", "custom", "admin", "lookup", "reload", "reset");
    private static final String PERM_ADMIN = "simplerewards.admin";

    private final SimpleRewardsPlugin plugin;

    public RewardsCommand(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            return openGUI(sender);
        }

        return switch (args[0].toLowerCase()) {
            case "online" -> openGUI(sender);
            case "custom" -> handleCustomReward(sender, args);
            case "admin" -> openAdminGUI(sender);
            case "lookup" -> handleLookup(sender, args);
            case "reload" -> handleReload(sender);
            case "reset" -> handleReset(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    // ==================== 子命令处理 ====================

    private boolean openGUI(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (plugin.getWeeklyOnlineManager() == null) {
            sendMessage(sender, plugin.getConfigManager().getMessage("weekly-not-enabled"));
            return true;
        }
        WeeklyOnlineGUI.open(plugin, player);
        return true;
    }

    private boolean handleCustomReward(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (plugin.getCustomRewardManager() == null) {
            sendMessage(sender, plugin.getConfigManager().getMessage("custom-reward-not-enabled"));
            return true;
        }

        // 无参数：打开 GUI
        if (args.length == 1) {
            CustomRewardPlayerGUI.open(plugin, player);
            return true;
        }

        // 有参数：直接领取
        String rewardId = args[1].toLowerCase();
        CustomRewardManager manager = plugin.getCustomRewardManager();

        manager.claimReward(player.getUniqueId(), rewardId, result -> {
            player.getScheduler().execute(plugin, () -> {
                switch (result) {
                    // SUCCESS 消息由 grantReward 内部发送，这里不再重复
                    case SUCCESS -> {}
                    case NOT_FOUND -> player.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-not-found"));
                    case EXPIRED -> player.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-expired"));
                    case LIMIT_REACHED -> player.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-limit"));
                    case DISABLED -> player.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-disabled"));
                    case ERROR -> player.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-error"));
                }
            }, () -> {}, 0L);
        });
        return true;
    }

    private boolean openAdminGUI(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!sender.hasPermission(PERM_ADMIN)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        if (plugin.getCustomRewardManager() == null) {
            sendMessage(sender, plugin.getConfigManager().getMessage("custom-reward-not-enabled"));
            return true;
        }
        CustomRewardAdminGUI.open(plugin, player);
        return true;
    }

    private boolean handleLookup(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sendMessage(sender, plugin.getConfigManager().getMessage("help-lookup"));
            return true;
        }
        if (plugin.getWeeklyOnlineManager() == null) {
            sendMessage(sender, plugin.getConfigManager().getMessage("weekly-not-enabled"));
            return true;
        }

        String targetName = args[1];
        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
        if (!offlineTarget.hasPlayedBefore()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-never-joined",
                    "player", targetName));
            return true;
        }

        UUID targetUuid = offlineTarget.getUniqueId();
        plugin.getDatabaseQueue().submit("Lookup-" + targetUuid, conn -> {
            String sql = "SELECT week_start_date, week_online_seconds, total_online_seconds, claimed_milestones " +
                         "FROM player_weekly_online WHERE player_uuid = ?";
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, targetUuid.toString());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new String[]{
                                rs.getString("week_start_date"),
                                String.valueOf(rs.getInt("week_online_seconds")),
                                String.valueOf(rs.getLong("total_online_seconds")),
                                rs.getString("claimed_milestones")
                        };
                    }
                }
            }
            return null;
        }, result -> {
            if (result == null) {
                sendMessage(sender, plugin.getConfigManager().getMessage("lookup-no-data",
                        "player", targetName));
            } else {
                int weeklySec = Integer.parseInt(result[1]);
                long totalSec = Long.parseLong(result[2]);
                int weeklyH = weeklySec / 3600;
                int weeklyM = (weeklySec % 3600) / 60;
                long totalH = totalSec / 3600;
                long totalM = (totalSec % 3600) / 60;
                String claimed = result[3];
                if (claimed == null || claimed.isBlank()) claimed = "无";

                sendMessage(sender, plugin.getConfigManager().getMessage("lookup-header",
                        "player", targetName));
                sendMessage(sender, plugin.getConfigManager().getMessage("lookup-week",
                        "week", result[0]));
                sendMessage(sender, plugin.getConfigManager().getMessage("lookup-weekly-online",
                        "hours", String.valueOf(weeklyH), "minutes", String.valueOf(weeklyM)));
                sendMessage(sender, plugin.getConfigManager().getMessage("lookup-total-online",
                        "hours", String.valueOf(totalH), "minutes", String.valueOf(totalM)));
                sendMessage(sender, plugin.getConfigManager().getMessage("lookup-claimed",
                        "claimed", claimed));
            }
        }, error -> {
            sendMessage(sender, plugin.getConfigManager().getMessage("lookup-failed",
                    "error", error.getMessage()));
        });

        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        plugin.reload();
        sendMessage(sender, plugin.getConfigManager().getMessage("reload-success"));
        return true;
    }

    private boolean handleReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("milestones")) {
            sendMessage(sender, plugin.getConfigManager().getMessage("help-reset"));
            return true;
        }
        if (plugin.getWeeklyOnlineManager() == null) {
            sendMessage(sender, plugin.getConfigManager().getMessage("weekly-not-enabled"));
            return true;
        }

        plugin.getWeeklyOnlineManager().resetAllMilestones(success -> {
            if (success) {
                sendMessage(sender, plugin.getConfigManager().getMessage("reset-milestones-success"));
            } else {
                sendMessage(sender, plugin.getConfigManager().getMessage("reset-milestones-failed"));
            }
        });
        return true;
    }

    // ==================== 辅助方法 ====================

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(plugin.getConfigManager().getMessage("help-header"));
        sender.sendMessage(plugin.getConfigManager().getMessage("help-online"));
        sender.sendMessage(plugin.getConfigManager().getMessage("help-custom"));
        if (sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("help-admin"));
            sender.sendMessage(plugin.getConfigManager().getMessage("help-lookup"));
            sender.sendMessage(plugin.getConfigManager().getMessage("help-reset"));
            sender.sendMessage(plugin.getConfigManager().getMessage("help-reload"));
        }
        sender.sendMessage(plugin.getConfigManager().getMessage("help-footer"));
    }

    /**
     * 发送消息给 sender（回调在 DB 线程执行，Player 需要调度到玩家线程）
     */
    private void sendMessage(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            player.getScheduler().execute(plugin, () -> player.sendMessage(message), () -> {}, 0L);
        } else {
            sender.sendMessage(message);
        }
    }

    // ==================== Tab 补全 ====================

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            for (String sub : SUB_COMMANDS) {
                if (sub.startsWith(input)) {
                    if (sub.equals("online") || sub.equals("custom") || sender.hasPermission(PERM_ADMIN)) {
                        completions.add(sub);
                    }
                }
            }
        } else if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            String input = args[1].toLowerCase();

            if (subCmd.equals("lookup") && sender.hasPermission(PERM_ADMIN)) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(input)) {
                        completions.add(player.getName());
                    }
                }
            } else if (subCmd.equals("reset") && sender.hasPermission(PERM_ADMIN)) {
                if ("milestones".startsWith(input)) {
                    completions.add("milestones");
                }
            } else if (subCmd.equals("custom")) {
                // 补全奖励 ID
                if (plugin.getCustomRewardManager() != null) {
                    for (var reward : plugin.getCustomRewardManager().getAllRewards()) {
                        if (reward.getRewardId().startsWith(input)) {
                            completions.add(reward.getRewardId());
                        }
                    }
                }
            }
        }

        return completions;
    }
}
