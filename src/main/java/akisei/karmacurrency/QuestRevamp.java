package akisei.karmacurrency;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import static akisei.karmacurrency.QuestService.QuestId;

/** Persistent quest rules; short-lived stat baselines never read stat files. */
public final class QuestRevamp {
    private QuestRevamp() {}
    public static final long WINDOW = 12L*60*60*1000;
    private static final Map<UUID, Sample> samples = new HashMap<>();
    private record Sample(ServerPlayer player, long tick, long[] stats, boolean eligible) {}
    private static final String[] MOVEMENT = {"walk_one_cm","sprint_one_cm","crouch_one_cm","swim_one_cm","walk_under_water_one_cm","walk_on_water_one_cm","climb_one_cm","horse_one_cm","pig_one_cm","strider_one_cm","boat_one_cm","aviate_one_cm","minecart_one_cm"};
    private static final int[] WEIGHTS = {100,100,100,100,100,100,100,75,75,75,50,25,25};
    static long get(ServerPlayer p,String k) {return KarmaPlayerState.get(p,"q2_"+k);}
    static void put(ServerPlayer p,String k,long v) {KarmaPlayerState.set(p,"q2_"+k,v);}
    public static void migrate(ServerPlayer p) {
        if(get(p,"version")>=1)return;
        // Catalog indices have changed. Never reinterpret an old accepted objective.
        boolean active=KarmaPlayerState.get(p,"quest_active_0")!=0;
        for(int i=0;i<3;i++)KarmaPlayerState.clear(p,"quest_active_"+i);
        QuestService.clearPlayerQuestState(p);
        LightShrineService.cancelQuestTrial(p);
        put(p,"version",1);
        if(active)p.sendSystemMessage(Component.literal("Quest rules updated: your old quest was reset without taking items or spending a completion."));
    }
    public static boolean submission(QuestId q) {return q==QuestId.ABUNDANCE||q==QuestId.COLLECTOR||q==QuestId.OFFERING;}
    public static QuestService.Type pool(QuestId q,long cycle) {
        if(q==QuestId.OFFERING)return (cycle&1)==0?QuestService.Type.TRIAL:QuestService.Type.CONTRACT;
        return q.type();
    }
    public static boolean inPool(QuestId q,QuestService.Type t) {return q==QuestId.CHALLENGER||pool(q,KicCycle.currentCycleId())==t;}
    public static int side(ServerPlayer p,QuestId q) {
        if(p!=null && QuestService.isStoredActive(p,q))return (int)get(p,"side");
        if(q==QuestId.CHALLENGER)return p==null?1:QuestService.alignmentCode(p);
        return pool(q,KicCycle.currentCycleId())==QuestService.Type.TRIAL?1:-1;
    }
    public static boolean eligible(ServerPlayer p,QuestId q) {
        return p!=null && !p.isCreative()&&!p.isSpectator()&&p.isAlive() && QuestService.alignmentCode(p)!=0 && QuestService.alignmentCode(p)==side(p,q);
    }
    static String identity(QuestId q,QuestObjectiveCatalog.Objective o) {
        // Quantity/difficulty variants of a single resource share one completion budget.
        String key=q==QuestId.ABUNDANCE?o.key().replaceFirst("_\\d+$",""):o.key();
        return q.id()+"_"+key;
    }
    public static int limit(QuestId q) {return submission(q)||q==QuestId.EXECUTIONER?2:1;}
    public static long used(ServerPlayer p,QuestId q,QuestObjectiveCatalog.Objective o) {
        String k=identity(q,o);return get(p,"cycle_"+k)==KicCycle.currentCycleId()+1?get(p,"count_"+k):0;
    }
    public static boolean available(ServerPlayer p,QuestId q,QuestObjectiveCatalog.Objective o) {
        if(q==QuestId.SURVIVOR)return System.currentTimeMillis()>=get(p,"endurance_next");
        return used(p,q,o)<limit(q);
    }
    public static void completed(ServerPlayer p,QuestId q,QuestObjectiveCatalog.Objective o) {
        String k=identity(q,o);long n=used(p,q,o);put(p,"cycle_"+k,KicCycle.currentCycleId()+1);put(p,"count_"+k,n+1);
    }
    public static void start(ServerPlayer p,QuestId q) {
        put(p,"side",QuestService.alignmentCode(p));
        put(p,"accepted_cycle",KicCycle.currentCycleId());
        put(p,"submitted",0);put(p,"confirm_until",0);put(p,"debug_hold",0);
        put(p,"started",Math.max(System.currentTimeMillis(),get(p,"started")+1));
        if(q==QuestId.SURVIVOR){
            KarmaPlayerState.clearPrefix(p,"q2_kill_area_");
            put(p,"endurance_next",System.currentTimeMillis()+WINDOW);
            for(String k:List.of("credit","rewarded_ticks","kills_half","damage","area_count"))put(p,k,0);
            samples.remove(p.getUUID());
        }
        if(q==QuestId.CHALLENGER){KarmaPlayerState.clearPrefix(p,"q2_spawner_");put(p,"encounters",0);put(p,"group_sum",0);}
        if(q==QuestId.PILGRIMAGE){put(p,"route_count",0);put(p,"route_distance",0);put(p,"route_done",0);}
    }
    public static boolean expired(ServerPlayer p) {return System.currentTimeMillis()-get(p,"started")>=WINDOW;}
    public static boolean ready(ServerPlayer p,QuestId q) {
        if(submission(q))return get(p,"submitted")==1;
        if(q==QuestId.SURVIVOR)return expired(p);
        if(q==QuestId.CHALLENGER)return get(p,"encounters")>=QuestService.activeObjective(p,q).targetCount();
        return q==QuestId.PILGRIMAGE&&get(p,"route_done")==1;
    }
    public static boolean matches(ItemStack stack,Item item) {
        return !SocialTokenService.isToken(stack) && (item==Items.OAK_LOG?KarmaOfferingConfig.isLog(stack.getItem()):stack.is(item));
    }
    public static int count(ServerPlayer p,Item item) {
        int n=0;for(ItemStack s:p.getInventory().getNonEquipmentItems())if(matches(s,item))n+=s.getCount();return n;
    }
    /** First invocation creates a 30-second preview; confirmation rechecks the entire bundle. */
    public static boolean submit(ServerPlayer p,boolean confirm) {
        QuestId q=QuestService.activeQuest(p);
        if(!submission(q)||get(p,"debug_hold")==1||!eligible(p,q)||!KarmaGameRules.enabled(p,KarmaGameRules.QUESTS_ENABLED))return false;
        if(!KarmaGameRules.enabled(p,side(p,q)>0?KarmaGameRules.TRIALS_ENABLED:KarmaGameRules.CONTRACTS_ENABLED))return false;
        var o=QuestService.activeObjective(p,q);
        if(!confirm){put(p,"confirm_until",System.currentTimeMillis()+30_000);p.sendSystemMessage(Component.literal(o.description()+". Items will be consumed. Reward: "+preview(p,q,o)+" Use /quest submit confirm within 30 seconds."));return true;}
        if(System.currentTimeMillis()>get(p,"confirm_until"))return false;
        put(p,"confirm_until",0);
        if(!available(p,q,o))return false;
        // No callbacks/yields between complete validation and consumption on the server thread.
        for(var r:o.requirements())if(count(p,r.item())<r.count()){p.sendSystemMessage(Component.literal("Missing required items. Nothing was consumed."));return false;}
        for(var r:o.requirements()){
            int left=r.count();for(ItemStack s:p.getInventory().getNonEquipmentItems())if(matches(s,r.item())){int n=Math.min(left,s.getCount());s.shrink(n);left-=n;if(left==0)break;}
        }
        p.getInventory().setChanged();put(p,"submitted",1);
        return QuestService.complete(p,q);
    }
    public static int enduranceBase(ServerPlayer p) {return (int)Math.min(160,(get(p,"rewarded_ticks")*40+get(p,"kills_half")*72000)/144000);}
    public static int socialReward(ServerPlayer p,QuestId q,QuestObjectiveCatalog.Objective o) {
        if(submission(q))return QuestObjectiveCatalog.fixedOfferingNet(o)+o.difficulty()*4;
        if(q==QuestId.SURVIVOR)return enduranceBase(p);
        if(q==QuestId.PILGRIMAGE)return p==null?24:(int)Math.min(64,24+get(p,"route_distance")/512);
        if(q==QuestId.CHALLENGER)return 8*o.targetCount()+(int)(8*get(p,"group_sum")/100);
        return 0;
    }
    public static String preview(ServerPlayer p,QuestId q,QuestObjectiveCatalog.Objective o) {
        if(q==QuestId.SURVIVOR)return "20 Reputation per rewarded hour (max 6h), +0.5 per eligible hostile (max 40), Karma=floor(base/4), Untouchable +20 after at least 1h";
        if(q==QuestId.PILGRIMAGE)return "24–64 Reputation based on direct route length, +15 Karma";
        if(q==QuestId.CHALLENGER)return (8*o.targetCount())+" social currency, "+(2*o.targetCount())+" signed Karma; group bonus up to 30% social";
        return socialReward(p,q,o)+ (side(p,q)>0?" Reputation, +":" Infamy, -")+(q==QuestId.PILGRIMAGE?15:5*o.difficulty())+" Karma";
    }
    public static boolean reward(ServerPlayer p,QuestId q,QuestObjectiveCatalog.Objective o) {
        if(!submission(q)&&q!=QuestId.SURVIVOR&&q!=QuestId.PILGRIMAGE&&q!=QuestId.CHALLENGER)return false;
        int social=socialReward(p,q,o), sign=(int)get(p,"side");
        int karma=q==QuestId.SURVIVOR?Math.min(40,social/4):q==QuestId.PILGRIMAGE?15:q==QuestId.CHALLENGER?2*o.targetCount():5*o.difficulty();
        if(q==QuestId.SURVIVOR&&get(p,"rewarded_ticks")>=72000&&get(p,"damage")==0&&KarmaGameRules.enabled(p,KarmaGameRules.BONUS_OBJECTIVES_ENABLED))social+=20;
        if(sign>0)SocialCurrencyService.addReputation(p,social,q.displayName());else KarmaService.addInfamyPoints(p,social,q.displayName());
        KarmaService.addQuietly(p,KarmaService.karma(sign*karma),q.displayName());return true;
    }
    public static void damage(ServerPlayer p) {if(QuestService.activeQuest(p)==QuestId.SURVIVOR)put(p,"damage",1);}
    public static void kill(ServerPlayer p) {
        if(expired(p))return;
        // One 64-block area: first 20 kills full, next 20 half, then none for this run.
        String area=p.level().dimension().identifier()+":"+(p.blockPosition().getX()>>6)+":"+(p.blockPosition().getZ()>>6);
        String key="kill_area_"+area;
        if(get(p,key+"_run")!=get(p,"started")){put(p,key+"_run",get(p,"started"));put(p,key,0);}
        long n=get(p,key);put(p,key,n+1);
        long half=get(p,"kills_half");if(n<20||(n<40&&n%2==0))put(p,"kills_half",Math.min(80,half+1));
    }
    public static void pruneSamples(java.util.Collection<ServerPlayer> online) {
        Set<UUID> ids=new HashSet<>();for(ServerPlayer p:online)ids.add(p.getUUID());samples.keySet().retainAll(ids);
    }
    public static void tick(ServerPlayer p,QuestId q,boolean eligible) {
        if(q==QuestId.SURVIVOR){
            if(!eligible)samples.remove(p.getUUID());
            long tick=p.level().getServer().getTickCount();
            if(tick%200!=0)return;
            long[] now=new long[MOVEMENT.length];
            for(int i=0;i<now.length;i++)now[i]=p.getStats().getValue(Stats.CUSTOM.get(Identifier.parse("minecraft:"+MOVEMENT[i])));
            Sample old=samples.put(p.getUUID(),new Sample(p,tick,now,eligible));
            if(!eligible||expired(p)||old==null||old.player()!=p||!old.eligible()||tick-old.tick()>200||tick<=old.tick())return;
            long credit=get(p,"credit");
            for(int i=0;i<now.length;i++)credit+=Math.max(0,now[i]-old.stats()[i])*WEIGHTS[i];
            // Units: 1/100 cm. 2,000 blocks = 20,000,000 units per rewarded hour.
            long dt=tick-old.tick();
            long spend=Math.min(Math.min(credit*72000/20_000_000,dt),432000-get(p,"rewarded_ticks"));
            put(p,"rewarded_ticks",get(p,"rewarded_ticks")+Math.max(0,spend));
            put(p,"credit",Math.min(20_000_000,Math.max(0,credit-spend*20_000_000/72000)));
        } else if(q==QuestId.PILGRIMAGE && eligible && p.level().getServer().getTickCount()%20==0)pilgrimage(p);
    }
    private static void pilgrimage(ServerPlayer p) {
        if(!"minecraft:overworld".equals(p.level().dimension().identifier().toString()))return;
        for(ShrineSite site:ShrineState.get(p.level().getServer()).sites()){
            if(site.lodestonePosition().isEmpty())continue;
            BlockPos pos=site.actualLodestonePosition();if(p.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>64)continue;
            int n=(int)get(p,"route_count");
            if(n==0){put(p,"route_0",pos.asLong());put(p,"route_count",1);return;}
            BlockPos last=BlockPos.of(get(p,"route_"+(n-1)));
            double distance=Math.sqrt(last.distSqr(pos));if(distance<256)return;
            if(n==3){if(pos.asLong()==get(p,"route_0")){put(p,"route_distance",get(p,"route_distance")+(long)distance);put(p,"route_done",1);QuestService.complete(p,QuestId.PILGRIMAGE);}return;}
            for(int i=0;i<n;i++)if(pos.asLong()==get(p,"route_"+i))return;
            put(p,"route_"+n,pos.asLong());put(p,"route_count",n+1);put(p,"route_distance",get(p,"route_distance")+(long)distance);return;
        }
    }
    public static void encounter(ServerLevel level,BlockPos pos,Set<UUID> participants) {
        List<ServerPlayer> qualifying=new ArrayList<>();
        for(UUID id:participants){ServerPlayer p=level.getServer().getPlayerList().getPlayer(id);
            if(p!=null&&p.level()==level&&QuestService.activeQuest(p)==QuestId.CHALLENGER&&eligible(p,QuestId.CHALLENGER)
                &&KarmaGameRules.enabled(p,KarmaGameRules.QUESTS_ENABLED)&&KarmaGameRules.enabled(p,side(p,QuestId.CHALLENGER)>0?KarmaGameRules.TRIALS_ENABLED:KarmaGameRules.CONTRACTS_ENABLED)
                &&p.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)<=2500)qualifying.add(p);
        }
        for(ServerPlayer p:qualifying){String key="spawner_"+level.dimension().identifier()+":"+pos.asLong();
            if(get(p,key)==get(p,"started"))continue;
            put(p,key,get(p,"started"));put(p,"encounters",get(p,"encounters")+1);
            put(p,"group_sum",get(p,"group_sum")+Math.min(30,10*(qualifying.size()-1)));
            if(ready(p,QuestId.CHALLENGER))QuestService.complete(p,QuestId.CHALLENGER);
        }
    }
    public static String status(ServerPlayer p,QuestId q) {
        if(q==QuestId.SURVIVOR)return "Rewarded time: "+get(p,"rewarded_ticks")/1200+"m / 360m; bank: "+get(p,"credit")/10000+" weighted blocks; kill reward: "+get(p,"kills_half")/2.0+"; base: "+enduranceBase(p)+"; Untouchable: "+(get(p,"damage")==0)+"; window: "+QuestService.formatDuration(Math.max(0,WINDOW-(System.currentTimeMillis()-get(p,"started"))));
        if(q==QuestId.CHALLENGER)return "Distinct spawners: "+get(p,"encounters")+"/"+QuestService.activeObjective(p,q).targetCount()+"; group bonus sum: "+get(p,"group_sum")+"%";
        if(q==QuestId.PILGRIMAGE)return "Shrines visited: "+get(p,"route_count")+"/3; return to first after third; route: "+get(p,"route_distance")+" blocks";
        return "";
    }
}
