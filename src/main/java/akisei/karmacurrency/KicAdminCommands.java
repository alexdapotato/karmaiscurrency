package akisei.karmacurrency;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.List;

/** Operator-only inspection and explicit testing tools. */
public final class KicAdminCommands {
    private KicAdminCommands() {}
    private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }
    private static int tell(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false); return 1;
    }
    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text)); return 0;
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.getRoot().getChild("kd").addChild(admin("history")
            .then(Commands.literal("world")
                .executes(c -> history(c.getSource(), null, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> history(c.getSource(), null, IntegerArgumentType.getInteger(c,"page")))))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(c -> history(c.getSource(), EntityArgument.getPlayer(c,"player"),1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> history(c.getSource(), EntityArgument.getPlayer(c,"player"),IntegerArgumentType.getInteger(c,"page"))))).build());
        var apply = Commands.argument("player", EntityArgument.player());
        for (String type : List.of("karma", "reputation", "infamy", "all"))
            apply.then(Commands.literal(type).executes(c -> decay(c.getSource(),EntityArgument.getPlayer(c,"player"),type)));
        dispatcher.getRoot().getChild("kd").addChild(admin("decay")
            .then(Commands.literal("preview").then(Commands.argument("player",EntityArgument.player()).executes(c -> tell(c.getSource(),KarmaService.decayPreview(EntityArgument.getPlayer(c,"player"))))))
            .then(Commands.literal("apply").then(apply)).build());
        var world = admin("worldprogress").then(Commands.literal("check").executes(c -> tell(c.getSource(), CommunityProgressionService.get(c.getSource().getServer()).debugSummary())));
        for (String operation : List.of("add","remove","set")) world.then(Commands.literal(operation)
            .then(Commands.argument("amount",IntegerArgumentType.integer(0)).executes(c -> pool(c.getSource(),operation,IntegerArgumentType.getInteger(c,"amount")))));
        world.then(Commands.literal("stage").then(Commands.argument("stage",IntegerArgumentType.integer(0,5))
            .then(Commands.argument("threshold",IntegerArgumentType.integer(0)).executes(c -> threshold(c.getSource(),IntegerArgumentType.getInteger(c,"stage"),IntegerArgumentType.getInteger(c,"threshold"))))));
        dispatcher.getRoot().getChild("kd").addChild(world.build());
        var sigil = dispatcher.getRoot().getChild("kd").getChild("sigil");
        if (sigil != null) {
            var target = sigil.getRedirect() == null ? sigil : sigil.getRedirect();
            target.addChild(Commands.literal("inspect").then(Commands.argument("player",EntityArgument.player()).executes(c -> {
                var p=EntityArgument.getPlayer(c,"player");
                return tell(c.getSource(),"Sigils for "+p.getName().getString()+"\n"+CurseBlessingService.slotSummary(p)+"\n"+CurseBlessingService.sigilDetails(p));
            })).build());
        }
        var quest=dispatcher.getRoot().getChild("kd").getChild("quest");
        quest.addChild(admin("clear").then(Commands.argument("player",EntityArgument.player()).executes(c -> {
            var p=EntityArgument.getPlayer(c,"player");QuestService.resetPlayer(c.getSource().getServer(),p);
            KicHistoryService.recordEvent(p,"Debug quest reset by "+c.getSource().getTextName());
            return tell(c.getSource(),"Reset quest state for "+p.getName().getString()+". No currency or sigils changed.");
        })).build());
    }
    private static int history(CommandSourceStack s,ServerPlayer p,int page) {
        List<String> lines=p==null?KicHistoryService.contributions(s.getServer()):KicHistoryService.personal(p);
        int pages=Math.max(1,(lines.size()+7)/8);
        if(page>pages)return fail(s,"Page must be between 1 and "+pages+".");
        StringBuilder out=new StringBuilder((p==null?"World history":p.getName().getString()+" history")+" — "+page+"/"+pages);
        if(lines.isEmpty())out.append("\nNo recorded changes.");
        int start=lines.size()-1-(page-1)*8;
        for(int i=start;i>=Math.max(0,start-7);i--)out.append("\n").append(lines.get(i));
        return tell(s,out.toString());
    }
    private static int decay(CommandSourceStack s,ServerPlayer p,String type) {
        String before=KarmaService.format(KarmaService.get(p))+" Karma, "+SocialCurrencyService.getReputation(p)+" Reputation, "+KarmaService.getInfamyPoints(p)+" Infamy";
        if(type.equals("karma")||type.equals("all"))KarmaService.debugDecayKarmaOnce(p);
        if(type.equals("reputation")||type.equals("all"))KarmaService.applySocialDecay(p,true);
        if(type.equals("infamy")||type.equals("all"))KarmaService.applySocialDecay(p,false);
        KicHistoryService.recordEvent(p,"Debug one-interval "+type+" decay by "+s.getTextName());
        return tell(s,"Applied one "+type+" decay interval. Scheduled timers unchanged.\nBefore: "+before+"\nAfter: "+KarmaService.format(KarmaService.get(p))+" Karma, "+SocialCurrencyService.getReputation(p)+" Reputation, "+KarmaService.getInfamyPoints(p)+" Infamy");
    }
    private static int pool(CommandSourceStack s,String operation,int amount) {
        var data=CommunityProgressionService.get(s.getServer());int oldStage=data.stage(),before=data.pool();
        long next=switch(operation){case "add"->(long)before+amount;case "remove"->(long)before-amount;default->amount;};
        data.setPool((int)Math.clamp(next,0L,Integer.MAX_VALUE));
        refresh(s,oldStage);
        String result="World Influence: "+before+" → "+data.pool()+"; stage "+data.stage();
        KicHistoryService.recordWorld(s.getServer(),s.getTextName(),"Debug "+operation+": "+result);
        return tell(s,result);
    }
    private static int threshold(CommandSourceStack s,int stage,int value) {
        var data=CommunityProgressionService.get(s.getServer());int oldStage=data.stage();
        if(!data.setThreshold(stage,value))return fail(s,"Thresholds must increase between adjacent stages. Stage 0 is fixed at 0. No values changed.");
        refresh(s,oldStage);
        String result="Stage "+stage+" threshold set to "+value+". Community pool unchanged. Current stage: "+data.stage();
        KicHistoryService.recordWorld(s.getServer(),s.getTextName(),result);
        return tell(s,result);
    }
    private static void refresh(CommandSourceStack s,int oldStage) {
        for(var p:s.getServer().getPlayerList().getPlayers()) {
            KarmaService.updateHealthBonus(p);
            CurseBlessingService.syncCurseEffects(p);CurseBlessingService.syncBlessingEffects(p);
            CommunityProgressionService.sendStageIfChanged(p,oldStage);
        }
    }
}
