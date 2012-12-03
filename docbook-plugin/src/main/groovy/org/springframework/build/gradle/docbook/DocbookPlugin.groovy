/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.build.gradle.docbook

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.artifacts.Configuration.State
import org.gradle.api.file.FileTree
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.internal.ConventionTask
import org.gradle.api.internal.project.taskfactory.ITaskFactory
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.TaskAction


/**
 * Gradle plugin that can be used to generate DocBook output.
 *
 * @author Chris Beams
 * @author Phillip Webb
 */
class DocbookPlugin implements Plugin<Project> {

	@Override
	public void apply(Project project) {
		println project
		project.extensions.create("docbook", DocbookPluginExtension)
		project.setProperty("DocbookTask", DocbookTask.class)
	}
}

/**
 * Docbook Task, see {@link DocbookPlugin} for details.
 */
class DocbookTask extends ConventionTask {

	/**
	 * The output directory for this task. If not specified defaults to the extension
	 * setting.
	 */
	Object outputDir = project.docbook.outputDir

	/**
	 * Enable syntax highlighting when processing docbook files. Default is the extension
	 * setting.
	 */
	boolean syntaxHighlighting = project.docbook.syntaxHighlighting

	Object stylesheet

	String outputName = "index.html"

	String fopMimeType = null

	DocbookTask() {
		if(outputDir == null) {
			outputDir = new File(project.buildDir, "reference")
		}
		outputDir = project.file(outputDir);
	}

	@TaskAction
	void docbook() {
		unpackDocbook()
		logConsoleOutput()
	}

	/**
	 * Unpack the embedded docbook xsl (only no other docbook dependency has created
	 * the docbook folder).
	 */
	private void unpackDocbook() {
		if(!new File(outputDir, "docbook").exists()) {
			Configuration classpathConfiguration = project.buildscript.configurations.classpath
			def classpathArtifacts = classpathConfiguration.resolvedConfiguration.resolvedArtifacts
			classpathArtifacts.find{ it.classifier == "docbook" }.each {
				unpack(it.file)
			}
			unpack(classpathArtifacts.find{ it.name == "docbook-xsl" }.file)
		}
	}

	/**
	 * Unpack a single zip file, ever overwiting any existing data.
	 */
	private void unpack(File file) {
		FileTree zip = project.zipTree(file)
		zip.visit{ FileVisitDetails f ->
			File target = new File(outputDir, f.path)
			if(!target.exists()) {
				f.copyTo(target)
			}
		}
	}

	private void logConsoleOutput() {
		def detailedLogging = [LogLevel.DEBUG, LogLevel.INFO] as Set
		if(!detailedLogging.contains(project.gradle.startParameter.logLevel)) {
			logging.captureStandardOutput(LogLevel.INFO)
			logging.captureStandardError(LogLevel.INFO)
		}
	}
}

/**
 * Docbook extension used to define default settings.
 */
class DocbookPluginExtension {

	/**
	 * The work directory used to store temporary docbook artifacts. Default is
	 * 'build/docbook-work'.
	 */
	Object workDir

	/**
	 * The ultimate output directory. Default is 'build/reference'
	 */
	Object outputDir

	/**
	 * Enable syntax highlighting when processing docbook files. Default is true.
	 */
	boolean syntaxHighlighting = true
}
