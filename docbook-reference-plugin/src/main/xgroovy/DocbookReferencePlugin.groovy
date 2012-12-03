/*
 * Copyright 2002-2011 the original author or authors.
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
import java.util.zip.*

import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.*
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

import org.apache.fop.apps.*
import org.apache.xml.resolver.CatalogManager
import org.apache.xml.resolver.tools.CatalogResolver
import org.gradle.api.*
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.*
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource
import org.xml.sax.XMLReader

import com.icl.saxon.TransformerFactoryImpl


/**
 * Gradle plugin that can be used to generate docbook reference guides.
 *
 * @author Chris Beams
 * @author Phillip Webb
 */
class DocbookReferencePlugin implements Plugin<Project> {

	def void apply(Project project) {

		// Apply base for the 'clean' task
		project.plugins.apply('base')

		def docbookTasks = [
			project.tasks.add("referenceHtmlMulti", HtmlMultiDocbookReferenceTask),
			project.tasks.add("referenceHtmlSingle", HtmlSingleDocbookReferenceTask),
			project.tasks.add("referencePdf", PdfDocbookReferenceTask)
		]

		def reference = project.tasks.add("reference") {
			group = 'Documentation'
			description = "Generates HTML and PDF reference documentation."
			dependsOn(docbookTasks)

			ext {
				sourceDir = null // e.g. new File('src/reference')
				outputDir = new File(project.buildDir, "reference")
				pdfFilename = "${project.rootProject.name}-reference.pdf"
			}

			outputs.dir(outputDir)
		}

		project.gradle.taskGraph.whenReady {
			docbookTasks.each() { docbookTask ->
				if(docbookTask.sourceDir == null) {
					docbookTask.sourceDir = reference.sourceDir
				}
				if(docbookTask.outputDir == null) {
					docbookTask.outputDir = reference.outputDir
				}
			}
		}
	}
}

/**
 * Base class of all dobook tasks.
 */
abstract class AbstractDocbookReferenceTask extends DefaultTask {

	/**
	 * The source directory, if not explicitly set will default to the value set
	 * in the reference task.
	 */
	@InputDirectory
	File sourceDir

	/**
	 * The source docbook XML
	 */
	@Input
	String sourceFileName = 'index.xml';

	@OutputDirectory
	File outputDir = new File(project.getBuildDir(), "reference");

	/**
	 * The name of the XSLT that should be applied.
	 */
	String stylesheet;

	String xdir;

	@TaskAction
	public final void transform() {
		// the docbook tasks issue spurious content to the console. redirect to INFO level
		// so it doesn't show up in the default log level of LIFECYCLE unless the user has
		// run gradle with '-d' or '-i' switches -- in that case show them everything
		switch (project.gradle.startParameter.logLevel) {
			case LogLevel.DEBUG:
			case LogLevel.INFO:
				break;
			default:
				logging.captureStandardOutput(LogLevel.INFO)
				logging.captureStandardError(LogLevel.INFO)
		}

		// TODO call only once
		sourceDir = filterDocbookSources(sourceDir)
		unpack()

		SAXParserFactory factory = new org.apache.xerces.jaxp.SAXParserFactoryImpl();
		factory.setXIncludeAware(true);
		outputDir.mkdirs();

		File srcFile = new File(sourceDir, sourceFileName);
		String outputFilename = srcFile.getName().substring(0, srcFile.getName().length() - 4) + '.' + this.getExtension();

		File oDir = new File(outputDir, xdir)
		File outputFile = new File(oDir, outputFilename);

		Result result = new StreamResult(outputFile.getAbsolutePath());
		CatalogResolver resolver = new CatalogResolver(createCatalogManager());
		InputSource inputSource = new InputSource(srcFile.getAbsolutePath());

		XMLReader reader = factory.newSAXParser().getXMLReader();
		reader.setEntityResolver(resolver);
		TransformerFactory transformerFactory = new TransformerFactoryImpl();
		transformerFactory.setURIResolver(resolver);

		def File stylesheetFile = new File(new File(sourceDir, "xsl"), stylesheet);
		if(!stylesheetFile.exists()) {
			stylesheetFile = new File("${project.buildDir}/docbook-resources/xsl/${stylesheet}");
		}
		URL url = stylesheetFile.toURI().toURL();
		Source source = new StreamSource(url.openStream(), url.toExternalForm());
		Transformer transformer = transformerFactory.newTransformer(source);

		transformer.setParameter("highlight.source", "1");
		transformer.setParameter("highlight.xslthl.config", new File("${project.buildDir}/docbook-resources/highlighting", "xslthl-config.xml").toURI().toURL());

		preTransform(transformer, srcFile, outputFile);

		transformer.transform(new SAXSource(reader, inputSource), result);

		postTransform(outputFile);
	}

