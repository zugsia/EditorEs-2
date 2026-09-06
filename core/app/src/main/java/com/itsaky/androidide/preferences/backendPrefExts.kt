/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.preferences

import android.content.Context
import androidx.preference.Preference
import com.itsaky.androidide.backend.InstallFlashbar
import com.itsaky.androidide.backend.build.ToolchainInstaller
import com.itsaky.androidide.backend.build.ToolchainKind
import com.itsaky.androidide.backend.build.ToolchainPaths
import com.itsaky.androidide.backend.build.ToolchainRepository
import com.itsaky.androidide.backend.proot.ProotConfig
import com.itsaky.androidide.backend.proot.UbuntuInstaller
import com.itsaky.androidide.preferences.internal.BackendPreferences
import com.itsaky.androidide.preferences.internal.BackendPreferences.buildAbi
import com.itsaky.androidide.preferences.internal.BackendPreferences.buildApiLevel
import com.itsaky.androidide.preferences.internal.BackendPreferences.buildTypeIndex
import com.itsaky.androidide.preferences.internal.BackendPreferences.cmakeVersion
import com.itsaky.androidide.preferences.internal.BackendPreferences.ndkVersion
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.tasks.executeAsync
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
class BackendPreferencesScreen(
  override val key: String = "idepref_backend",
  override val title: Int = string.idepref_backend_title,
  override val summary: Int? = string.idepref_backend_summary,
  override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(EnvironmentGroup())
    addPreference(BuildOptionsGroup())
  }
}

@Parcelize
private class EnvironmentGroup(
  override val key: String = "idepref_backend_env",
  override val title: Int = string.idepref_backend_env,
  override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(InstallUbuntuPreference())
    addPreference(InstallNdkPreference())
    addPreference(InstallCmakePreference())
  }
}

@Parcelize
private class BuildOptionsGroup(
  override val key: String = "idepref_backend_build_options",
  override val title: Int = string.idepref_backend_build_options,
  override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(BuildAbiPreference())
    addPreference(BuildApiPreference())
    addPreference(BuildTypePreference())
  }
}

@Parcelize
private class InstallUbuntuPreference(
  override val key: String = "idepref_backend_install_ubuntu",
  override val title: Int = string.idepref_backend_install_ubuntu,
) : SimplePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { updateSummary(context, it) }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val activity = context as? android.app.Activity
    val progress = activity?.let {
      InstallFlashbar(it, context.getString(string.idepref_backend_install_ubuntu))
    } ?: run {
      flashInfo(string.idepref_backend_install_started)
      null
    }
    executeAsync(callable = {
      runBlocking {
        UbuntuInstaller(context.applicationContext).install { phase ->
          progress?.update(phase)
        }
      }
      ProotConfig.isInstalled(context.applicationContext)
    }) { installed ->
      if (progress == null) {
        if (installed == true) {
          flashSuccess(string.idepref_backend_install_done)
        } else {
          flashError(string.idepref_backend_install_failed_simple)
        }
      } else if (installed != true) {
        progress.failed(context.getString(string.idepref_backend_install_failed_simple))
      }
      updateSummary(context, preference)
    }
    return true
  }

  private fun updateSummary(context: Context, preference: Preference) {
    preference.summary = if (ProotConfig.isInstalled(context.applicationContext)) {
      context.getString(string.idepref_backend_installed, ProotConfig.RootfsVersion)
    } else {
      context.getString(string.idepref_backend_missing)
    }
  }
}

@Parcelize
private class InstallNdkPreference(
  override val key: String = "idepref_backend_install_ndk",
  override val title: Int = string.idepref_backend_install_ndk,
) : SimplePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { updateSummary(context, it) }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    installToolchain(preference, ToolchainKind.Ndk)
    return true
  }

  private fun updateSummary(context: Context, preference: Preference) {
    val installed = ToolchainPaths.installedVersion(context.applicationContext, ToolchainKind.Ndk)
    preference.summary = if (installed != null) {
      context.getString(string.idepref_backend_installed, installed)
    } else {
      context.getString(string.idepref_backend_missing)
    }
  }
}

@Parcelize
private class InstallCmakePreference(
  override val key: String = "idepref_backend_install_cmake",
  override val title: Int = string.idepref_backend_install_cmake,
) : SimplePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { updateSummary(context, it) }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    installToolchain(preference, ToolchainKind.CMake)
    return true
  }

  private fun updateSummary(context: Context, preference: Preference) {
    val installed = ToolchainPaths.installedVersion(context.applicationContext, ToolchainKind.CMake)
    preference.summary = if (installed != null) {
      context.getString(string.idepref_backend_installed, installed)
    } else {
      context.getString(string.idepref_backend_missing)
    }
  }
}

