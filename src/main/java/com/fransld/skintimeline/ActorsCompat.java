package com.fransld.skintimeline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optional support for the "Flashback Actors" mod (pdx.dev.fbactors). Reads its list of virtual actors by
 * reflection, so this add-on still works when Flashback Actors isn't installed.
 *
 * Flashback Actors spawns each actor as a player whose UUID is the take id, and stores the actor's skin in
 * Flashback's skinOverrideFromFile under that same id, so the Skin Timeline drives actors like any player.
 */
public final class ActorsCompat {

    private static boolean unavailable = false;
    private static Object manager;
    private static Method actorsMethod;

    private ActorsCompat() {
    }

    /** Actor id -> display name, or an empty map if Flashback Actors isn't installed. */
    public static Map<UUID, String> actors() {
        Map<UUID, String> result = new LinkedHashMap<>();
        if (unavailable) {
            return result;
        }
        try {
            if (manager == null) {
                Class<?> main = Class.forName("pdx.dev.fbactors.FlashbackActors");
                manager = main.getField("ACTORS").get(null);
                actorsMethod = manager.getClass().getMethod("actors");
            }
            List<?> list = new ArrayList<>((List<?>) actorsMethod.invoke(manager));
            for (Object actor : list) {
                if (actor == null) {
                    continue;
                }
                Field takeField = actor.getClass().getField("take");
                Object take = takeField.get(actor);
                if (take == null) {
                    continue;
                }
                Object id = take.getClass().getField("id").get(take);
                if (!(id instanceof UUID uuid)) {
                    continue;
                }
                String name = null;
                try {
                    Object value = actor.getClass().getMethod("displayName").invoke(actor);
                    name = value instanceof String s ? s : null;
                } catch (ReflectiveOperationException ignored) {
                }
                result.put(uuid, name == null || name.isBlank() ? "Actor" : name);
            }
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException e) {
            unavailable = true; // not installed, or a version we don't understand
        } catch (Throwable t) {
            // e.g. the list changed while we read it; try again next refresh
        }
        return result;
    }
}
