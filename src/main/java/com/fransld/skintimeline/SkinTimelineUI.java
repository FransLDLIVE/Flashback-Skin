package com.fransld.skintimeline;

import com.fransld.skintimeline.SkinTimelineData.ActorTimeline;
import com.fransld.skintimeline.SkinTimelineData.Expression;
import com.fransld.skintimeline.SkinTimelineData.SkinChange;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.utils.AsyncFileDialogs;
import imgui.moulberry90.ImGui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;

import java.nio.file.Path;
import java.util.UUID;

/**
 * The "Skin Timeline" section added to the bottom of Flashback's right-click (selected entity) popup.
 */
public final class SkinTimelineUI {

    private static final int BUTTONS_PER_ROW = 4;

    private static UUID confirmClearFor = null;
    private static String lastDialogFolder = null;

    private SkinTimelineUI() {
    }

    public static void render(Entity entity, EditorState editorState) {
        if (!(entity instanceof AbstractClientPlayer)) {
            return;
        }
        ReplayServer server = Flashback.getReplayServer();
        if (server == null || !SkinTimelineController.isLoaded()) {
            return;
        }

        UUID actor = entity.getUUID();
        int tick = server.getReplayTick();
        ActorTimeline timeline = SkinTimelineController.getTimeline(actor);
        boolean hasChanges = timeline != null && !timeline.changes.isEmpty();

        ImGui.pushID("flashback_skin_timeline");
        try {
            ImGuiHelper.separatorWithText("Skin Timeline (expressions)");

            String showing = hasChanges
                    ? SkinTimelineController.describe(timeline, SkinTimelineController.activeSkin(timeline, tick))
                    : "not using the timeline";
            ImGui.textDisabled("At " + formatTick(tick) + "  |  Showing: " + showing);

            // ---- Expression buttons ----
            Runnable action = null;
            if (timeline == null || timeline.expressions.isEmpty()) {
                ImGui.textWrapped("Add this actor's expression skins (happy, sad, angry...). Then move the replay "
                        + "to a moment and click an expression to switch to it from that moment on.");
            } else {
                for (int i = 0; i < timeline.expressions.size(); i++) {
                    Expression expression = timeline.expressions.get(i);
                    String problem = SkinTimelineController.fileProblem(expression.path);
                    if (problem != null) {
                        ImGui.beginDisabled();
                    }
                    if (ImGui.button(expression.name + "##expr" + i)) {
                        String path = expression.path;
                        action = () -> SkinTimelineController.addChange(actor, tick, path, editorState);
                    }
                    if (problem != null) {
                        ImGui.endDisabled();
                    } else if (ImGui.isItemHovered()) {
                        ImGui.setTooltip("Switch to \"" + expression.name + "\" at " + formatTick(tick));
                    }
                    if ((i + 1) % BUTTONS_PER_ROW != 0) {
                        ImGui.sameLine();
                    }
                }
            }
            if (ImGui.button("Original skin##orig")) {
                action = () -> SkinTimelineController.addChange(actor, tick, null, editorState);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Switch back to the actor's normal skin at " + formatTick(tick));
            }

            if (ImGui.button("+ Add skin file...")) {
                openAddFileDialog(actor);
            }
            ImGui.sameLine();
            if (ImGui.button("+ Add whole folder...")) {
                openAddFolderDialog(actor);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Adds every .png in a folder, named after the file (e.g. happy.png -> Happy)");
            }
            ImGui.sameLine();
            if (ImGui.button("Open Skin Timeline window")) {
                if (timeline == null) {
                    SkinTimelineController.addActor(actor, null);
                }
                SkinTimelineWindow.open();
                ImGui.closeCurrentPopup();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("See and edit every actor's skin changes on a timeline");
            }

            String message = SkinTimelineController.getMessage(actor);
            if (message != null) {
                ImGui.textColored(1.0f, 0.8f, 0.3f, 1.0f, message);
                ImGui.sameLine();
                if (ImGui.smallButton("OK##msg")) {
                    SkinTimelineController.clearMessage(actor);
                }
            }

            // ---- Change list ----
            if (hasChanges) {
                ImGui.textDisabled("While this actor has skin changes, this timeline controls their skin.");
                int activeIndex = SkinTimelineController.activeIndex(timeline, tick);

                if (ImGui.treeNode("changes", "Skin changes (" + timeline.changes.size() + ")")) {
                    // "Previous" = the change before the playhead (or before the one we're standing on)
                    int previousIndex = activeIndex >= 0 && timeline.changes.get(activeIndex).tick == tick ? activeIndex - 1 : activeIndex;
                    boolean hasPrevious = previousIndex >= 0;
                    if (hasPrevious && ImGui.smallButton("< Previous change")) {
                        SkinTimelineWindow.jumpTo(server, timeline.changes.get(previousIndex).tick);
                    }
                    if (activeIndex + 1 < timeline.changes.size()) {
                        if (hasPrevious) {
                            ImGui.sameLine();
                        }
                        if (ImGui.smallButton("Next change >")) {
                            SkinTimelineWindow.jumpTo(server, timeline.changes.get(activeIndex + 1).tick);
                        }
                    }

                    for (int i = 0; i < timeline.changes.size(); i++) {
                        SkinChange change = timeline.changes.get(i);
                        String label = formatTick(change.tick) + "   " + SkinTimelineController.describe(timeline, change.skin);
                        String problem = SkinTimelineController.fileProblem(change.skin);
                        if (problem != null) {
                            ImGui.textColored(1.0f, 0.4f, 0.4f, 1.0f, label + "  (" + problem + ")");
                        } else if (i == activeIndex) {
                            ImGui.textColored(0.4f, 1.0f, 0.5f, 1.0f, "> " + label);
                        } else {
                            ImGui.text("  " + label);
                        }

                        final int index = i;
                        ImGui.sameLine();
                        if (ImGui.smallButton("Go##go" + i)) {
                            SkinTimelineWindow.jumpTo(server, change.tick);
                        }
                        if (ImGui.isItemHovered()) {
                            ImGui.setTooltip("Jump the replay to this change");
                        }
                        ImGui.sameLine();
                        if (ImGui.smallButton("Move here##mv" + i)) {
                            action = () -> SkinTimelineController.moveChange(actor, index, tick);
                        }
                        if (ImGui.isItemHovered()) {
                            ImGui.setTooltip("Move this change to the current time (" + formatTick(tick) + ")");
                        }
                        ImGui.sameLine();
                        if (ImGui.smallButton("X##rm" + i)) {
                            action = () -> SkinTimelineController.removeChange(actor, index);
                        }
                        if (ImGui.isItemHovered()) {
                            ImGui.setTooltip("Delete this change");
                        }
                    }

                    if (actor.equals(confirmClearFor)) {
                        ImGui.textColored(1.0f, 0.5f, 0.4f, 1.0f, "Delete all skin changes for this actor?");
                        if (ImGui.smallButton("Yes, delete all##clearyes")) {
                            action = () -> SkinTimelineController.clearChanges(actor);
                            confirmClearFor = null;
                        }
                        ImGui.sameLine();
                        if (ImGui.smallButton("Cancel##clearno")) {
                            confirmClearFor = null;
                        }
                    } else if (ImGui.smallButton("Delete all changes##clear")) {
                        confirmClearFor = actor;
                    }
                    ImGui.treePop();
                }
            }

            // ---- Manage the expression list ----
            if (timeline != null && !timeline.expressions.isEmpty()
                    && ImGui.treeNode("manage", "Manage expression skins (" + timeline.expressions.size() + ")")) {
                for (int i = 0; i < timeline.expressions.size(); i++) {
                    Expression expression = timeline.expressions.get(i);
                    final int index = i;
                    if (ImGui.smallButton("^##up" + i)) {
                        action = () -> SkinTimelineController.moveExpression(actor, index, -1);
                    }
                    ImGui.sameLine();
                    if (ImGui.smallButton("v##down" + i)) {
                        action = () -> SkinTimelineController.moveExpression(actor, index, 1);
                    }
                    ImGui.sameLine();
                    if (ImGui.smallButton("Remove##exrm" + i)) {
                        action = () -> SkinTimelineController.removeExpression(actor, index);
                    }
                    ImGui.sameLine();
                    String problem = SkinTimelineController.fileProblem(expression.path);
                    if (problem != null) {
                        ImGui.textColored(1.0f, 0.4f, 0.4f, 1.0f, expression.name + "  (" + problem + ")");
                    } else {
                        ImGui.text(expression.name);
                    }
                    if (ImGui.isItemHovered()) {
                        ImGui.setTooltip(expression.path);
                    }
                }
                if (ImGui.smallButton("Reload skin files##reload")) {
                    action = SkinTimelineController::reloadSkins;
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Use this after editing a PNG so the new version shows up");
                }
                ImGui.treePop();
            }

            if (action != null) {
                action.run();
            }
        } finally {
            ImGui.popID();
        }
    }

