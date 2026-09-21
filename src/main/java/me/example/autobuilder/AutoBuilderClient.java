package me.example.autobuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
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

        HudRenderCallback.EVENT.register(new BuildHud());

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            BuildController.tick(mc);
            while (OPEN.wasPressed()) {
                if (mc.player == null || mc.currentScreen != null) continue;
                mc.setScreen(new AutoBuildScreen(computeOrigin(mc)));
            }
        });

        // Chat-command alternative to the UI: /autobuilder start|stop|status
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("autobuilder")
                        .then(ClientCommandManager.literal("start").executes(ctx -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.player == null) return 0;
                            if (BuildController.running()) {
                                mc.player.sendMessage(Text.literal("[AutoBuilder] Already building. Use /autobuilder stop first."), false);
                                return 0;
                            }
                            BuildController.start(computeOrigin(mc));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("stop").executes(ctx -> {
                            BuildController.stop();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("status").executes(ctx -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoBuilder] " + BuildController.status), false);
                            return 1;
                        }))
        ));
    }

    /** Player's feet, or the block just in front of whatever they're looking at, whichever they targeted. */
    static BlockPos computeOrigin(MinecraftClient mc) {
        BlockPos origin = mc.player.getBlockPos();
        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            origin = hit.getBlockPos().offset(hit.getSide());
        }
        return origin;
    }
}
