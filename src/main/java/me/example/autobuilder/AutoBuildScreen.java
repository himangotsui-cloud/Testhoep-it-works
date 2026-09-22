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
    private static final int[] ROTATIONS = { 0, 90, 180, 270 };

    // theme — dark panel, cyan accent, Meteor-Client-ish click-GUI
    private static final int BG = 0xE6141414, PANEL_BORDER = 0xFF2B2B2B, ACCENT = 0xFF3FD6C6, ACCENT_DIM = 0x553FD6C6;
    private static final int TEXT = 0xE6DFDFDF, TEXT_DIM = 0xFF8A8A8A;
    private static final int PANEL_W = 300, PANEL_H = 250, SIDEBAR_W = 72;

    private final BlockPos origin;
    private boolean settings = false;
    private TextFieldWidget cmdField;
    private List<String> files = List.of();
    private int idx = 0;

    private ButtonWidget modeBtn, strictBtn, soundBtn, speedLabelBtn, layerBtn, rotBtn, moveBtn;
    private int panelX, panelY;

    public AutoBuildScreen(BlockPos origin) {
        super(Text.literal("AutoBuilder"));
        this.origin = origin;
    }

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

    private void select(int newIdx) {
        if (files.isEmpty()) return;
        idx = Math.floorMod(newIdx, files.size());
        Config.INSTANCE.schematic = files.get(idx);
    }

    private void refresh() {
        files = listSchematics();
        idx = 0;
        if (!files.isEmpty()) Config.INSTANCE.schematic = files.get(idx);
    }

    /** Small tinted button used for the sidebar tabs and the mini +/- controls. */
    private ButtonWidget tinted(Text text, ButtonWidget.PressAction action, int x, int y, int w, int h) {
        return ButtonWidget.builder(text, action).dimensions(x, y, w, h).build();
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
        int px = panelX + SIDEBAR_W + 14; // content column left edge
        int pw = PANEL_W - SIDEBAR_W - 28;

        if (BuildController.running()) {
            boolean paused = BuildController.paused();
            int y = panelY + 90;
            addDrawableChild(tinted(Text.literal(paused ? "\u25b6 Resume" : "\u23f8 Pause"), b -> {
                if (paused) BuildController.resume(); else BuildController.pause();
                close();
            }, px, y, pw, 20));
            addDrawableChild(tinted(Text.literal("\u2716 Cancel"), b -> {
                BuildController.stop();
                close();
            }, px, y + 26, pw, 20));
            return;
        }

        // sidebar tabs
        addDrawableChild(tinted(Text.literal("Build"), b -> { settings = false; clearAndInit(); },
                panelX + 8, panelY + 34, SIDEBAR_W - 16, 20));
        addDrawableChild(tinted(Text.literal("Settings"), b -> { settings = true; clearAndInit(); },
                panelX + 8, panelY + 58, SIDEBAR_W - 16, 20));

        if (!settings) {
            refresh();
            int y = panelY + 34;

            addDrawableChild(tinted(Text.literal("Materials"), b -> {
                if (!files.isEmpty()) BuildController.showMaterials(origin, Config.INSTANCE.schematic);
            }, px, y, pw - 24, 20));
            addDrawableChild(tinted(Text.literal("\u27f3"), b -> { refresh(); clearAndInit(); },
                    px + pw - 20, y, 20, 20));
            y += 26;

            addDrawableChild(tinted(Text.literal("<"), b -> { select(idx - 1); clearAndInit(); }, px, y, 20, 20));
            addDrawableChild(tinted(Text.literal(">"), b -> { select(idx + 1); clearAndInit(); }, px + pw - 20, y, 20, 20));
            y += 68; // leaves room for the two text lines drawn in render() below the arrows

            addDrawableChild(tinted(Text.literal("\u25b6 Auto Build"), b -> {
                if (files.isEmpty()) { BuildController.status = "No .schem files in the schematics folder"; return; }
                Config.INSTANCE.save();
                BuildController.start(origin);
                close();
            }, px, y, pw, 22));
        } else {
            int y = panelY + 36;

            cmdField = new TextFieldWidget(textRenderer, px, y, pw, 20, Text.literal("Shop command"));
            cmdField.setMaxLength(100);
            cmdField.setText(Config.INSTANCE.shopCommand);
            addDrawableChild(cmdField);
            y += 25;

            modeBtn = tinted(modeText(), b -> {
                int i = java.util.Arrays.asList(MODES).indexOf(Config.INSTANCE.mode);
                Config.INSTANCE.mode = MODES[(i + 1) % MODES.length];
                modeBtn.setMessage(modeText());
            }, px, y, pw, 18);
            addDrawableChild(modeBtn);
            y += 21;

            moveBtn = tinted(moveText(), b -> {
                Config.INSTANCE.autoMove = !Config.INSTANCE.autoMove;
                moveBtn.setMessage(moveText());
            }, px, y, pw, 18);
            addDrawableChild(moveBtn);
            y += 21;

            int half = (pw - 4) / 2;
            strictBtn = tinted(strictText(), b -> {
                Config.INSTANCE.strictMode = !Config.INSTANCE.strictMode;
                strictBtn.setMessage(strictText());
            }, px, y, half, 18);
            addDrawableChild(strictBtn);
            soundBtn = tinted(soundText(), b -> {
                Config.INSTANCE.soundOnComplete = !Config.INSTANCE.soundOnComplete;
                soundBtn.setMessage(soundText());
            }, px + half + 4, y, half, 18);
            addDrawableChild(soundBtn);
            y += 21;

            layerBtn = tinted(layerText(), b -> {
                Config.INSTANCE.layerByLayer = !Config.INSTANCE.layerByLayer;
                layerBtn.setMessage(layerText());
            }, px, y, half, 18);
            addDrawableChild(layerBtn);
            rotBtn = tinted(rotText(), b -> {
                int i = java.util.Arrays.asList(0, 90, 180, 270).indexOf(Config.INSTANCE.rotation);
                Config.INSTANCE.rotation = ROTATIONS[(i + 1) % ROTATIONS.length];
                rotBtn.setMessage(rotText());
            }, px + half + 4, y, half, 18);
            addDrawableChild(rotBtn);
            y += 21;

            addDrawableChild(tinted(Text.literal("-"), b -> {
                Config.INSTANCE.speed = Math.max(1, Config.INSTANCE.speed - 1);
                speedLabelBtn.setMessage(speedText());
            }, px, y, 18, 18));
            speedLabelBtn = tinted(speedText(), b -> {}, px + 21, y, pw - 42, 18);
            speedLabelBtn.active = false;
            addDrawableChild(speedLabelBtn);
            addDrawableChild(tinted(Text.literal("+"), b -> {
                Config.INSTANCE.speed = Math.min(5, Config.INSTANCE.speed + 1);
                speedLabelBtn.setMessage(speedText());
            }, px + pw - 18, y, 18, 18));
            y += 26;

            addDrawableChild(tinted(Text.literal("Save"), b -> {
                Config.INSTANCE.shopCommand = cmdField.getText().trim();
                Config.INSTANCE.save();
                settings = false;
                clearAndInit();
            }, px, y, pw, 20));
        }
    }

    private Text modeText() { return Text.literal("Mode: " + Config.INSTANCE.mode); }
    private Text strictText() { return Text.literal("Strict: " + (Config.INSTANCE.strictMode ? "ON" : "OFF")); }
    private Text soundText() { return Text.literal("Sound: " + (Config.INSTANCE.soundOnComplete ? "ON" : "OFF")); }
    private Text speedText() { return Text.literal("Speed: " + Config.INSTANCE.speed + "/5"); }
    private Text layerText() { return Text.literal("Layers: " + (Config.INSTANCE.layerByLayer ? "ON" : "OFF")); }
    private Text rotText() { return Text.literal("Rotate: " + Config.INSTANCE.rotation + "\u00b0"); }
    private Text moveText() { return Text.literal("Auto-move: " + (Config.INSTANCE.autoMove ? "ON" : "OFF")); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // dim the world behind the panel, then draw the panel itself
        ctx.fill(0, 0, width, height, 0x66000000);
        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, BG);
        ctx.drawBorder(panelX, panelY, PANEL_W, PANEL_H, PANEL_BORDER);
        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + 22, ACCENT_DIM);
        ctx.fill(panelX, panelY + 22, panelX + PANEL_W, panelY + 23, ACCENT);
        ctx.drawText(textRenderer, Text.literal("AutoBuilder"), panelX + 8, panelY + 7, ACCENT, true);

        if (!BuildController.running()) {
            ctx.fill(panelX + SIDEBAR_W, panelY + 23, panelX + SIDEBAR_W + 1, panelY + PANEL_H, PANEL_BORDER);
            int tabY = settings ? panelY + 58 : panelY + 34;
            ctx.fill(panelX + 8, tabY, panelX + SIDEBAR_W - 8, tabY + 20, ACCENT_DIM);
        }

        super.render(ctx, mouseX, mouseY, delta);

        int px = panelX + SIDEBAR_W + 14;
        int pw = PANEL_W - SIDEBAR_W - 28;

        if (BuildController.running()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(BuildController.status), panelX + SIDEBAR_W + 14 + pw / 2, panelY + 42, TEXT);
            int pct = BuildController.totalBlocks <= 0 ? 0 : (int) (100.0 * (BuildController.placedBlocks + BuildController.skippedBlocks) / BuildController.totalBlocks);
            String eta = BuildController.etaString();
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal(pct + "%  (" + BuildController.placedBlocks + "/" + BuildController.totalBlocks + ")" + (eta.isEmpty() ? "" : "  " + eta + " left")),
                    panelX + SIDEBAR_W + 14 + pw / 2, panelY + 62, TEXT_DIM);
            return;
        }

        if (!settings) {
            int y = panelY + 86;
            String name = files.isEmpty() ? "No .schem files found" : files.get(idx);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(name), px + pw / 2, y, files.isEmpty() ? 0xFFFF5555 : TEXT);
            if (!files.isEmpty()) {
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("(" + (idx + 1) + "/" + files.size() + ")"), px + pw / 2, y + 11, TEXT_DIM);
            }
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal(BuildController.resolvedModeLabel(MinecraftClient.getInstance()) + "  \u00b7  Rotate " + Config.INSTANCE.rotation + "\u00b0"),
                    px + pw / 2, y + 26, ACCENT);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(BuildController.status), px + pw / 2, panelY + 158, TEXT_DIM);
        }
    }
}
