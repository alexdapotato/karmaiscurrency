package akisei.karmacurrency;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Registered only on operator-gated administrative branches. Mutations never award currency. */
public final class QuestDebugCommands {
    private QuestDebugCommands() {}
    private static int tell(CommandSourceStack s,String text){s.sendSuccess(()->Component.literal(text),false);return 1;}
    private static int fail(CommandSourceStack s,String message){s.sendFailure(Component.literal(message));return 0;}
    private static void audit(CommandSourceStack s,String action,ServerPlayer p){KarmaIsCurrency.LOGGER.info("Quest debug: {} {} {}",s.getTextName(),action,p.getUUID());KicHistoryService.recordEvent(p,"Debug quest "+action+" by "+s.getTextName());}
    public static LiteralArgumentBuilder<CommandSourceStack> inspectNode(String name){
        return Commands.literal(name).then(Commands.argument("player",EntityArgument.player()).executes(c->inspect(c.getSource(),EntityArgument.getPlayer(c,"player"))));
    }
    private static int inspect(CommandSourceStack s,ServerPlayer p){
        var q=QuestService.activeQuest(p);
        return tell(s,q==null?p.getName().getString()+": No active quest.":p.getName().getString()+"\n"+q.id()+" / "+QuestService.activeObjective(p,q).key()+"\n"+QuestService.status(p,q)+"\n"+QuestService.alignmentBlockReason(p,q)+"\n"+QuestService.rewardDescription(p,q)+"\n"+QuestService.bonusDescription(p,q)+"\nDebug payout held: "+(QuestRevamp.get(p,"debug_hold")!=0)
            +(q==QuestService.QuestId.SURVIVOR?"\nCredited minutes: "+QuestRevamp.get(p,"rewarded_ticks")/1200.0+"\nStarted: "+QuestRevamp.get(p,"started"):"")
            +(q==QuestService.QuestId.CHALLENGER?"\nTrial spawners: "+QuestRevamp.get(p,"encounters")+"/"+QuestService.activeObjective(p,q).targetCount():""));
    }
    private static int rotation(CommandSourceStack s, ServerPlayer p) {
        return tell(s,"Cycle "+KicCycle.currentCycleId()+"\nTrials: "+QuestService.currentTrials(p)+"\nContracts: "+QuestService.currentContracts(p)+"\nOffering pool: "+QuestRevamp.pool(QuestService.QuestId.OFFERING,KicCycle.currentCycleId()));
    }
    public static LiteralArgumentBuilder<CommandSourceStack> rotationNode(){return Commands.literal("rotation")
        .executes(c -> rotation(c.getSource(),c.getSource().getPlayerOrException()))
        .then(Commands.argument("player",EntityArgument.player()).executes(c -> rotation(c.getSource(),EntityArgument.getPlayer(c,"player"))));}
    public static LiteralArgumentBuilder<CommandSourceStack> rewardNode(){return Commands.literal("reward").then(Commands.literal("preview").then(Commands.argument("player",EntityArgument.player()).executes(c->inspect(c.getSource(),EntityArgument.getPlayer(c,"player")))));}
    public static LiteralArgumentBuilder<CommandSourceStack> node(){
        return Commands.literal("quest")
            .then(inspectNode("inspect"))
            .then(Commands.literal("limits").then(Commands.argument("player",EntityArgument.player()).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");StringBuilder out=new StringBuilder("Objective completion budgets this cycle:");
                java.util.Set<String> seen=new java.util.HashSet<>();
                for(var q:QuestService.QuestId.values())if(q!=QuestService.QuestId.DUEL&&q!=QuestService.QuestId.HUNT)
                    for(var o:QuestObjectiveCatalog.objectives(q)){String id=QuestRevamp.identity(q,o);if(seen.add(id))out.append("\n").append(id).append(": ").append(QuestRevamp.used(p,q,o)).append('/').append(QuestRevamp.limit(q));}
                out.append("\nEndurance next start in ").append(QuestService.formatDuration(Math.max(0,QuestRevamp.get(p,"endurance_next")-System.currentTimeMillis())));
                return tell(c.getSource(),out.toString());
            })))
            .then(Commands.literal("assign").then(Commands.argument("player",EntityArgument.player())
                .then(Commands.argument("quest",StringArgumentType.word()).suggests((c,b)->{for(var q:QuestService.QuestId.values())if(q!=QuestService.QuestId.DUEL && q!=QuestService.QuestId.COURAGE)b.suggest(q.id());return b.buildFuture();}).then(Commands.argument("objective",StringArgumentType.word()).suggests((c,b)->{var q=QuestService.QuestId.byId(StringArgumentType.getString(c,"quest"));if(q!=null)for(var o:QuestObjectiveCatalog.objectives(q))b.suggest(o.key());return b.buildFuture();}).executes(c->{
                    ServerPlayer p=EntityArgument.getPlayer(c,"player");var q=QuestService.QuestId.byId(StringArgumentType.getString(c,"quest"));
                    String objective=StringArgumentType.getString(c,"objective");
                    boolean ok=QuestService.debugAssign(p,q,objective);if(ok)audit(c.getSource(),"assign "+q+"/"+objective,p);
                    return ok ? tell(c.getSource(),"Quest assigned. Payout held until /kd quest payout <player>.") : fail(c.getSource(),"Cannot assign: check quest/objective ID, alignment, rules, active slot or objective limit.");
                })))) )
            .then(Commands.literal("progress").then(Commands.argument("player",EntityArgument.player()).then(Commands.argument("amount",IntegerArgumentType.integer(0,1000000)).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");var q=QuestService.activeQuest(p);if(q==null)return fail(c.getSource(),"This player has no active quest.");
                int n=IntegerArgumentType.getInteger(c,"amount");
                if(q==QuestService.QuestId.MERCY)QuestRevamp.put(p,"mercy_cures",Math.min(4,n));
                else if(q==QuestService.QuestId.HIDDEN_BLUE){QuestRevamp.put(p,"blue_catch_cycle",KicCycle.currentCycleId()+1);QuestRevamp.put(p,"blue_catches",Math.min(75,n));}
                else if(q==QuestService.QuestId.SURVIVOR)QuestRevamp.put(p,"rewarded_ticks",Math.min(432000L,n*1200L));
                else if(q==QuestService.QuestId.CHALLENGER)QuestRevamp.put(p,"encounters",Math.min(QuestService.activeObjective(p,q).targetCount(),n));
                else if(QuestService.activeObjective(p,q).kind()==QuestObjectiveCatalog.Kind.KILL||q==QuestService.QuestId.BLOOD_PRICE)KarmaPlayerState.set(p,"quest_progress_"+q.id(),n);
                else return fail(c.getSource(),"Progress overrides support kills, Challenger spawners, Endurance minutes, Mercy cures and fishing catches.");
                QuestRevamp.put(p,"debug_hold",1);audit(c.getSource(),"progress "+n,p);
                return tell(c.getSource(),"Progress set; automatic payout held. Use /kd quest payout <player> to explicitly claim, or cancel to discard.");
            }))))
            .then(Commands.literal("expire").then(Commands.argument("player",EntityArgument.player()).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");var q=QuestService.activeQuest(p);
                if(q==QuestService.QuestId.SURVIVOR)QuestRevamp.put(p,"started",System.currentTimeMillis()-QuestRevamp.WINDOW);
                else if(q==QuestService.QuestId.BLOOD_PRICE)KarmaPlayerState.set(p,"quest_started_at",System.currentTimeMillis()-QuestService.BLOOD_PRICE_DURATION_MILLIS);
                else return fail(c.getSource(),"Expire supports Endurance and Blood Price.");
                QuestRevamp.put(p,"debug_hold",1);audit(c.getSource(),"expire",p);return tell(c.getSource(),"Timer expired; payout held until the explicit payout command.");
            })))
            .then(Commands.literal("resume").then(Commands.argument("player",EntityArgument.player()).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");
                if(QuestService.activeQuest(p)==null)return fail(c.getSource(),"This player has no active quest.");
                QuestRevamp.put(p,"debug_hold",0);audit(c.getSource(),"resume",p);
                return tell(c.getSource(),"Debug payout hold removed; normal quest tracking and completion resumed.");
            })))
            .then(Commands.literal("payout").then(Commands.argument("player",EntityArgument.player()).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");var q=QuestService.activeQuest(p);if(q==null)return fail(c.getSource(),"This player has no active quest.");
                QuestRevamp.put(p,"debug_hold",0);boolean ok=KicMessages.transaction(p,"Debug quest payout",()->QuestService.complete(p,q));if(!ok)QuestRevamp.put(p,"debug_hold",1);
                audit(c.getSource(),"payout "+ok,p);return ok ? tell(c.getSource(),"Reward claimed once.") : fail(c.getSource(),"Requirements or alignment not met. Payout remains held.");
            })))
            .then(Commands.literal("reset").then(Commands.argument("player",EntityArgument.player()).then(Commands.argument("objective",StringArgumentType.word()).suggests((c,b)->{for(var q:QuestService.QuestId.values())for(var o:QuestObjectiveCatalog.objectives(q))b.suggest(QuestRevamp.identity(q,o));return b.buildFuture();}).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");String id=StringArgumentType.getString(c,"objective");
                for(var q:QuestService.QuestId.values())for(var o:QuestObjectiveCatalog.objectives(q))if(QuestRevamp.identity(q,o).equals(id)){
                    QuestRevamp.put(p,"cycle_"+id,0);QuestRevamp.put(p,"count_"+id,0);
                    if(q==QuestService.QuestId.SURVIVOR)QuestRevamp.put(p,"endurance_next",0);
                    if(q==QuestService.QuestId.HIDDEN_BLUE){QuestRevamp.put(p,"blue_attempt_cycle",0);QuestRevamp.put(p,"blue_next_cycle",0);}
                    audit(c.getSource(),"reset "+id,p);return tell(c.getSource(),"Reset only "+id+". No currency changed.");
                }
                return fail(c.getSource(),"Unknown objective identity. Use /kd quest limits <player>.");
            }))));
    }
}
