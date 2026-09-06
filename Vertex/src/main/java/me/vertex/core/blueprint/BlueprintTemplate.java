package me.vertex.core.blueprint;

/** One entry from blueprints.yml's `templates:` section. */
// If you defined it like this:
public record BlueprintTemplate(
                String name,
                String schematicFile, // <-- Here
                String displayName,
                Integer buildTimeSeconds,
                Integer maxBlocksPerTick) {
}
