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

package com.itsaky.androidide.editor.schemes.internal.parser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Properties

/**
 * Parses the color schemes bundled in `assets/editor/schemes` so that a broken file reference
 * (e.g. a deleted language scheme) fails the build instead of the runtime scheme loader.
 */
@RunWith(RobolectricTestRunner::class)
class BundledSchemesTest {

  private val schemesDir = File("./src/main/assets/editor/schemes")

  @Test
  fun `bundled schemes parse and support the registered tree sitter languages`() {
    val schemeDirs = schemesDir.listFiles { file -> file.isDirectory }
    assertThat(schemeDirs).isNotNull()
    assertThat(schemeDirs!!.map { it.name }).containsAtLeast("default", "default-dark")

    for (dir in schemeDirs) {
      val props = File(dir, "scheme.prop").reader().use { reader ->
        Properties().apply { load(reader) }
      }

      val name = props.getProperty("scheme.name")
      val file = props.getProperty("scheme.file")
      assertThat(name).isNotEmpty()
      assertThat(file).isNotEmpty()

      val scheme = SchemeParser { File(dir, it) }
        .parse(File(dir, file), name, props.getProperty("scheme.isDark", "false").toBoolean())

      for (type in listOf("kt", "kts", "json", "log")) {
        assertWithMessage("%s supports '%s'", dir.name, type)
          .that(scheme.getLanguageScheme(type))
          .isNotNull()
      }
    }
  }
}
