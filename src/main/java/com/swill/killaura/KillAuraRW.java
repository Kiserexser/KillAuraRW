package com.swill.killaura;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector2f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static net.minecraft.util.math.MathHelper.clamp;
import static net.minecraft.util.math.MathHelper.wrapDegrees;
import static java.lang.Math.hypot;

@Mod("killaurarw")
public class KillAuraRW {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyBinding keyBind;
    private static boolean enabled = false;
    private static long lastAttackTime = 0;
    private static final long ATTACK_DELAY_MS = 250;
    private static LivingEntity target = null;
    private static Vector2f currentRot = new Vector2f(0, 0);
    private static final Random random = new Random();
    private static boolean correctionEnabled = true;

    public KillAuraRW() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLClientSetupEvent event) {
        keyBind = new KeyBinding("Toggle KillAura", GLFW.GLFW_KEY_R, "Combat");
        net.minecraftforge.fml.client.registry.ClientRegistry.registerKeyBinding(keyBind);
    }

    @SubscribeEvent
    @OnlyIn(Dist.CLIENT)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.player == null || mc.world == null) return;

        if (keyBind.isPressed()) {
            enabled = !enabled;
            mc.player.sendMessage(net.minecraft.util.text.StringTextComponent.EMPTY.create().appendString(
                    "KillAura " + (enabled ? "§aON (игроки)" : "§cOFF")), mc.player.getUniqueID());
        }

        if (!enabled) {
            target = null;
            return;
        }

        findTarget();
        if (target == null) return;

        boolean canAttack = System.currentTimeMillis() - lastAttackTime >= ATTACK_DELAY_MS;
        updateRotation(canAttack);

        if (canAttack && mc.player.getDistanceSq(target) <= 9.0 && hasLineOfSight(target)) {
            mc.playerController.attackEntity(mc.player, target);
            mc.player.swingArm(Hand.MAIN_HAND);
            lastAttackTime = System.currentTimeMillis();
        }

        // Применяем ротацию
        mc.player.rotationYaw = currentRot.x;
        mc.player.rotationPitch = currentRot.y;
        mc.player.rotationYawHead = currentRot.x;
        mc.player.rotationPitchHead = currentRot.y;
        if (correctionEnabled) {
            mc.player.rotationYawOffset = currentRot.x;
            mc.player.renderYawOffset = currentRot.x;
        }
    }

    private void findTarget() {
        List<PlayerEntity> players = mc.world.getPlayers().stream()
                .filter(p -> p != mc.player)
                .filter(p -> !p.isRemoved() && p.isAlive())
                .filter(p -> mc.player.getDistanceSq(p) <= 36.0) // 6 блоков
                .sorted((a, b) -> Double.compare(mc.player.getDistanceSq(a), mc.player.getDistanceSq(b)))
                .collect(Collectors.toList());
        target = players.isEmpty() ? null : players.get(0);
    }

    private boolean hasLineOfSight(LivingEntity entity) {
        Vector3d start = mc.player.getEyePosition(1.0F);
        Vector3d end = entity.getPositionVec().add(0, entity.getHeight() * 0.8, 0);
        net.minecraft.util.math.RayTraceContext context = new net.minecraft.util.math.RayTraceContext(
                start, end, net.minecraft.util.math.RayTraceContext.BlockMode.COLLIDER,
                net.minecraft.util.math.RayTraceContext.FluidMode.NONE, mc.player);
        return mc.world.rayTraceBlocks(context).getType() == net.minecraft.util.math.RayTraceResult.Type.MISS;
    }

    private void updateRotation(boolean attack) {
        if (target == null) return;
        Vector3d vec = target.getPositionVec().add(0, target.getHeight() * 0.5, 0)
                .subtract(mc.player.getEyePosition(1.0F));

        float baseYawSpeed = 52.0f;
        float basePitchSpeed = 44.0f;

        float targetYaw = (float) wrapDegrees(Math.toDegrees(Math.atan2(vec.z, vec.x)) - 90);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(vec.y, hypot(vec.x, vec.z))));
        targetPitch = clamp(targetPitch, -89.0F, 89.0F);

        float yawDelta1 = wrapDegrees(targetYaw - currentRot.x);
        float pitchDelta1 = wrapDegrees(targetPitch - currentRot.y);

        float newYaw, newPitch;
        if (attack) {
            float snapFactor = 0.88f + (Math.min(Math.abs(yawDelta1) / 90.0f, 1.0f) * 0.12f);
            newYaw = currentRot.x + yawDelta1 * snapFactor;
            newPitch = currentRot.y + pitchDelta1 * snapFactor;
        } else {
            float yawSpeed = Math.min(Math.abs(yawDelta1), baseYawSpeed);
            float pitchSpeed = Math.min(Math.abs(pitchDelta1), basePitchSpeed);
            newYaw = currentRot.x + (yawDelta1 > 0 ? yawSpeed : -yawSpeed);
            newPitch = currentRot.y + (pitchDelta1 > 0 ? pitchSpeed : -pitchSpeed);
        }

        long time = System.currentTimeMillis();
        float timeFactor = time * 0.001f;

        if (mc.player.ticksExisted % (3 + (int)(Math.sin(timeFactor) * 2)) == 0) {
            float shakeIntensity = 0.12f + (float) Math.sin(timeFactor * 2.5f) * 0.06f;
            newYaw += (random.nextFloat() - 0.5f) * shakeIntensity;
            newPitch += (random.nextFloat() - 0.5f) * shakeIntensity * 0.8f;
        }

        newYaw += (random.nextFloat() - 0.5f) * 0.018f;
        newPitch += (random.nextFloat() - 0.5f) * 0.014f;

        float gcd = 0.0078125f + (random.nextFloat() * 0.0005f);
        float gcdVariation = 0.985f + (random.nextFloat() * 0.03f);
        newYaw -= (newYaw - currentRot.x) % (gcd * gcdVariation);
        newPitch -= (newPitch - currentRot.y) % (gcd * gcdVariation * 0.95f);

        float maxChange = attack ? 38.0f : 28.0f;
        newYaw = currentRot.x + MathHelper.clamp(newYaw - currentRot.x, -maxChange, maxChange);
        newPitch = currentRot.y + MathHelper.clamp(newPitch - currentRot.y, -maxChange * 0.85f, maxChange * 0.85f);
        newPitch = clamp(newPitch, -89.0F, 89.0F);

        float smoothFactor = 0.78f + (random.nextFloat() * 0.22f);
        newYaw = currentRot.x + (newYaw - currentRot.x) * smoothFactor;
        newPitch = currentRot.y + (newPitch - currentRot.y) * smoothFactor;

        currentRot = new Vector2f(newYaw, newPitch);

        if (correctionEnabled) {
            mc.player.rotationYawOffset = newYaw;
            mc.player.renderYawOffset = newYaw;
            if (mc.player.ticksExisted % 8 == 0) {
                mc.player.rotationYawHead += (random.nextFloat() - 0.5f) * 0.6f;
            }
        }

        if (time % 2000 < 100) {
            float randomizer = 0.9f + (random.nextFloat() * 0.2f);
            currentRot = new Vector2f(
                    currentRot.x * randomizer,
                    clamp(currentRot.y * randomizer, -89.0F, 89.0F)
            );
        }
    }
}
