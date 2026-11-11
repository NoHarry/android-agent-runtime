import java.io.IOException
import java.util.concurrent.TimeUnit
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Task

/**
 * Before a task that runs a Python script: fails it with a message that says what is missing when `python3` cannot be
 * started, instead of the bare "A problem occurred starting process" the Exec task gives.
 */
object RequirePython3 : Action<Task> {
    override fun execute(task: Task) {
        val runs = try {
            val process = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: IOException) {
            false
        }
        if (!runs) {
            throw GradleException(
                "${task.path} runs a Python 3 script (tools/app-server-api/api_dump.py), but `python3` is not on the PATH. " +
                    "Install Python 3 so that `python3 --version` works (see CONTRIBUTING.md), then run the build again.",
            )
        }
    }

    private const val TIMEOUT_SECONDS = 30L
}
