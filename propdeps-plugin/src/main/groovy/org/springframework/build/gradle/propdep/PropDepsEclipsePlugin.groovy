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
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.*
import org.gradle.plugins.ide.eclipse.EclipsePlugin;

/**
 * Plugin to allow optional and provided dependency configurations to work with the
 * standard gradle 'eclipse' plugin
 *
 * @author Phillip Webb
 */
class PropDepsEclipsePlugin implements Plugin<Project> {

	public void apply(Project project) {
		project.plugins.apply(PropDepsPlugin)
		project.plugins.apply(EclipsePlugin)

		// Include the provided and optional configurations
		def configurations = [project.configurations.provided, project.configurations.optional];
		project.eclipse.classpath {
			plusConfigurations += configurations
		}

		// Ensure only the compile configuration is exported
		project.eclipse.classpath.file {
			whenMerged { classpath ->
				def resolved = project.configurations.compile.resolve()
				classpath.entries.findAll{it.kind == 'lib'}.each {
					it.exported = resolved.contains(project.file(it.path))
				}
			}
		}
	}
}
