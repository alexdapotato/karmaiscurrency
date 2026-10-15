package akisei.karmacurrency;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Registered only under the operator-gated /karma debug tree. Mutations never award currency. */
public final class QuestDebugCommands {
    private QuestDebugCommands() {}
    private static int tell(CommandSourceStack s,String text){s.sendSuccess(()->Component.literal(text),false);return 1;}
    private static void audit(CommandSourceStack s,String action,ServerPlayer p){KarmaIsCurrency.LOGGER.info("Quest debug: {} {} {}",s.getTextName(),action,p.getUUID());}
    public static LiteralArgumentBuilder<CommandSourceStack> inspectNode(String name){
        return Commands.literal(name).then(Commands.argument("player",EntityArgument.player()).executes(c->inspect(c.getSource(),EntityArgument.getPlayer(c,"player"))));
    }
    private static int inspect(CommandSourceStack s,ServerPlayer p){
        var q=QuestService.activeQuest(p);
        return tell(s,q==null?"No active quest.":q.id()+" / "+QuestService.activeObjective(p,q).key()+"\n"+QuestService.status(p,q)+"\n"+QuestService.alignmentBlockReason(p,q)+"\n"+QuestService.rewardDescription(p,q)+"\n"+QuestService.bonusDescription(p,q));
    }
    public static LiteralArgumentBuilder<CommandSourceStack> rotationNode(){return Commands.literal("rotation").executes(c->{
        ServerPlayer p=c.getSource().getPlayerOrException();
        return tell(c.getSource(),"Cycle "+KicCycle.currentCycleId()+"\nTrials: "+QuestService.currentTrials(p)+"\nContracts: "+QuestService.currentContracts(p)+"\nOffering pool: "+QuestRevamp.pool(QuestService.QuestId.OFFERING,KicCycle.currentCycleId()));
    });}
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
                .then(Commands.argument("quest",StringArgumentType.word()).then(Commands.argument("objective",StringArgumentType.word()).executes(c->{
                    ServerPlayer p=EntityArgument.getPlayer(c,"player");var q=QuestService.QuestId.byId(StringArgumentType.getString(c,"quest"));
                    String objective=StringArgumentType.getString(c,"objective");
                    boolean ok=QuestService.debugAssign(p,q,objective);if(ok)audit(c.getSource(),"assign "+q+"/"+objective,p);
                    return tell(c.getSource(),ok?"Assigned debug quest. Payout is held until /karma debug quest payout <player>.":"Cannot assign: check quest/objective ID, alignment, enabled rules, active slot or objective limit.");
                })))) )
            .then(Commands.literal("progress").then(Commands.argument("player",EntityArgument.player()).then(Commands.argument("amount",IntegerArgumentType.integer(0,1000000)).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");var q=QuestService.activeQuest(p);if(q==null)return 0;
                int n=IntegerArgumentType.getInteger(c,"amount");
                if(q==QuestService.QuestId.SURVIVOR)QuestRevamp.put(p,"rewarded_ticks",Math.min(432000L,n*1200L));
                else if(q==QuestService.QuestId.CHALLENGER)QuestRevamp.put(p,"encounters",Math.min(QuestService.activeObjective(p,q).targetCount(),n));
                else if(QuestService.activeObjective(p,q).kind()==QuestObjectiveCatalog.Kind.KILL||q==QuestService.QuestId.BLOOD_PRICE)KarmaPlayerState.set(p,"quest_progress_"+q.id(),n);
                else return tell(c.getSource(),"Progress overrides support kill counts, Challenger spawners, and Endurance minutes only.");
                QuestRevamp.put(p,"debug_hold",1);audit(c.getSource(),"progress "+n,p);
                return tell(c.getSource(),"Progress set; automatic payout held. Use /karma debug quest payout <player> to explicitly claim, or cancel to discard.");
            }))))
            .then(Commands.literal("expire").then(Commands.argument("player",EntityArgument.player()).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");var q=QuestService.activeQuest(p);
                if(q==QuestService.QuestId.SURVIVOR)QuestRevamp.put(p,"started",System.currentTimeMillis()-QuestRevamp.WINDOW);
                else if(q==QuestService.QuestId.BLOOD_PRICE)KarmaPlayerState.set(p,"quest_started_at",System.currentTimeMillis()-QuestService.BLOOD_PRICE_DURATION_MILLIS);
                else return tell(c.getSource(),"Expire supports Endurance and Blood Price.");
                QuestRevamp.put(p,"debug_hold",1);audit(c.getSource(),"expire",p);return tell(c.getSource(),"Timer expired; payout held until the explicit payout command.");
            })))
            .then(Commands.literal("payout").then(Commands.argument("player",EntityArgument.player()).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");var q=QuestService.activeQuest(p);if(q==null)return 0;
                QuestRevamp.put(p,"debug_hold",0);boolean ok=QuestService.complete(p,q);if(!ok)QuestRevamp.put(p,"debug_hold",1);
                audit(c.getSource(),"payout "+ok,p);return tell(c.getSource(),ok?"Reward claimed once.":"Requirements or alignment not met. Payout remains held.");
            })))
            .then(Commands.literal("reset").then(Commands.argument("player",EntityArgument.player()).then(Commands.argument("objective",StringArgumentType.word()).executes(c->{
                ServerPlayer p=EntityArgument.getPlayer(c,"player");String id=StringArgumentType.getString(c,"objective");
                for(var q:QuestService.QuestId.values())for(var o:QuestObjectiveCatalog.objectives(q))if(QuestRevamp.identity(q,o).equals(id)){
                    QuestRevamp.put(p,"cycle_"+id,0);QuestRevamp.put(p,"count_"+id,0);
                    if(q==QuestService.QuestId.SURVIVOR)QuestRevamp.put(p,"endurance_next",0);
                    audit(c.getSource(),"reset "+id,p);return tell(c.getSource(),"Reset only "+id+". No currency changed.");
                }
                return tell(c.getSource(),"Unknown objective identity. Copy one from quest limits.");
            }))));
    }
}
