package me.example.autobuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    public String shopCommand = "/shop";
    public String schematic = "build.schem";
    public int maxPages = 10;
    public double maxPricePerClick = 0; // 0 = no limit

    /** AUTO = creative if singleplayer+creative, else shop. CREATIVE = always pull from creative. SHOP = always buy. */
    public String mode = "AUTO";
    /** If false, a wrong/unplaceable block is skipped with a warning instead of stopping the whole build. */
    public boolean strictMode = true;
    /** Play a sound when the build finishes. */
    public boolean soundOnComplete = true;
    /** 1 (careful/slow) .. 5 (fast). Controls the delay between actions. */
    public int speed = 3;
    /** Finish each Y layer (bottom-up) before starting the next, instead of just going for whatever's reachable. */
    public boolean layerByLayer = true;
    /** Degrees to rotate the schematic around the origin before building: 0, 90, 180, or 270. */
    public int rotation = 0;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("autobuilder.json");
    public static Config INSTANCE = load();

    private static Config load() {
        try {
            if (Files.exists(FILE)) {
                Config c = new Gson().fromJson(Files.readString(FILE), Config.class);
                if (c != null) return c;
            }
        } catch (Exception ignored) {}
        return new Config();
    }

    public void save() {
        try {
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(this));
        } catch (Exception ignored) {}
    }
}
