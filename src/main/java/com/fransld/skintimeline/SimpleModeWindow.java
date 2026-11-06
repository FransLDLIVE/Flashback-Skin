package com.fransld.skintimeline;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.keybinds.Keybind;
import com.moulberry.flashback.editor.keybinds.Keybinds;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.windows.ExportScreenshotWindow;
import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import com.moulberry.flashback.editor.ui.windows.WindowType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImBoolean;

/**
 * "Simple Mode": one tidy window with big, labelled buttons for the everyday machinima steps.
 *
 * Buttons for keybind actions (play/pause, mark in/out, add camera keyframe, undo/redo) "press" Flashback's own
 * keybind once (see KeybindMixin), so Flashback runs its own code for them. Other buttons call Flashback's
 * public functions directly (jump in time, open the export window, ...).
 */
public final class SimpleModeWindow {

    private static final ImBoolean OPEN = new ImBoolean(true);
    private static boolean loadedSetting = false;
    private static long lastErrorLog = 0;

    private static final float BIG = 34f;
    private static final float GAP = 6f;

    // One pending "virtual key press", consumed by Flashback's timeline on the next frame.
    private static volatile Keybind pendingKey = null;
    private static volatile long pendingAtNanos = 0;
    private static final long PRESS_TIMEOUT_NANOS = 500_000_000L;
    private static final String TIMELINE_CLASS = "com.moulberry.flashback.editor.ui.windows.TimelineWindow";

    private SimpleModeWindow() {
    }

    public static boolean isOpen() {
        ensureLoaded();
        return OPEN.get();
    }

    public static void toggle() {
        ensureLoaded();
        OPEN.set(!OPEN.get());
        UiSettings.get().simpleModeOpen = OPEN.get();
        UiSettings.save();
    }

