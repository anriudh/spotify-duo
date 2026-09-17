package dev.anriudh.spotifyduo

/**
 * Per-person visual identity. Two people, so this is a fixed mapping rather
 * than a stored preference; it would become a column if a third ever existed.
 */
object Person {
    private const val BLUE_ID = "anirudh"

    fun chipFor(id: String): Int = if (id == BLUE_ID) R.drawable.chip_blue else R.drawable.chip_pink

    fun barFor(id: String): Int = if (id == BLUE_ID) R.id.progress_blue else R.id.progress_pink

    fun otherBar(bar: Int): Int = if (bar == R.id.progress_blue) R.id.progress_pink else R.id.progress_blue
}
