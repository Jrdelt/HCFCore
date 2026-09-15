package me.vertex.core.enchant.binds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One player's whole {@code /binds} state: the live 1-7 layout, every saved
 * preset, and which preset (if any) the live layout currently mirrors.
 * Every mutation -- GUI clicks, preset load, legacy/malformed data load at
 * startup -- goes through {@link #setSlot}/{@link #loadBind}, the single
 * choke points that enforce the 3-rune-per-bind cap, so the cap can never
 * be bypassed by any one entry point.
 */
public final class PlayerBinds {
    public static final int MAX_BIND_INDEX = 7;
    public static final int MAX_SLOTS_PER_BIND = 3;
    public static final int HARD_MAX_PRESETS = 7;

    private final Map<Integer, List<String>> binds = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, List<String>>> presets = new LinkedHashMap<>();
    private Integer activePresetIndex;
    private boolean dirty;

    public List<String> bind(int bindIndex) {
        return List.copyOf(binds.getOrDefault(bindIndex, List.of()));
    }

    public Map<Integer, List<String>> binds() {
        return Map.copyOf(binds);
    }

    public Integer activePresetIndex() {
        return activePresetIndex;
    }

    public boolean dirty() {
        return dirty;
    }

    public Map<Integer, List<String>> preset(int presetIndex) {
        return Map.copyOf(presets.getOrDefault(presetIndex, Map.of()));
    }

    public java.util.Set<Integer> presetIndexes() {
        return java.util.Set.copyOf(presets.keySet());
    }

    /** Overwrites one bind's whole slot list from persisted/legacy data, trimming and logging any overflow past {@link #MAX_SLOTS_PER_BIND}. */
    public void loadBind(int bindIndex, List<String> runeIds, Logger logger) {
        if (bindIndex < 1 || bindIndex > MAX_BIND_INDEX) {
            return;
        }
        List<String> trimmed = trim(runeIds, logger, "bind " + bindIndex);
        if (trimmed.isEmpty()) {
            binds.remove(bindIndex);
        } else {
            binds.put(bindIndex, trimmed);
        }
    }

    public void loadPreset(int presetIndex, Map<Integer, List<String>> raw, Logger logger) {
        if (presetIndex < 1 || presetIndex > HARD_MAX_PRESETS) {
            return;
        }
        Map<Integer, List<String>> loaded = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String>> entry : raw.entrySet()) {
            if (entry.getKey() < 1 || entry.getKey() > MAX_BIND_INDEX) {
                continue;
            }
            List<String> trimmed = trim(entry.getValue(), logger, "preset " + presetIndex + " bind " + entry.getKey());
            if (!trimmed.isEmpty()) {
                loaded.put(entry.getKey(), trimmed);
            }
        }
        presets.put(presetIndex, loaded);
    }

    public void loadActivePreset(Integer presetIndex, boolean dirty) {
        this.activePresetIndex = presetIndex;
        this.dirty = dirty;
    }

    /** @return false when {@code bindIndex}/{@code slotIndex} are out of range or the bind is already full. */
    public boolean setSlot(int bindIndex, int slotIndex, String runeId) {
        if (bindIndex < 1 || bindIndex > MAX_BIND_INDEX || slotIndex < 0 || slotIndex >= MAX_SLOTS_PER_BIND || runeId == null) {
            return false;
        }
        List<String> current = new ArrayList<>(binds.getOrDefault(bindIndex, List.of()));
        if (slotIndex < current.size()) {
            current.set(slotIndex, runeId);
        } else if (slotIndex == current.size()) {
            current.add(runeId);
        } else {
            // Would leave a gap before this slot -- reject rather than pad with nulls.
            return false;
        }
        binds.put(bindIndex, current);
        markDirty();
        return true;
    }

    public void removeSlot(int bindIndex, int slotIndex) {
        List<String> current = binds.get(bindIndex);
        if (current == null || slotIndex < 0 || slotIndex >= current.size()) {
            return;
        }
        List<String> next = new ArrayList<>(current);
        next.remove(slotIndex);
        if (next.isEmpty()) {
            binds.remove(bindIndex);
        } else {
            binds.put(bindIndex, next);
        }
        markDirty();
    }

    /** Swaps a slot with its neighbor -- {@code direction} -1 = earlier, +1 = later. Does nothing at either edge. */
    public void reorder(int bindIndex, int slotIndex, int direction) {
        List<String> current = binds.get(bindIndex);
        int target = slotIndex + direction;
        if (current == null || slotIndex < 0 || slotIndex >= current.size() || target < 0 || target >= current.size()) {
            return;
        }
        List<String> next = new ArrayList<>(current);
        String moved = next.remove(slotIndex);
        next.add(target, moved);
        binds.put(bindIndex, next);
        markDirty();
    }

    public void loadPresetIntoActive(int presetIndex) {
        binds.clear();
        for (Map.Entry<Integer, List<String>> entry : preset(presetIndex).entrySet()) {
            binds.put(entry.getKey(), entry.getValue());
        }
        activePresetIndex = presetIndex;
        dirty = false;
    }

    public void updateActivePresetFromLive() {
        if (activePresetIndex == null) {
            return;
        }
        presets.put(activePresetIndex, Map.copyOf(binds));
        dirty = false;
    }

    public void saveAsNewPreset(int presetIndex) {
        presets.put(presetIndex, Map.copyOf(binds));
        activePresetIndex = presetIndex;
        dirty = false;
    }

    public void deletePreset(int presetIndex) {
        presets.remove(presetIndex);
        if (activePresetIndex != null && activePresetIndex == presetIndex) {
            activePresetIndex = null;
            dirty = !binds.isEmpty();
        }
    }

    /** Trims every preset above {@code maxPresetIndex} -- used when a rank downgrade lowers the player's entitlement. */
    public List<Integer> presetsAbove(int maxPresetIndex) {
        return presets.keySet().stream().filter(index -> index > maxPresetIndex).sorted().toList();
    }

    /** Removes {@code runeId} from every bind slot and every saved preset -- called when a rune stops being bindable or is removed entirely. */
    public boolean pruneRune(String runeId) {
        boolean changed = false;
        for (Integer bindIndex : List.copyOf(binds.keySet())) {
            List<String> current = binds.get(bindIndex);
            if (current.contains(runeId)) {
                List<String> next = new ArrayList<>(current);
                next.removeIf(runeId::equals);
                if (next.isEmpty()) {
                    binds.remove(bindIndex);
                } else {
                    binds.put(bindIndex, next);
                }
                changed = true;
            }
        }
        for (Integer presetIndex : List.copyOf(presets.keySet())) {
            Map<Integer, List<String>> preset = presets.get(presetIndex);
            Map<Integer, List<String>> next = new LinkedHashMap<>();
            boolean presetChanged = false;
            for (Map.Entry<Integer, List<String>> entry : preset.entrySet()) {
                if (entry.getValue().contains(runeId)) {
                    List<String> trimmed = new ArrayList<>(entry.getValue());
                    trimmed.removeIf(runeId::equals);
                    presetChanged = true;
                    if (!trimmed.isEmpty()) {
                        next.put(entry.getKey(), trimmed);
                    }
                } else {
                    next.put(entry.getKey(), entry.getValue());
                }
            }
            if (presetChanged) {
                presets.put(presetIndex, next);
                changed = true;
            }
        }
        if (changed) {
            markDirty();
        }
        return changed;
    }

    private void markDirty() {
        if (activePresetIndex != null) {
            dirty = !preset(activePresetIndex).equals(Map.copyOf(binds));
        }
    }

    private static List<String> trim(List<String> runeIds, Logger logger, String where) {
        if (runeIds.size() <= MAX_SLOTS_PER_BIND) {
            return List.copyOf(runeIds);
        }
        if (logger != null) {
            logger.log(Level.WARNING, "Trimming " + where + " from " + runeIds.size()
                    + " runes to the maximum of " + MAX_SLOTS_PER_BIND + ".");
        }
        return List.copyOf(runeIds.subList(0, MAX_SLOTS_PER_BIND));
    }
}
