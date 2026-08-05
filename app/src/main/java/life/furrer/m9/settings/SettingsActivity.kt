package life.furrer.m9.settings

import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.settings.SettingsScreen

/** M9's Settings. M1 has no M9-specific settings yet — Default messaging app, SIM for sending,
 *  delivery reports, messages-to-load and the notification privacy toggle (docs/m9-plan.md's
 *  "SettingsActivity" section) all need code that doesn't exist until M3/M4/M6 — so
 *  [buildAppSections] adds nothing. [NineSettingsActivity] already skips rendering a section
 *  header for a section with no rows (see its own doc comment), so this doesn't show an empty
 *  "Messages" heading; Theme, Backup and About still come free from :core. */
class SettingsActivity : NineSettingsActivity() {
    override fun buildAppSections(screen: SettingsScreen, surface: NineSurface) {
        // Intentionally empty for M1 — see the class doc comment above.
    }
}
