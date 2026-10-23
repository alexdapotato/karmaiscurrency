package akisei.karmacurrency.mixin;

import akisei.karmacurrency.CurseBlessingService;
import akisei.karmacurrency.KarmaPlayerState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies server-side Curse combat effects. */
@Mixin(LivingEntity.class)
public abstract class CurseCombatMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float karmaiscurrency$modifyDamage(float amount, ServerLevel level, DamageSource source) {
        LivingEntity victim = (LivingEntity) (Object) this;
        ServerPlayer attacker = source.getEntity() instanceof ServerPlayer player ? player : null;

        if (attacker != null && CurseBlessingService.hasCurse(attacker, "strength")) {
            int stacks = CurseBlessingService.curseStack(attacker, "strength");
            amount += 0.5F * stacks;
        }

        if (source.getDirectEntity() instanceof AbstractArrow arrow
            && arrow.getOwner() instanceof ServerPlayer archer
            && CurseBlessingService.hasCurse(archer, "archer")) {
            amount += 0.75F;
        }

        if (attacker != null && CurseBlessingService.hasCurse(attacker, "justice")) {
            amount += 1.0F * CurseBlessingService.curseStack(attacker, "justice");
        }

        if (attacker != null && CurseBlessingService.hasCurse(attacker, "predator") && !(victim instanceof ServerPlayer)) {
            amount *= 1.20F;
        }

        if (attacker != null && CurseBlessingService.hasBlessing(attacker, "hunter") && victim instanceof Enemy) {
            amount *= 1.0F + 0.05F * CurseBlessingService.blessingStack(attacker, "hunter");
        }

        if (attacker != null && CurseBlessingService.hasCurse(attacker, "duelist")) {
            amount *= victim instanceof ServerPlayer ? 1.25F : 0.70F;
        }

        if (attacker != null && CurseBlessingService.hasCurse(attacker, "executioner")) {
            float healthPercent = victim.getMaxHealth() <= 0.0F ? 1.0F : victim.getHealth() / victim.getMaxHealth();
            if (healthPercent < 0.40F) amount *= 1.25F;
            else if (healthPercent > 0.50F) amount *= 0.80F;
        }

        if (victim instanceof ServerPlayer victimPlayer) {
            if (CurseBlessingService.hasCurse(victimPlayer, "strength")) {
                amount += 0.5F * CurseBlessingService.curseStack(victimPlayer, "strength");
            }
            if (CurseBlessingService.hasCurse(victimPlayer, "justice")) {
                amount += 1.0F * CurseBlessingService.curseStack(victimPlayer, "justice");
            }
            if (attacker != null && CurseBlessingService.hasCurse(victimPlayer, "predator")) {
                amount *= 1.15F;
            }
            if (CurseBlessingService.hasBlessing(victimPlayer, "aegis")) {
                long now = victimPlayer.level().getGameTime();
                long availableAt = KarmaPlayerState.get(victimPlayer, "aegis_next_proc");
                if (amount > 0 && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) && now < KarmaPlayerState.get(victimPlayer,"aegis_protected_until")) {
                    amount *= 0.50F;
                } else if (amount > 0 && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) && now >= availableAt && victimPlayer.getRandom().nextFloat() < 0.10F) {
                    amount *= 0.50F;
                    KarmaPlayerState.set(victimPlayer, "aegis_pending_proc_until", now + (CurseBlessingService.blessingStack(victimPlayer,"aegis")>=2?200L:300L));
                }
            }
        }

        return Math.max(0.0F, amount);
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void karmaiscurrency$afterHurt(ServerLevel level, DamageSource source, float amount,
                                           CallbackInfoReturnable<Boolean> cir) {
        LivingEntity victim = (LivingEntity) (Object) this;
        if (victim instanceof ServerPlayer victimPlayer) {
            long pendingProc = KarmaPlayerState.get(victimPlayer, "aegis_pending_proc_until");
            if (pendingProc > 0L) {
                if (cir.getReturnValue()) {
                    KarmaPlayerState.set(victimPlayer, "aegis_next_proc", pendingProc);
                    KarmaPlayerState.set(victimPlayer, "aegis_protected_until", victimPlayer.level().getGameTime()+30L);
                }
                KarmaPlayerState.clear(victimPlayer, "aegis_pending_proc_until");
            }
        }
        if (!cir.getReturnValue()) return;
        if(victim instanceof ServerPlayer p && CurseBlessingService.hasBlessing(p,"aegis")
            && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)
            && p.level().getGameTime()<KarmaPlayerState.get(p,"aegis_protected_until"))
            level.playSound(null,p.getX(),p.getY(),p.getZ(),net.minecraft.sounds.SoundEvents.SHIELD_BLOCK,net.minecraft.sounds.SoundSource.PLAYERS,0.7F,1.1F);

        ServerPlayer attacker = source.getEntity() instanceof ServerPlayer player ? player : null;
        if (victim instanceof ServerPlayer victimPlayer) CurseBlessingService.markInCombat(victimPlayer);
        if (attacker != null) CurseBlessingService.markInCombat(attacker);
        if (attacker == null || attacker == victim) return;

        if (CurseBlessingService.hasCurse(attacker, "parasite")
            && source.getDirectEntity() == attacker
            && attacker.isAlive()) {
            attacker.heal(1.0F);
        }

        if (CurseBlessingService.hasCurse(attacker, "gambler")
            && source.getDirectEntity() == attacker
            && attacker.isAlive()
            && attacker.getRandom().nextFloat() < 0.10F) {
            attacker.hurtServer(level, attacker.damageSources().generic(), 1.0F);
        }
    }
}