	abstract protected String getExtension()

	protected void preTransform(Transformer transformer, File sourceFile, File outputFile) {
	}

	protected void postTransform(File outputFile) {
		copyImagesAndCss(project, xdir)
	}

	/**
	 * @param sourceDir directory of unfiltered sources
	 * @return directory of filtered sources
	 */
	private File filterDocbookSources(File sourceDir) {
		def workDir = new File("${project.buildDir}/reference-work");
		workDir.mkdirs();

		// copy everything but index.xml
		project.copy {
				into(workDir)
				from(sourceDir) { exclude '**/index.xml' }
		}

		// copy index.xml and expand ${...} variables along the way
		// e.g.: ${version} needs to be replaced in the header
		project.copy {
				into(workDir)
				from(sourceDir) { include '**/index.xml' }
				expand(version: "${project.version}")
		}

		// Copy and process any custom titlepages
		def titlePageWorkDir = new File(new File(workDir, "xsl"), "titlepage");
		titlePageWorkDir.mkdirs();
		Transformer transformer = new TransformerFactoryImpl().newTransformer(
				new StreamSource(this.class.classLoader.getResourceAsStream("docbook/template/titlepage.xsl")));
		transformer.setParameter("ns", "http://www.w3.org/1999/xhtml");
		new File(sourceDir, "titlepage").eachFileMatch( ~/.*\.xml/, { f ->
				File output = new File(titlePageWorkDir, f.name.replace(".xml", ".xsl"))
				transformer.transform(new StreamSource(f), new StreamResult(output));
				// Ugly hack to work around Java XSLT bug
				output.setText(output.text.replaceFirst("xsl:stylesheet", "xsl:stylesheet xmlns:exsl=\"http://exslt.org/common\" "));
		})

		return workDir;
	}

	private void unpack() {
		def resourcesZipPath = 'META-INF/docbook-resources.zip'
		def resourcesZip = this.class.classLoader.getResource(resourcesZipPath)
		if (resourcesZip == null) {
			throw new GradleException("could not find ${resourcesZipPath} on the classpath");
		}
		// the file is a jar:file - write it to disk first
		def zipInputStream = resourcesZip.getContent()
		def zipFile = new File("${project.buildDir}/docbook-resources.zip")
		copyFile(zipInputStream, zipFile)
		project.copy {
			from project.zipTree(zipFile)
			into "${project.buildDir}/docbook-resources"
		}
	}

	private void copyFile(InputStream source, File destFile) {
		destFile.createNewFile();
		FileOutputStream to = null;
		try {
			to = new FileOutputStream(destFile);
			byte[] buffer = new byte[4096];
			int bytesRead;

			while ((bytesRead = source.read(buffer)) > 0) {
				to.write(buffer, 0, bytesRead);
			}
		} finally {
			if (source != null) {
				source.close();
			}
			if (to != null) {
				to.close();
			}
		}
	}

	// for some reason, statically typing the return value leads to the following
	// error when Gradle tries to subclass the task class at runtime:
	// java.lang.NoClassDefFoundError: org/apache/xml/resolver/CatalogManager
	private Object createCatalogManager() {
		CatalogManager manager = new CatalogManager();
		manager.setIgnoreMissingProperties(true);
		ClassLoader classLoader = this.getClass().getClassLoader();
		StringBuilder builder = new StringBuilder();
		String docbookCatalogName = "docbook/catalog.xml";
		URL docbookCatalog = classLoader.getResource(docbookCatalogName);

		if (docbookCatalog == null) {
			throw new IllegalStateException("Docbook catalog " + docbookCatalogName + " could not be found in " + classLoader);
		}

		builder.append(docbookCatalog.toExternalForm());

		Enumeration enumeration = classLoader.getResources("/catalog.xml");
		while (enumeration.hasMoreElements()) {
			builder.append(';');
			URL resource = (URL) enumeration.nextElement();
			builder.append(resource.toExternalForm());
		}
		String catalogFiles = builder.toString();
		manager.setCatalogFiles(catalogFiles);
		return manager;
	}

