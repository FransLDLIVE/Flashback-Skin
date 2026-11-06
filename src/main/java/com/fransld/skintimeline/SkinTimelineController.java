package com.fransld.skintimeline;

import com.fransld.skintimeline.SkinTimelineData.ActorTimeline;
import com.fransld.skintimeline.SkinTimelineData.Expression;
import com.fransld.skintimeline.SkinTimelineData.SkinChange;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.moulberry.flashback.FilePlayerSkin;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * Drives Flashback's "skin from file" override from a per-actor timeline.
 *
 * Every client tick (this also runs for every exported tick) it looks at the current replay tick and puts the
 * skin that should be active into Flashback's EditorState.skinOverrideFromFile map, which Flashback's own
 * rendering code already reads. Skins are loaded once and cached, so switching is instant.
 */
public final class SkinTimelineController {

    public static final Logger LOGGER = LoggerFactory.getLogger("FlashbackSkinTimeline");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    /** Work handed over from file-dialog threads, run on the client thread. */
    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    private static UUID replayId = null;
    private static SkinTimelineData data = null;

    /** Loaded skins by path. Kept alive while the replay is open so textures are not reloaded on every switch. */
    private static final Map<String, FilePlayerSkin> skinCache = new HashMap<>();
    /** Our own FilePlayerSkin instances, so we never remove a skin override the user set through Flashback. */
    private static final Set<FilePlayerSkin> ownSkins = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** Path -> problem message (missing file, wrong size...). Empty string means the file is fine. */
    private static final Map<String, String> fileChecks = new HashMap<>();
    /** Actors whose skin we controlled last tick. */
    private static final Set<UUID> managed = new HashSet<>();
    /** Each actor's skin without the timeline ("Original skin"), for the editor and for the current export. */
    private static final Map<UUID, FilePlayerSkin> liveBases = new HashMap<>();
    private static final Map<UUID, FilePlayerSkin> exportBases = new HashMap<>();
    private static boolean wasExporting = false;

    private static final Map<UUID, String> messages = new HashMap<>();
    private static long lastErrorLog = 0;

    private SkinTimelineController() {
    }

    // ------------------------------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------------------------------

