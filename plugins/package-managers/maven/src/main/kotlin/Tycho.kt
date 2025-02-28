/*
 * Copyright (C) 2025 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.packagemanagers.maven

import java.io.File
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicReference

import org.apache.logging.log4j.kotlin.logger
import org.apache.maven.AbstractMavenLifecycleParticipant
import org.apache.maven.cli.MavenCli
import org.apache.maven.execution.MavenSession
import org.apache.maven.project.MavenProject

import org.codehaus.plexus.DefaultPlexusContainer
import org.codehaus.plexus.PlexusContainer
import org.codehaus.plexus.classworlds.ClassWorld
import org.codehaus.plexus.logging.BaseLoggerManager

import org.eclipse.aether.graph.DependencyNode

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.analyzer.ProjectResults
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.utils.DependencyGraphBuilder
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.DependencyTreeLogger
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.LocalProjectWorkspaceReader
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.MavenDependencyHandler
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.MavenLogger
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.MavenSupport
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.identifier
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.internalId
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.isTychoProject
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.parseDependencyTree
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.toOrtProject
import org.ossreviewtoolkit.utils.ort.createOrtTempFile

/**
 * A package manager implementation supporting Maven projects using [Tycho](https://github.com/eclipse-tycho/tycho).
 */
class Tycho(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, "Tycho", analysisRoot, analyzerConfig, repoConfig) {
    class Factory : AbstractPackageManagerFactory<Tycho>("Tycho") {
        override val globsForDefinitionFiles = listOf("pom.xml")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Tycho(type, analysisRoot, analyzerConfig, repoConfig)
    }

    /**
     * The builder to generate the dependency graph. This could actually be a local variable, but it is also needed
     * to construct the final [PackageManagerResult].
     */
    private lateinit var graphBuilder: DependencyGraphBuilder<DependencyNode>

    override fun mapDefinitionFiles(definitionFiles: List<File>): List<File> = definitionFiles.filter(::isTychoProject)

    override fun resolveDependencies(definitionFile: File, labels: Map<String, String>): List<ProjectAnalyzerResult> {
        logger.info { "Resolving Tycho dependencies for $definitionFile." }

        val collector = TychoProjectsCollector()
        val (_, buildLog) = runBuild(collector, definitionFile.parentFile)
        // TODO: Create issues for a failed build and projects for which dependencies could not be resolved.

        val resolvedProjects = createMavenSupport(collector).use { mavenSupport ->
            val dependencyHandler =
                MavenDependencyHandler(managerName, projectType, mavenSupport, collector.mavenProjects, false)

            graphBuilder = DependencyGraphBuilder(dependencyHandler)

            buildLog.inputStream().use { stream ->
                parseDependencyTree(stream, collector.mavenProjects.values).map { projectNode ->
                    val project = collector.mavenProjects.getValue(projectNode.artifact.identifier())
                    processProjectDependencies(graphBuilder, project, projectNode.children)
                    project
                }.toList()
            }
        }

        buildLog.delete()

        return resolvedProjects.map { mavenProject ->
            val projectId = mavenProject.identifier(projectType)
            val project = mavenProject.toOrtProject(
                projectId,
                mavenProject.file,
                mavenProject.file.parentFile,
                graphBuilder.scopesFor(projectId)
            )
            ProjectAnalyzerResult(project, emptySet())
        }
    }

    override fun createPackageManagerResult(projectResults: ProjectResults): PackageManagerResult =
        PackageManagerResult(projectResults, graphBuilder.build(), graphBuilder.packages())

