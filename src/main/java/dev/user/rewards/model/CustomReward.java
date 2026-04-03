package dev.user.rewards.model;

/**
 * 自定义奖励数据模型
 */
public class CustomReward {
    private final String rewardId;
    private String displayName;
    private String description;
    private double money;
    private int points;
    private int maxClaimCount;
    private long expireAt;
    private long createdAt;
    private String createdBy;
    private boolean enabled;

    public CustomReward(String rewardId, String displayName, String description,
                        double money, int points, int maxClaimCount, long expireAt,
                        long createdAt, String createdBy, boolean enabled) {
        this.rewardId = rewardId;
        this.displayName = displayName;
        this.description = description;
        this.money = money;
        this.points = points;
        this.maxClaimCount = maxClaimCount;
        this.expireAt = expireAt;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.enabled = enabled;
    }

    // ==================== Getters ====================

    public String getRewardId() { return rewardId; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public double getMoney() { return money; }
    public int getPoints() { return points; }
    public int getMaxClaimCount() { return maxClaimCount; }
    public long getExpireAt() { return expireAt; }
    public long getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public boolean isEnabled() { return enabled; }

    // ==================== Setters ====================

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setDescription(String description) { this.description = description; }
    public void setMoney(double money) { this.money = money; }
    public void setPoints(int points) { this.points = points; }
    public void setMaxClaimCount(int maxClaimCount) { this.maxClaimCount = maxClaimCount; }
    public void setExpireAt(long expireAt) { this.expireAt = expireAt; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // ==================== 辅助方法 ====================

    /**
     * 检查奖励是否已过期
     */
    public boolean isExpired() {
        return expireAt > 0 && System.currentTimeMillis() > expireAt;
    }

    /**
     * 检查玩家是否可以领取
     * @param currentClaimCount 玩家当前已领取次数
     */
    public boolean canClaim(int currentClaimCount) {
        if (!enabled || isExpired()) return false;
        if (maxClaimCount < 0) return true; // 无限次数
        return currentClaimCount < maxClaimCount;
    }

    /**
     * 获取剩余可领取次数
     * @param currentClaimCount 玩家当前已领取次数
     */
    public int getRemainingClaims(int currentClaimCount) {
        if (maxClaimCount < 0) return -1; // 无限
        return Math.max(0, maxClaimCount - currentClaimCount);
    }
}