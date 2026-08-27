package tern.translate

import tern.domain.LanguageCode

/**
 * The outcome of asking the third party what language something is in.
 *
 * A nullable [LanguageCode] would collapse three different situations into one `null` and lose
 * the reason. As a sealed hierarchy the caller has to acknowledge each case, and `when` over it
 * is exhaustive without an else branch - so adding a fourth outcome later becomes a compile
 * error at every site that has to care, rather than a silent fall-through.
 */
sealed interface Detection {

    /** The detector answered with a code the domain accepts. */
    data class Detected(val code: LanguageCode) : Detection

    /** The detector answered, but with nothing usable: no candidates, or a code we reject. */
    object Unrecognised : Detection

    /** The detector could not be reached, timed out, or failed. */
    object Unavailable : Detection
}
