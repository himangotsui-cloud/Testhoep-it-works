package me.example.autobuilder;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/** Small always-on-screen progress readout while a build is running (the AutoBuilder screen itself closes on start). */
public class BuildHud implements HudRenderCallback {
    private static final int X = 6, Y = 6, BAR_W = 130, BAR_H = 4;

    @Override
    public void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!BuildController.running()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.textRenderer == null) return;

        int total = BuildController.totalBlocks;
        int done = BuildController.placedBlocks + BuildController.skippedBlocks;
        int pct = total <= 0 ? 100 : Math.min(100, (int) (100.0 * done / total));
        String eta = BuildController.etaString();

        String line1 = "AutoBuilder " + pct + "%  (" + BuildController.placedBlocks + "/" + total + ")";
        String line2 = (eta.isEmpty() ? "" : eta + " left \u2014 ") + BuildController.status;

        ctx.fill(X, Y + 12, X + BAR_W, Y + 12 + BAR_H, 0x66000000);
        int fillW = (int) (BAR_W * (pct / 100.0));
        ctx.fill(X, Y + 12, X + Math.max(0, fillW), Y + 12 + BAR_H, 0xFF55FF55);

        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(line1), X, Y, 0xFFFFFF);
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(line2), X, Y + 20, 0xAAAAAA);
    }
}
