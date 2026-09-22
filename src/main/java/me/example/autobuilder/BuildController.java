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
    enum State { IDLE, PLACING, VERIFY, OPEN_SHOP, BUYING, PAUSED }

    static State state = State.IDLE;
    static State prevState = State.PLACING;
    static String status = "Idle";

    private static List<Map.Entry<BlockPos, BlockState>> queue = new ArrayList<>();
    private static int wait, shopTimer, buyFails, pages, lastCount, pendingTries;
    private static Item shopItem;
    private static int wantCount;
    private static BlockPos pendingPos;
    private static BlockState pendingState;
    private static Direction pendingDir;
    private static double curSpeed = 0;
    /** Faces already tried (and rejected) at a position — lets us rotate around a block to fix its orientation instead of giving up. */
    private static final Map<BlockPos, java.util.EnumSet<Direction>> triedDirs = new java.util.HashMap<>();

    // ---------- progress tracking (read by BuildHud) ----------
    static int totalBlocks, placedBlocks, skippedBlocks;
    static long startMillis;

    public static boolean running() { return state != State.IDLE; }
    public static boolean paused() { return state == State.PAUSED; }

    public static void pause() {
        if (state == State.IDLE || state == State.PAUSED) return;
        prevState = state;
        state = State.PAUSED;
        status = "Paused. (" + queue.size() + " left)";
        msg(status);
    }

    public static void resume() {
        if (state != State.PAUSED) return;
        state = prevState;
        status = "Resumed. (" + queue.size() + " left)";
        msg(status);
    }

    private static void pauseFor(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        prevState = (state == State.BUYING || state == State.OPEN_SHOP) ? State.PLACING : state;
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        state = State.PAUSED;
        status = "Paused: " + reason;
        msg(status);
    }

    /** Resolves AUTO to CREATIVE or SHOP based on the current world. */
    static boolean useCreative(MinecraftClient mc) {
        return switch (Config.INSTANCE.mode) {
            case "CREATIVE" -> true;
            case "SHOP" -> false;
            default -> mc.isInSingleplayer() && mc.player != null && mc.player.getAbilities().creativeMode;
        };
    }

    /** What AUTO currently resolves to, for display purposes. */
    static String resolvedModeLabel(MinecraftClient mc) {
        String m = Config.INSTANCE.mode;
        if (!"AUTO".equals(m)) return m;
        return useCreative(mc) ? "AUTO (creative)" : "AUTO (shop)";
    }

    // ---------- start / stop ----------
    public static void start(BlockPos origin) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        try {
            List<String> skipped = new ArrayList<>();
            queue = new ArrayList<>(buildQueueFor(mc, origin, Config.INSTANCE.schematic, skipped));
            pendingTries = 0;
            wait = 0;
            curSpeed = 0;
            triedDirs.clear();
            totalBlocks = queue.size();
            placedBlocks = 0;
            skippedBlocks = 0;
            startMillis = System.currentTimeMillis();
            state = State.PLACING;
            String unknownWarn = skipped.isEmpty() ? ""
                    : "  (unrecognized block(s) skipped: " + String.join(", ", skipped) + ")";
            msg("Building " + queue.size() + " blocks from " + Config.INSTANCE.schematic
                    + "  [" + resolvedModeLabel(mc) + "]" + unknownWarn);
        } catch (Exception ex) {
            msg("Could not load schematic: " + ex.getMessage());
        }
    }

    public static void stop() {
        queue.clear();
        triedDirs.clear();
        halt("Cancelled.");
    }

    /** Prints what's still needed to build the given schematic at the given origin (no world changes). */
    public static void showMaterials(BlockPos origin, String file) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        try {
            List<String> skipped = new ArrayList<>();
            var list = buildQueueFor(mc, origin, file, skipped);
            Map<String, Integer> mats = new java.util.LinkedHashMap<>();
            for (var e : list) {
                Item item = e.getValue().getBlock().asItem();
                if (item == Items.AIR) continue;
                mats.merge(item.getName().getString(), 1, Integer::sum);
            }
            int total = list.size();
            mc.player.sendMessage(Text.literal("[AutoBuilder] " + total + " blocks needed at this spot:"), false);
            mats.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(25)
                    .forEach(e -> mc.player.sendMessage(Text.literal("  " + e.getValue() + "x " + e.getKey()), false));
            if (mats.size() > 25) mc.player.sendMessage(Text.literal("  ...and " + (mats.size() - 25) + " more types"), false);
            if (!skipped.isEmpty()) mc.player.sendMessage(Text.literal("  (unrecognized, skipped: " + String.join(", ", skipped) + ")"), false);
        } catch (Exception ex) {
            mc.player.sendMessage(Text.literal("[AutoBuilder] Could not read schematic: " + ex.getMessage()), false);
        }
    }

    /** Loads a schematic, applies the configured rotation, and returns only the positions that don't already match. */
    private static List<Map.Entry<BlockPos, BlockState>> buildQueueFor(MinecraftClient mc, BlockPos origin, String file, List<String> skippedOut) throws Exception {
        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve(file);
        Schematic s = Schematic.load(path);
        skippedOut.addAll(s.skippedTypes);
        List<Map.Entry<BlockPos, BlockState>> list = new ArrayList<>();
        net.minecraft.util.BlockRotation br = rotationFor(Config.INSTANCE.rotation);
        for (var e : s.blocks.entrySet()) {
            BlockPos p = origin.add(rotatePos(e.getKey(), Config.INSTANCE.rotation));
            BlockState st = e.getValue().rotate(br);
            if (mc.world.getBlockState(p) == st) continue; // already built (resume support)
            list.add(Map.entry(p, st));
        }
        return list;
    }

    private static BlockPos rotatePos(BlockPos p, int deg) {
        int x = p.getX(), z = p.getZ();
        return switch (deg) {
            case 90 -> new BlockPos(-z, p.getY(), x);
            case 180 -> new BlockPos(-x, p.getY(), -z);
            case 270 -> new BlockPos(z, p.getY(), -x);
            default -> p;
        };
    }

    private static net.minecraft.util.BlockRotation rotationFor(int deg) {
        return switch (deg) {
            case 90 -> net.minecraft.util.BlockRotation.CLOCKWISE_90;
            case 180 -> net.minecraft.util.BlockRotation.CLOCKWISE_180;
            case 270 -> net.minecraft.util.BlockRotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.util.BlockRotation.NONE;
        };
    }

    /** Human-readable time remaining, or "" if not enough data yet. */
    static String etaString() {
        if (placedBlocks <= 0 || totalBlocks <= 0) return "";
        long elapsed = System.currentTimeMillis() - startMillis;
        double perBlock = elapsed / (double) placedBlocks;
        int remaining = Math.max(0, totalBlocks - placedBlocks - skippedBlocks);
        long etaMs = (long) (perBlock * remaining);
        long s = etaMs / 1000;
        return s < 60 ? ("~" + s + "s") : ("~" + (s / 60) + "m" + (s % 60) + "s");
    }

    private static void halt(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        state = State.IDLE;
        status = reason;
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        msg(reason);
    }

    private static void finish() {
        MinecraftClient mc = MinecraftClient.getInstance();
        state = State.IDLE;
        String extra = skippedBlocks > 0 ? " (" + skippedBlocks + " skipped)" : "";
        status = "Build complete!";
        msg("Build complete! " + placedBlocks + " blocks placed" + extra + ".");
        if (Config.INSTANCE.soundOnComplete && mc.player != null) {
            mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                    net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F));
        }
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
        if (queue.isEmpty()) { finish(); return; }

        // layer-by-layer: only consider the lowest Y still left in the queue until it's fully done
        int targetY = Integer.MIN_VALUE;
        if (Config.INSTANCE.layerByLayer) {
            int minY = Integer.MAX_VALUE;
            for (var e : queue) minY = Math.min(minY, e.getKey().getY());
            targetY = minY;
        }

        Vec3d eye = pl.getEyePos();
        for (var e : queue) {
            BlockPos p = e.getKey();
            if (Config.INSTANCE.layerByLayer && p.getY() != targetY) continue;
            BlockState want = e.getValue();
            BlockState cur = w.getBlockState(p);

            if (!cur.isReplaceable()) {
                // right or wrong block, something's already there that shouldn't be — fix it
                if (useCreative(mc)) {
                    mc.interactionManager.attackBlock(p, Direction.UP);
                    status = (cur.getBlock() == want.getBlock() ? "Fixing orientation" : "Fixing wrong block")
                            + " at " + p.toShortString();
                    wait = 1;
                    return;
                }
                if (Config.INSTANCE.strictMode) {
                    halt("Wrong block at " + p.toShortString() + ": " + cur.getBlock().getName().getString()
                            + "  (turn off Strict mode to skip these instead)");
                    return;
                }
                skip(p, "wrong block at " + p.toShortString());
                return;
            }
            Item item = want.getBlock().asItem();
            if (item == Items.AIR) {
                if (Config.INSTANCE.strictMode) { halt("No item form for " + want); return; }
                skip(p, "no item form for " + want.getBlock().getName().getString());
                return;
            }
            if (pl.getBoundingBox().intersects(new Box(p))) {
                // standing on the target spot — step aside instead of stalling forever
                Vec3d away = pl.getPos().subtract(Vec3d.ofCenter(p));
                Vec3d flat = away.lengthSquared() < 0.0001 ? new Vec3d(1, 0, 0) : new Vec3d(away.x, 0, away.z).normalize();
                pl.setVelocity(flat.x * 0.2, pl.getVelocity().y, flat.z * 0.2);
                continue;
            }

            java.util.EnumSet<Direction> tried = triedDirs.computeIfAbsent(p, k -> java.util.EnumSet.noneOf(Direction.class));
            BlockHitResult hit = null;
            Direction chosen = null;
            for (Direction d : Direction.values()) {
                if (tried.contains(d)) continue;
                BlockPos nb = p.offset(d);
                BlockState ns = w.getBlockState(nb);
                if (ns.isAir() || ns.isReplaceable() || !ns.getFluidState().isEmpty()) continue;
                Vec3d v = Vec3d.ofCenter(p).offset(d, 0.5);
                if (eye.distanceTo(v) > 4.4) continue;
                hit = new BlockHitResult(v, d.getOpposite(), nb, false);
                chosen = d;
                break;
            }
            if (hit == null) {
                if (tried.size() >= 6) { // every face tried and rejected — give up cleanly on this one
                    tried.clear();
                    if (Config.INSTANCE.strictMode) { halt("Could not orient block at " + p.toShortString()); return; }
                    skip(p, "could not orient block at " + p.toShortString());
                    return;
                }
                continue;
            }

            // found a placeable spot — stop drifting before we interact, and aim precisely (helps orientation)
            boolean fly = pl.getAbilities().flying;
            pl.setVelocity(0, fly ? 0 : pl.getVelocity().y, 0);
            pl.setSprinting(false);
            aimAt(pl, hit.getPos());

            if (pl.getInventory().count(item) == 0) { supply(mc, item); return; }

            int eq = equip(mc, item);
            if (eq == 2) { wait = 3; return; }
            if (eq == 0) { supply(mc, item); return; }

            mc.interactionManager.interactBlock(pl, Hand.MAIN_HAND, hit);
            pl.swingHand(Hand.MAIN_HAND);
            pendingPos = p;
            pendingState = want;
            pendingDir = chosen;
            state = State.VERIFY;
            wait = delayTicks();
            return;
        }
        // nothing in range — walk toward the closest queued block (in-layer, if layer-by-layer) instead of just waiting
        BlockPos closest = null;
        double best = Double.MAX_VALUE;
        for (var e : queue) {
            if (Config.INSTANCE.layerByLayer && e.getKey().getY() != targetY) continue;
            double d = pl.getPos().squaredDistanceTo(Vec3d.ofCenter(e.getKey()));
            if (d < best) { best = d; closest = e.getKey(); }
        }
        if (closest == null) { wait = 0; return; }
        if (Config.INSTANCE.autoMove) {
            walkToward(pl, closest);
            wait = 0;
        } else {
            status = "Move closer (" + queue.size() + " left)";
            wait = 10;
        }
    }

    /** Turns the player to look directly at a point — vanilla placement logic (stair/log/repeater orientation etc.) reads this. */
    private static void aimAt(net.minecraft.client.network.ClientPlayerEntity pl, Vec3d point) {
        Vec3d d = point.subtract(pl.getEyePos());
        double xz = Math.sqrt(d.x * d.x + d.z * d.z);
        pl.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        pl.setPitch((float) -Math.toDegrees(Math.atan2(d.y, xz)));
    }

    /** Nudges the local player toward a target block each tick, walking (or flying, if the player has flight) with
     *  smoothed turning and sprint-speed so it reads as a normal player moving rather than a rigid bot. */
    private static void walkToward(net.minecraft.client.network.ClientPlayerEntity pl, BlockPos target) {
        boolean flying = pl.getAbilities().flying;
        Vec3d to = Vec3d.ofCenter(target).subtract(pl.getPos());
        Vec3d dir3 = flying ? to : new Vec3d(to.x, 0, to.z);
        if (dir3.lengthSquared() < 0.04) {
            pl.setVelocity(0, flying ? 0 : pl.getVelocity().y, 0);
            pl.setSprinting(false);
            curSpeed = 0;
            return;
        }
        Vec3d dir = dir3.normalize();
        double maxSpeed = flying ? 0.55 : 0.27;
        curSpeed = Math.min(maxSpeed, curSpeed + (flying ? 0.05 : 0.03)); // ramp up like a person accelerating, not an instant-speed bot
        double vy = flying ? dir.y * curSpeed : pl.getVelocity().y;
        pl.setVelocity(dir.x * curSpeed, vy, dir.z * curSpeed);
        pl.setSprinting(!flying);

        // turn smoothly toward the target instead of snapping — reads as a person walking, not a bot
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float delta = net.minecraft.util.math.MathHelper.wrapDegrees(targetYaw - pl.getYaw());
        pl.setYaw(pl.getYaw() + delta * 0.35f);

        if (!flying && pl.horizontalCollision && pl.isOnGround()) pl.jump();
    }

    /** Drops one queue entry (used in non-strict mode) and lets the next tick pick up where it left off. */
    private static void skip(BlockPos p, String reason) {
        queue.removeIf(e -> e.getKey().equals(p));
        triedDirs.remove(p);
        skippedBlocks++;
        status = "Skipped " + reason + " (" + queue.size() + " left)";
        wait = 1;
    }

    /** Delay (in ticks) between placement actions. Config.speed 1 (careful) .. 5 (fast). */
    private static int delayTicks() {
        return Math.max(1, 6 - Config.INSTANCE.speed);
    }

    private static void verify(MinecraftClient mc) {
        BlockState now = mc.world.getBlockState(pendingPos);
        if (now == pendingState) {
            queue.removeIf(e -> e.getKey().equals(pendingPos));
            triedDirs.remove(pendingPos);
            pendingTries = 0;
            placedBlocks++;
            state = State.PLACING;
            wait = Math.max(1, delayTicks() / 3);
        } else if (now.getBlock() == pendingState.getBlock()) {
            // right block, wrong orientation — in creative, break it and try again from a different face; otherwise give up on it
            var tried = triedDirs.computeIfAbsent(pendingPos, k -> java.util.EnumSet.noneOf(Direction.class));
            if (pendingDir != null) tried.add(pendingDir);
            if (useCreative(mc) && tried.size() < 6) {
                mc.interactionManager.attackBlock(pendingPos, Direction.UP);
                status = "Fixing orientation at " + pendingPos.toShortString();
                state = State.PLACING;
                wait = 1;
            } else if (Config.INSTANCE.strictMode) {
                halt("State mismatch at " + pendingPos.toShortString() + ": got " + now + ", wanted " + pendingState);
            } else {
                skip(pendingPos, "state mismatch at " + pendingPos.toShortString());
                state = State.PLACING;
            }
        } else if (++pendingTries >= 3) {
            var tried = triedDirs.computeIfAbsent(pendingPos, k -> java.util.EnumSet.noneOf(Direction.class));
            if (pendingDir != null) tried.add(pendingDir);
            if (useCreative(mc) && tried.size() < 6) {
                pendingTries = 0;
                state = State.PLACING;
            } else if (Config.INSTANCE.strictMode) {
                halt("Could not place " + pendingState.getBlock().getName().getString() + " at " + pendingPos.toShortString());
            } else {
                skip(pendingPos, "could not place at " + pendingPos.toShortString());
                state = State.PLACING;
            }
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

    // ---------- supply (creative pull or shop buy) ----------
    /** Decides how to get more of `item` into the hotbar: pull it for free in creative, or open the shop. */
    private static void supply(MinecraftClient mc, Item item) {
        if (useCreative(mc)) {
            supplyCreative(mc, item);
        } else {
            startShop(mc, item);
        }
    }

    /** Places the item directly into the current hotbar slot via the creative-inventory action (no shop needed). */
    private static void supplyCreative(MinecraftClient mc, Item item) {
        int hotbarSlot = mc.player.getInventory().selectedSlot;
        int screenSlot = 36 + hotbarSlot; // player screen handler: 36-44 are the hotbar
        mc.interactionManager.clickCreativeStack(new ItemStack(item, item.getMaxCount()), screenSlot);
        status = "Creative: grabbed " + item.getName().getString() + " (" + queue.size() + " left)";
        wait = Math.max(1, delayTicks() / 2);
    }

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
        if (mc.player.getInventory().getEmptySlot() == -1) { pauseFor("Inventory full — free some space, then Resume."); return; }

        int slot = findItem(h, shopItem);
        if (slot < 0) {
            int next = findNext(h);
            if (next < 0 || ++pages > Config.INSTANCE.maxPages) {
                pauseFor(shopItem.getName().getString() + " not found in shop — restock it, then Resume.");
                return;
            }
            click(mc, h, next);
            wait = 6;
            return;
        }

        double price = readPrice(h.getSlot(slot).getStack());
        double max = Config.INSTANCE.maxPricePerClick;
        if (max > 0 && price > max) { pauseFor("Price " + price + " is above your limit " + max + "."); return; }

        if (inv == lastCount) {
            if (++buyFails >= 4) { pauseFor("Purchase isn't registering (money? wrong GUI?)."); return; }
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
