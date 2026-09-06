package com.itsaky.androidide.backend.proot

import android.content.Context
import android.os.Process
import com.itsaky.androidide.backend.build.ToolchainPaths
import java.io.File

object ProotConfig {

    const val RootfsName = "ubuntu"
    const val FakeKernelVersion = "6.2.1-PRoot-Distro"
    const val InstallMarker = ".installed"
    const val RootfsVersion = "noble-24.04.4"

    private const val TarballUrlArm64 =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
    private const val TarballSha256Arm64 =
        "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
    private const val TarballUrlArm =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-armhf.tar.gz"
    private const val TarballSha256Arm =
        "991520b47f6586f38a78505cf016e300b6191bb8ff86a0723481ec23a37ab7f4"

    private const val GuestPath =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    private const val GuestCMakeBin = "/opt/cmake/bin"
    private const val GuestNdkBin = "/opt/ndk/toolchains/llvm/prebuilt/linux-arm64/bin"
    private const val GuestNdkRoot = "/opt/ndk"

    private const val PrimaryStorage = "/storage/emulated/0"

    private val StorageBinds: List<String>
        get() = buildList {
            val primary = File(PrimaryStorage)
            if (primary.isDirectory) {
                add("$PrimaryStorage:$PrimaryStorage")
                add("$PrimaryStorage:/sdcard")
            }
            val external = File("/storage")
            if (external.isDirectory) add("/storage:/storage")
        }

    fun prepareStorageMounts(context: Context) {
        val rootfs = rootfsDir(context)
        runCatching {
            File(rootfs, "storage/emulated/0").mkdirs()
            File(rootfs, "sdcard").mkdirs()
            File(rootfs, "opt").mkdirs()
        }
    }

    private fun toolchainBind(context: Context): String? {
        val host = ToolchainPaths.toolchainRoot(context)
        if (!host.isDirectory) return null
        return "${host.absolutePath}:/opt"
    }

    private val ToolchainPath = "$GuestCMakeBin:$GuestNdkBin"

    private val FullGuestPath = "$ToolchainPath:$GuestPath"

    fun rootfsDir(context: Context): File = File(context.filesDir, RootfsName)

    fun stagingDir(context: Context): File = File(context.filesDir, "$RootfsName.new")

