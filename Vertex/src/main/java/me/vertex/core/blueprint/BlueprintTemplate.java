package me.vertex.core.blueprint;

/** One entry from blueprints.yml's `templates:` section. */
public record BlueprintTemplate(String name, String schematicFileName, String displayName) {
}
