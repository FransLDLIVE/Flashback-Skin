# Flashback Skin Timeline

An add-on for **Flashback 0.43.x on Minecraft 26.3 (Fabric)**. It lets an actor switch skins in the middle of a scene, so you can give them different expressions (happy, sad, angry, shocked...) instead of using one skin for the whole video. Skin changes play back in the editor and appear in exported videos. HD skins (128×128, 256×256) work.

## Easiest: double-click build.bat (Windows)

1. Unzip this folder somewhere, e.g. your Desktop.
2. Double-click **build.bat**. If Windows shows "Windows protected your PC", click **More info**, then **Run anyway**.
3. Wait a few minutes. It downloads a temporary Java 25 and the Minecraft 26.3 files, builds the add-on, then deletes the temporary Java.
4. When it says **DONE!**, `flashback-skin-timeline-1.1.0+mc26.3.jar` is in this folder. Put it in your mods folder next to Flashback.

Nothing is installed on your PC. If it says **BUILD FAILED**, take a screenshot of the window.

## Build it on GitHub (nothing to install)

1. On github.com, click **+** (top right), then **New repository**. Name it `flashback-skin-timeline`, choose **Private**, and click **Create repository**.
2. On the empty repo page, click **uploading an existing file**. Open the unzipped `flashback-skin-timeline` folder, select **everything inside it** (including the `.github` folder), and drag it all onto the page. Click **Commit changes**.
3. Click the **Actions** tab. A **Build** run starts automatically and takes about 3–6 minutes. If it asks you to enable workflows, click the green button, then **Build**, then **Run workflow**.
4. When the run shows a green check, click it. Under **Artifacts**, download **flashback-skin-timeline**. It's a zip with the add-on jar inside.
5. Put the jar in your `mods` folder, next to Flashback.

If the `.github` folder didn't upload (Windows can hide it): in the repo, click **Add file**, then **Create new file**. Type `.github/workflows/build.yml` as the name, paste in the contents of that file from the zip, and commit.

You don't need to put the Flashback jar in `libs` for the GitHub build. The build script downloads Flashback 0.43.6 from Modrinth itself.

## Build it on GitHub from your phone

Use github.com in your phone's browser, not the GitHub app (the app can't upload files). If GitHub's page looks cut down, turn on **Desktop site** in the browser menu.