    fun isInstalled(context: Context): Boolean {
        val rootfs = rootfsDir(context)
        return File(rootfs, InstallMarker).readTextOrNull() == RootfsVersion &&
            File(rootfs, "etc").isDirectory &&
            File(rootfs, "usr/bin/bash").exists()
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    fun prootBinary(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    fun loaderBinary(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libloader.so").absolutePath

    fun isAvailable(context: Context): Boolean = File(prootBinary(context)).exists()

    private fun isArm64(): Boolean =
        android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true

    fun tarballUrl(): String =
        if (isArm64()) TarballUrlArm64 else TarballUrlArm

    fun tarballSha256(): String =
        if (isArm64()) TarballSha256Arm64 else TarballSha256Arm

    fun tarballFile(context: Context): File = File(context.cacheDir, "ubuntu-rootfs.tar.gz")

    fun tmpDir(context: Context): File = File(context.cacheDir, "proot-tmp").apply { mkdirs() }

    fun prootArgs(
        context: Context,
        cwd: String = "/root",
        bootCommand: String? = null
    ): Array<String> {
        val rootfs = rootfsDir(context).absolutePath
        val extraBinds = (StorageBinds + listOfNotNull(toolchainBind(context)))
            .map { "--bind=$it" }
        return arrayOf(
            "proot",
            "-L",
            "--kernel-release=$FakeKernelVersion",
            "--link2symlink",
            "--sysvipc",
            "--kill-on-exit",
            "--rootfs=$rootfs",
            "--change-id=0:0",
            "--cwd=$cwd",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=$rootfs/proc/.loadavg:/proc/loadavg",
            "--bind=$rootfs/proc/.stat:/proc/stat",
            "--bind=$rootfs/proc/.uptime:/proc/uptime",
            "--bind=$rootfs/proc/.version:/proc/version",
            "--bind=$rootfs/proc/.vmstat:/proc/vmstat",
            "--bind=$rootfs/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap",
            *extraBinds.toTypedArray(),
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "LANG=C.UTF-8",
            "PATH=$FullGuestPath",
            "ANDROID_NDK_ROOT=$GuestNdkRoot",
            "ANDROID_NDK_HOME=$GuestNdkRoot",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            *bootShell(bootCommand)
        )
    }

    private fun bootShell(bootCommand: String?): Array<String> =
        if (bootCommand == null) {
            arrayOf("/usr/bin/bash", "-l")
        } else {
            arrayOf("/usr/bin/bash", "-lc", "$bootCommand; exec /usr/bin/bash -l")
        }

    fun prootEnv(context: Context): Array<String> = arrayOf(
        "TERM=xterm-256color",
        "HOME=${context.filesDir.absolutePath}",
        "PROOT_LOADER=${loaderBinary(context)}",
        "PROOT_TMP_DIR=${tmpDir(context).absolutePath}"
    )

    fun prootEnvMap(context: Context): Map<String, String> = mapOf(
        "TERM" to "xterm-256color",
        "HOME" to context.filesDir.absolutePath,
        "PROOT_LOADER" to loaderBinary(context),
        "PROOT_TMP_DIR" to tmpDir(context).absolutePath
    )

    fun commandArgs(
        context: Context,
        script: String,
        guestCwd: String,
        binds: List<String> = emptyList(),
        extraPath: List<String> = emptyList()
    ): List<String> {
        val rootfs = rootfsDir(context).absolutePath
        val path = (extraPath + GuestPath.split(':')).joinToString(":")
        val args = mutableListOf(
            prootBinary(context),
            "-L",
            "--kernel-release=$FakeKernelVersion",
            "--link2symlink",
            "--sysvipc",
            "--kill-on-exit",
            "--rootfs=$rootfs",
            "--change-id=0:0",
            "--cwd=$guestCwd",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=$rootfs/proc/.loadavg:/proc/loadavg",
            "--bind=$rootfs/proc/.stat:/proc/stat",
            "--bind=$rootfs/proc/.uptime:/proc/uptime",
            "--bind=$rootfs/proc/.version:/proc/version",
            "--bind=$rootfs/proc/.vmstat:/proc/vmstat",
            "--bind=$rootfs/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap"
        )
        StorageBinds.forEach { args += "--bind=$it" }
        toolchainBind(context)?.let { args += "--bind=$it" }
        binds.forEach { args += "--bind=$it" }
        args += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "LANG=C.UTF-8",
            "PATH=$path",
            "ANDROID_NDK_ROOT=$GuestNdkRoot",
            "ANDROID_NDK_HOME=$GuestNdkRoot",
            "TERM=dumb",
            "TMPDIR=/tmp",
            "/usr/bin/bash",
            "-c",
            script
        )
        return args
    }

    fun rawArgs(
        context: Context,
        command: List<String>,
        guestCwd: String,
        binds: List<String> = emptyList(),
        extraPath: List<String> = emptyList(),
        extraEnv: List<String> = emptyList()
    ): List<String> {
        val rootfs = rootfsDir(context).absolutePath
        val path = (extraPath + GuestPath.split(':')).joinToString(":")
        val args = mutableListOf(
            prootBinary(context),
            "-L",
            "--kernel-release=$FakeKernelVersion",
            "--link2symlink",
            "--sysvipc",
            "--kill-on-exit",
            "--rootfs=$rootfs",
            "--change-id=0:0",
            "--cwd=$guestCwd",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=$rootfs/proc/.loadavg:/proc/loadavg",
            "--bind=$rootfs/proc/.stat:/proc/stat",
            "--bind=$rootfs/proc/.uptime:/proc/uptime",
            "--bind=$rootfs/proc/.version:/proc/version",
            "--bind=$rootfs/proc/.vmstat:/proc/vmstat",
            "--bind=$rootfs/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap"
        )
        StorageBinds.forEach { args += "--bind=$it" }
        toolchainBind(context)?.let { args += "--bind=$it" }
        binds.forEach { args += "--bind=$it" }
        args += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "LANG=C.UTF-8",
            "PATH=$path",
            "TERM=dumb",
            "TMPDIR=/tmp"
        )
        args += extraEnv
        args += command
        return args
    }

    fun writeShellProfile(context: Context) {
        val rootfs = rootfsDir(context)
        if (!File(rootfs, "etc").isDirectory) return
        runCatching {
            File(rootfs, "etc/profile.d").mkdirs()
            val profileScript = """
                export PATH=$ToolchainPath:${'$'}PATH
                export ANDROID_NDK_ROOT=$GuestNdkRoot
                export ANDROID_NDK_HOME=$GuestNdkRoot
                export LANG=C.UTF-8
                export TMPDIR=/tmp
                export DEBIAN_FRONTEND=noninteractive
                export PS1='\[\033[01;32m\]\u@ubuntu\[\033[00m\]:\[\033[01;36m\]\w\[\033[00m\]\$ '
                alias ll='ls -alF'
            """.trimIndent() + "\n"

            File(rootfs, "etc/profile.d/00-ide.sh").writeText(profileScript)

            val etcBashrc = File(rootfs, "etc/bash.bashrc")
            if (etcBashrc.exists()) {
                val current = etcBashrc.readText()
                if (!current.contains("00-ide")) {
                    etcBashrc.appendText("\n[ -f /etc/profile.d/00-ide.sh ] && . /etc/profile.d/00-ide.sh # 00-ide\n")
                }
            }

            val bashrc = File(rootfs, "root/.bashrc")
            bashrc.parentFile?.mkdirs()
            if (!bashrc.exists()) {
                bashrc.writeText(profileScript)
            } else {
                val content = bashrc.readText()
                if (!content.contains("00-ide.sh") && !content.contains("u@ubuntu")) {
                    bashrc.appendText("\n[ -f /etc/profile.d/00-ide.sh ] && . /etc/profile.d/00-ide.sh\n")
                }
            }

            val profile = File(rootfs, "root/.profile")
            if (!profile.exists()) {
                profile.writeText(
                    "[ -n \"\$BASH_VERSION\" ] && [ -f ~/.bashrc ] && . ~/.bashrc\n"
                )
            }
        }
    }

    fun registerAndroidIds(context: Context) {
        val rootfs = rootfsDir(context)
        val uid = Process.myUid()
        val userName = "aid_app_$uid"
        val passwd = File(rootfs, "etc/passwd")
        val shadow = File(rootfs, "etc/shadow")
        val group = File(rootfs, "etc/group")
        val gshadow = File(rootfs, "etc/gshadow")
        runCatching {
            if (passwd.exists() && !passwd.readText().contains(userName)) {
                passwd.appendText("$userName:x:$uid:$uid:AndroidIDE:/:/usr/sbin/nologin\n")
            }
            if (shadow.exists() && !shadow.readText().contains(userName)) {
                shadow.appendText("$userName:*:18446:0:99999:7:::\n")
            }
        }
        val existing = runCatching { group.readText() }.getOrDefault("")
        val lines = StringBuilder()
        val shadowLines = StringBuilder()
        for (gid in supplementaryGids()) {
            val name = "aid_$gid"
            if (existing.contains(":$gid:") || existing.contains("$name:")) continue
            lines.append("$name:x:$gid:root,$userName\n")
            shadowLines.append("$name:*::root,$userName\n")
        }
        runCatching {
            if (group.exists() && lines.isNotEmpty()) group.appendText(lines.toString())
            if (gshadow.exists() && shadowLines.isNotEmpty()) gshadow.appendText(shadowLines.toString())
        }
    }

    private fun supplementaryGids(): List<Int> {
        val gids = linkedSetOf(Process.myUid())
        runCatching {
            File("/proc/self/status").forEachLine { line ->
                when {
                    line.startsWith("Groups:") -> line.removePrefix("Groups:")
                    line.startsWith("Gid:") -> line.removePrefix("Gid:")
                    else -> null
                }?.trim()
                    ?.split(Regex("\\s+"))
                    ?.mapNotNull { it.toIntOrNull() }
                    ?.forEach { gids.add(it) }
            }
        }
        return gids.toList()
    }
}
