package com.fransld.skintimeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.moulberry.flashback.Flashback;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Remembers which add-on windows were open (.minecraft/flashback/skin_timelines/settings.json). */
public final class UiSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class Values {
        public boolean simpleModeOpen = true;
        public boolean skinTimelineOpen = false;
    }

    private static Values values = null;

    private UiSettings() {
    }

    public static Values get() {
        if (values == null) {
            values = new Values();
            try {
                Path file = file();
                if (Files.isRegularFile(file)) {
                    Values loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Values.class);
                    if (loaded != null) {
                        values = loaded;
                    }
                }
            } catch (Exception e) {
                SkinTimelineController.LOGGER.warn("Couldn't read add-on settings", e);
            }
        }
        return values;
    }

    public static void save() {
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkinTimelineController.LOGGER.warn("Couldn't save add-on settings", e);
        }
    }

    private static Path file() {
        return Flashback.getDataDirectory().resolve("skin_timelines").resolve("settings.json");
    }
}
