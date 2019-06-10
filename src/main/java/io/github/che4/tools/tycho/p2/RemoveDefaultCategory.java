package io.github.che4.tools.tycho.p2;



import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.io.RawInputStreamFacade;
import org.eclipse.tycho.PackagingType;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.core.ArtifactDependencyVisitor;
import org.eclipse.tycho.core.FeatureDescription;
import org.eclipse.tycho.core.TychoProject;
import org.eclipse.tycho.core.osgitools.EclipseRepositoryProject;
import org.eclipse.tycho.model.FeatureRef;
import org.eclipse.tycho.model.UpdateSite;
import org.eclipse.tycho.model.UpdateSite.SiteFeatureRef;
import org.eclipse.tycho.packaging.AbstractTychoPackagingMojo;
import org.eclipse.tycho.packaging.UpdateSiteAssembler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@Mojo(name = "generate-p2-repository", defaultPhase = LifecyclePhase.PACKAGE)
public class RemoveDefaultCategory extends AbstractTychoPackagingMojo {
	
	private enum ReferenceStrategy {
		embedReferences,
		compositeReferences
	}
	
	public static final Set<String> defaultSystemProperties = new HashSet<String>(Arrays.asList(new String[] {
			// these are all parameters of the Jenkins job; if not set they'll be null
			// TODO should default to null or "" ?
			"BUILD_ALIAS",
			"JOB_NAME",
			"BUILD_NUMBER",
			"RELEASE",
			"ZIPSUFFIX",
			"TARGET_PLATFORM_VERSION",
			"TARGET_PLATFORM_VERSION_MAXIMUM",
			"NODE_NAME", // The name of the node the current build is running on
			
			// these are environment variables so should be valid when run in Jenkins or for local builds
			"HOSTNAME", // replaces HUDSON_SLAVE: more portable & means the same thing
			"WORKSPACE", // likely the same as user.dir, unless -DWORKSPACE= used to override
			"os.name",
			"os.version",
			"os.arch",
			"java.vendor",
			"java.version",
			"user.dir"
		}));

	@Parameter(defaultValue="embedReferences")
	private ReferenceStrategy referenceStrategy;

	/**
	 * template folder for HTML contents
	 */
	@Parameter
	private File siteTemplateFolder;

	/**
	 * name of the file in ${siteTemplateFolder} to use as template for
	 * index.html
	 */
	@Parameter(defaultValue = "index.html")
	private String indexName;
	/**
	 * name of the file in ${siteTemplateFolder}/web to use for CSS
	 */
	@Parameter(defaultValue = "site.css")
	private String cssName;
	
	@Parameter
	private Map<String, String> symbols;
	
	@Parameter
	private Set<String> systemProperties;
	
	private File categoryFile;
	

	/**
	 * Whether to skip generation of index.html and associated files
	 */
	@Parameter(defaultValue = "false")
	private boolean skipWebContentGeneration;
	
	/**
	 * Additional files to add to repo and that are not in the
	 * "siteTemplateFolder". These can be folders.
	 */
	@Parameter
	private List<File> additionalWebResources;
	/**
	 * Additional sites to add to repo associateSites
	 */
	@Parameter
	private List<String> associateSites;
	
	/**
	 * Whether to remove or not the "Uncategorized" default category
	 */
	@Parameter(defaultValue = "false")
	private boolean removeDefaultCategory;
	/**
	 * Search p2 IU (installable unit) that contains <tt>defaultCategoryPattern</tt> in ID.
	 * The parameter is only evaluated if <tt>removeDefaultCategory</tt> is <tt>true</tt>.
	 * <p>
	 * By default update site generates category named <tt>Uncategorized</tt> that may be unwanted. If update site contains features or plugins, then
	 * that category ID is randomly generated with <tt>.Default</tt> suffix &ndash; <tt>js72gsl234.Default</tt>. But when update site contains only categories tree
	 * the generated ID is <tt>Default</tt> - in that case set <tt>defaultCategoryPattern</tt> to <tt>Default</tt>
	 * </p>
	 */
	@Parameter(defaultValue = ".Default")
	private String defaultCategoryPattern;
	

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (!PackagingType.TYPE_ECLIPSE_REPOSITORY.equals(this.project.getPackaging())) {
			return;
		}
		if (systemProperties == null) {
			systemProperties = defaultSystemProperties;
		}
		this.categoryFile = new File(project.getBasedir(), "category.xml");
		if (!this.categoryFile.isFile()) {
			// happens in case of definition based on .product
			return;
		}

		if (this.symbols == null) {
			this.symbols = new HashMap<String, String>();
		}
		// TODO populate default symbols: ${update.site.name} &
		// ${update.site.description}

