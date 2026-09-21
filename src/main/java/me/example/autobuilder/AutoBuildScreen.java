package me.example.autobuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public class AutoBuildScreen extends Screen {
    private final BlockPos origin;
    private boolean settings = false;
    private TextFieldWidget cmdField, schemField;

    public AutoBuildScreen(BlockPos origin) {
        super(Text.literal("AutoBuilder"));
        this.origin = origin;
    }

    /** Button that reports right-clicks to a separate handler. */
    private static class RightClickButton extends ButtonWidget {
        private final Runnable onRight;

        RightClickButton(int x, int y, int w, int h, Text t, PressAction left, Runnable right) {
            super(x, y, w, h, t, left, DEFAULT_NARRATION_SUPPLIER);
            this.onRight = right;
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button == 1 && active && visible && isMouseOver(mx, my)) {
                playDownSound(MinecraftClient.getInstance().getSoundManager());
                onRight.run();
                return true;
            }
            return super.mouseClicked(mx, my, button);
        }
    }

    @Override
    protected void init() {
        int cx = width / 2, cy = height / 2;

        if (!settings) {
            Text label = Text.literal(BuildController.running() ? "Stop Auto Build" : "Auto Build");
            addDrawableChild(new RightClickButton(cx - 75, cy - 10, 150, 20, label, b -> {
                if (BuildController.running()) BuildController.stop();
                else BuildController.start(origin);
                close();
            }, () -> {
                settings = true;
                clearAndInit();
            }));
        } else {
            cmdField = new TextFieldWidget(textRenderer, cx - 100, cy - 30, 200, 20, Text.literal("Shop command"));
            cmdField.setMaxLength(100);
            cmdField.setText(Config.INSTANCE.shopCommand);
            addDrawableChild(cmdField);

            schemField = new TextFieldWidget(textRenderer, cx - 100, cy + 10, 200, 20, Text.literal("Schematic"));
            schemField.setMaxLength(100);
            schemField.setText(Config.INSTANCE.schematic);
            addDrawableChild(schemField);

            addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> {
                Config.INSTANCE.shopCommand = cmdField.getText().trim();
                Config.INSTANCE.schematic = schemField.getText().trim();
                Config.INSTANCE.save();
                settings = false;
                clearAndInit();
            }).dimensions(cx - 50, cy + 40, 100, 20).build());
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2, cy = height / 2;
        if (!settings) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("AutoBuilder"), cx, cy - 40, 0xFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Left-click: start/stop   Right-click: settings"), cx, cy + 18, 0xAAAAAA);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(BuildController.status), cx, cy + 34, 0x55FF55);
        } else {
            ctx.drawTextWithShadow(textRenderer, Text.literal("Shop command"), cx - 100, cy - 42, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Schematic file (in /schematics)"), cx - 100, cy - 2, 0xFFFFFF);
        }
    }
}
