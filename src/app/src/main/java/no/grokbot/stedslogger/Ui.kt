package no.grokbot.stedslogger

import android.content.Context

/**
 * 1.49: colours of the Material 3 look (like AudioAddict). They come from res/values-…/colors.xml:
 * the seed palette (light / dark following the system), or the wallpaper colours on Android 14+.
 */
object Ui {
    fun c(ctx: Context, id: Int): Int = ctx.getColor(id)
    fun onSurface(ctx: Context) = c(ctx, R.color.m3_on_surface)
    fun variant(ctx: Context) = c(ctx, R.color.m3_on_surface_variant)
    fun primary(ctx: Context) = c(ctx, R.color.m3_primary)
    fun comment(ctx: Context) = c(ctx, R.color.m3_c_purple)
    fun green(ctx: Context) = c(ctx, R.color.m3_c_green)
    fun blue(ctx: Context) = c(ctx, R.color.m3_c_blue)
    fun red(ctx: Context) = c(ctx, R.color.m3_c_red)
    fun placeholder(ctx: Context) = c(ctx, R.color.m3_surface_container_highest)
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
