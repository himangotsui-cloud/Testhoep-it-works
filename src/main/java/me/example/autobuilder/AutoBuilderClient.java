package me.example.autobuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class AutoBuilderClient implements ClientModInitializer {
    static KeyBinding OPEN;

    @Override
    public void onInitializeClient() {
        OPEN = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobuilder.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "category.autobuilder"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            BuildController.tick(mc);
            while (OPEN.wasPressed()) {
                if (mc.player == null || mc.currentScreen != null) continue;
                BlockPos origin = mc.player.getBlockPos();
                if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                    origin = hit.getBlockPos().offset(hit.getSide());
                }
                mc.setScreen(new AutoBuildScreen(origin));
            }
        });
    }
}
