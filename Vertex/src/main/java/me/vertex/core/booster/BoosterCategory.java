package me.vertex.core.booster;

import java.util.Locale;

/**
 * The kinds of bonus Vertex stacks. A source contributes to one or more of
 * these; everything a player can see in {@code /boosters} is grouped by them.
 */
public enum BoosterCategory {
    ORE_DROP,
    SELL,
    BUY_DISCOUNT,
    MOB_SPAWN_RATE,
    MOB_DROP,
    EXP;

    /** Key under {@code categories:} in boosters.yml, and the lang key suffix. */
    public String configKey() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static BoosterCategory fromConfigKey(String value) {
        if (value == null) {
            return null;
        }
        for (BoosterCategory category : values()) {
            if (category.configKey().equalsIgnoreCase(value)) {
                return category;
            }
        }
        return null;
    }
}
