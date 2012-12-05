/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.build.gradle.propdep

import org.gradle.api.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.UnionFileCollection
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.*

/**
 * Plugin to allow 'optional' and 'provided' dependency configurations
 *
 * @author Phillip Webb
 * @see PropDepsEclipsePlugin
 * @see PropDepsIdeaPlugin
 * @see PropDepsMavenPlugin
 */
class PropDepsPlugin implements Plugin<Project> {

	public void apply(Project project) {
		project.plugins.apply(JavaPlugin)

		def provided = addConfiguration(project, "provided")
		def optional = addConfiguration(project, "optional")

		JavaPluginConvention javaConvention = project.convention.plugins["java"]
		SourceSetContainer sourceSets = javaConvention.sourceSets
		addToSourceSet(sourceSets.getByName("main"), provided, optional)
		addToSourceSet(sourceSets.getByName("test"), provided, optional)
	}

	private Configuration addConfiguration(Project project, String name) {
		Configuration configuration = project.configurations.add(name)
		configuration.transitive = false
		configuration.visible = false
		return configuration
	}

	private addToSourceSet(SourceSet sourceSet, FileCollection... configurations) {
		sourceSet.compileClasspath = new UnionFileCollection(
			[sourceSet.compileClasspath] + configurations)

	}
}
