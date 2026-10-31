package moe.chenxy.oppopods.utils

import android.util.Log
import java.io.ByteArrayOutputStream

data class MelodyImageCandidate(
    val label: String,
    val imageDir: String,
    val boxPath: String,
    val leftPath: String,
    val rightPath: String,
    val boxBytes: ByteArray,
)

object RootManager {
    private const val TAG = "PuddingPods-MelodyImport"
    private val packageNameRegex = Regex("^[A-Za-z0-9_.]+$")
    private val melodyImagePathRegex = Regex(
        "^/(data/(data|user/\\d+|user_de/\\d+)|data_mirror/data_(ce|de)/null/\\d+)/com\\.heytap\\.headset/files/melody-model-download/control_[A-Za-z0-9_-]+/res/image/img_(detail|left|right)\\.png$"
    )
    private val melodyDirPathRegex = Regex(
        "^/(data/(data|user/\\d+|user_de/\\d+)|data_mirror/data_(ce|de)/null/\\d+)/com\\.heytap\\.headset/files/melody-model-download$"
    )
    private val melodyModelDirs = listOf(
        "/data_mirror/data_ce/null/0/com.heytap.headset/files/melody-model-download",
        "/data_mirror/data_de/null/0/com.heytap.headset/files/melody-model-download",
        "/data/data/com.heytap.headset/files/melody-model-download",
        "/data/user/0/com.heytap.headset/files/melody-model-download",
        "/data/user_de/0/com.heytap.headset/files/melody-model-download",
    )

    fun restartPackages(packages: Collection<String>): Boolean {
        val targets = packages.distinct().filter { it.matches(packageNameRegex) }
        if (targets.isEmpty()) return false

        return runCatching {
            val command = targets.joinToString("; ") { "am force-stop $it" }
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    fun hasRootAccess(): Boolean {
        return runRootText("echo yes")?.trim() == "yes"
    }

    fun scanMelodyImageCandidates(): List<MelodyImageCandidate> {
        val modelDir = melodyModelDirs.firstOrNull { dir ->
            dir.matches(melodyDirPathRegex) && runRootText("test -d ${dir.shellQuote()} && echo yes")?.trim() == "yes"
        } ?: return emptyList()

        val command = "for d in ${modelDir.shellQuote()}/control_*; do test -f \"\$d/res/image/img_detail.png\" && echo \"\$d/res/image/img_detail.png\"; done 2>/dev/null"
        val detailPaths = runRootText(command)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.matches(melodyImagePathRegex) }
            ?.distinct()
            ?.toList()
            .orEmpty()

        return detailPaths.mapNotNull { boxPath ->
            val imageDir = boxPath.removeSuffix("/img_detail.png")
            val leftPath = "$imageDir/img_left.png"
            val rightPath = "$imageDir/img_right.png"
            if (!leftPath.matches(melodyImagePathRegex) || !rightPath.matches(melodyImagePathRegex)) return@mapNotNull null
            val hasAll = runRootText("test -f ${leftPath.shellQuote()} -a -f ${rightPath.shellQuote()} && echo yes")
                ?.trim() == "yes"
            if (!hasAll) return@mapNotNull null
            val boxBytes = readMelodyImage(boxPath) ?: return@mapNotNull null
            MelodyImageCandidate(
                label = imageDir.substringBeforeLast("/res/image").substringAfterLast('/'),
                imageDir = imageDir,
                boxPath = boxPath,
                leftPath = leftPath,
                rightPath = rightPath,
                boxBytes = boxBytes,
            )
        }
    }

    fun readMelodyImage(path: String): ByteArray? {
        if (!path.matches(melodyImagePathRegex)) {
            return null
        }
        return runCatching {
            val process = ProcessBuilder("su", "-c", "cat ${path.shellQuote()}")
                .redirectErrorStream(false)
                .start()
            val bytes = ByteArrayOutputStream().use { output ->
                process.inputStream.use { input -> input.copyTo(output) }
                output.toByteArray()
            }
            val exitCode = process.waitFor()
            if (exitCode == 0 && bytes.isNotEmpty()) bytes else null
        }.onFailure { Log.e(TAG, "read failed path=$path", it) }.getOrNull()
    }

    private fun runRootText(command: String): String? {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode == 0) output else null
        }.onFailure { Log.e(TAG, "root text failed command=$command", it) }.getOrNull()
    }

    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
}