		File outputRepository = new File(this.project.getBuild().getDirectory(), "repository");
		File buildinfoFolder = new File(this.project.getBuild().getDirectory(), "buildinfo");

		// If a siteTemplateFolder is set, pull index.html and site.css from
		// there; otherwise use defaults
		if (!skipWebContentGeneration) {
			try {
				copyTemplateResources(outputRepository);
			} catch (Exception ex) {
				throw new MojoExecutionException("Error while copying siteTemplateFolder content to " + outputRepository, ex);
			}
			if (this.additionalWebResources != null) {
				for (File resource : this.additionalWebResources) {
					try {
						if (resource.isDirectory()) {
							FileUtils.copyDirectoryStructure(resource, new File(outputRepository, resource.getName()));
						} else if (resource.isFile()) {
							FileUtils.copyFile(resource, new File(outputRepository, resource.getName()));
						}
					} catch (Exception ex) {
						throw new MojoExecutionException("Error while copying resource " + resource.getPath(), ex);
					}
				}
			}

			File outputCategoryXml = generateCategoryXml(outputRepository);
			if (new File(outputRepository, "features").isDirectory()) { //$NON-NLS-1$
				generateSiteProperties(outputRepository, outputCategoryXml);
			}
			generateWebStuff(outputRepository, outputCategoryXml);
		}
		try {
			alterContentJar(outputRepository);
		} catch (Exception ex) {
			throw new MojoExecutionException("Error while altering content.jar", ex);
		}

