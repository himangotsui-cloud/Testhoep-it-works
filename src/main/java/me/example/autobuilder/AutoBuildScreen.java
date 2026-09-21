package me.example.autobuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public class AutoBuildScreen extends Screen {
    private static final String[] MODES = { "AUTO", "CREATIVE", "SHOP" };

    private final BlockPos origin;
    private boolean settings = false;
    private TextFieldWidget cmdField;
    private List<String> files = List.of();
    private int idx = 0;

    private ButtonWidget modeBtn, strictBtn, soundBtn, speedLabelBtn;

    public AutoBuildScreen(BlockPos origin) {
        super(Text.literal("AutoBuilder"));
        this.origin = origin;
    }

    /** All .schem files in .minecraft/schematics, newest first. */
    static List<String> listSchematics() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".schem"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
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

    private void select(int newIdx) {
        if (files.isEmpty()) return;
        idx = Math.floorMod(newIdx, files.size());
        Config.INSTANCE.schematic = files.get(idx);
    }

    private void refresh() {
        files = listSchematics();
        idx = Math.max(0, files.indexOf(Config.INSTANCE.schematic));
        if (!files.isEmpty()) Config.INSTANCE.schematic = files.get(idx);
    }

    @Override
    protected void init() {
        int cx = width / 2, cy = height / 2;

        if (!settings) {
            refresh();

            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> select(idx - 1))
                    .dimensions(cx - 110, cy - 30, 20, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> select(idx + 1))
                    .dimensions(cx + 90, cy - 30, 20, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("\u27f3"), b -> { refresh(); clearAndInit(); })
                    .dimensions(cx + 60, cy - 52, 20, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal("Rescan schematics folder"))).build());

            Text label = Text.literal(BuildController.running() ? "Stop Auto Build" : "Auto Build");
            addDrawableChild(new RightClickButton(cx - 75, cy, 150, 20, label, b -> {
                if (BuildController.running()) {
                    BuildController.stop();
                } else {
                    if (files.isEmpty()) {
                        BuildController.status = "No .schem files in the schematics folder";
                        return;
                    }
                    Config.INSTANCE.save();
                    BuildController.start(origin);
                }
                close();
            }, () -> {
                settings = true;
                clearAndInit();
            }));
        } else {
            int row = cy - 46;

            cmdField = new TextFieldWidget(textRenderer, cx - 100, row, 200, 20, Text.literal("Shop command"));
            cmdField.setMaxLength(100);
            cmdField.setText(Config.INSTANCE.shopCommand);
            addDrawableChild(cmdField);
            row += 26;

            modeBtn = ButtonWidget.builder(modeText(), b -> {
                int i = java.util.Arrays.asList(MODES).indexOf(Config.INSTANCE.mode);
                Config.INSTANCE.mode = MODES[(i + 1) % MODES.length];
                modeBtn.setMessage(modeText());
            }).dimensions(cx - 100, row, 200, 20).build();
            addDrawableChild(modeBtn);
            row += 24;

            strictBtn = ButtonWidget.builder(strictText(), b -> {
                Config.INSTANCE.strictMode = !Config.INSTANCE.strictMode;
                strictBtn.setMessage(strictText());
            }).dimensions(cx - 100, row, 96, 20).build();
            addDrawableChild(strictBtn);

            soundBtn = ButtonWidget.builder(soundText(), b -> {
                Config.INSTANCE.soundOnComplete = !Config.INSTANCE.soundOnComplete;
                soundBtn.setMessage(soundText());
            }).dimensions(cx + 4, row, 96, 20).build();
            addDrawableChild(soundBtn);
            row += 24;

            addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
                Config.INSTANCE.speed = Math.max(1, Config.INSTANCE.speed - 1);
                speedLabelBtn.setMessage(speedText());
            }).dimensions(cx - 100, row, 20, 20).build());
            speedLabelBtn = ButtonWidget.builder(speedText(), b -> {}).dimensions(cx - 76, row, 152, 20).build();
            speedLabelBtn.active = false;
            addDrawableChild(speedLabelBtn);
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
                Config.INSTANCE.speed = Math.min(5, Config.INSTANCE.speed + 1);
                speedLabelBtn.setMessage(speedText());
            }).dimensions(cx + 80, row, 20, 20).build());
            row += 30;

            addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> {
                Config.INSTANCE.shopCommand = cmdField.getText().trim();
                Config.INSTANCE.save();
                settings = false;
                clearAndInit();
            }).dimensions(cx - 50, row, 100, 20).build());
        }
    }

    private Text modeText() {
        return Text.literal("Mode: " + Config.INSTANCE.mode
                + ("AUTO".equals(Config.INSTANCE.mode) ? " (auto-detects singleplayer creative)" : ""));
    }

    private Text strictText() {
        return Text.literal("Strict: " + (Config.INSTANCE.strictMode ? "ON" : "OFF"));
    }

    private Text soundText() {
        return Text.literal("Sound: " + (Config.INSTANCE.soundOnComplete ? "ON" : "OFF"));
    }

    private Text speedText() {
        return Text.literal("Speed: " + Config.INSTANCE.speed + "/5");
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2, cy = height / 2;
        if (!settings) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("AutoBuilder"), cx, cy - 74, 0xFFFFFF);
            String name = files.isEmpty() ? "No .schem files found" : files.get(idx) + "  (" + (idx + 1) + "/" + files.size() + ")";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(name), cx, cy - 24, files.isEmpty() ? 0xFF5555 : 0xFFFF55);
            String modeLine = "Mode: " + BuildController.resolvedModeLabel(MinecraftClient.getInstance());
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Left-click: start/stop   Right-click: settings"), cx, cy + 26, 0xAAAAAA);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(modeLine), cx, cy + 40, 0x66CCFF);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(BuildController.status), cx, cy + 54, 0x55FF55);
            if (files.isEmpty()) {
                ctx.drawCenteredTextWithShadow(textRenderer,
                        Text.literal("Put your .schem file in .minecraft/schematics"), cx, cy + 68, 0xAAAAAA);
            }
        } else {
            ctx.drawTextWithShadow(textRenderer, Text.literal("Shop command"), cx - 100, cy - 58, 0xFFFFFF);
        }
    }
}
