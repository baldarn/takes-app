package app.takes.core

/**
 * The kinds of difference ([TrackParityKinds]) that have been read and
 * accepted: with `TAKES_PARITY_GATE=1` in the environment the parity suite
 * fails on any difference whose kind is not in this list. A kind goes in
 * here when it has been decided what the function that shows it does once it
 * runs on the track model; a difference no kind explains can never be
 * accepted.
 */
internal object TrackParityAccepted {
    val kinds: Set<String> = setOf()
}
