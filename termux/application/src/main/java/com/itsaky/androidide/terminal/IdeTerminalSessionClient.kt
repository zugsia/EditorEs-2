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

package com.itsaky.androidide.terminal

import com.itsaky.androidide.activities.TerminalActivity
import com.itsaky.androidide.backend.proot.ProotConfig
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.terminal.TerminalSessionClient

/**
 * [TerminalSessionClient] delegate for AndroidIDE.
 *
 * @author Akash Yadav
 */
class IdeTerminalSessionClient(
  activity: TerminalActivity
) : TermuxTerminalSessionActivityClient(activity) {

  override fun addNewSession(isFailSafe: Boolean, sessionName: String?, workingDirectory: String?) {
    val activity = mActivity
    val service = activity?.termuxService
    if (!isFailSafe && activity != null && service != null &&
      ProotConfig.isInstalled(activity) && ProotConfig.isAvailable(activity)
    ) {
      var cwd = workingDirectory
      if (cwd == null) {
        val current = activity.currentSession
        cwd = current?.cwd ?: activity.properties.defaultWorkingDirectory
      }
      // Supplementary GIDs (e.g. external storage) may be granted after the rootfs was installed;
      // register them so Ubuntu's bash.bashrc `groups` call can resolve every group name.
      ProotConfig.registerAndroidIds(activity)
      val fullArgs = ProotConfig.prootArgs(activity)
      val session = service.createTermuxSession(
        ProotConfig.prootBinary(activity),
        fullArgs.drop(1).toTypedArray(),
        null,
        cwd,
        false,
        sessionName
      ) ?: return
      setCurrentSession(session.terminalSession)
      activity.drawer.closeDrawers()
      return
    }
    super.addNewSession(isFailSafe, sessionName, workingDirectory)
  }
}