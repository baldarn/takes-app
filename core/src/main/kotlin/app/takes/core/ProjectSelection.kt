package app.takes.core

/**
 * Which project Record and Edit act on, as the original's store chooses it
 * (YouRec/ProjectStore.swift:150-154, 256-271). Projects are given by id, the
 * most recently updated first, as the library lists them.
 */
object ProjectSelection {
    /**
     * The project selected when the app starts: the one selected when it was
     * last used, while it is still in the library, else the most recently
     * updated one, else none (YouRec/ProjectStore.swift:150-154).
     */
    fun atLaunch(saved: String?, projects: List<String>): String? =
        saved?.takeIf { it in projects } ?: projects.firstOrNull()

    /**
     * The project selected once [removed] has gone to Recently deleted, [remaining]
     * being the library without it: the same one when another was selected, else
     * the most recently updated one left (YouRec/ProjectStore.swift:269).
     */
    fun afterRemoving(selected: String?, removed: String, remaining: List<String>): String? =
        if (selected != removed) selected else remaining.firstOrNull { it != removed }

    /**
     * Whether a project is to be made when the app starts: when the library holds
     * none it can open, so that Record has one (YouRec/ContentView.swift:341).
     */
    fun createsAtLaunch(projects: List<String>): Boolean = projects.isEmpty()
}