		File repoZipFile = new File(this.project.getBuild().getDirectory(), this.project.getArtifactId() + "-" + this.project.getVersion() + ".zip");
		repoZipFile.delete();
		ZipArchiver archiver = new ZipArchiver();
		archiver.setDestFile(repoZipFile);
		archiver.setForced(true);
		archiver.addDirectory(outputRepository);
		try {
			archiver.createArchive();
		} catch (IOException ex) {
			throw new MojoFailureException("Could not create " + repoZipFile.getName(), ex);
		}

		
	}
	
	
	
	
	
	private void copyTemplateResources(File outputSite) throws IOException, MojoExecutionException {
		getLog().debug("Using outputSite = " + outputSite);
		getLog().debug("Using siteTemplateFolder = " + this.siteTemplateFolder);
		if (this.siteTemplateFolder != null) {
			if (!this.siteTemplateFolder.isDirectory()) {
				throw new MojoExecutionException("'siteTemplateFolder' not correctly set. " + this.siteTemplateFolder.getAbsolutePath() + " is not a directory");
			}
			if (!outputSite.isDirectory())
			{
				outputSite.mkdirs();
			}
			FileUtils.copyDirectoryStructure(this.siteTemplateFolder, outputSite);

			// verify we have everything we need after copying from the
			// siteTemplateFolder
			if (!new File(outputSite, this.indexName).isFile()) {
				// copy default index
				getLog().warn("No " + this.siteTemplateFolder + "/" + this.indexName + " found; using default.");
				InputStream indexStream = getClass().getResourceAsStream("/index.html");
				FileUtils.copyStreamToFile(new RawInputStreamFacade(indexStream), new File(outputSite, this.indexName));
				indexStream.close();
			}
			File webFolder = new File(outputSite, "web");
			if (!webFolder.exists()) {
				webFolder.mkdir();
			}
			if (!new File(webFolder, this.cssName).isFile()) {
				// copy default css
				getLog().warn("No " + webFolder + "/" + this.cssName + " found; using default.");
				InputStream cssStream = getClass().getResourceAsStream("/web/" + this.cssName);
				FileUtils.copyStreamToFile(new RawInputStreamFacade(cssStream), new File(webFolder, this.cssName));
				cssStream.close();
			}
		} else {
			// copy default index
			InputStream indexStream = getClass().getResourceAsStream("/index.html");
			FileUtils.copyStreamToFile(new RawInputStreamFacade(indexStream), new File(outputSite, this.indexName));
			indexStream.close();
			File webFolder = new File(outputSite, "web");
			if (!webFolder.exists()) {
				webFolder.mkdir();
			}
			// copy default css
			InputStream cssStream = getClass().getResourceAsStream("/web/" + this.cssName);
			FileUtils.copyStreamToFile(new RawInputStreamFacade(cssStream), new File(webFolder, this.cssName));
			cssStream.close();
		}
	}
	

	private void generateSiteProperties(File outputRepository, File outputCategoryXml) throws TransformerFactoryConfigurationError, MojoExecutionException {
		// Generate site.properties
		try {
			InputStream siteXsl = getClass().getResourceAsStream("/xslt/site.properties.xsl");
			Source xsltSource = new StreamSource(siteXsl);
			Transformer transformer = TransformerFactory.newInstance().newTransformer(xsltSource);
			FileOutputStream out = new FileOutputStream(new File(outputRepository, "site.properties"));
			Result res = new StreamResult(out);
			transformer.transform(new StreamSource(outputCategoryXml), res);
			siteXsl.close();
			out.close();
		} catch (Exception ex) {
			throw new MojoExecutionException("Error occured while generating 'site.properties'", ex);
		}
	}
	
	private void generateWebStuff(File outputRepository, File outputCategoryXml) throws TransformerFactoryConfigurationError, MojoExecutionException {
		// Generate index.html
		try {
			InputStream siteXsl = getClass().getResourceAsStream("/xslt/site.xsl");
			Source xsltSource = new StreamSource(siteXsl);
			Transformer transformer = TransformerFactory.newInstance().newTransformer(xsltSource);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			Result res = new StreamResult(out);
			transformer.transform(new StreamSource(outputCategoryXml), res);
			siteXsl.close();
			this.symbols.put("${site.contents}", out.toString());
			out.close();
		} catch (Exception ex) {
			throw new MojoExecutionException("Error occured while generating 'site.xsl'", ex);
		}
		try {
			alterIndexFile(outputRepository);
		} catch (Exception ex) {
			throw new MojoExecutionException("Error writing file " + indexName, ex);
		}
	}

	
	/*
	 * This version of category.xml (including feature/bundle versions) is used to generate list of features in site.properties and index.html
	 */
	private File generateCategoryXml(File outputRepository) throws MojoExecutionException {
		// Generate category.xml
		UpdateSite site = null;
		try {
			site = UpdateSite.read(this.categoryFile);
		} catch (IOException ex) {
			throw new MojoExecutionException("Could not read 'category.xml' file", ex);
		}
		new EclipseRepositoryProject().getDependencyWalker(this.project).traverseUpdateSite(site, new ArtifactDependencyVisitor() {
			@Override
			public boolean visitFeature(FeatureDescription feature) {
				FeatureRef featureRef = feature.getFeatureRef();
				String id = featureRef.getId();
				ReactorProject otherProject = feature.getMavenProject();
				String version;
				if (otherProject != null) {
					version = otherProject.getExpandedVersion();
				} else {
					version = feature.getKey().getVersion();
				}
				String url = UpdateSiteAssembler.FEATURES_DIR + id + "_" + version + ".jar";
				((SiteFeatureRef) featureRef).setUrl(url);
				featureRef.setVersion(version);
				return false; // don't traverse included features
			}
		});

		File outputCategoryXml = new File(outputRepository, "category.xml");
		try {
			if (!outputCategoryXml.exists()) {
				outputCategoryXml.createNewFile();
			}
			UpdateSite.write(site, outputCategoryXml);
		} catch (IOException ex) {
			ex.printStackTrace();
			throw new MojoExecutionException("Could not write category.xml to '" + outputCategoryXml.getAbsolutePath() + "'", ex);
		}
		return outputCategoryXml;
	}
	
	/**
	 * Alter content.xml, content.jar, content.xml.xz to:
	 * remove default "Uncategorized" category, 
	 * remove 3rd party associate sites, and 
	 * add associate sites defined in site's pom.xml
	 *
	 * @param p2repository
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws TransformerFactoryConfigurationError
	 * @throws TransformerConfigurationException
	 * @throws TransformerException
	 * @throws MojoFailureException
	 */
	private void alterContentJar(File p2repository) throws FileNotFoundException, IOException, SAXException, ParserConfigurationException, TransformerFactoryConfigurationError,
			TransformerConfigurationException, TransformerException, MojoFailureException {
		File contentJar = new File(p2repository, "content.jar");
		ZipInputStream contentStream = new ZipInputStream(new FileInputStream(contentJar));
		ZipEntry entry = null;
		Document contentDoc = null;
		boolean done = false;
		while (!done && (entry = contentStream.getNextEntry()) != null) {
			if (entry.getName().equals("content.xml")) {
				contentDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(contentStream);
				Element repoElement = (Element) contentDoc.getElementsByTagName("repository").item(0);
				{
					NodeList references = repoElement.getElementsByTagName("references");
					// remove default references
					for (int i = 0; i < references.getLength(); i++) {
						Node currentRef = references.item(i);
						currentRef.getParentNode().removeChild(currentRef);
					}
					// add associateSites
					if (this.associateSites != null && this.associateSites.size() > 0 && this.referenceStrategy == ReferenceStrategy.embedReferences) {
						Element refElement = contentDoc.createElement("references");
						refElement.setAttribute("size", Integer.valueOf(2 * associateSites.size()).toString());
						for (String associate : associateSites) {
							Element rep0 = contentDoc.createElement("repository");
							rep0.setAttribute("uri", associate);
							rep0.setAttribute("url", associate);
							rep0.setAttribute("type", "0");
							rep0.setAttribute("options", "1");
							refElement.appendChild(rep0);
							Element rep1 = (Element) rep0.cloneNode(true);
							rep1.setAttribute("type", "1");
							refElement.appendChild(rep1);
						}
						repoElement.appendChild(refElement);
					}
				}
				// remove default "Uncategorized" category
				if (this.removeDefaultCategory) {
					Element unitsElement = (Element) repoElement.getElementsByTagName("units").item(0);
					NodeList units = unitsElement.getElementsByTagName("unit");
					for (int i = 0; i < units.getLength(); i++) {
						Element unit = (Element) units.item(i);
						String id = unit.getAttribute("id");
						if (id != null && id.contains(defaultCategoryPattern)) {
							unit.getParentNode().removeChild(unit);
							getLog().info("Removed Uncategorized category with ID="+id);
						}
					}
					unitsElement.setAttribute("size", Integer.toString(unitsElement.getElementsByTagName("unit").getLength()));
				}
				done = true;
			}
		}
		// .close and .closeEntry raise exception:
		// https://issues.apache.org/bugzilla/show_bug.cgi?id=3862
		ZipOutputStream outContentStream = new ZipOutputStream(new FileOutputStream(contentJar));
		ZipEntry contentXmlEntry = new ZipEntry("content.xml");
		outContentStream.putNextEntry(contentXmlEntry);
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
		transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
		DOMSource source = new DOMSource(contentDoc);
		StreamResult result = new StreamResult(outContentStream);
		transformer.transform(source, result);
		contentStream.close();
		outContentStream.closeEntry();
		outContentStream.close();
		alterXzFile(new File(p2repository, "content.xml"), new File(p2repository,"content.xml.xz"), transformer, source);
	}
	
	
	/**
	 * Add p2 stats to the repository's artifacts.xml (and .jar and .xml.xz) 
	 * See http://wiki.eclipse.org/Equinox_p2_download_stats
	 * 
	 * @param theXml
	 * @param theXmlXz
	 * @param transformer
	 * @param source
	 * @throws MojoFailureException
	 * 
	 * */

	private void alterXzFile(File theXml, File theXmlXz, Transformer transformer, DOMSource source) throws MojoFailureException {
		try {
			// JBDS-3929 overwrite the artifacts.xml.xz file too
			// see also https://bugs.eclipse.org/bugs/show_bug.cgi?id=464614
			//getLog().debug("delete " + theXmlXz.toString());
			FileUtils.forceDelete(theXmlXz);
			//getLog().debug("create " + theXml.toString() + " from transformed XML");
			FileOutputStream outStreamXml = new FileOutputStream(theXml);
			StreamResult resultXml = new StreamResult(outStreamXml);
			transformer.transform(source, resultXml);
			outStreamXml.close();
			//getLog().debug("stream " + theXml.toString() + " to " + theXmlXz.toString());
			BufferedInputStream in = new BufferedInputStream(new FileInputStream(theXml));
			XZCompressorOutputStream out = new XZCompressorOutputStream(new FileOutputStream(theXmlXz));
			final byte[] buffer = new byte[1024];
			int n = 0;
			while (-1 != (n = in.read(buffer))) {
				out.write(buffer, 0, n);
			}
			out.close();
			in.close();
			//getLog().debug("new " + theXmlXz.toString() + " written; remove " + theXml.toString());
			FileUtils.forceDelete(theXml);
		} catch (IOException|TransformerException ex) { 
			getLog().error(ex); 
			throw new MojoFailureException("Error while compressing " + theXml.toString(), ex);
		} 
	}

	private void alterIndexFile(File outputSite) throws FileNotFoundException, IOException {
		File templateFile = new File(outputSite, this.indexName);
		FileInputStream fis = new FileInputStream(templateFile);
		String htmlFile = IOUtil.toString(fis, "UTF-8");
		for (Entry<String, String> entry : this.symbols.entrySet()) {
			String key = entry.getKey();
			if (!key.startsWith("${")) {
				key = "${" + key + "}";
			}
			if (entry.getValue() != null) {
				htmlFile = htmlFile.replace(key, entry.getValue());
			}
		}
		FileOutputStream out = new FileOutputStream(templateFile);
		out.write(htmlFile.getBytes("UTF-8"));
		fis.close();
		out.close();
}
}