    public static void onClientTick() {
        try {
            tick();
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastErrorLog > 10_000) {
                lastErrorLog = now;
                LOGGER.error("Skin timeline tick failed", t);
            }
        }
    }

    private static void tick() {
        if (!Flashback.isInReplay()) {
            if (replayId != null) {
                unload();
            }
            PENDING.clear();
            return;
        }

        ReplayServer server = Flashback.getReplayServer();
        if (server == null) {
            return;
        }
        FlashbackMeta meta = server.getMetadata();
        if (meta == null || meta.replayIdentifier == null) {
            return;
        }
        if (!meta.replayIdentifier.equals(replayId)) {
            unload();
            load(meta.replayIdentifier);
        }

        Runnable task;
        while ((task = PENDING.poll()) != null) {
            task.run();
        }

        // A save/copy that threw half-way never reached its RETURN hook: put our skins back now.
        if (cleanDepth > 0) {
            cleanDepth = 1;
            afterSaveOrCopy();
        }

        boolean exporting = Flashback.isExporting();
        if (exporting && !wasExporting) {
            exportBases.clear(); // a fresh copy of the editor state is used for each export
        }
        wasExporting = exporting;

        EditorState state = EditorStateManager.getCurrent();
        if (state != null) {
            apply(state, server.getReplayTick(), exporting ? exportBases : liveBases);
        }
    }

    /**
     * Puts the right skin into Flashback's skinOverrideFromFile map for every actor that has skin changes.
     *
     * "Original skin" means whatever was in that slot without the timeline (a Flashback "Load skin from file"
     * override, a Flashback Actors skin, or nothing = the recorded skin). We remember that as the actor's
     * base skin and put it back, instead of emptying the slot. Emptying it would make Flashback Actors
     * re-apply its own skin every tick and fight the timeline.
     */
    private static void apply(EditorState state, int tick, Map<UUID, FilePlayerSkin> bases) {
        Map<UUID, FilePlayerSkin> overrides = state.skinOverrideFromFile;
        if (overrides == null || data == null) {
            return;
        }

        Set<UUID> nowManaged = new HashSet<>();
        for (Map.Entry<String, ActorTimeline> entry : data.actors.entrySet()) {
            ActorTimeline timeline = entry.getValue();
            if (timeline == null || timeline.changes.isEmpty()) {
                continue;
            }
            UUID uuid = parseUuid(entry.getKey());
            if (uuid == null) {
                continue;
            }
            nowManaged.add(uuid);

            FilePlayerSkin current = overrides.get(uuid);
            if (current != null && !ownSkins.contains(current)) {
                bases.put(uuid, current); // someone else (Flashback, Flashback Actors, the user) set this skin
            }

            String path = activeSkin(timeline, tick);
            FilePlayerSkin wanted = path == null ? null : getUsableSkin(path);
            if (wanted != null) {
                if (current != wanted) {
                    overrides.put(uuid, wanted);
                }
            } else {
                restoreBase(overrides, bases, uuid, current);
            }
        }

        // Timeline cleared or actor removed: hand the slot back.
        for (UUID uuid : managed) {
            if (!nowManaged.contains(uuid)) {
                FilePlayerSkin current = overrides.get(uuid);
                if (current == null || ownSkins.contains(current)) {
                    restoreBase(overrides, bases, uuid, current);
                }
                bases.remove(uuid);
            }
        }
        managed.clear();
        managed.addAll(nowManaged);
    }

    private static void restoreBase(Map<UUID, FilePlayerSkin> overrides, Map<UUID, FilePlayerSkin> bases, UUID uuid, FilePlayerSkin current) {
        FilePlayerSkin base = bases.get(uuid);
        if (base != null) {
            if (current != base) {
                overrides.put(uuid, base);
            }
        } else if (current != null && ownSkins.contains(current)) {
            overrides.remove(uuid);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Keep timeline skins out of Flashback's saved files and export copies
    // ------------------------------------------------------------------------------------------------

    private static int cleanDepth = 0;
    private static Map<UUID, FilePlayerSkin> cleanedMap = null;
    private static final Map<UUID, FilePlayerSkin> swappedOut = new HashMap<>();

    /**
     * Called right before Flashback saves or copies its editor state: swap our timeline skins for the
     * actors' base skins, so the saved file (and each export's copy) only contains Flashback's own data.
     * Uninstalling this add-on therefore never leaves a timeline skin stuck on an actor.
     */
    public static void beforeSaveOrCopy(EditorState state) {
        try {
            if (cleanDepth++ > 0 || state == null || state.skinOverrideFromFile == null) {
                return;
            }
            Map<UUID, FilePlayerSkin> overrides = state.skinOverrideFromFile;
            // wasExporting is updated every tick; a copy made to *start* an export is taken from the live editor
            Map<UUID, FilePlayerSkin> bases = wasExporting ? exportBases : liveBases;
            swappedOut.clear();
            cleanedMap = overrides;
            // Look at every entry (not just the actors managed right now), so this also works while a replay
            // is closing and the timeline has already been unloaded.
            for (Map.Entry<UUID, FilePlayerSkin> entry : overrides.entrySet()) {
                if (entry.getValue() != null && ownSkins.contains(entry.getValue())) {
                    swappedOut.put(entry.getKey(), entry.getValue());
                }
            }
            for (UUID uuid : swappedOut.keySet()) {
                FilePlayerSkin base = bases.get(uuid);
                if (base != null) {
                    overrides.put(uuid, base);
                } else {
                    overrides.remove(uuid);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Skin timeline: couldn't prepare editor state for saving", t);
        }
    }

    /** Called right after the save/copy: put our skins back so nothing flickers. */
    public static void afterSaveOrCopy() {
        try {
            if (cleanDepth == 0 || --cleanDepth > 0) {
                return;
            }
            if (cleanedMap != null) {
                for (Map.Entry<UUID, FilePlayerSkin> entry : swappedOut.entrySet()) {
                    cleanedMap.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Skin timeline: couldn't restore skins after saving", t);
        } finally {
            if (cleanDepth == 0) {
                swappedOut.clear();
                cleanedMap = null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Queries used by the UI
    // ------------------------------------------------------------------------------------------------

    public static boolean isLoaded() {
        return data != null;
    }

    public static ActorTimeline getTimeline(UUID actor) {
        return data == null ? null : data.actors.get(actor.toString());
    }

    /** Path active at this tick, or null for the original skin. */
    public static String activeSkin(ActorTimeline timeline, int tick) {
        int index = activeIndex(timeline, tick);
        return index < 0 ? null : timeline.changes.get(index).skin;
    }

    /** Index of the change in effect at this tick, or -1 if none yet. */
    public static int activeIndex(ActorTimeline timeline, int tick) {
        if (timeline == null) {
            return -1;
        }
        int found = -1;
        for (int i = 0; i < timeline.changes.size(); i++) {
            if (timeline.changes.get(i).tick <= tick) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    /** Returns null if the file is usable, otherwise a short reason. */
    public static String fileProblem(String path) {
        if (path == null) {
            return null;
        }
        String result = fileChecks.computeIfAbsent(path, SkinTimelineController::checkSkinFile);
        return result.isEmpty() ? null : result;
    }

    public static String describe(ActorTimeline timeline, String path) {
        if (path == null) {
            return "Original skin";
        }
        if (timeline != null) {
            for (Expression expression : timeline.expressions) {
                if (path.equals(expression.path)) {
                    return expression.name;
                }
            }
        }
        return nameFromPath(path);
    }

    public static String getMessage(UUID actor) {
        return messages.get(actor);
    }

    public static void clearMessage(UUID actor) {
        messages.remove(actor);
    }

    /** Thread-safe: queue work (e.g. from a file dialog callback) to run on the client thread. */
    public static void runOnClientThread(Runnable runnable) {
        PENDING.add(runnable);
    }

    /** Actors in this replay's skin timeline, in the order they were added. */
    public static List<Map.Entry<UUID, ActorTimeline>> actors() {
        List<Map.Entry<UUID, ActorTimeline>> result = new ArrayList<>();
        if (data == null) {
            return result;
        }
        for (Map.Entry<String, ActorTimeline> entry : data.actors.entrySet()) {
            UUID uuid = parseUuid(entry.getKey());
            if (uuid != null && entry.getValue() != null) {
                result.add(Map.entry(uuid, entry.getValue()));
            }
        }
        return result;
    }

    /** Adds an actor row to the Skin Timeline window (no skin changes yet). */
    public static void addActor(UUID actor, String name) {
        if (data == null) {
            return;
        }
        ActorTimeline timeline = getOrCreate(actor);
        if (name != null && !name.isBlank()) {
            timeline.name = name;
        }
        save();
    }

    /** Remembers the actor's display name (used when they're not loaded in the world). */
    public static void rememberName(UUID actor, String name) {
        ActorTimeline timeline = getTimeline(actor);
        if (timeline != null && name != null && !name.isBlank() && !name.equals(timeline.name)) {
            timeline.name = name;
            save();
        }
    }

    /** Removes the actor's row, expression list and all their skin changes. */
    public static void removeActor(UUID actor) {
        if (data == null) {
            return;
        }
        data.actors.remove(actor.toString());
        messages.remove(actor);
        save();
    }

    /** Changes which skin an existing change switches to (null = original). */
    public static void setChangeSkin(UUID actor, int index, String skinPath) {
        ActorTimeline timeline = getTimeline(actor);
        if (timeline == null || index < 0 || index >= timeline.changes.size()) {
            return;
        }
        timeline.changes.get(index).skin = skinPath;
        save();
    }

    // ------------------------------------------------------------------------------------------------
    // Editing (client thread only)
    // ------------------------------------------------------------------------------------------------

    /** Switch the actor to this skin (null = original) starting at this tick. */
    public static void addChange(UUID actor, int tick, String skinPath, EditorState state) {
        if (data == null) {
            return;
        }
        ActorTimeline timeline = getOrCreate(actor);
        // Before the first change the actor keeps whatever skin they had (Flashback override, Flashback
        // Actors skin, or their recorded skin): apply() remembers it as their base skin.
        timeline.changes.removeIf(change -> change.tick == tick);
        timeline.changes.add(new SkinChange(tick, skinPath));
        sortChanges(timeline);
        save();
    }

    public static void removeChange(UUID actor, int index) {
        ActorTimeline timeline = getTimeline(actor);
        if (timeline == null || index < 0 || index >= timeline.changes.size()) {
            return;
        }
        timeline.changes.remove(index);
        save();
    }

    public static void moveChange(UUID actor, int index, int newTick) {
        ActorTimeline timeline = getTimeline(actor);
        if (timeline == null || index < 0 || index >= timeline.changes.size()) {
            return;
        }
        SkinChange change = timeline.changes.get(index);
        timeline.changes.removeIf(other -> other != change && other.tick == newTick);
        change.tick = Math.max(0, newTick);
        sortChanges(timeline);
        save();
    }

    public static void clearChanges(UUID actor) {
        ActorTimeline timeline = getTimeline(actor);
        if (timeline == null) {
            return;
        }
        timeline.changes.clear();
        save();
    }

    public static void addExpressionFile(UUID actor, String path) {
        if (data == null || path == null) {
            return;
        }
        path = Path.of(path).toAbsolutePath().normalize().toString();
        fileChecks.remove(path);
        String problem = fileProblem(path);
        if (problem != null) {
            messages.put(actor, nameFromPath(path) + ": " + problem);
            return;
        }
        ActorTimeline timeline = getOrCreate(actor);
        if (findExpression(timeline, path) >= 0) {
            messages.put(actor, nameFromPath(path) + " is already in the list.");
            return;
        }
        timeline.expressions.add(new Expression(nameFromPath(path), path));
        messages.remove(actor);
        save();
    }

    public static void addExpressionFolder(UUID actor, String folder) {
        if (data == null || folder == null) {
            return;
        }
        List<Path> pngs = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(folder))) {
            files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .forEach(pngs::add);
        } catch (Exception e) {
            messages.put(actor, "Couldn't read that folder: " + e.getMessage());
            return;
        }
        if (pngs.isEmpty()) {
            messages.put(actor, "No .png files in that folder.");
            return;
        }

        ActorTimeline timeline = getOrCreate(actor);
        int added = 0;
        List<String> rejected = new ArrayList<>();
        for (Path png : pngs) {
            String path = png.toAbsolutePath().normalize().toString();
            fileChecks.remove(path);
            if (fileProblem(path) != null) {
                rejected.add(nameFromPath(path));
                continue;
            }
            if (findExpression(timeline, path) < 0) {
                timeline.expressions.add(new Expression(nameFromPath(path), path));
                added++;
            }
        }
        String message = "Added " + added + " skin" + (added == 1 ? "" : "s") + ".";
        if (!rejected.isEmpty()) {
            message += " Skipped (not a valid skin size): " + String.join(", ", rejected);
        }
        messages.put(actor, message);
        save();
    }

    public static void removeExpression(UUID actor, int index) {
        ActorTimeline timeline = getTimeline(actor);
        if (timeline == null || index < 0 || index >= timeline.expressions.size()) {
            return;
        }
        timeline.expressions.remove(index);
        save();
    }

    public static void moveExpression(UUID actor, int index, int direction) {
        ActorTimeline timeline = getTimeline(actor);
        if (timeline == null) {
            return;
        }
        int target = index + direction;
        if (index < 0 || target < 0 || index >= timeline.expressions.size() || target >= timeline.expressions.size()) {
            return;
        }
        Expression moved = timeline.expressions.remove(index);
        timeline.expressions.add(target, moved);
        save();
    }

    /** Re-check every file (e.g. after the user fixed or replaced a PNG) and reload textures. */
    public static void reloadSkins() {
        fileChecks.clear();
        skinCache.clear();
        // ownSkins keeps the old instances so apply() still recognises (and replaces) them.
    }

    // ------------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------------

    private static FilePlayerSkin getUsableSkin(String path) {
        FilePlayerSkin cached = skinCache.get(path);
        if (cached != null) {
            return cached;
        }
        if (fileProblem(path) != null) {
            return null;
        }
        FilePlayerSkin skin = new FilePlayerSkin(path);
        skin.getSkin(); // load the texture now (we're on the render thread) so the switch frame has no hitch
        skinCache.put(path, skin);
        ownSkins.add(skin);
        return skin;
    }

    /**
     * Reads the PNG header to check the size. Minecraft's player model needs the modern square layout:
     * 64x64, or an HD version of it (128x128, 256x256, ...).
     */
    private static String checkSkinFile(String path) {
        try {
            Path file = Path.of(path);
            if (!Files.isRegularFile(file)) {
                return "file not found";
            }
            byte[] header = new byte[24];
            int read;
            try (InputStream in = Files.newInputStream(file)) {
                read = in.readNBytes(header, 0, 24);
            }
            if (read < 24 || (header[0] & 0xFF) != 0x89 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') {
                return "not a PNG image";
            }
            int width = readInt(header, 16);
            int height = readInt(header, 20);
            if (width != height || width < 64 || width % 64 != 0) {
                return "size is " + width + "x" + height + "; skins must be square 64x64, 128x128, 256x256...";
            }
            return "";
        } catch (Exception e) {
            return "can't read file (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static ActorTimeline getOrCreate(UUID actor) {
        return data.actors.computeIfAbsent(actor.toString(), key -> new ActorTimeline());
    }

    private static int findExpression(ActorTimeline timeline, String path) {
        for (int i = 0; i < timeline.expressions.size(); i++) {
            if (path.equals(timeline.expressions.get(i).path)) {
                return i;
            }
        }
        return -1;
    }

    private static void sortChanges(ActorTimeline timeline) {
        timeline.changes.sort(Comparator.comparingInt(change -> change.tick));
    }

    private static String nameFromPath(String path) {
        String name = Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replace('_', ' ').replace('-', ' ').trim();
        if (name.isEmpty()) {
            return "Skin";
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Path saveFile(UUID id) {
        return Flashback.getDataDirectory().resolve("skin_timelines").resolve(id + ".json");
    }

    private static void load(UUID id) {
        // Only now forget the previous replay's skins: Flashback may still save that replay after we unloaded.
        skinCache.clear();
        ownSkins.clear();
        managed.clear();
        liveBases.clear();
        exportBases.clear();
        replayId = id;
        data = new SkinTimelineData();
        Path file = saveFile(id);
        if (Files.isRegularFile(file)) {
            try {
                SkinTimelineData loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SkinTimelineData.class);
                if (loaded != null && loaded.actors != null) {
                    data = loaded;
                }
            } catch (Exception e) {
                LOGGER.error("Couldn't read skin timeline {}", file, e);
            }
        }
        // Repair anything odd in the file.
        data.actors.values().removeIf(timeline -> timeline == null);
        for (ActorTimeline timeline : data.actors.values()) {
            if (timeline.expressions == null) timeline.expressions = new ArrayList<>();
            if (timeline.changes == null) timeline.changes = new ArrayList<>();
            timeline.expressions.removeIf(e -> e == null || e.path == null);
            timeline.changes.removeIf(c -> c == null);
            sortChanges(timeline);
        }
    }

    private static void save() {
        if (data == null || replayId == null) {
            return;
        }
        Path file = saveFile(replayId);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't save skin timeline {}", file, e);
        }
    }

    private static void unload() {
        // ownSkins / bases are kept until the next replay loads, so a late save of this replay is still cleaned.
        replayId = null;
        data = null;
        fileChecks.clear();
        messages.clear();
    }
}