    /**
     * Create the [MavenCli] instance to trigger a build on the analyzed project. Register the given [collector], so
     * that it is invoked during the build. Also install a specific logger that forwards the output of the dependency
     * tree plugin to the given [outputStream].
     */
    private fun createMavenCli(collector: TychoProjectsCollector, outputStream: PrintStream): MavenCli =
        object : MavenCli(ClassWorld("plexus.core", javaClass.classLoader)) {
            override fun customizeContainer(container: PlexusContainer) {
                container.addComponent(
                    collector,
                    AbstractMavenLifecycleParticipant::class.java,
                    "TychoProjectsCollector"
                )

                (container as? DefaultPlexusContainer)?.loggerManager = object : BaseLoggerManager() {
                    override fun createLogger(name: String): org.codehaus.plexus.logging.Logger =
                        if (DEPENDENCY_TREE_LOGGER == name) {
                            DependencyTreeLogger(outputStream)
                        } else {
                            MavenLogger(logger.delegate.level)
                        }
                }
            }
        }

    /**
     * Run a Maven build on the Tycho project in [projectRoot] utilizing the given [collector]. Return a pair with
     * the exit code of the Maven project and a [File] that contains the output generated during the build.
     */
    private fun runBuild(collector: TychoProjectsCollector, projectRoot: File): Pair<Int, File> {
        // The Maven CLI seems to change the context class loader. This has side effects on ORT's plugin mechanism.
        // To prevent this, store the class loader and restore it at the end of this function.
        val tccl = Thread.currentThread().contextClassLoader

        try {
            val buildLog = createOrtTempFile()

            val exitCode = PrintStream(buildLog.outputStream()).use { out ->
                val cli = createMavenCli(collector, out)

                // With the current CLI API, there does not seem to be another way to set the build root folder than
                // using a system property.
                System.setProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY, projectRoot.absolutePath)

                cli.doMain(
                    // The "package" goal is required; otherwise the Tycho extension is not activated.
                    arrayOf("package", "dependency:tree", "-DoutputType=json"),
                    projectRoot.path,
                    null,
                    null
                ).also { logger.info { "Tycho analysis completed. Exit code: $it." } }
            }

            return exitCode to buildLog
        } finally {
            Thread.currentThread().contextClassLoader = tccl
        }
    }

    /**
     * Create a [MavenSupport] instance to be used for resolving the packages found during the Maven build. Obtain
     * the local projects from the given [collector]
     */
    private fun createMavenSupport(collector: TychoProjectsCollector): MavenSupport {
        val localProjects = collector.mavenProjects
        val resolveFunc: (String) -> File? = { projectId -> localProjects[projectId]?.file }

        return MavenSupport(LocalProjectWorkspaceReader(resolveFunc))
    }

    /**
     * Process the [dependencies] of the given [project] by adding them to the [graphBuilder].
     */
    private fun processProjectDependencies(
        graphBuilder: DependencyGraphBuilder<DependencyNode>,
        project: MavenProject,
        dependencies: Collection<DependencyNode>
    ) {
        val projectId = project.identifier(projectType)

        dependencies.forEach { node ->
            graphBuilder.addDependency(DependencyGraph.qualifyScope(projectId, node.dependency.scope), node)
        }
    }
}

/** The name of the logger used by the Maven dependency tree plugin. */
private const val DEPENDENCY_TREE_LOGGER = "org.apache.maven.plugins.dependency.tree.TreeMojo"

/**
 * An internal helper class that gets registered as a Maven lifecycle participant to obtain all [MavenProject]s
 * encountered during the build.
 */
internal class TychoProjectsCollector : AbstractMavenLifecycleParticipant() {
    /**
     * Stores the projects that have been found during the Maven build. To be on the safe side with regard to
     * possible threading issues, use an [AtomicReference] to ensure safe publication.
     */
    private val projects = AtomicReference<Map<String, MavenProject>>(emptyMap())

    /**
     * Return the projects that have been found during the Maven build.
     */
    val mavenProjects: Map<String, MavenProject>
        get() = projects.get()

    override fun afterSessionEnd(session: MavenSession) {
        val builtProjects = session.projects.associateBy(MavenProject::internalId)
        projects.set(builtProjects)

        logger.info { "Found ${builtProjects.size} projects during build." }
    }
}
