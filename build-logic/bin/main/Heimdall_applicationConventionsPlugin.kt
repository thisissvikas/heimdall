/**
 * Precompiled [heimdall.application-conventions.gradle.kts][Heimdall_application_conventions_gradle] script plugin.
 *
 * @see Heimdall_application_conventions_gradle
 */
public
class Heimdall_applicationConventionsPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Heimdall_application_conventions_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