	private void copyImagesAndCss(def project, def dir) {
		project.copy {
			into "${project.buildDir}/reference/${dir}/images"
			from "${sourceDir}/images" // SI specific
		}
		project.copy {
			into "${project.buildDir}/reference/${dir}/images"
			from "${project.buildDir}/docbook-resources/images" // Common
		}
		project.copy {
			into "${project.buildDir}/reference/${dir}/css"
			from "${project.buildDir}/docbook-resources/css" // Common
		}
	}
}

class HtmlSingleDocbookReferenceTask extends AbstractDocbookReferenceTask {

		public HtmlSingleDocbookReferenceTask() {
			setDescription('Generates single-page HTML reference documentation.')
			stylesheet =  "html-single-custom.xsl";
			xdir = 'htmlsingle'
		}

		@Override
		protected String getExtension() {
			return 'html'
		}
}


class HtmlMultiDocbookReferenceTask extends AbstractDocbookReferenceTask {

		public HtmlMultiDocbookReferenceTask() {
			setDescription('Generates multi-page HTML reference documentation.')
			stylesheet = "html-custom.xsl";
			xdir = 'html'
		}

		@Override
		protected String getExtension() {
			return 'html'
		}

		@Override
		protected void preTransform(Transformer transformer, File sourceFile, File outputFile) {
			String rootFilename = outputFile.getName();
			rootFilename = rootFilename.substring(0, rootFilename.lastIndexOf('.'));
			transformer.setParameter("root.filename", rootFilename);
			transformer.setParameter("base.dir", outputFile.getParent() + File.separator);
		}
}


class PdfDocbookReferenceTask extends AbstractDocbookReferenceTask {

	String admonGraphicsPath

	public PdfDocbookReferenceTask() {
		setDescription('Generates PDF reference documentation.')
		stylesheet = "pdf-custom.xsl"
		xdir = 'pdf'
		admonGraphicsPath = "${project.buildDir}/docbook-resources/images/admon/"
	}

	@Override
	protected String getExtension() {
		return 'fo'
	}

	@Override
	protected void preTransform(Transformer transformer, File sourceFile, File outputFile) {
		transformer.setParameter("admon.graphics", "1");
		transformer.setParameter("admon.graphics.path", admonGraphicsPath);
	}

	/**
	 * <a href="http://xmlgraphics.apache.org/fop/0.95/embedding.html#render">From the FOP usage guide</a>
	 */
	@Override
	protected void postTransform(File foFile) {
		FopFactory fopFactory = FopFactory.newInstance();

		OutputStream out = null;
		final File pdfFile = getPdfOutputFile(foFile);
		logger.debug("Transforming 'fo' file " + foFile + " to PDF: " + pdfFile);

		try {
			out = new BufferedOutputStream(new FileOutputStream(pdfFile));

			Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, out);

			TransformerFactory factory = TransformerFactory.newInstance();
			Transformer transformer = factory.newTransformer();

			Source src = new StreamSource(foFile);

			Result res = new SAXResult(fop.getDefaultHandler());

			switch (project.gradle.startParameter.logLevel) {
				case LogLevel.DEBUG:
				case LogLevel.INFO:
					break;
				default:
					// only show verbose fop output if the user has specified 'gradle -d' or 'gradle -i'
					LoggerFactory.getILoggerFactory().getLogger('org.apache.fop').level =
					LoggerFactory.getILoggerFactory().getLogger('ROOT').level.class.ERROR
			}

			transformer.transform(src, res);
		} finally {
			if (out != null) {
				out.close();
			}
		}

		if (!foFile.delete()) {
			logger.warn("Failed to delete 'fo' file " + foFile);
		}
	}

	private File getPdfOutputFile(File foFile) {
		return new File(foFile.parent, project.reference.pdfFilename)
	}
}

