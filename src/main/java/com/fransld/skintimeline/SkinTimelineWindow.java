package com.fransld.skintimeline;

import com.fransld.skintimeline.SkinTimelineData.ActorTimeline;
import com.fransld.skintimeline.SkinTimelineData.Expression;
import com.fransld.skintimeline.SkinTimelineData.SkinChange;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import imgui.moulberry90.ImDrawList;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Skin Timeline" window: one row per actor, coloured blocks showing which skin is active when.
 * Click a row to add an expression, drag a marker to move it, right-click a marker for options.
 * Follows Flashback's timeline zoom so both timelines show the same stretch of the replay.
 */
public final class SkinTimelineWindow {

    private static final ImBoolean OPEN = new ImBoolean(false);

    private static final String POPUP_ADD = "stl_popup_add";
    private static final String POPUP_KEY = "stl_popup_key";
    private static final String POPUP_ACTOR = "stl_popup_actor";
    private static final String POPUP_ADD_ACTOR = "stl_popup_add_actor";

    private static final float NAME_WIDTH = 160f;
    private static final float LANE_HEIGHT = 26f;
    private static final float RULER_HEIGHT = 20f;
    private static final float ROW_GAP = 3f;
    private static final float HANDLE_GRAB_PX = 6f;

    // Colours are ImGui's 0xAABBGGRR format
    private static final int COL_RULER_BG = rgba(28, 28, 33, 255);
    private static final int COL_LANE_BG = rgba(38, 38, 44, 255);
    private static final int COL_NAME_BG = rgba(48, 48, 56, 255);
    private static final int COL_NAME_HOVER = rgba(66, 66, 78, 255);
    private static final int COL_ORIGINAL = rgba(64, 64, 72, 255);
    private static final int COL_PROBLEM = rgba(150, 50, 50, 255);
    private static final int COL_TEXT = rgba(235, 235, 235, 255);
    private static final int COL_TEXT_DIM = rgba(150, 150, 158, 255);
    private static final int COL_TEXT_DARK = rgba(20, 20, 24, 255);
    private static final int COL_HANDLE = rgba(255, 255, 255, 190);
    private static final int COL_HANDLE_HOT = rgba(255, 215, 80, 255);
    private static final int COL_PLAYHEAD = rgba(255, 85, 85, 255);
    private static final int COL_GRID = rgba(255, 255, 255, 22);

    // Drag a marker
    private static UUID dragActor = null;
    private static int dragIndex = -1;
    private static float dragStartX = 0;
    private static boolean dragMoved = false;
    private static int dragTick = 0;
    // Click on an empty part of a row
    private static UUID pressActor = null;
    private static int pressTick = 0;
    private static float pressX = 0;
    // Scrubbing on the ruler
    private static int lastScrubTick = -1;

    // Popup context
    private static String requestPopup = null;
    private static UUID popupActor = null;
    private static int popupTick = 0;
    private static int popupIndex = -1;
    private static UUID messageActor = null;

    private static final Map<UUID, String> onlineNames = new LinkedHashMap<>();
    /** Virtual actors from the Flashback Actors mod (empty if it isn't installed). */
    private static final Map<UUID, String> fbActors = new LinkedHashMap<>();
    private static long namesUpdatedAt = 0;
    private static long lastErrorLog = 0;

    private SkinTimelineWindow() {
    }

    private static boolean loadedSetting = false;

    private static void ensureLoaded() {
        if (!loadedSetting) {
            loadedSetting = true;
            OPEN.set(UiSettings.get().skinTimelineOpen);
        }
    }

    private static void setOpen(boolean open) {
        ensureLoaded();
        OPEN.set(open);
        UiSettings.get().skinTimelineOpen = open;
        UiSettings.save();
    }

    public static void toggle() {
        ensureLoaded();
        setOpen(!OPEN.get());
    }

    public static void open() {
        setOpen(true);
    }

    public static boolean isOpen() {
        ensureLoaded();
        return OPEN.get();
    }

    // ------------------------------------------------------------------------------------------------

