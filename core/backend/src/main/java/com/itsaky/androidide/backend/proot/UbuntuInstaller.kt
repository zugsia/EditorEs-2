package com.itsaky.androidide.backend.proot

import android.content.Context
import com.itsaky.androidide.backend.build.ToolchainPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

sealed class InstallPhase {
    data object Idle : InstallPhase()
    data class Downloading(val percent: Int, val receivedMb: Double, val totalMb: Double) : InstallPhase()
    data class Extracting(val entry: String, val count: Int) : InstallPhase()
    data object Finalizing : InstallPhase()
    data object Done : InstallPhase()
    data class Failed(val message: String) : InstallPhase()
}

class UbuntuInstaller(private val context: Context) {

    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    suspend fun install(onProgress: (InstallPhase) -> Unit): Unit = withContext(Dispatchers.IO) {
        try {
            ToolchainPaths.migrateLegacyLayout(context)
            if (ProotConfig.isInstalled(context)) {
                onProgress(InstallPhase.Done)
                return@withContext
            }
            downloadTarball(onProgress)
            val staging = ProotConfig.stagingDir(context)
            staging.deleteRecursivelySafe()
            extractRootfs(staging, onProgress)
            preserveUserData(ProotConfig.rootfsDir(context), staging)
            activateStaging(staging)
            finalizeRootfs(onProgress)
            onProgress(InstallPhase.Done)
        } catch (e: Exception) {
            ProotConfig.tarballFile(context).delete()
            ProotConfig.stagingDir(context).deleteRecursivelySafe()
            onProgress(InstallPhase.Failed(e.message ?: "Installation failed"))
        }
    }

    private fun downloadTarball(onProgress: (InstallPhase) -> Unit) {
        val tarball = ProotConfig.tarballFile(context)
        if (tarball.exists() && verifySha256(tarball)) return
        val tmp = File(tarball.parentFile, tarball.name + ".tmp")
        val connection = openFollowingRedirects(URL(ProotConfig.tarballUrl()))
        val total = connection.contentLengthLong
        FileOutputStream(tmp).use { out ->
            val buffer = ByteArray(64 * 1024)
            connection.inputStream.use { input ->
                var received = 0L
                var lastPercent = -1
                while (true) {
                    if (cancelled.get()) throw InstallCancelledException()
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    received += read
                    if (total > 0) {
                        val percent = ((received * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(
                                InstallPhase.Downloading(
                                    percent = percent,
                                    receivedMb = received / (1024.0 * 1024.0),
                                    totalMb = total / (1024.0 * 1024.0)
                                )
                            )
                        }
                    }
                }
            }
        }
        connection.disconnect()
        if (!verifySha256(tmp)) {
            tmp.delete()
            throw IllegalStateException("Checksum mismatch, please retry")
        }
        if (!tmp.renameTo(tarball)) throw IllegalStateException("Unable to finalize download")
    }

    private fun openFollowingRedirects(url: URL): HttpURLConnection {
        var current = url
        repeat(5) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.setRequestProperty("User-Agent", "AndroidIDE/1.0")
            val status = connection.responseCode
            if (status in 301..303 || status == 307 || status == 308) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without location")
                connection.disconnect()
                current = URL(current, location)
                return@repeat
            }
            if (status != 200) throw IllegalStateException("Download failed with HTTP $status")
            return connection
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun verifySha256(file: File): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex == ProotConfig.tarballSha256()
    }

