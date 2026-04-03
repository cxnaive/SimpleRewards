package dev.user.rewards.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.config.ConfigManager;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库管理器
 * 支持 H2（本地）和 MySQL（跨服）数据库
 */
public class DatabaseManager {

    private final SimpleRewardsPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        // 先关闭可能存在的旧连接
        close();

        // 保存原始 ClassLoader
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(plugin.getClass().getClassLoader());

            ConfigManager config = plugin.getConfigManager();
            String type = config.getDatabaseType();

            if (type.equalsIgnoreCase("mysql")) {
                initMySQL();
            } else {
                initH2();
            }

            // 创建表
            createTables();

            plugin.getLogger().info("数据库连接成功！类型: " + type);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("数据库初始化失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private void initMySQL() {
        HikariConfig config = new HikariConfig();
        ConfigManager cfg = plugin.getConfigManager();

        // 手动注册 MySQL 驱动
        try {
            Driver mysqlDriver = (Driver) Class.forName("dev.user.rewards.libs.com.mysql.cj.jdbc.Driver", true, plugin.getClass().getClassLoader()).getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(mysqlDriver));
        } catch (Exception e) {
            plugin.getLogger().warning("MySQL 驱动注册失败（可能已注册）: " + e.getMessage());
        }

        config.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                cfg.getMysqlHost(), cfg.getMysqlPort(), cfg.getMysqlDatabase()));
        config.setUsername(cfg.getMysqlUsername());
        config.setPassword(cfg.getMysqlPassword());
        config.setMaximumPoolSize(cfg.getMysqlPoolSize());
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setDriverClassName("dev.user.rewards.libs.com.mysql.cj.jdbc.Driver");

        dataSource = new HikariDataSource(config);
    }

    private void initH2() {
        HikariConfig config = new HikariConfig();
        String filename = plugin.getConfigManager().getH2Filename();
        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // 手动注册 H2 驱动
        try {
            Driver h2Driver = (Driver) Class.forName("dev.user.rewards.libs.org.h2.Driver", true, plugin.getClass().getClassLoader()).getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(h2Driver));
        } catch (Exception e) {
            plugin.getLogger().warning("H2 驱动注册失败（可能已注册）: " + e.getMessage());
        }

        config.setJdbcUrl("jdbc:h2:" + new File(dataFolder, filename).getAbsolutePath() +
                ";AUTO_RECONNECT=TRUE;DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setDriverClassName("dev.user.rewards.libs.org.h2.Driver");
        config.setConnectionTestQuery("SELECT 1");

        dataSource = new HikariDataSource(config);
    }

    /**
     * JDBC 驱动包装类
     */
    private static class DriverShim implements Driver {
        private final Driver driver;

        DriverShim(Driver driver) {
            this.driver = driver;
        }

        @Override
        public Connection connect(String url, java.util.Properties info) throws SQLException {
            return driver.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return driver.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws SQLException {
            return driver.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger(DriverShim.class.getName());
        }
    }

    private void createTables() throws SQLException {
        boolean isMySQL = isMySQL();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {

            String idColumn = isMySQL ? "id BIGINT AUTO_INCREMENT PRIMARY KEY" : "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY";

            // 每周在线时长记录表
            String playerOnlineTable = "CREATE TABLE IF NOT EXISTS player_weekly_online (" +
                    "    player_uuid VARCHAR(36) PRIMARY KEY," +
                    "    week_start_date VARCHAR(10) NOT NULL," +
                    "    week_online_seconds INT DEFAULT 0," +
                    "    total_online_seconds BIGINT DEFAULT 0," +
                    "    claimed_milestones TEXT DEFAULT ''" +
                    ")";
            stmt.execute(playerOnlineTable);

            // 奖励记录表
            String rewardLogTable = "CREATE TABLE IF NOT EXISTS reward_log (" +
                    idColumn + "," +
                    "    player_uuid VARCHAR(36) NOT NULL," +
                    "    reward_type VARCHAR(32) NOT NULL," +
                    "    amount DOUBLE NOT NULL," +
                    "    currency_type VARCHAR(16) NOT NULL," +
                    "    description VARCHAR(256) DEFAULT ''," +
                    "    created_at BIGINT NOT NULL" +
                    ")";
            stmt.execute(rewardLogTable);

            // 自定义奖励配置表
            String customRewardsTable = "CREATE TABLE IF NOT EXISTS custom_rewards (" +
                    "    reward_id VARCHAR(64) PRIMARY KEY," +
                    "    display_name VARCHAR(128) NOT NULL," +
                    "    description VARCHAR(256) DEFAULT ''," +
                    "    money DOUBLE DEFAULT 0," +
                    "    points INT DEFAULT 0," +
                    "    max_claim_count INT DEFAULT 1," +
                    "    expire_at BIGINT DEFAULT -1," +
                    "    created_at BIGINT NOT NULL," +
                    "    created_by VARCHAR(36) DEFAULT ''," +
                    "    enabled BOOLEAN DEFAULT TRUE" +
                    ")";
            stmt.execute(customRewardsTable);

            // 自定义奖励领取记录表
            String customRewardClaimsId = isMySQL ? "id BIGINT AUTO_INCREMENT PRIMARY KEY" : "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY";
            String customRewardClaimsTable = "CREATE TABLE IF NOT EXISTS custom_reward_claims (" +
                    customRewardClaimsId + "," +
                    "    player_uuid VARCHAR(36) NOT NULL," +
                    "    reward_id VARCHAR(64) NOT NULL," +
                    "    claim_count INT DEFAULT 1," +
                    "    first_claim_at BIGINT NOT NULL," +
                    "    last_claim_at BIGINT NOT NULL," +
                    "    UNIQUE (player_uuid, reward_id)" +
                    ")";
            stmt.execute(customRewardClaimsTable);

            // 创建索引
            createIndexes(stmt, isMySQL);

            plugin.getLogger().info("数据库表创建/检查完成");
        }
    }

    private void createIndexes(Statement stmt, boolean isMySQL) throws SQLException {
        String[][] indexes = {
            {"idx_reward_log_player", "reward_log", "player_uuid"},
            {"idx_reward_log_type", "reward_log", "reward_type"},
            {"idx_reward_log_time", "reward_log", "created_at"},
            {"idx_custom_reward_claims_player", "custom_reward_claims", "player_uuid"},
            {"idx_custom_reward_claims_reward", "custom_reward_claims", "reward_id"}
        };

        for (String[] index : indexes) {
            String indexName = index[0];
            String tableName = index[1];
            String columnName = index[2];

            try {
                if (isMySQL) {
                    stmt.execute("CREATE INDEX " + indexName + " ON " + tableName + " (" + columnName + ")");
                } else {
                    stmt.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + " (" + columnName + ")");
                }
            } catch (SQLException e) {
                String msg = e.getMessage().toLowerCase();
                if (msg.contains("duplicate") || msg.contains("already exists")) {
                    plugin.getLogger().fine("索引 " + indexName + " 已存在，跳过创建");
                } else {
                    throw e;
                }
            }
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();

            // 注销驱动
            try {
                ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
                java.util.Enumeration<Driver> drivers = DriverManager.getDrivers();
                while (drivers.hasMoreElements()) {
                    Driver driver = drivers.nextElement();
                    if (driver.getClass().getClassLoader() == pluginClassLoader) {
                        DriverManager.deregisterDriver(driver);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isMySQL() {
        String dbType = plugin.getConfigManager().getDatabaseType().toLowerCase();
        return dbType.equals("mysql") || dbType.equals("mariadb");
    }
}