    public static void render() {
        ensureLoaded();
        if (!OPEN.get()) {
            resetInteraction();
            return;
        }
        ReplayServer server = Flashback.getReplayServer();
        if (server == null || !SkinTimelineController.isLoaded()) {
            return;
        }

        ImGui.setNextWindowSize(780, 240, 4); // 4 = ImGuiCond_FirstUseEver
        boolean wasOpen = OPEN.get();
        if (ImGui.begin("Skin Timeline###flashback_skin_timeline", OPEN, 0)) {
            ImDrawList drawList = ImGui.getWindowDrawList();
            try {
                renderContents(server, EditorStateManager.getCurrent());
            } catch (Throwable t) {
                while (clipDepth > 0) { // keep ImGui's clip stack balanced if drawing stopped half-way
                    drawList.popClipRect();
                    clipDepth--;
                }
                resetInteraction();
                long now = System.currentTimeMillis();
                if (now - lastErrorLog > 10_000) {
                    lastErrorLog = now;
                    SkinTimelineController.LOGGER.error("Skin Timeline window failed to draw", t);
                }
            }
        } else {
            resetInteraction(); // collapsed
        }
        ImGui.end();
        if (wasOpen && !OPEN.get()) {
            setOpen(false); // closed with the window's X
        }
    }

    private static int clipDepth = 0;

    private static void pushClip(ImDrawList draw, float x1, float y1, float x2, float y2) {
        pushClip(draw, x1, y1, x2, y2);
        clipDepth++;
    }

    private static void popClip(ImDrawList draw) {
        popClip(draw);
        clipDepth--;
    }

    private static void resetInteraction() {
        dragActor = null;
        dragIndex = -1;
        dragMoved = false;
        pressActor = null;
        lastScrubTick = -1;
    }

    /** Jump the replay like Flashback's own timeline does (also moves the camera to its keyframed spot). */
    static void jumpTo(ReplayServer server, int tick) {
        server.goToReplayTick(Math.max(0, Math.min(server.getTotalReplayTicks(), tick)));
        if (server.forceApplyKeyframes != null) {
            server.forceApplyKeyframes.set(true);
        }
    }

    private static void renderContents(ReplayServer server, EditorState state) {
        refreshOnlineNames(false);

        int total = Math.max(1, server.getTotalReplayTicks());
        int tick = server.getReplayTick();
        double zoomMin = state != null ? state.zoomMin : 0;
        double zoomMax = state != null ? state.zoomMax : 1;
        if (!(zoomMax > zoomMin) || zoomMin < 0 || zoomMax > 1.0001) {
            zoomMin = 0;
            zoomMax = 1;
        }
        View view = new View((float) (zoomMin * total), (float) (zoomMax * total), total);

        // ---- Header ----
        ImGui.text("At " + SkinTimelineUI.formatTick(tick));
        ImGui.sameLine();
        if (ImGui.smallButton("+ Add actor")) {
            refreshOnlineNames(true);
            requestPopup = POPUP_ADD_ACTOR;
        }
        ImGui.sameLine();
        ImGui.textDisabled("Click a row to add an expression | drag a marker to move it | right-click for options");

        if (messageActor != null) {
            String message = SkinTimelineController.getMessage(messageActor);
            if (message != null) {
                ImGui.textColored(1.0f, 0.8f, 0.3f, 1.0f, message);
                ImGui.sameLine();
                if (ImGui.smallButton("OK##stl_msg")) {
                    SkinTimelineController.clearMessage(messageActor);
                    messageActor = null;
                }
            }
        }

        List<Map.Entry<UUID, ActorTimeline>> actors = SkinTimelineController.actors();

        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        float availableWidth = ImGui.getContentRegionAvailX();
        view.laneX = x0 + NAME_WIDTH;
        view.laneW = Math.max(60f, availableWidth - NAME_WIDTH);
        float mouseX = ImGui.getMousePosX();
        float mouseY = ImGui.getMousePosY();
        ImDrawList draw = ImGui.getWindowDrawList();

        // ---- Ruler (click / drag to scrub the replay) ----
        ImGui.setCursorScreenPos(view.laneX, y0);
        ImGui.invisibleButton("##stl_ruler", view.laneW, RULER_HEIGHT);
        boolean rulerHovered = ImGui.isItemHovered();
        if (ImGui.isItemActive()) {
            int target = view.tickAt(mouseX);
            if (target != lastScrubTick) {
                lastScrubTick = target;
                jumpTo(server, target);
            }
        } else {
            lastScrubTick = -1;
        }
        if (rulerHovered) {
            ImGui.setTooltip("Click or drag to move the replay to " + SkinTimelineUI.formatTick(view.tickAt(mouseX)));
        }
        drawRuler(draw, view, y0);

        // ---- Rows ----
        float y = y0 + RULER_HEIGHT + ROW_GAP;
        if (actors.isEmpty()) {
            ImGui.setCursorScreenPos(x0, y + 4);
            ImGui.textWrapped("No actors yet. Click \"+ Add actor\", or right-click an actor in the replay and add their expression skins.");
            y = ImGui.getCursorScreenPosY();
        }
        for (Map.Entry<UUID, ActorTimeline> entry : actors) {
            renderRow(draw, view, entry.getKey(), entry.getValue(), x0, y, mouseX, mouseY, state);
            y += LANE_HEIGHT + ROW_GAP;
        }
        float rowsBottom = y;

        // ---- Playhead ----
        if (tick >= view.start && tick <= view.end) {
            float px = view.xFor(tick);
            draw.addLine(px, y0, px, rowsBottom, COL_PLAYHEAD, 2f);
            draw.addTriangleFilled(px - 6, y0, px + 6, y0, px, y0 + 8, COL_PLAYHEAD);
        }

        ImGui.setCursorScreenPos(x0, rowsBottom);
        ImGui.dummy(availableWidth, 2);

        handleMouseRelease(view, mouseX);

        if (requestPopup != null) {
            ImGui.openPopup(requestPopup);
            requestPopup = null;
        }
        renderPopups(server, state, tick);
    }

