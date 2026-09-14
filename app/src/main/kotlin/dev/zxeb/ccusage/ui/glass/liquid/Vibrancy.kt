// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).
// Mirrored from the compose-miuix-ui example.

package dev.zxeb.ccusage.ui.glass.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/**
 * Saturation boost for glass.
 *
 * A plain blur washes color out and the panel reads as grey haze. Pushing
 * saturation above 1 restores "vibrancy" the way iOS materials do. Keep brightness
 * and contrast at their neutral values unless you deliberately want a tinted glass.
 */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
