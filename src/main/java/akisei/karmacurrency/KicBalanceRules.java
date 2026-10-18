package akisei.karmacurrency;

/** Pure balance calculations shared by execution, previews, and regression tests. */
public final class KicBalanceRules {
    private KicBalanceRules() {}
    public static int socialDecay(int balance, int percent) {
        return (int)((long)Math.max(0,balance) * Math.max(0,Math.min(100,percent)) / 100L);
    }
    public static int protectionCost(int currentStacks) { return currentStacks <= 0 ? 60 : 75; }
    public static int hasteCost(int currentStacks) { return 60 * Math.max(1,Math.min(3,currentStacks+1)); }
    public static int fetteredArmor(int stacks) { return stacks <= 0 ? 0 : (int)Math.floor(2 + 1.5 * (Math.min(4,stacks)-1)); }
    public static double fetteredMovement(int stacks) { return stacks <= 0 ? 0 : stacks >= 3 ? -0.10 : -0.05; }
    public static int stage(int pool, int[] thresholds) {
        int stage=0;
        for(int threshold:thresholds) { if(pool<threshold)break; stage++; }
        return stage;
    }
    public static boolean validThreshold(int stage,int threshold,int[] thresholds) {
        if(stage==0)return threshold==0;
        if(stage<1||stage>thresholds.length||threshold<=0)return false;
        return threshold>(stage==1?0:thresholds[stage-2]) && (stage==thresholds.length||threshold<thresholds[stage]);
    }
}