    // ------------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------------

    private static void drawRuler(ImDrawList draw, View view, float y0) {
        draw.addRectFilled(view.laneX, y0, view.laneX + view.laneW, y0 + RULER_HEIGHT, COL_RULER_BG, 3f);
        float pxPerTick = view.laneW / Math.max(1f, view.end - view.start);
        int[] steps = {1, 2, 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400, 6000, 12000, 24000, 72000};
        int step = steps[steps.length - 1];
        for (int candidate : steps) {
            if (candidate * pxPerTick >= 70f) {
                step = candidate;
                break;
            }
        }
        pushClip(draw, view.laneX, y0, view.laneX + view.laneW, y0 + RULER_HEIGHT);
        int first = (int) Math.ceil(view.start / step) * step;
        for (int t = first; t <= view.end; t += step) {
            float x = view.xFor(t);
            draw.addLine(x, y0 + RULER_HEIGHT - 6, x, y0 + RULER_HEIGHT, COL_TEXT_DIM, 1f);
            String label = step < 20 ? SkinTimelineUI.formatTick(t) : formatShort(t);
            draw.addText(x + 3, y0 + 2, COL_TEXT_DIM, label);
        }
        popClip(draw);
    }

    private static void renderRow(ImDrawList draw, View view, UUID actor, ActorTimeline timeline,
                                  float x0, float y, float mouseX, float mouseY, EditorState state) {
        String name = displayName(actor, timeline);
        ImGui.pushID(actor.toString());
        try {
            // ---- Name column (click for actor options) ----
            ImGui.setCursorScreenPos(x0, y);
            ImGui.invisibleButton("##name", NAME_WIDTH - 6, LANE_HEIGHT);
            boolean nameHovered = ImGui.isItemHovered();
            if (ImGui.isItemClicked(0) || ImGui.isItemClicked(1)) {
                popupActor = actor;
                requestPopup = POPUP_ACTOR;
            }
            if (nameHovered) {
                ImGui.setTooltip("Click for " + name + "'s options (add skins, clear, remove)");
            }
            draw.addRectFilled(x0, y, x0 + NAME_WIDTH - 6, y + LANE_HEIGHT, nameHovered ? COL_NAME_HOVER : COL_NAME_BG, 3f);
            pushClip(draw, x0, y, x0 + NAME_WIDTH - 10, y + LANE_HEIGHT);
            draw.addText(x0 + 6, y + (LANE_HEIGHT - ImGui.getTextLineHeight()) / 2f, COL_TEXT, name);
            popClip(draw);

            // ---- Lane ----
            ImGui.setCursorScreenPos(view.laneX, y);
            ImGui.invisibleButton("##lane", view.laneW, LANE_HEIGHT, 3); // 3 = left + right mouse buttons
            boolean laneHovered = ImGui.isItemHovered();

            // What to draw (the marker being dragged is shown at its new position)
            List<SkinChange> shown = new ArrayList<>();
            int draggedShownIndex = -1;
            for (int i = 0; i < timeline.changes.size(); i++) {
                SkinChange change = timeline.changes.get(i);
                boolean dragged = actor.equals(dragActor) && i == dragIndex && dragMoved;
                shown.add(new SkinChange(dragged ? dragTick : change.tick, change.skin));
                if (dragged) {
                    draggedShownIndex = i;
                }
            }
            SkinChange draggedChange = draggedShownIndex >= 0 ? shown.get(draggedShownIndex) : null;
            shown.sort((a, b) -> Integer.compare(a.tick, b.tick));

            float laneRight = view.laneX + view.laneW;
            draw.addRectFilled(view.laneX, y, laneRight, y + LANE_HEIGHT, COL_LANE_BG, 3f);
            pushClip(draw, view.laneX, y, laneRight, y + LANE_HEIGHT);

            // Before the first change: original skin
            float firstX = shown.isEmpty() ? laneRight : view.xFor(shown.get(0).tick);
            if (firstX > view.laneX) {
                draw.addRectFilled(view.laneX, y + 3, Math.min(firstX, laneRight), y + LANE_HEIGHT - 3, COL_ORIGINAL, 2f);
                if (!shown.isEmpty() && firstX - view.laneX > 70) {
                    draw.addText(view.laneX + 5, y + (LANE_HEIGHT - ImGui.getTextLineHeight()) / 2f, COL_TEXT_DIM, "Original");
                }
            }

            for (int i = 0; i < shown.size(); i++) {
                SkinChange change = shown.get(i);
                int endTick = i + 1 < shown.size() ? shown.get(i + 1).tick : view.total;
                float sx = view.xFor(change.tick);
                float ex = view.xFor(endTick);
                if (ex < view.laneX || sx > laneRight) {
                    continue;
                }
                boolean problem = SkinTimelineController.fileProblem(change.skin) != null;
                int fill = change.skin == null ? COL_ORIGINAL : problem ? COL_PROBLEM : colourFor(SkinTimelineController.describe(timeline, change.skin));
                draw.addRectFilled(Math.max(sx, view.laneX), y + 3, Math.min(ex, laneRight), y + LANE_HEIGHT - 3, fill, 2f);
                if (ex - sx > 24) {
                    String label = SkinTimelineController.describe(timeline, change.skin) + (problem ? " (missing)" : "");
                    float textX = Math.max(sx, view.laneX) + 6;
                    pushClip(draw, textX - 2, y, Math.min(ex, laneRight) - 2, y + LANE_HEIGHT);
                    draw.addText(textX, y + (LANE_HEIGHT - ImGui.getTextLineHeight()) / 2f,
                            change.skin == null ? COL_TEXT_DIM : COL_TEXT_DARK, label);
                    popClip(draw);
                }
            }

            // Markers
            int hoveredIndex = laneHovered && dragActor == null ? markerAt(view, timeline, mouseX) : -1;
            for (int i = 0; i < timeline.changes.size(); i++) {
                boolean isDragged = actor.equals(dragActor) && i == dragIndex;
                int markerTick = isDragged && dragMoved ? dragTick : timeline.changes.get(i).tick;
                float mx = view.xFor(markerTick);
                if (mx < view.laneX - 4 || mx > laneRight + 4) {
                    continue;
                }
                boolean hot = isDragged || i == hoveredIndex;
                draw.addRectFilled(mx - (hot ? 2.5f : 1.5f), y, mx + (hot ? 2.5f : 1.5f), y + LANE_HEIGHT, hot ? COL_HANDLE_HOT : COL_HANDLE);
            }
            popClip(draw);

            if (draggedChange != null) {
                ImGui.setTooltip("Move to " + SkinTimelineUI.formatTick(dragTick));
            }

            // ---- Mouse ----
            if (laneHovered && dragActor == null) {
                int mouseTick = view.tickAt(mouseX);
                if (ImGui.isMouseClicked(0)) {
                    if (hoveredIndex >= 0) {
                        dragActor = actor;
                        dragIndex = hoveredIndex;
                        dragStartX = mouseX;
                        dragMoved = false;
                        dragTick = timeline.changes.get(hoveredIndex).tick;
                    } else {
                        pressActor = actor;
                        pressTick = mouseTick;
                        pressX = mouseX;
                    }
                } else if (ImGui.isMouseClicked(1)) {
                    int index = hoveredIndex >= 0 ? hoveredIndex : SkinTimelineController.activeIndex(timeline, mouseTick);
                    popupActor = actor;
                    popupTick = mouseTick;
                    if (index >= 0) {
                        popupIndex = index;
                        requestPopup = POPUP_KEY;
                    } else {
                        requestPopup = POPUP_ADD;
                    }
                } else if (hoveredIndex >= 0) {
                    SkinChange change = timeline.changes.get(hoveredIndex);
                    ImGui.setTooltip(SkinTimelineController.describe(timeline, change.skin) + " at " + SkinTimelineUI.formatTick(change.tick)
                            + "\nDrag to move | click to jump here | right-click for options");
                } else {
                    ImGui.setTooltip("Click to add an expression at " + SkinTimelineUI.formatTick(mouseTick));
                }
            }
        } finally {
            ImGui.popID();
        }
    }

