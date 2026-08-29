package com.spacelaunch;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpaceLaunchMod implements ModInitializer {
    public static final String MOD_ID = "spacelaunch";

    /** Damage type that produces "<player> failed to fly." */
    public static final RegistryKey<DamageType> FAILED_TO_FLY =
            RegistryKey.of(RegistryKeys.DAMAGE_TYPE, Identifier.of(MOD_ID, "failed_to_fly"));

    /** Blocks climbed per tick while ascending. */
    private static final double ASCENT_SPEED = 3.0;
    /** How far above the launch point space is. */
    private static final double SPACE_HEIGHT = 320.0;
    /** How long you hang weightless up there, in ticks (20 ticks = 1 second). */
    private static final int FLOAT_TICKS = 120;

    private static final Map<UUID, Launch> ACTIVE = new HashMap<>();

    private enum Phase { ASCENT, FLOAT, FALL }

    private static final class Launch {
        Phase phase = Phase.ASCENT;
        double startY;
        double targetY;
        int floatTicks;

        Launch(double startY) {
            this.startY = startY;
            this.targetY = startY + SPACE_HEIGHT;
        }
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            com.mojang.brigadier.tree.LiteralCommandNode<ServerCommandSource> node =
                dispatcher.register(CommandManager.literal("spaceLaunch")
                        .executes(ctx -> launch(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                                .executes(ctx -> {
                                    Collection<ServerPlayerEntity> targets =
                                            EntityArgumentType.getPlayers(ctx, "targets");
                                    int count = 0;
                                    for (ServerPlayerEntity target : targets) {
                                        count += launch(ctx.getSource(), target);
                                    }
                                    return count;
                                })));

            // Minecraft command literals are case-sensitive, so keep the old spelling working.
            dispatcher.register(CommandManager.literal("spacelaunch").redirect(node));
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ACTIVE.isEmpty()) return;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                Launch launch = ACTIVE.get(player.getUuid());
                if (launch != null) {
                    tickLaunch(player, launch);
                }
            }
        });

        // Vanilla fall damage would steal the kill (and the death message), so eat it
        // while a launch is in progress — we do the killing ourselves on impact.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!ACTIVE.containsKey(entity.getUuid())) return true;
            return !source.isOf(DamageTypes.FALL);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                clear(handler.getPlayer()));
    }

    private static int launch(ServerCommandSource source, ServerPlayerEntity player) {
        if (ACTIVE.containsKey(player.getUuid())) {
            source.sendFeedback(() -> Text.literal(player.getName().getString()
                    + " is already airborne."), false);
            return 0;
        }

        ACTIVE.put(player.getUuid(), new Launch(player.getY()));
        player.setNoGravity(true);
        player.fallDistance = 0.0F;

        player.getEntityWorld().playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.0F, 0.6F);
        player.sendMessage(Text.literal("3... 2... 1... liftoff!"), true);
        return 1;
    }

    private static void tickLaunch(ServerPlayerEntity player, Launch launch) {
        World world = player.getEntityWorld();

        switch (launch.phase) {
            case ASCENT -> {
                double next = Math.min(player.getY() + ASCENT_SPEED, launch.targetY);
                teleportTo(player, next);

                if (world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                    serverWorld.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                            player.getX(), player.getY() - 1.0, player.getZ(),
                            12, 0.2, 0.2, 0.2, 0.01);
                }

                if (player.getY() >= launch.targetY - 1.0E-4) {
                    launch.phase = Phase.FLOAT;
                    launch.floatTicks = FLOAT_TICKS;
                    player.sendMessage(Text.literal("You are in space."), true);
                    world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BEACON_AMBIENT,
                            SoundCategory.PLAYERS, 1.0F, 1.6F);
                }
            }

            case FLOAT -> {
                // Hold position exactly — weightless drift, no gravity, no input authority.
                teleportTo(player, launch.targetY);
                if (--launch.floatTicks <= 0) {
                    launch.phase = Phase.FALL;
                    player.setNoGravity(false);
                    player.setVelocity(new Vec3d(0.0, -0.5, 0.0));
                    player.velocityDirty = true;
                    player.sendMessage(Text.literal("...gravity remembers you."), true);
                }
            }

            case FALL -> {
                boolean landed = player.isOnGround()
                        || player.isTouchingWater()
                        || player.getY() <= world.getBottomY() + 1;

                if (landed) {
                    kill(player);
                    clear(player);
                }
            }
        }
    }

    private static void teleportTo(ServerPlayerEntity player, double y) {
        player.setNoGravity(true);
        player.fallDistance = 0.0F;
        player.setVelocity(Vec3d.ZERO);
        player.velocityDirty = true;
        player.networkHandler.requestTeleport(player.getX(), y, player.getZ(),
                player.getYaw(), player.getPitch());
    }

    private static void kill(ServerPlayerEntity player) {
        if (!(player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
            return;
        }
        DamageSource source = new DamageSource(
                serverWorld.getRegistryManager()
                        .getOrThrow(RegistryKeys.DAMAGE_TYPE)
                        .getEntry(FAILED_TO_FLY.getValue())
                        .orElseThrow());
        player.damage(serverWorld, source, Float.MAX_VALUE);
    }

    private static void clear(ServerPlayerEntity player) {
        if (ACTIVE.remove(player.getUuid()) != null) {
            player.setNoGravity(false);
            player.fallDistance = 0.0F;
        }
    }
}
