/**
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE, Version 3, 29 June 2007;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kevoree.tools.mavenplugin;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.kevoree.ContainerRoot;
import org.kevoree.DeployUnit;
import org.kevoree.Value;
import org.kevoree.api.helper.KModelHelper;
import org.kevoree.factory.DefaultKevoreeFactory;
import org.kevoree.factory.KevoreeFactory;
import org.kevoree.pmodeling.api.json.JSONModelSerializer;
import org.kevoree.tools.mavenplugin.util.Annotations2Model;
import org.kevoree.tools.mavenplugin.util.ModelBuilderHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class KevGenerateMojo extends AbstractMojo {

	@Parameter(required = true, readonly = true, defaultValue = "${project}")
	public MavenProject project;

	@Parameter(defaultValue = "${localRepository}")
	private ArtifactRepository localRepository = null;

	@Parameter(defaultValue = "${project.build.directory}/classes", required = true)
	protected File modelOutputDirectory;
	
	@Parameter(required = true)
	private String namespace = null;

	private Annotations2Model annotations2Model = new Annotations2Model();
	private KevoreeFactory factory = new DefaultKevoreeFactory();

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (project.getArtifact().getType().equals("jar")) {
			getLog().info("Generating a Kevoree model by reflection...");
			final ContainerRoot model = factory.createContainerRoot();
			factory.root(model);

			final HashMap<String, Set<String>> collectedClasses = new HashMap<String, Set<String>>();
            DeployUnit deployUnit = processModel(model, collectedClasses);

			try {
				annotations2Model.fillModel(modelOutputDirectory, model, deployUnit,
						project.getCompileClasspathElements(), collectedClasses);
			} catch (Exception e) {
				throw new MojoExecutionException("Error while parsing Kevoree annotations", e);
			}

			processModelSerialization(model);
		}
	}
	
	private DeployUnit processModel(ContainerRoot model, Map<String, Set<String>> collectedClasses) {
		model.setGenerated_KMF_ID("0");

        DeployUnit du = factory.createDeployUnit();
        du.setName(project.getArtifact().getArtifactId());
        String hashcode = ModelBuilderHelper.createKey(namespace, project.getArtifactId(), project.getVersion(), null);
        du.setHashcode(hashcode);
        du.setVersion(project.getArtifact().getBaseVersion());

        Value platform = factory.createValue();
        platform.setName("platform");
        platform.setValue("java");
        du.addFilters(platform);

        // add repositories
        for (Repository repo : project.getRepositories()) {
            Value repoVal = factory.createValue();
            repoVal.setName("repo_" + repo.getId());
            repoVal.setValue(repo.getUrl());
            du.addFilters(repoVal);
        }

        org.kevoree.Package pack = KModelHelper.fqnCreate(namespace, model, factory);
        if (pack == null) {
            getLog().info("Package " + project.getArtifact().getGroupId() + " " + pack);
        } else {
            pack.addDeployUnits(du);
        }
        du.setUrl(project.getArtifact().getGroupId() + ":" + du.getName() + ":" + du.getVersion());

        try {
            File f2 = localRepository.find(project.getArtifact()).getFile();
            if (f2 != null && f2.getAbsolutePath().endsWith(".jar")) {
                JarFile file = new JarFile(f2);
                Enumeration<JarEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    // System.err.println(entry.getName());
                    Set<String> sources = collectedClasses.get(entry.getName());
                    if (sources == null) {
                        sources = new HashSet<String>();
                        collectedClasses.put(entry.getName(), sources);
                    }
                    sources.add(project.getArtifact().getGroupId() + ":" + project.getArtifact().getArtifactId() + ":"
                            + project.getArtifact().getVersion() + ":" + project.getArtifact().getType());
                }
                file.close();
            }
        } catch (Exception ignore) {}

        return du;
	}

	private void processModelSerialization(ContainerRoot model) throws MojoExecutionException {
		JSONModelSerializer saver = factory.createJSONSerializer();
		Path path = Paths.get(modelOutputDirectory.getPath(), "KEV-INF", "kevlib.json");
		File modelJson = new File(path.toString());
		
		try {
			modelJson.getParentFile().mkdirs();
			modelJson.createNewFile();
			FileOutputStream fos = new FileOutputStream(modelJson);
			saver.serializeToStream(model, fos);
			fos.flush();
			fos.close();
			getLog().info("Model saved at " + Paths.get(project.getBasedir().getAbsolutePath()).relativize(path));
		} catch (Exception e) {
			getLog().error(e);
			throw new MojoExecutionException("Unable to write model at "
					+ Paths.get(project.getBasedir().getAbsolutePath()).relativize(path));
		}
	}
}