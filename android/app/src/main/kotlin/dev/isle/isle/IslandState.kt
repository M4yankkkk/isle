package dev.isle.isle

/** Pure transition rules: system suppression overrides manual and event requests. */
enum class Presentation { IDLE, COMPACT, EXPANDED }
data class IslandState(
    val presentation: Presentation = Presentation.IDLE,
    val suppressed: Boolean = false,
    val pinnedByUser: Boolean = false,
) {
    fun open(user: Boolean = false): IslandState = if (suppressed) this else
        copy(presentation = Presentation.EXPANDED, pinnedByUser = user || pinnedByUser)
    fun settle(hasActivity: Boolean) = copy(
        presentation = if (hasActivity && !suppressed) Presentation.COMPACT else Presentation.IDLE,
        pinnedByUser = false,
    )
    fun suppress(value: Boolean, hasActivity: Boolean) = if (value)
        IslandState(suppressed = true) else if (suppressed) IslandState().settle(hasActivity) else this
}
