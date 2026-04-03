package dev.user.rewards.economy;

import dev.user.rewards.SimpleRewardsPlugin;
import me.yic.xconomy.api.XConomyAPI;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 经济管理器（软依赖 XConomy）
 * 处理金币相关操作
 * 异步回调在队列线程中直接调用，调用方负责调度到合适的线程
 */
public class EconomyManager {

    private final SimpleRewardsPlugin plugin;
    private XConomyAPI xconomyAPI;
    private boolean enabled = false;

    // 异步任务队列
    private BlockingQueue<EconomyTask<?>> taskQueue;
    private ExecutorService executor;
    private volatile boolean running = true;

    public EconomyManager(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            xconomyAPI = new XConomyAPI();
            enabled = true;
            plugin.getLogger().info("已连接到 XConomy 经济系统");

            this.taskQueue = new LinkedBlockingQueue<>();
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SimpleRewards-Economy-Queue");
                t.setDaemon(true);
                return t;
            });
            startProcessing();
        } catch (Exception e) {
            plugin.getLogger().info("XConomy 未找到，经济功能不可用: " + e.getMessage());
            enabled = false;
        }
    }

    private void startProcessing() {
        executor.submit(() -> {
            while (running || !taskQueue.isEmpty()) {
                try {
                    EconomyTask<?> task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        processTask(task);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> void processTask(EconomyTask<T> task) {
        try {
            T result;
            switch (task.getType()) {
                case GET_BALANCE -> result = (T) Double.valueOf(getBalanceSync(task.getUuid()));
                case HAS_ENOUGH -> result = (T) Boolean.valueOf(hasEnoughSync(task.getUuid(), task.getAmount()));
                case WITHDRAW -> result = (T) Boolean.valueOf(withdrawSync(task.getUuid(), task.getName(), task.getAmount()));
                case DEPOSIT -> result = (T) Boolean.valueOf(depositSync(task.getUuid(), task.getName(), task.getAmount()));
                default -> throw new IllegalStateException("未知任务类型: " + task.getType());
            }

            if (task.getCallback() != null) {
                try {
                    ((Consumer<T>) task.getCallback()).accept(result);
                } catch (Exception e) {
                    plugin.getLogger().warning("经济操作回调执行失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("经济操作失败 [" + task.getType() + "]: " + e.getMessage());

            if (task.getErrorCallback() != null) {
                task.getErrorCallback().accept(e);
            }
        }
    }

    public void shutdown() {
        running = false;
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== 同步方法 ====================

    private double getBalanceSync(UUID uuid) {
        if (!enabled) return 0;
        try {
            BigDecimal bal = xconomyAPI.getPlayerData(uuid).getBalance();
            return bal.doubleValue();
        } catch (Exception e) {
            plugin.getLogger().warning("获取余额失败: " + e.getMessage());
            return 0;
        }
    }

    private boolean withdrawSync(UUID uuid, String name, double amount) {
        if (!enabled) return false;
        if (amount <= 0) return true;
        try {
            BigDecimal bal = xconomyAPI.getPlayerData(uuid).getBalance();
            if (bal.compareTo(BigDecimal.valueOf(amount)) < 0) {
                return false;
            }
            int result = xconomyAPI.changePlayerBalance(
                uuid,
                name,
                BigDecimal.valueOf(amount),
                false
            );
            return result == 0;
        } catch (Exception e) {
            plugin.getLogger().warning("扣除金钱失败: " + e.getMessage());
            return false;
        }
    }

    private boolean depositSync(UUID uuid, String name, double amount) {
        if (!enabled) return false;
        if (amount <= 0) return true;
        try {
            int result = xconomyAPI.changePlayerBalance(
                uuid,
                name,
                BigDecimal.valueOf(amount),
                true
            );
            return result == 0;
        } catch (Exception e) {
            plugin.getLogger().warning("给予金钱失败: " + e.getMessage());
            return false;
        }
    }

    private boolean hasEnoughSync(UUID uuid, double amount) {
        if (!enabled || amount <= 0) return true;
        return getBalanceSync(uuid) >= amount;
    }

    // ==================== 同步方法（供主线程直接调用） ====================

    public double getBalance(Player player) {
        return getBalanceSync(player.getUniqueId());
    }

    public boolean withdraw(Player player, double amount) {
        return withdrawSync(player.getUniqueId(), player.getName(), amount);
    }

    public boolean deposit(Player player, double amount) {
        return depositSync(player.getUniqueId(), player.getName(), amount);
    }

    public boolean hasEnough(Player player, double amount) {
        return hasEnoughSync(player.getUniqueId(), amount);
    }

    // ==================== 异步方法 ====================

    public void getBalanceAsync(Player player, Consumer<Double> callback) {
        getBalanceAsync(player, callback, null);
    }

    public void getBalanceAsync(Player player, Consumer<Double> callback, Consumer<Exception> errorCallback) {
        submitTask(TaskType.GET_BALANCE, player.getUniqueId(), player.getName(), 0, callback, errorCallback);
    }

    public void withdrawAsync(Player player, double amount, Consumer<Boolean> callback) {
        withdrawAsync(player, amount, callback, null);
    }

    public void withdrawAsync(Player player, double amount, Consumer<Boolean> callback, Consumer<Exception> errorCallback) {
        submitTask(TaskType.WITHDRAW, player.getUniqueId(), player.getName(), amount, callback, errorCallback);
    }

    public void depositAsync(Player player, double amount, Consumer<Boolean> callback) {
        depositAsync(player, amount, callback, null);
    }

    public void depositAsync(Player player, double amount, Consumer<Boolean> callback, Consumer<Exception> errorCallback) {
        submitTask(TaskType.DEPOSIT, player.getUniqueId(), player.getName(), amount, callback, errorCallback);
    }

    public void hasEnoughAsync(Player player, double amount, Consumer<Boolean> callback) {
        hasEnoughAsync(player, amount, callback, null);
    }

    public void hasEnoughAsync(Player player, double amount, Consumer<Boolean> callback, Consumer<Exception> errorCallback) {
        submitTask(TaskType.HAS_ENOUGH, player.getUniqueId(), player.getName(), amount, callback, errorCallback);
    }

    // ==================== 内部辅助方法 ====================

    @SuppressWarnings("unchecked")
    private <T> void submitTask(TaskType type, UUID uuid, String name, double amount, Consumer<T> callback, Consumer<Exception> errorCallback) {
        if (!running || taskQueue == null) {
            plugin.getLogger().warning("经济队列已关闭，无法提交任务: " + type);
            return;
        }

        EconomyTask<T> task = new EconomyTask<>(type, uuid, name, amount, callback, errorCallback);
        try {
            taskQueue.offer(task, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("提交经济任务被中断: " + type);
        }
    }

    public String format(double amount) {
        return String.format("%.2f", amount);
    }

    // ==================== 内部类和枚举 ====================

    private enum TaskType {
        GET_BALANCE,
        HAS_ENOUGH,
        WITHDRAW,
        DEPOSIT
    }

    private static class EconomyTask<T> {
        private final TaskType type;
        private final UUID uuid;
        private final String name;
        private final double amount;
        private final Consumer<T> callback;
        private final Consumer<Exception> errorCallback;

        public EconomyTask(TaskType type, UUID uuid, String name, double amount, Consumer<T> callback, Consumer<Exception> errorCallback) {
            this.type = type;
            this.uuid = uuid;
            this.name = name;
            this.amount = amount;
            this.callback = callback;
            this.errorCallback = errorCallback;
        }

        public TaskType getType() { return type; }
        public UUID getUuid() { return uuid; }
        public String getName() { return name; }
        public double getAmount() { return amount; }
        public Consumer<T> getCallback() { return callback; }
        public Consumer<Exception> getErrorCallback() { return errorCallback; }
    }
}