    private static void handleMouseRelease(View view, float mouseX) {
        if (dragActor != null) {
            if (Math.abs(mouseX - dragStartX) > 3f) {
                dragMoved = true;
            }
            if (dragMoved) {
                dragTick = view.tickAt(mouseX);
            }
            if (ImGui.isMouseReleased(0)) {
                UUID actor = dragActor;
                int index = dragIndex;
                ActorTimeline timeline = SkinTimelineController.getTimeline(actor);
                if (timeline != null && index >= 0 && index < timeline.changes.size()) {
                    if (dragMoved) {
                        SkinTimelineController.moveChange(actor, index, dragTick);
                    } else {
                        ReplayServer server = Flashback.getReplayServer();
                        if (server != null) {
                            jumpTo(server, timeline.changes.get(index).tick);
                        }
                    }
                }
                dragActor = null;
                dragIndex = -1;
                dragMoved = false;
            }
        }
        if (pressActor != null && ImGui.isMouseReleased(0)) {
            if (Math.abs(mouseX - pressX) <= 4f) {
                popupActor = pressActor;
                popupTick = pressTick;
                requestPopup = POPUP_ADD;
            }
            pressActor = null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Popups
    // ------------------------------------------------------------------------------------------------

    private static void renderPopups(ReplayServer server, EditorState state, int tick) {
        Runnable action = null;

        if (ImGui.beginPopup(POPUP_ADD)) {
            try {
                ActorTimeline timeline = popupActor == null ? null : SkinTimelineController.getTimeline(popupActor);
                if (timeline == null) {
                    ImGui.closeCurrentPopup();
                } else {
                    ImGui.textDisabled(displayName(popupActor, timeline) + ": switch at " + SkinTimelineUI.formatTick(popupTick));
                    if (popupTick != tick && ImGui.smallButton("Use playhead time instead (" + SkinTimelineUI.formatTick(tick) + ")")) {
                        popupTick = tick;
                    }
                    ImGui.separator();
                    if (timeline.expressions.isEmpty()) {
                        ImGui.textDisabled("No expression skins yet.");
                    }
                    UUID actor = popupActor;
                    int at = popupTick;
                    for (int i = 0; i < timeline.expressions.size(); i++) {
                        Expression expression = timeline.expressions.get(i);
                        boolean usable = SkinTimelineController.fileProblem(expression.path) == null;
                        if (ImGui.menuItem(expression.name + "##add" + i, "", false, usable)) {
                            String path = expression.path;
                            action = () -> SkinTimelineController.addChange(actor, at, path, state);
                        }
                    }
                    if (ImGui.menuItem("Original skin##add_orig")) {
                        action = () -> SkinTimelineController.addChange(actor, at, "", state);
                    }
                    ImGui.separator();
                    if (ImGui.menuItem("Add skin file...##add_file")) {
                        messageActor = actor;
                        SkinTimelineUI.openAddFileDialog(actor);
                    }
                    if (ImGui.menuItem("Add whole folder...##add_folder")) {
                        messageActor = actor;
                        SkinTimelineUI.openAddFolderDialog(actor);
                    }
                }
            } finally {
                ImGui.endPopup();
            }
        }

        if (ImGui.beginPopup(POPUP_KEY)) {
            try {
                ActorTimeline timeline = popupActor == null ? null : SkinTimelineController.getTimeline(popupActor);
                if (timeline == null || popupIndex < 0 || popupIndex >= timeline.changes.size()) {
                    ImGui.closeCurrentPopup();
                } else {
                    UUID actor = popupActor;
                    int index = popupIndex;
                    SkinChange change = timeline.changes.get(index);
                    ImGui.textDisabled(displayName(actor, timeline) + ": " + SkinTimelineController.describe(timeline, change.skin)
                            + " at " + SkinTimelineUI.formatTick(change.tick));
                    ImGui.separator();
                    if (ImGui.menuItem("Jump here")) {
                        jumpTo(server, change.tick);
                    }
                    if (ImGui.menuItem("Move to playhead (" + SkinTimelineUI.formatTick(tick) + ")")) {
                        action = () -> SkinTimelineController.moveChange(actor, index, tick);
                    }
                    if (ImGui.beginMenu("Switch to")) {
                        for (int i = 0; i < timeline.expressions.size(); i++) {
                            Expression expression = timeline.expressions.get(i);
                            boolean usable = SkinTimelineController.fileProblem(expression.path) == null;
                            boolean current = expression.path.equals(change.skin);
                            if (ImGui.menuItem(expression.name + "##sw" + i, "", current, usable)) {
                                String path = expression.path;
                                action = () -> SkinTimelineController.setChangeSkin(actor, index, path);
                            }
                        }
                        if (ImGui.menuItem("Original skin##sw_orig", "", change.skin == null)) {
                            action = () -> SkinTimelineController.setChangeSkin(actor, index, null);
                        }
                        ImGui.endMenu();
                    }
                    if (ImGui.menuItem("Add another change at playhead (" + SkinTimelineUI.formatTick(tick) + ")...")) {
                        popupTick = tick;
                        requestPopup = POPUP_ADD;
                    }
                    ImGui.separator();
                    if (ImGui.menuItem("Delete this change")) {
                        action = () -> SkinTimelineController.removeChange(actor, index);
                    }
                }
            } finally {
                ImGui.endPopup();
            }
        }

        if (ImGui.beginPopup(POPUP_ACTOR)) {
            try {
                ActorTimeline timeline = popupActor == null ? null : SkinTimelineController.getTimeline(popupActor);
                if (timeline == null) {
                    ImGui.closeCurrentPopup();
                } else {
                    UUID actor = popupActor;
                    ImGui.textDisabled(displayName(actor, timeline) + "  (" + timeline.expressions.size() + " skins, "
                            + timeline.changes.size() + " changes)");
                    ImGui.separator();
                    if (ImGui.menuItem("Add expression at playhead (" + SkinTimelineUI.formatTick(tick) + ")...")) {
                        popupTick = tick;
                        requestPopup = POPUP_ADD;
                    }
                    if (ImGui.menuItem("Add skin file...")) {
                        messageActor = actor;
                        SkinTimelineUI.openAddFileDialog(actor);
                    }
                    if (ImGui.menuItem("Add whole folder...")) {
                        messageActor = actor;
                        SkinTimelineUI.openAddFolderDialog(actor);
                    }
                    if (!timeline.expressions.isEmpty() && ImGui.beginMenu("Remove a skin from the list")) {
                        for (int i = 0; i < timeline.expressions.size(); i++) {
                            final int index = i;
                            if (ImGui.menuItem(timeline.expressions.get(i).name + "##rmexp" + i)) {
                                action = () -> SkinTimelineController.removeExpression(actor, index);
                            }
                        }
                        ImGui.endMenu();
                    }
                    if (ImGui.menuItem("Reload skin files")) {
                        action = SkinTimelineController::reloadSkins;
                    }
                    ImGui.separator();
                    if (ImGui.beginMenu("Delete all skin changes", !timeline.changes.isEmpty())) {
                        if (ImGui.menuItem("Yes, delete all " + timeline.changes.size() + " changes")) {
                            action = () -> SkinTimelineController.clearChanges(actor);
                        }
                        ImGui.endMenu();
                    }
                    if (ImGui.beginMenu("Remove actor from Skin Timeline")) {
                        if (ImGui.menuItem("Yes, remove " + displayName(actor, timeline) + " (skins list + changes)")) {
                            action = () -> SkinTimelineController.removeActor(actor);
                        }
                        ImGui.endMenu();
                    }
                }
            } finally {
                ImGui.endPopup();
            }
        }

        if (ImGui.beginPopup(POPUP_ADD_ACTOR)) {
            try {
                ImGui.textDisabled("Add an actor to the Skin Timeline");
                ImGui.separator();
                int shown = 0;
                Map<UUID, String> candidates = new LinkedHashMap<>();
                for (Map.Entry<UUID, String> entry : fbActors.entrySet()) {
                    candidates.put(entry.getKey(), entry.getValue() + "  (Flashback Actor)");
                }
                for (Map.Entry<UUID, String> entry : onlineNames.entrySet()) {
                    candidates.putIfAbsent(entry.getKey(), entry.getValue() + (entry.getKey().version() == 2 ? "  (NPC)" : ""));
                }
                for (Map.Entry<UUID, String> entry : candidates.entrySet()) {
                    if (SkinTimelineController.getTimeline(entry.getKey()) != null) {
                        continue;
                    }
                    shown++;
                    String label = entry.getValue();
                    if (ImGui.menuItem(label + "##addactor" + entry.getKey())) {
                        UUID uuid = entry.getKey();
                        String name = fbActors.containsKey(uuid) ? fbActors.get(uuid) : onlineNames.get(uuid);
                        action = () -> SkinTimelineController.addActor(uuid, name);
                    }
                }
                if (shown == 0) {
                    ImGui.textDisabled("No other players or actors in this replay right now.");
                }
            } finally {
                ImGui.endPopup();
            }
        }

        if (action != null) {
            action.run();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private static int markerAt(View view, ActorTimeline timeline, float mouseX) {
        int best = -1;
        float bestDistance = HANDLE_GRAB_PX;
        for (int i = 0; i < timeline.changes.size(); i++) {
            float distance = Math.abs(view.xFor(timeline.changes.get(i).tick) - mouseX);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static String displayName(UUID actor, ActorTimeline timeline) {
        String online = fbActors.containsKey(actor) ? fbActors.get(actor) : onlineNames.get(actor);
        if (online != null) {
            if (timeline != null && !online.equals(timeline.name)) {
                SkinTimelineController.rememberName(actor, online);
            }
            return online;
        }
        if (timeline != null && timeline.name != null && !timeline.name.isBlank()) {
            return timeline.name;
        }
        return "Actor " + actor.toString().substring(0, 8);
    }

    private static void refreshOnlineNames(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - namesUpdatedAt < 2000) {
            return;
        }
        namesUpdatedAt = now;
        onlineNames.clear();
        fbActors.clear();
        fbActors.putAll(ActorsCompat.actors());
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            return;
        }
        UUID self = minecraft.player != null ? minecraft.player.getUUID() : null;
        List<Map.Entry<UUID, String>> sorted = new ArrayList<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            UUID id = info.getProfile().id();
            if (id == null || id.equals(self)) {
                continue;
            }
            sorted.add(Map.entry(id, info.getProfile().name()));
        }
        sorted.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        for (Map.Entry<UUID, String> entry : sorted) {
            onlineNames.put(entry.getKey(), entry.getValue());
        }
    }

    private static final Map<String, Integer> colourCache = new HashMap<>();

    /** A stable, readable pastel colour per expression name. */
    private static int colourFor(String name) {
        return colourCache.computeIfAbsent(name, key -> {
            float hue = ((key.toLowerCase().hashCode() * 0x9E3779B9) >>> 8) % 360 / 360f;
            float[] rgb = hsvToRgb(hue, 0.45f, 0.92f);
            return rgba(Math.round(rgb[0] * 255), Math.round(rgb[1] * 255), Math.round(rgb[2] * 255), 255);
        });
    }

    private static float[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float hp = (h * 6f) % 6f;
        float x = c * (1 - Math.abs(hp % 2f - 1));
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else { r = c; b = x; }
        float m = v - c;
        return new float[]{r + m, g + m, b + m};
    }

    private static int rgba(int r, int g, int b, int a) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static String formatShort(int tick) {
        int seconds = tick / 20;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /** Maps replay ticks to screen x positions for the visible part of the timeline. */
    static final class View {
        final float start;
        final float end;
        final int total;
        float laneX;
        float laneW;

        View(float start, float end, int total) {
            this.start = start;
            this.end = Math.max(start + 1, end);
            this.total = total;
        }

        float xFor(float tick) {
            return laneX + (tick - start) / (end - start) * laneW;
        }

        int tickAt(float x) {
            float t = start + (x - laneX) / laneW * (end - start);
            return Math.max(0, Math.min(total, Math.round(t)));
        }
    }
}
