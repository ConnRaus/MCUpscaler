package dev.mcupscaler.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mcupscaler.WorldUpscaler;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The poses the first-person arms are drawn with (see HandMotion): the one both share (the camera bobbing and the sway
 * behind the camera's turning), and each arm's final one with its own animation (swinging, switching items, eating). Also
 * reached when Vitrail draws the hands itself with a shader pack.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
	@Inject(
		method = "submitHandsWithItems",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V", ordinal = 1,
			shift = At.Shift.AFTER),
		require = 0
	)
	private void mcupscaler$handPose(float partialTicks, PoseStack poseStack, SubmitNodeCollector collector, PlayerRenderState playerState,
		FirstPersonHandsAndItemsRenderState state, CallbackInfo ci) {
		WorldUpscaler.handsSubmitted(poseStack.last().pose(), RenderSystem.getModelViewStack());
	}

	// The bare arm (also the arms holding a map).
	@Inject(method = "renderPlayerHand", at = @At("HEAD"), require = 0)
	private void mcupscaler$armPose(PoseStack poseStack, SubmitNodeCollector collector, int light, HumanoidArm arm, PlayerRenderState playerState,
		CallbackInfo ci) {
		WorldUpscaler.armSubmitted(arm == HumanoidArm.RIGHT, poseStack.last().pose(), RenderSystem.getModelViewStack());
	}

	// A held item.
	@Inject(
		method = "submitArmWithItem",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
		require = 0
	)
	private void mcupscaler$itemPose(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
		InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector,
		int light, CallbackInfo ci) {
		HumanoidArm mainArm = playerState.avatarRenderState.mainArm;
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? mainArm : mainArm.getOpposite();
		WorldUpscaler.armSubmitted(arm == HumanoidArm.RIGHT, poseStack.last().pose(), RenderSystem.getModelViewStack());
	}
}