1. Create a new **Private** repository and tick **Add a README file**.
2. Click **Add file**, then **Upload files**. Pick `flashback-skin-timeline.zip` itself (**don't unzip it**) and click **Commit changes**.
3. Click **Add file**, then **Create new file**. For the name, type `.github/workflows/build.yml` (the slashes create the folders). Paste in the contents of `build.yml` and click **Commit changes**.
4. Open the **Actions** tab, wait for the green check, and download **flashback-skin-timeline** under **Artifacts**.
5. Move the jar to your PC's `mods` folder (via Google Drive, Discord, USB...). Flashback and this add-on only run on Minecraft Java on a PC or Mac.

## Or build it on your own PC

You need **Java 25 (JDK)** installed, for example from adoptium.net (Temurin 25). Minecraft 26.x and Flashback 0.43 are built with Java 25.

1. Unzip this folder somewhere, e.g. `Desktop\flashback-skin-timeline`.
2. Copy your **Flashback jar** (`Flashback-0.43.6-for-MC26.3.jar`) into the `libs` folder.
3. Open a terminal in the project folder (in File Explorer, click the address bar, type `cmd`, press Enter) and run:
   - Windows: `gradlew build`
   - Mac/Linux: `./gradlew build`

   The first build downloads Gradle, Minecraft and Fabric, so it takes a few minutes.
4. The add-on is at `build\libs\flashback-skin-timeline-1.1.0+mc26.3.jar`. Ignore any `-sources` jar.
5. Put that jar in your Fabric `mods` folder, next to Flashback. In the Modrinth app: your 26.3 profile → ⋯ → Open folder → `mods`.

## Use it

1. Open a replay in Flashback.
2. **Right-click an actor** (a player). Scroll to the bottom of the menu: there's a new **Skin Timeline (expressions)** section.
3. Click **+ Add whole folder...** and pick a folder holding that actor's expression skins (`happy.png`, `sad.png`, `angry.png`...). Each file becomes a button named after it. You can also add skins one at a time with **+ Add skin file...**.
4. Move the replay to the moment the expression should change, right-click the actor, and click an expression button. From that moment on, the actor wears that skin.
5. Repeat for each expression change. **Original skin** switches back to their normal skin.

Under **Skin changes** you'll see every switch, with buttons for each one:
- **Go** jumps the replay to that change.
- **Move here** moves the change to the current time (for fine-tuning timing).
- **X** deletes the change.
- **< Previous change / Next change >** steps through the changes.

Tips:
- Make an expression skin by copying the actor's skin and editing only the face. The switch is instant, so it looks like the face changed.
- Each actor has their own expression list and timeline.
- If you edit a PNG while the replay is open, click **Manage expression skins → Reload skin files**.
- Changes happen on whole ticks (1/20 s). For frame-perfect timing, move to the exact tick and use **Move here**.

## The Skin Timeline window

Click **Skin Timeline** in Flashback's top menu bar (next to Player List and Movement). You can also click **Open Skin Timeline window** in an actor's right-click menu.

- **One row per actor.** Coloured blocks show which skin is active when; grey means their original skin. Add actors with **+ Add actor**, or by clicking the window button in an actor's right-click menu.
- **Click an empty spot in a row** to add an expression at that time. The popup also offers **Use playhead time instead**.
- **Drag a white marker** to move a change. **Click a marker** to jump the replay to it.
- **Right-click a marker or block** to switch it to another expression, move it to the playhead, or delete it.
- **Click an actor's name** to add skin files or folders, remove skins from their list, clear all changes, or remove them from the window.
- **Click or drag the time ruler** at the top to move the replay.
- The window follows the zoom of Flashback's own timeline, so zoom there to work on a short moment. You can dock the window under Flashback's timeline like any other Flashback window.

## Simple Mode

A tidy panel with big buttons for the everyday steps. It opens by itself the first time; reopen it from the top menu bar (**Simple Mode**).

1. **Find the moment:** Play/Pause, jump to Start/End, step 1 second or 1 tick.
2. **Camera:** fly the camera into place and click **Add camera keyframe here**. Add more at other moments and the camera glides between them. Undo/Redo are there too.
3. **What part to export:** **Start here** / **End here** set where the video begins and ends.
4. **Export:** opens Flashback's export window, or takes a screenshot.
5. **Characters:** opens the Skin Timeline and the Player List.

The keyframe, play and export-range buttons press Flashback's own shortcut for you, so they behave exactly like the keys. They need Flashback's Timeline window to be open; if it isn't, Simple Mode tells you.

## Works with Flashback Actors

Virtual actors from the Flashback Actors mod work like players: right-click one and use the Skin Timeline section, or pick them in the Skin Timeline window's **+ Add actor** list (marked "Flashback Actor"). Between skin changes they keep the skin you gave them in Flashback Actors. Flashback Actors is optional; the add-on works without it.

## Good to know

- Skins must be the modern square layout: **64×64, 128×128, 256×256...** The old 64×32 shape is rejected, with a message.
- **Original skin** means the skin the actor has without the timeline: their recorded skin, a skin you set with Flashback's "Load skin from file", or their Flashback Actors skin. If you set a new skin on an actor while the timeline is switching them, that becomes their new original.
- Flashback's saved replay edits never contain timeline skins: the add-on swaps the original skins back in whenever Flashback saves. Removing the add-on later never leaves an expression stuck on someone.
- Saved per replay in `.minecraft\flashback\skin_timelines\<replay id>.json`. The skin PNGs are referenced by path, so keep them where they are, or re-add them after moving them.
- Applies to whole replays, not per Flashback scene.
- Client-only add-on. It doesn't change Flashback's files and doesn't bundle any Flashback code.

## If something goes wrong

- **Build says "Put your Flashback jar ... in the libs/ folder"**: step 2 was skipped.
- **Build error about Java version**: install JDK 25, and make sure `java -version` in the terminal shows 25.
- **Game won't start / mixin error mentioning `SelectedEntityPopup` or `FilePlayerSkin`**: your Flashback version changed those parts. Send the crash log (`.minecraft\logs\latest.log`) and the new Flashback jar.
