package dev.pawfect.addons.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.combat.Hitsound;
import dev.pawfect.addons.features.dungeon.secrets.SecretTracker;
import dev.pawfect.addons.features.experiments.ExperimentManager;
import dev.pawfect.addons.features.visual.StarMobGlow;
import net.minecraft.world.item.ItemStack;
import java.util.List;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$muteExplosion(ClientboundSoundPacket packet, CallbackInfo callback) {
        try {
            if (Hitsound.muteExplosion(packet)) {
                callback.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void pawfectaddons$sound(ClientboundSoundPacket packet, CallbackInfo callback) {
        try {
            SecretTracker.onSound(packet);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleGameEvent", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$arrowPing(ClientboundGameEventPacket packet, CallbackInfo callback) {
        try {
            if (packet.getEvent() != ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND) {
                return;
            }
            if (Hitsound.suppressVanillaArrowPing()) {
                callback.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void pawfectaddons$takeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo callback) {
        try {
            SecretTracker.onItemTaken(packet.getItemId());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"))
    private void pawfectaddons$removeEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo callback) {
        try {
            packet.getEntityIds().forEach(SecretTracker::onItemTaken);
            StarMobGlow.onRemoveEntities(packet.getEntityIds());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void pawfectaddons$entityData(ClientboundSetEntityDataPacket packet, CallbackInfo callback) {
        try {
            StarMobGlow.onEntityData(packet);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void pawfectaddons$login(ClientboundLoginPacket packet, CallbackInfo callback) {
        StarMobGlow.reset();
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void pawfectaddons$respawn(ClientboundRespawnPacket packet, CallbackInfo callback) {
        StarMobGlow.reset();
    }

    @Inject(method = "handleOpenScreen", at = @At("HEAD"))
    private void pawfectaddons$openScreen(ClientboundOpenScreenPacket packet, CallbackInfo callback) {
        try {
            SecretTracker.onScreenOpened();
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void pawfectaddons$containerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo callback) {
        try {
            ExperimentManager.onSlotChanged(packet.getContainerId(), packet.getSlot(), packet.getItem());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void pawfectaddons$containerContent(ClientboundContainerSetContentPacket packet, CallbackInfo callback) {
        try {
            List<ItemStack> items = packet.items();
            for (int i = 0; i < items.size(); i++) {
                ExperimentManager.onSlotChanged(packet.containerId(), i, items.get(i));
            }
        } catch (Throwable ignored) {
        }
    }
}
