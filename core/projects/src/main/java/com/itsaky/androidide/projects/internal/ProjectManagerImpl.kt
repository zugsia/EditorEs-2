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

package com.itsaky.androidide.projects.internal

import androidx.annotation.RestrictTo
import com.google.auto.service.AutoService
import com.itsaky.androidide.eventbus.events.EventReceiver
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.projects.CppModule
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.IWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.stream.Collectors
import kotlin.io.path.isDirectory
import kotlin.io.path.name

@AutoService(IProjectManager::class)
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class ProjectManagerImpl : IProjectManager, EventReceiver {

  private var _workspace: WorkspaceImpl? = null
  private var _projectDir: File? = null

  override var projectInitialized: Boolean = false
    private set

  override val projectDir: File
    get() = checkNotNull(_projectDir) {
      "Cannot get project directory. Path has not been set."
    }

  override fun getWorkspace(): IWorkspace? {
    return _workspace
  }

  override fun openProject(directory: File) {
    this._projectDir = directory.canonicalFile
  }

  override suspend fun setupProject() = withContext(Dispatchers.IO) {
    val dir = projectDir
    _workspace = WorkspaceImpl(dir, discoverModules(dir))
    projectInitialized = true
    log.info("Workspace ready with {} module(s)", _workspace!!.getModules().size)
  }

  override fun destroy() {
    log.info("Destroying project manager")

    this._workspace = null
    this._projectDir = null
    this.projectInitialized = false
  }

  override fun notifyFileCreated(file: File) {
    rescanWorkspace()
  }

  override fun notifyFileDeleted(file: File) {
    rescanWorkspace()
  }

  override fun notifyFileRenamed(from: File, to: File) {
    rescanWorkspace()
  }

  @Suppress("unused", "UNUSED_PARAMETER")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileCreated(event: FileCreationEvent) {
    rescanWorkspace()
  }

  @Suppress("unused", "UNUSED_PARAMETER")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileDeleted(event: FileDeletionEvent) {
    rescanWorkspace()
  }

  @Suppress("unused", "UNUSED_PARAMETER")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileRenamed(event: FileRenameEvent) {
    rescanWorkspace()
  }

  private fun rescanWorkspace() {
    val dir = _projectDir ?: return
    if (!projectInitialized || !dir.isDirectory) {
      return
    }
    _workspace = WorkspaceImpl(dir, discoverModules(dir))
  }

  private fun discoverModules(dir: File): List<CppModule> {
    val moduleDirs = runCatching {
      Files.walk(dir.toPath(), MAX_SCAN_DEPTH).use { stream ->
        stream.filter { it.fileName.name == CMAKE_LISTS && it.parent != null }
          .map { it.parent.toFile().canonicalFile }
          .filter { it.absolutePath.startsWith(dir.canonicalPath) }
          .distinct()
          .limit(MAX_MODULES.toLong())
          .collect(Collectors.toList())
      }
    }.getOrDefault(emptyList())

    if (moduleDirs.isEmpty()) {
      return listOf(CppModule(dir.canonicalFile))
    }

    return moduleDirs.map { CppModule(it) }.sortedBy { it.dir.absolutePath }
  }

  companion object {
    private val log = LoggerFactory.getLogger(ProjectManagerImpl::class.java)

    private const val CMAKE_LISTS = "CMakeLists.txt"
    private const val MAX_SCAN_DEPTH = 8
    private const val MAX_MODULES = 64

    @JvmStatic
    fun getInstance(): ProjectManagerImpl = IProjectManager.getInstance() as ProjectManagerImpl
  }
}
