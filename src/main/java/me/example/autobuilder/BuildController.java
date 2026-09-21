package me.example.autobuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuildController {
    enum State { IDLE, PLACING, VERIFY, OPEN_SHOP, BUYING }

    static State state = State.IDLE;
    static String status = "Idle";

    private static List<Map.Entry<BlockPos, BlockState>> queue = new ArrayList<>();
    private static int wait, shopTimer, buyFails, pages, lastCount, pendingTries;
    private static Item shopItem;
    private static int wantCount;
    private static BlockPos pendingPos;
    private static BlockState pendingState;

    public static boolean running() { return state != State.IDLE; }

    // ---------- start / stop ----------
    public static void start(BlockPos origin) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve(Config.INSTANCE.schematic);
            Schematic s = Schematic.load(file);
            queue = new ArrayList<>();
            for (var e : s.blocks.entrySet()) {
                BlockPos p = origin.add(e.getKey());
                if (mc.world.getBlockState(p) == e.getValue()) continue; // already built (resume support)
                queue.add(Map.entry(p, e.getValue()));
            }
            pendingTries = 0;
            wait = 0;
            state = State.PLACING;
            msg("Building " + queue.size() + " blocks from " + Config.INSTANCE.schematic);
        } catch (Exception ex) {
            msg("Could not load schematic: " + ex.getMessage());
        }
    }

    public static void stop() { halt("Stopped."); }

    private static void halt(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        state = State.IDLE;
        status = reason;
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        msg(reason);
    }

    private static void msg(String s) {
        status = s;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoBuilder] " + s), false);
    }

    // ---------- main loop ----------
    public static void tick(MinecraftClient mc) {
        if (state == State.IDLE) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) { state = State.IDLE; return; }
        if (wait > 0) { wait--; return; }

        switch (state) {
            case PLACING -> place(mc);
            case VERIFY -> verify(mc);
            case OPEN_SHOP -> openShop(mc);
            case BUYING -> buy(mc);
            default -> {}
        }
    }

    // ---------- placing ----------
    private static void place(MinecraftClient mc) {
        var w = mc.world;
        var pl = mc.player;
        if (mc.currentScreen != null) return; // pause while another GUI is open

        queue.removeIf(e -> w.getBlockState(e.getKey()) == e.getValue());
        if (queue.isEmpty()) { halt("Build complete!"); return; }

        Vec3d eye = pl.getEyePos();
        for (var e : queue) {
            BlockPos p = e.getKey();
            BlockState want = e.getValue();
            BlockState cur = w.getBlockState(p);

            if (!cur.isReplaceable()) {
                halt("Wrong block at " + p.toShortString() + ": " + cur.getBlock().getName().getString());
                return;
            }
            Item item = want.getBlock().asItem();
            if (item == Items.AIR) { halt("No item form for " + want); return; }
            if (pl.getBoundingBox().intersects(new Box(p))) continue; // standing in the spot

            BlockHitResult hit = null;
            for (Direction d : Direction.values()) {
                BlockPos nb = p.offset(d);
                BlockState ns = w.getBlockState(nb);
                if (ns.isAir() || ns.isReplaceable() || !ns.getFluidState().isEmpty()) continue;
                Vec3d v = Vec3d.ofCenter(p).offset(d, 0.5);
                if (eye.distanceTo(v) > 4.4) continue;
                hit = new BlockHitResult(v, d.getOpposite(), nb, false);
                break;
            }
            if (hit == null) continue;

            if (pl.getInventory().count(item) == 0) { startShop(mc, item); return; }

            int eq = equip(mc, item);
            if (eq == 2) { wait = 3; return; }
            if (eq == 0) { startShop(mc, item); return; }

            mc.interactionManager.interactBlock(pl, Hand.MAIN_HAND, hit);
            pl.swingHand(Hand.MAIN_HAND);
            pendingPos = p;
            pendingState = want;
            state = State.VERIFY;
            wait = 4;
            return;
        }
        pl.sendMessage(Text.literal("[AutoBuilder] Nothing reachable, move closer (" + queue.size() + " left)"), true);
        wait = 10;
    }

    private static void verify(MinecraftClient mc) {
        BlockState now = mc.world.getBlockState(pendingPos);
        if (now == pendingState) {
            queue.removeIf(e -> e.getKey().equals(pendingPos));
            pendingTries = 0;
            state = State.PLACING;
            wait = 1;
        } else if (now.getBlock() == pendingState.getBlock()) {
            halt("State mismatch at " + pendingPos.toShortString() + ": got " + now + ", wanted " + pendingState);
        } else if (++pendingTries >= 3) {
            halt("Could not place " + pendingState.getBlock().getName().getString() + " at " + pendingPos.toShortString());
        } else {
            state = State.PLACING;
        }
    }

    /** 0 = not in inventory, 1 = ready in hand, 2 = swapped, wait a moment. */
    private static int equip(MinecraftClient mc, Item item) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) { inv.selectedSlot = i; return 1; }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(item)) {
                mc.interactionManager.clickSlot(0, i, inv.selectedSlot, SlotActionType.SWAP, mc.player);
                return 2;
            }
        }
        return 0;
    }

    // ---------- shop ----------
    private static void startShop(MinecraftClient mc, Item item) {
        shopItem = item;
        wantCount = (int) Math.min(576, queue.stream().filter(q -> q.getValue().getBlock().asItem() == item).count());
        String cmd = Config.INSTANCE.shopCommand.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        mc.getNetworkHandler().sendChatCommand(cmd);
        shopTimer = 0;
        state = State.OPEN_SHOP;
        msg("Buying " + wantCount + "x " + item.getName().getString());
    }

    private static void openShop(MinecraftClient mc) {
        if (mc.currentScreen instanceof GenericContainerScreen) {
            state = State.BUYING;
            buyFails = 0;
            pages = 0;
            lastCount = -1;
            wait = 5;
            return;
        }
        if (++shopTimer > 100) halt("Shop GUI did not open. Check the command.");
    }

    private static void buy(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) { halt("Shop closed unexpectedly."); return; }
        GenericContainerScreenHandler h = gcs.getScreenHandler();
        int inv = mc.player.getInventory().count(shopItem);

        if (inv >= wantCount) {
            mc.player.closeHandledScreen();
            state = State.PLACING;
            wait = 5;
            return;
        }
        if (mc.player.getInventory().getEmptySlot() == -1) { halt("Inventory full."); return; }

        int slot = findItem(h, shopItem);
        if (slot < 0) {
            int next = findNext(h);
            if (next < 0 || ++pages > Config.INSTANCE.maxPages) {
                halt(shopItem.getName().getString() + " not found in shop.");
                return;
            }
            click(mc, h, next);
            wait = 6;
            return;
        }

        double price = readPrice(h.getSlot(slot).getStack());
        double max = Config.INSTANCE.maxPricePerClick;
        if (max > 0 && price > max) { halt("Price " + price + " is above your limit " + max); return; }

        if (inv == lastCount) {
            if (++buyFails >= 4) { halt("Purchase is not registering (money? wrong GUI?)."); return; }
        } else {
            buyFails = 0;
        }
        lastCount = inv;
        click(mc, h, slot);
        wait = 4;
    }

    private static void click(MinecraftClient mc, GenericContainerScreenHandler h, int slot) {
        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    private static int findItem(GenericContainerScreenHandler h, Item item) {
        for (int i = 0; i < h.getRows() * 9; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (!s.isEmpty() && s.isOf(item)) return i;
        }
        return -1;
    }

    private static int findNext(GenericContainerScreenHandler h) {
        for (int i = 0; i < h.getRows() * 9; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (!s.isEmpty() && s.getName().getString().toLowerCase().contains("next")) return i;
        }
        return -1;
    }

    private static final Pattern PRICE = Pattern.compile("(?i)(?:buy|price|cost)?\\D{0,12}?([0-9][0-9,]*(?:\\.[0-9]+)?)");

    /** Reads the first number from the item's lore (e.g. "Buy: $12.50"). Returns 0 if none. */
    private static double readPrice(ItemStack s) {
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return 0;
        for (Text line : lore.lines()) {
            Matcher m = PRICE.matcher(line.getString());
            if (m.find()) {
                try { return Double.parseDouble(m.group(1).replace(",", "")); } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }
}
