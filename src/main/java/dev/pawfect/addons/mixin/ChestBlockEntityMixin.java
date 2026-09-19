package dev.pawfect.addons.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.dungeon.secrets.SecretTracker;

@Mixin(ChestBlockEntity.class)
public abstract class ChestBlockEntityMixin extends RandomizableContainerBlockEntity {

    protected ChestBlockEntityMixin(BlockEntityType<?> blockEntityType, BlockPos blockPos, BlockState blockState) {
        super(blockEntityType, blockPos, blockState);
    }

    @Inject(at = @At("HEAD"), method = "triggerEvent(II)Z")
    private void pawfectaddons$chestOpened(int type, int data, CallbackInfoReturnable<Boolean> callback) {
        if (type != 1 || data <= 0) return;
        try {
            SecretTracker.onChestOpened(worldPosition);
        } catch (Throwable ignored) {
        }
    }
}