private fun installToolchain(preference: Preference, kind: ToolchainKind) {
  val context = preference.context
  val activity = context as? android.app.Activity
  val label = if (kind == ToolchainKind.Ndk) {
    context.getString(string.idepref_backend_install_ndk)
  } else {
    context.getString(string.idepref_backend_install_cmake)
  }
  val progress = activity?.let { InstallFlashbar(it, label) } ?: run {
    flashInfo(string.idepref_backend_install_started)
    null
  }
  executeAsync(callable = {
    runBlocking {
      val releases = ToolchainRepository.fetchReleases(kind)
      val release = releases.firstOrNull() ?: return@runBlocking false
      var done = false
      ToolchainInstaller(context.applicationContext, kind).install(release) { phase ->
        progress?.update(phase)
        done = phase is com.itsaky.androidide.backend.build.ToolchainPhase.Done
      }
      if (done) {
        val tag = ToolchainPaths.installedVersion(context.applicationContext, kind) ?: release.tag
        if (kind == ToolchainKind.Ndk) ndkVersion = tag else cmakeVersion = tag
      }
      done
    }
  }) { done ->
    if (progress == null) {
      if (done == true) {
        flashSuccess(string.idepref_backend_install_done)
      } else {
        flashError(string.idepref_backend_install_failed_simple)
      }
    } else if (done != true) {
      progress.failed(context.getString(string.idepref_backend_install_failed_simple))
    }
    val installed = ToolchainPaths.installedVersion(context.applicationContext, kind)
    preference.summary = if (installed != null) {
      context.getString(string.idepref_backend_installed, installed)
    } else {
      context.getString(string.idepref_backend_missing)
    }
  }
}

@Parcelize
private class BuildAbiPreference(
  override val key: String = BackendPreferences.BUILD_ABI,
  override val title: Int = string.idepref_backend_abi,
) : SingleChoicePreference() {

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    return arrayOf(
      PreferenceChoices.Entry("arm64-v8a", buildAbi == BackendPreferences.ABI_ARM64, BackendPreferences.ABI_ARM64),
      PreferenceChoices.Entry("armeabi-v7a", buildAbi == BackendPreferences.ABI_ARM32, BackendPreferences.ABI_ARM32),
      PreferenceChoices.Entry("arm64-v8a + armeabi-v7a", buildAbi == BackendPreferences.ABI_ALL, BackendPreferences.ABI_ALL),
    )
  }

  override fun onChoiceConfirmed(
    preference: Preference,
    entry: PreferenceChoices.Entry?,
    position: Int
  ) {
    super.onChoiceConfirmed(preference, entry, position)
    buildAbi = (entry?.data as? Int) ?: BackendPreferences.ABI_ARM64
    updatePreference(preference)
  }

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { updatePreference(it) }
  }

  private fun updatePreference(preference: Preference) {
    val label = when (buildAbi) {
      BackendPreferences.ABI_ARM32 -> "armeabi-v7a"
      BackendPreferences.ABI_ALL -> "arm64-v8a + armeabi-v7a"
      else -> "arm64-v8a"
    }
    preference.summary = preference.context.getString(string.idepref_backend_current, label)
  }
}

@Parcelize
private class BuildApiPreference(
  override val key: String = BackendPreferences.BUILD_API_LEVEL,
  override val title: Int = string.idepref_backend_api,
) : SingleChoicePreference() {

  @IgnoredOnParcel
  private val levels = intArrayOf(24, 26, 28, 29, 30, 33, 34)

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    return levels.map { level ->
      PreferenceChoices.Entry("android-$level", buildApiLevel == level, level)
    }.toTypedArray()
  }

  override fun onChoiceConfirmed(
    preference: Preference,
    entry: PreferenceChoices.Entry?,
    position: Int
  ) {
    super.onChoiceConfirmed(preference, entry, position)
    buildApiLevel = (entry?.data as? Int) ?: 24
    updatePreference(preference)
  }

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { updatePreference(it) }
  }

  private fun updatePreference(preference: Preference) {
    preference.summary = preference.context.getString(
      string.idepref_backend_current, "android-$buildApiLevel")
  }
}

@Parcelize
private class BuildTypePreference(
  override val key: String = BackendPreferences.BUILD_TYPE,
  override val title: Int = string.idepref_backend_buildtype,
) : SingleChoicePreference() {

  @IgnoredOnParcel
  private val types = intArrayOf(
    BackendPreferences.BUILD_TYPE_RELEASE,
    BackendPreferences.BUILD_TYPE_DEBUG,
    BackendPreferences.BUILD_TYPE_REL_WITH_DEB_INFO,
    BackendPreferences.BUILD_TYPE_MIN_SIZE_REL
  )

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    return types.map { type ->
      PreferenceChoices.Entry(typeName(type), buildTypeIndex == type, type)
    }.toTypedArray()
  }

  override fun onChoiceConfirmed(
    preference: Preference,
    entry: PreferenceChoices.Entry?,
    position: Int
  ) {
    super.onChoiceConfirmed(preference, entry, position)
    buildTypeIndex = (entry?.data as? Int) ?: BackendPreferences.BUILD_TYPE_RELEASE
    updatePreference(preference)
  }

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { updatePreference(it) }
  }

  private fun updatePreference(preference: Preference) {
    preference.summary = preference.context.getString(
      string.idepref_backend_current, typeName(buildTypeIndex))
  }

  private fun typeName(type: Int): String = when (type) {
    BackendPreferences.BUILD_TYPE_DEBUG -> "Debug"
    BackendPreferences.BUILD_TYPE_REL_WITH_DEB_INFO -> "RelWithDebInfo"
    BackendPreferences.BUILD_TYPE_MIN_SIZE_REL -> "MinSizeRel"
    else -> "Release"
  }
}
