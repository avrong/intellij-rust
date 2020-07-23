/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.clion

import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.ide.util.projectWizard.AbstractNewProjectStep
import com.intellij.ide.util.projectWizard.CustomStepProjectGenerator
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.welcomeScreen.AbstractActionWithPanel
import com.intellij.openapiext.isHeadlessEnvironment
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.platform.DirectoryProjectGeneratorBase
import com.intellij.platform.ProjectGeneratorPeer
import com.jetbrains.cidr.cpp.cmake.projectWizard.generators.CLionProjectGenerator
import org.rust.ide.icons.RsIcons
import org.rust.ide.newProject.ConfigurationData
import org.rust.ide.newProject.RsPackageNameValidator
import org.rust.ide.newProject.RsProjectGeneratorPeer
import org.rust.ide.newProject.RsProjectSettingsStep
import org.rust.openapiext.computeWithCancelableProgress
import java.io.File
import javax.swing.Icon

class RsCLionProjectGenerator : CLionProjectGenerator<ConfigurationData>() {

    private var peer: RsProjectGeneratorPeer? = null

    override fun getName() = "Rust Package"
    override fun getGroupName() = "Rust"
    override fun getLogo() = RsIcons.RUST

    override fun createPeer(): ProjectGeneratorPeer<ConfigurationData> = RsProjectGeneratorPeer().also { peer = it }

    override fun validate(baseDirPath: String): ValidationResult {
        val crateName = File(baseDirPath).nameWithoutExtension
        val isBinary = peer?.settings?.createBinary == true
        val message = RsPackageNameValidator.validate(crateName, isBinary) ?: return ValidationResult.OK
        return ValidationResult(message)
    }

    override fun generateProject(project: Project, baseDir: VirtualFile, data: ConfigurationData, module: Module) {
        val (settings, createBinary) = data
        val generatedFiles = project.computeWithCancelableProgress("Generating Cargo project...") {
            settings.toolchain?.rawCargo()?.init(project, module, baseDir, createBinary)
        } ?: return

        // Open new files
        if (!isHeadlessEnvironment) {
            val navigation = PsiNavigationSupport.getInstance()
            navigation.createNavigatable(project, generatedFiles.manifest, -1).navigate(false)
            for (file in generatedFiles.sourceFiles) {
                navigation.createNavigatable(project, file, -1).navigate(true)
            }
        }
    }
}