    private static void ensureLoaded() {
        if (!loadedSetting) {
            loadedSetting = true;
            OPEN.set(UiSettings.get().simpleModeOpen);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Virtual key presses
    // ------------------------------------------------------------------------------------------------

    private static boolean lastPressMissed = false;

    private static void press(Keybind keybind) {
        pendingKey = keybind;
        pendingAtNanos = System.nanoTime();
    }

    /** Called from Keybind.isPressed: true once for the keybind a Simple Mode button asked for. */
    public static boolean consumeVirtualPress(Keybind keybind) {
        Keybind pending = pendingKey;
        if (pending == null) {
            return false;
        }
        if (System.nanoTime() - pendingAtNanos > PRESS_TIMEOUT_NANOS) {
            pendingKey = null; // Flashback didn't read it in time (e.g. a popup was open); drop it
            lastPressMissed = true;
            return false;
        }
        if (pending != keybind || !calledFromTimeline()) {
            return false;
        }
        pendingKey = null;
        lastPressMissed = false;
        return true;
    }

    private static boolean calledFromTimeline() {
        return StackWalker.getInstance().walk(frames -> frames.limit(12)
                .anyMatch(frame -> TIMELINE_CLASS.equals(frame.getClassName())));
    }

    // ------------------------------------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------------------------------------

    public static void render() {
        ensureLoaded();
        if (!OPEN.get()) {
            return;
        }
        ReplayServer server = Flashback.getReplayServer();
        if (server == null) {
            return;
        }

        ImGui.setNextWindowSize(340, 600, 4); // 4 = ImGuiCond_FirstUseEver
        boolean wasOpen = OPEN.get();
        if (ImGui.begin("Simple Mode###flashback_simple_mode", OPEN, 0)) {
            try {
                renderContents(server, EditorStateManager.getCurrent());
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                if (now - lastErrorLog > 10_000) {
                    lastErrorLog = now;
                    SkinTimelineController.LOGGER.error("Simple Mode window failed to draw", t);
                }
            }
        }
        ImGui.end();
        if (wasOpen && !OPEN.get()) {
            UiSettings.get().simpleModeOpen = false; // closed with the window's X
            UiSettings.save();
        }
    }

    private static void renderContents(ReplayServer server, EditorState state) {
        // A button press Flashback never picked up (its timeline window is closed/collapsed, or a popup is open)
        if (pendingKey != null && System.nanoTime() - pendingAtNanos > PRESS_TIMEOUT_NANOS) {
            pendingKey = null;
            lastPressMissed = true;
        }
        if (lastPressMissed) {
            ImGui.textColored(1.0f, 0.75f, 0.3f, 1.0f, "Flashback didn't react to that button.");
            ImGui.textWrapped("Make sure Flashback's Timeline window is open (not collapsed) and no popup is open, then try again.");
            if (ImGui.smallButton("OK##missed")) {
                lastPressMissed = false;
            }
        }
        int tick = server.getReplayTick();
        int total = Math.max(1, server.getTotalReplayTicks());
        boolean paused = server.replayPaused;

        // ---- 1. Playback ----
        ImGuiHelper.separatorWithText("1. Find the moment");
        ImGui.text(SkinTimelineUI.formatTick(tick) + " / " + SkinTimelineUI.formatTick(total));
        ImGui.sameLine();
        if (paused) {
            ImGui.textDisabled("(paused)");
        } else {
            ImGui.textColored(0.4f, 1.0f, 0.5f, 1.0f, "(playing)");
        }

        if (wideButton(paused ? "Play##play" : "Pause##play", BIG)) {
            press(Keybinds.PAUSE);
        }
        tooltip("Play or pause the replay (same as Flashback's Pause/Play key)");

        int choice = buttonRow(BIG, "|< Start", "-1 sec", "+1 sec", "End >|");
        if (choice == 0) jump(server, 0);
        if (choice == 1) jump(server, tick - 20);
        if (choice == 2) jump(server, tick + 20);
        if (choice == 3) jump(server, total);
        int fine = buttonRow(0, "-1 tick", "+1 tick");
        if (fine == 0) jump(server, tick - 1);
        if (fine == 1) jump(server, tick + 1);
        tooltip("A tick is 1/20 of a second, for exact timing");

        // ---- 2. Camera ----
        ImGuiHelper.separatorWithText("2. Camera");
        if (wideButton("Add camera keyframe here", BIG)) {
            press(Keybinds.ADD_CAMERA);
        }
        tooltip("Saves where the camera is right now, at this moment.\nThe camera glides between keyframes when you play or export.");
        ImGui.textWrapped("Fly the camera into place, then click. Add more at other moments for a camera move.");
        int edit = buttonRow(0, "Undo", "Redo");
        if (edit == 0) press(Keybinds.UNDO);
        if (edit == 1) press(Keybinds.REDO);
        tooltip("Undo or redo keyframe changes");

        // ---- 3. Video range ----
        ImGuiHelper.separatorWithText("3. What part to export");
        EditorState.StartAndEnd range = state != null ? state.getExportStartAndEnd() : null;
        int start = range != null ? range.start() : -1;
        int end = range != null ? range.end() : -1;
        String startText = start >= 0 ? SkinTimelineUI.formatTick(start) : "beginning";
        String endText = end >= 0 ? SkinTimelineUI.formatTick(end) : "end";
        ImGui.text("From " + startText + "  to  " + endText);
        int mark = buttonRow(0, "Start here", "End here");
        if (mark == 0) press(Keybinds.MARK_IN);
        if (mark == 1) press(Keybinds.MARK_OUT);
        tooltip("Sets where the exported video starts / ends (the In / Out points)");
        int clear = buttonRow(0, "Clear start", "Clear end");
        if (clear == 0) press(Keybinds.CLEAR_IN);
        if (clear == 1) press(Keybinds.CLEAR_OUT);

        // ---- 4. Export ----
        ImGuiHelper.separatorWithText("4. Export");
        if (wideButton("Export video...", BIG)) {
            StartExportWindow.open();
        }
        tooltip("Opens Flashback's export window (resolution, frame rate, file name)");
        if (wideButton("Take a screenshot...", 0)) {
            ExportScreenshotWindow.open();
        }

        // ---- 5. Characters ----
        ImGuiHelper.separatorWithText("5. Characters");
        if (wideButton((SkinTimelineWindow.isOpen() ? "Hide" : "Show") + " Skin Timeline (expressions)", 0)) {
            SkinTimelineWindow.toggle();
        }
        tooltip("Change actors' skins mid-scene: happy, sad, angry...");
        if (wideButton("Player list", 0)) {
            WindowType.PLAYER_LIST.toggle();
        }
        tooltip("Find a player, hide them, or jump the camera to them");
        ImGui.textWrapped("Right-click any character in the replay for more options.");

        // ---- Help ----
        ImGuiHelper.separatorWithText("Help");
        if (wideButton("Keyboard shortcuts", 0)) {
            WindowType.KEYBINDS.toggle();
        }
        ImGui.textDisabled("Close this panel any time; reopen it from the top menu: Simple Mode.");
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private static void jump(ReplayServer server, int target) {
        SkinTimelineWindow.jumpTo(server, target);
    }

    private static boolean wideButton(String label, float height) {
        return ImGui.button(label, Math.max(60f, ImGui.getContentRegionAvailX()), height);
    }

    /** Equal-width buttons on one row. Returns the index of the clicked one, or -1. */
    private static int buttonRow(float height, String... labels) {
        float width = (ImGui.getContentRegionAvailX() - GAP * (labels.length - 1)) / labels.length;
        width = Math.max(40f, width);
        int clicked = -1;
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                ImGui.sameLine(0, GAP);
            }
            if (ImGui.button(labels[i] + "##row" + labels[0] + i, width, height)) {
                clicked = i;
            }
        }
        return clicked;
    }

    private static void tooltip(String text) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(text);
        }
    }
}