    private fun extractRootfs(rootfs: File, onProgress: (InstallPhase) -> Unit) {
        rootfs.mkdirs()
        val tarball = ProotConfig.tarballFile(context)
        val pendingLinks = mutableListOf<Pair<File, String>>()
        var count = 0
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(tarball.inputStream(), 256 * 1024))
        ).use { tar ->
            while (true) {
                if (cancelled.get()) throw InstallCancelledException()
                val entry = tar.nextEntry ?: break
                val name = normalizeEntryName(entry.name) ?: continue
                if (name == "dev" || name.startsWith("dev/")) continue
                if (name == "proc" || name.startsWith("proc/")) continue
                if (name == "sys" || name.startsWith("sys/")) continue
                val target = File(rootfs, name)
                if (!target.canonicalPath.startsWith(rootfs.canonicalPath)) continue
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        runCatching {
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(entry.linkName)
                            )
                        }
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        pendingLinks += target to (normalizeEntryName(entry.linkName) ?: continue)
                    }
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = tar.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                            }
                        }
                        applyPermissions(target, entry)
                    }
                }
                count++
                onProgress(InstallPhase.Extracting(name, count))
            }
        }
        for ((target, linkName) in pendingLinks) {
            val source = File(rootfs, linkName)
            if (!source.exists()) continue
            target.delete()
            if (!runCatching {
                    java.nio.file.Files.createLink(target.toPath(), source.toPath())
                }.isSuccess
            ) {
                runCatching { source.copyTo(target, overwrite = true) }
            }
        }
        if (!File(rootfs, "etc").isDirectory) {
            throw IllegalStateException("Rootfs structure is invalid")
        }
        if (!File(rootfs, "usr/bin/bash").exists()) {
            throw IllegalStateException("Rootfs is missing bash")
        }
    }

    private fun normalizeEntryName(name: String): String? {
        var cleaned = name.replace('\\', '/')
        while (cleaned.startsWith("./")) cleaned = cleaned.substring(2)
        cleaned = cleaned.trimStart('/').trimEnd('/')
        if (cleaned.isBlank() || cleaned == ".") return null
        if (cleaned.split('/').any { it == ".." }) return null
        return cleaned
    }

    private fun applyPermissions(target: File, entry: TarArchiveEntry) {
        val mode = entry.mode
        target.setReadable(true, false)
        if (mode and 128 != 0) target.setWritable(true, false)
        if (mode and 64 != 0) target.setExecutable(true, false)
    }

    private fun preserveUserData(previous: File, fresh: File) {
        if (!previous.isDirectory) return
        for (relative in listOf("root", "usr/local")) {
            val from = File(previous, relative)
            if (!from.isDirectory) continue
            runCatching {
                from.copyRecursively(File(fresh, relative), overwrite = true)
            }
        }
    }

    private fun activateStaging(staging: File) {
        val rootfs = ProotConfig.rootfsDir(context)
        val backup = File(rootfs.parentFile, "${ProotConfig.RootfsName}.bak")
        backup.deleteRecursivelySafe()
        if (rootfs.exists() && !rootfs.renameTo(backup)) {
            throw IllegalStateException("Unable to stage previous install")
        }
        if (!staging.renameTo(rootfs)) {
            runCatching { backup.renameTo(rootfs) }
            throw IllegalStateException("Unable to activate install")
        }
        backup.deleteRecursivelySafe()
    }

    private fun finalizeRootfs(onProgress: (InstallPhase) -> Unit) {
        onProgress(InstallPhase.Finalizing)
        val rootfs = ProotConfig.rootfsDir(context)

        File(rootfs, "etc/resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n"
        )

        File(rootfs, "etc/hosts").writeText(
            """
            127.0.0.1   localhost.localdomain localhost
            ::1         localhost.localdomain localhost ip6-localhost ip6-loopback
            fe00::0     ip6-localnet
            ff00::0     ip6-mcastprefix
            ff02::1     ip6-allnodes
            ff02::2     ip6-allrouters
            ff02::3     ip6-allhosts
            """.trimIndent() + "\n"
        )

        File(rootfs, "etc/hostname").writeText("localhost\n")

        File(rootfs, "etc/environment").writeText(
            """
            PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            LANG=C.UTF-8
            TMPDIR=/tmp
            DEBIAN_FRONTEND=noninteractive
            """.trimIndent() + "\n"
        )

        File(rootfs, "etc/apt/apt.conf.d").mkdirs()
        File(rootfs, "etc/apt/apt.conf.d/99ide").writeText(
            """
            APT::Sandbox::User "root";
            Acquire::Retries "3";
            Acquire::http::Pipeline-Depth "0";
            Acquire::ForceIPv4 "true";
            DPkg::Options {"--force-confdef";"--force-confold";};
            """.trimIndent() + "\n"
        )

        File(rootfs, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfs, "etc/dpkg/dpkg.cfg.d/99ide").writeText(
            "force-unsafe-io\nno-debsig\n"
        )

        ProotConfig.writeShellProfile(context)
        ProotConfig.prepareStorageMounts(context)

        listOf("tmp", "root", "var/tmp", "var/cache/apt/archives/partial", "var/lib/apt/lists/partial")
            .forEach { path ->
                File(rootfs, path).apply {
                    mkdirs()
                    setWritable(true, false)
                    setExecutable(true, false)
                }
            }

        ProotConfig.registerAndroidIds(context)

        val proc = File(rootfs, "proc")
        proc.mkdirs()
        val fakeEntries = mapOf(
            ".loadavg" to "fake_loadavg",
            ".stat" to "fake_stat",
            ".uptime" to "fake_uptime",
            ".version" to "fake_version",
            ".vmstat" to "fake_vmstat",
            ".sysctl_entry_cap_last_cap" to "fake_sysctl_entry_cap_last_cap"
        )
        for ((targetName, assetName) in fakeEntries) {
            val target = File(proc, targetName)
            context.assets.open("proot/$assetName").use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            target.setReadable(true, false)
        }
        proc.setExecutable(true, false)
        proc.setReadable(true, false)
        proc.setWritable(true, false)

        File(rootfs, "sys").mkdirs()
        File(rootfs, "dev").mkdirs()

        File(rootfs, ProotConfig.InstallMarker).writeText(ProotConfig.RootfsVersion)
        ProotConfig.tarballFile(context).delete()
    }
}

class InstallCancelledException : Exception("Installation cancelled")

fun File.deleteRecursivelySafe() {
    if (!exists()) return
    walkBottomUp().forEach { file ->
        if (!file.delete()) {
            file.setWritable(true)
            file.setExecutable(true)
            file.delete()
        }
    }
}
