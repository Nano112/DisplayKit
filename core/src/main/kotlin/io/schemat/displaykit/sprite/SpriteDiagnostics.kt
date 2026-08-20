package io.schemat.displaykit.sprite

import java.util.logging.Logger

/**
 * Once-only warnings for sprite misuse.
 *
 * Everything here is advisory — the index is data, not a correctness
 * dependency, so a mismatch or a muddy tint degrades rather than throws. The
 * once-only discipline matters because these checks sit on render paths that
 * run every tick.
 */
object SpriteDiagnostics {

    private val logger = Logger.getLogger("displaykit/sprite")
    private val seen = LinkedHashMap<String, String>()

    fun warnOnce(key: String, message: String) {
        if (seen.putIfAbsent(key, message) == null) {
            logger.warning(message)
        }
    }

    /** Warn if the committed index was generated for a different Minecraft version. */
    fun checkVersion(index: SpriteIndex, runtimeVersion: String) {
        if (index.sourceVersion == runtimeVersion) return
        warnOnce(
            "version",
            "Sprite index was generated for Minecraft ${index.sourceVersion} but the " +
                "server is running $runtimeVersion. Dimensions may be wrong; re-run " +
                ":libs:displaykit:pack:generateSpriteIndex."
        )
    }

    /**
     * Warn if [entry] is about to be tinted but is not greyscale.
     *
     * Bitmap glyph tinting multiplies the glyph colour by the style colour, so
     * a coloured source tints muddy rather than to the requested colour.
     */
    fun checkTintable(entry: SpriteEntry) {
        if (entry.greyscale) return
        warnOnce(
            "tint:${entry.id}",
            "Tinting ${entry.id}, which is not greyscale. Tint is multiplicative, " +
                "so the result will be darker and off-hue rather than the requested colour."
        )
    }

    /** Warn once that by-reference glyphs are unavailable without the pack. */
    fun packDisabled() {
        warnOnce(
            "pack",
            "DisplayKit resource pack is disabled, so sprite glyphs, tinting and the " +
                "compositor are unavailable. Sprites fall back to square, untinted " +
                "AtlasSprite components. Set FabricDisplayKit.enableResourcePack = true."
        )
    }

    fun warnings(): List<String> = seen.values.toList()

    fun reset() = seen.clear()
}
