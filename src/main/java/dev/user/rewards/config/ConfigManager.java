package dev.user.rewards.config;

import dev.user.rewards.SimpleRewardsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配置管理器
 */
public class ConfigManager {

    private final SimpleRewardsPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration messagesConfig;

    // 数据库配置
    private String databaseType;
    private String h2Filename;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private int mysqlPoolSize;

    // 每周在线奖励配置
    private boolean weeklyOnlineEnabled;
    private DayOfWeek weekStartDay;
    private int weeklyOnlineCheckInterval;
    private boolean autoGrant;
    private ReminderMode reminderMode;
    private int reminderInterval; // 分钟
    private Map<Integer, Milestone> milestones; // minutes -> Milestone

    // 首次加入奖励
    private boolean firstJoinEnabled;
    private double firstJoinMoney;
    private int firstJoinPoints;

    /**
     * 提醒模式
     */
    public enum ReminderMode {
        ONCE,    // 达成时仅提醒一次
        INTERVAL, // 定时重复提醒
        NONE     // 不提醒
    }

    /**
     * 里程碑配置
     */
    public static class Milestone {
        private final int minutes;
        private final double money;
        private final int points;
        private final String description;

        public Milestone(int minutes, double money, int points, String description) {
            this.minutes = minutes;
            this.money = money;
            this.points = points;
            this.description = description;
        }

        public int getMinutes() { return minutes; }
        public double getMoney() { return money; }
        public int getPoints() { return points; }
        public String getDescription() { return description; }
    }

    public ConfigManager(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
        this.milestones = new TreeMap<>();
    }

    public void load() {
        // 加载主配置
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // 读取数据库配置
        this.databaseType = config.getString("database.type", "h2");
        this.h2Filename = config.getString("database.h2.filename", "rewards");
        this.mysqlHost = config.getString("database.mysql.host", "localhost");
        this.mysqlPort = config.getInt("database.mysql.port", 3306);
        this.mysqlDatabase = config.getString("database.mysql.database", "simplerewards");
        this.mysqlUsername = config.getString("database.mysql.username", "root");
        this.mysqlPassword = config.getString("database.mysql.password", "password");
        this.mysqlPoolSize = config.getInt("database.mysql.pool-size", 5);

        // 读取每周在线奖励配置
        this.weeklyOnlineEnabled = config.getBoolean("weekly-online.enabled", true);
        this.weekStartDay = parseDayOfWeek(config.getString("weekly-online.week-start-day", "MONDAY"));
        this.weeklyOnlineCheckInterval = config.getInt("weekly-online.check-interval", 60);
        this.autoGrant = config.getBoolean("weekly-online.auto-grant", true);
        this.reminderMode = parseReminderMode(config.getString("weekly-online.reminder-mode", "once"));
        this.reminderInterval = config.getInt("weekly-online.reminder-interval", 60);

        // 解析里程碑配置
        this.milestones.clear();
        ConfigurationSection milestonesSection = config.getConfigurationSection("weekly-online.milestones");
        if (milestonesSection != null) {
            for (String key : milestonesSection.getKeys(false)) {
                try {
                    int minutes = Integer.parseInt(key);
                    ConfigurationSection ms = milestonesSection.getConfigurationSection(key);
                    if (ms != null) {
                        double money = ms.getDouble("money", 0);
                        int points = ms.getInt("points", 0);
                        String desc = ms.getString("description", "");
                        milestones.put(minutes, new Milestone(minutes, money, points, desc));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 读取首次加入奖励
        this.firstJoinEnabled = config.getBoolean("first-join.enabled", true);
        this.firstJoinMoney = config.getDouble("first-join.money", 1000.0);
        this.firstJoinPoints = config.getInt("first-join.points", 100);

        // 加载消息配置
        loadMessagesConfig();
    }

    private DayOfWeek parseDayOfWeek(String value) {
        try {
            return DayOfWeek.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("无效的 week-start-day: " + value + "，使用默认值 MONDAY");
            return DayOfWeek.MONDAY;
        }
    }

    private ReminderMode parseReminderMode(String value) {
        try {
            return ReminderMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("无效的 reminder-mode: " + value + "，使用默认值 ONCE");
            return ReminderMode.ONCE;
        }
    }

    private void loadMessagesConfig() {
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        try (InputStreamReader defaultReader = new InputStreamReader(
                plugin.getResource("messages.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(defaultReader);
            this.messagesConfig.setDefaults(defaultConfig);
        } catch (Exception e) {
            plugin.getLogger().warning("加载默认消息配置失败: " + e.getMessage());
        }
    }

    /**
     * 获取消息
     */
    public String getMessage(String key, String... replacements) {
        String message = messagesConfig.getString(key, "");
        if (message.isEmpty()) {
            return "§c消息未找到: " + key;
        }
        for (int i = 0; i < replacements.length - 1; i += 2) {
            message = message.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    // ==================== Getters ====================

    public String getDatabaseType() { return databaseType; }
    public String getH2Filename() { return h2Filename; }
    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUsername() { return mysqlUsername; }
    public String getMysqlPassword() { return mysqlPassword; }
    public int getMysqlPoolSize() { return mysqlPoolSize; }

    public boolean isWeeklyOnlineEnabled() { return weeklyOnlineEnabled; }
    public DayOfWeek getWeekStartDay() { return weekStartDay; }
    public int getWeeklyOnlineCheckInterval() { return weeklyOnlineCheckInterval; }
    public boolean isAutoGrant() { return autoGrant; }
    public ReminderMode getReminderMode() { return reminderMode; }
    public int getReminderInterval() { return reminderInterval; }
    public Map<Integer, Milestone> getMilestones() { return milestones; }
}