    /** 1234 ticks -> "1:01.70" (ticks are 1/20 s). */
    public static String formatTick(int tick) {
        int totalHundredths = tick * 5;
        int minutes = totalHundredths / 6000;
        int seconds = (totalHundredths / 100) % 60;
        int hundredths = totalHundredths % 100;
        return String.format("%d:%02d.%02d", minutes, seconds, hundredths);
    }

    public static void openAddFileDialog(UUID actor) {
        AsyncFileDialogs.openFileDialog(dialogFolder(), "Skin Texture", "png").thenAccept(path -> {
            if (path != null) {
                rememberFolder(path, false);
                SkinTimelineController.runOnClientThread(() -> SkinTimelineController.addExpressionFile(actor, path));
            }
        });
    }

    public static void openAddFolderDialog(UUID actor) {
        AsyncFileDialogs.openFolderDialog(dialogFolder()).thenAccept(path -> {
            if (path != null) {
                rememberFolder(path, true);
                SkinTimelineController.runOnClientThread(() -> SkinTimelineController.addExpressionFolder(actor, path));
            }
        });
    }

    private static String dialogFolder() {
        if (lastDialogFolder != null) {
            return lastDialogFolder;
        }
        return FabricLoader.getInstance().getGameDir().toString();
    }

    private static void rememberFolder(String path, boolean isFolder) {
        try {
            Path p = Path.of(path);
            Path folder = isFolder ? p : p.getParent();
            if (folder != null) {
                lastDialogFolder = folder.toString();
            }
        } catch (Exception ignored) {
        }
    }
}
