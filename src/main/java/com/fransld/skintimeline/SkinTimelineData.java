package com.fransld.skintimeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything saved for one replay. Stored as JSON in .minecraft/flashback/skin_timelines/<replay id>.json
 */
public class SkinTimelineData {

    public int version = 1;

    /** Key: actor (player) UUID as a string. */
    public Map<String, ActorTimeline> actors = new LinkedHashMap<>();

    public static class ActorTimeline {
        /** Display name shown in the Skin Timeline window. */
        public String name;
        /** The skins you prepared for this actor (happy, sad, angry...). Clicking one adds a change. */
        public List<Expression> expressions = new ArrayList<>();
        /** Skin changes, sorted by tick. Before the first change the actor uses their original skin. */
        public List<SkinChange> changes = new ArrayList<>();

        public ActorTimeline() {
        }
    }

    public static class Expression {
        public String name;
        public String path;

        public Expression() {
        }

        public Expression(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    public static class SkinChange {
        public int tick;
        /** Absolute path to a PNG skin, or null for "original skin". */
        public String skin;

        public SkinChange() {
        }

        public SkinChange(int tick, String skin) {
            this.tick = tick;
            this.skin = skin;
        }
    }
}
