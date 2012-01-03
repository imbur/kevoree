/**
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE, Version 3, 29 June 2007;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.gnu.org/licenses/lgpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kevoree.tools.war.wrapperplugin

import org.apache.maven.project.MavenProject
import java.util.jar.Attributes
import io.Source
import java.io.{FileWriter, FileOutputStream, File}

/**
 * Created by IntelliJ IDEA.
 * User: duke
 * Date: 16/12/11
 * Time: 17:58
 * To change this template use File | Settings | File Templates.
 */

object BundlerHelper {

  
  def copyWebInf(warDir : File){
    val webInf = new FileWriter(warDir.getAbsolutePath+File.separator+"web.xml")
    Source.fromFile(warDir.getAbsolutePath+File.separator+"WEB-INF"+File.separator+"web.xml").getLines().foreach{l=>
      webInf.write(l+"\n")
    }
    webInf.close
  }

  def generateManifest(project: MavenProject, outDir: File) {
    val mf = new java.util.jar.Manifest
    mf.getMainAttributes.put(new Attributes.Name("Manifest-Version"), "1")
    mf.getMainAttributes.put(new Attributes.Name("Bundle-ManifestVersion"), "2")
    mf.getMainAttributes.put(new Attributes.Name("Bundle-SymbolicName"), project.getGroupId + "." + project.getArtifactId)
    mf.getMainAttributes.put(new Attributes.Name("Bundle-Version"), project.getVersion.replace("-","."))
    mf.getMainAttributes.put(new Attributes.Name("Bundle-Name"), project.getName)
    val fileWebLib = new File(outDir.getAbsolutePath + File.separator + "WEB-INF" + File.separator + "lib")
    if (fileWebLib.exists()) {
      var paths = List(".")
      paths = paths ++ List("WEB_INF/classes")
      fileWebLib.listFiles().foreach {
        subF =>
          if (subF.getName.endsWith(".jar")) {
            paths = paths ++ List("WEB-INF/lib/" + subF.getName)
          }
      }
      mf.getMainAttributes.put(new Attributes.Name("Bundle-ClassPath"), paths.mkString(","))
    } else {
      mf.getMainAttributes.put(new Attributes.Name("Bundle-ClassPath"), ".")
    }

    /*
    Import-Package: org.kevoree.annotation;version="[1.5,2)",org.kevoree.f
     ramework,org.kevoree.framework.osgi,org.kevoree.framework.port,org.sl
     f4j;version="[1.6,2)",scala,scala.actors,scala.collection.immutable,s
     cala.collection.mutable,scala.reflect,scala.runtime
*/

    var importPackages = List("org.kevoree.annotation")
    importPackages = importPackages ++ List("org.osgi.framework")
    importPackages = importPackages ++ List("org.kevoree.library.javase.webserver")
    importPackages = importPackages ++ List("org.kevoree.library.javase.webserver.servlet")
    importPackages = importPackages ++ List("org.kevoree.framework")
    importPackages = importPackages ++ List("org.kevoree.framework.osgi")
    importPackages = importPackages ++ List("org.kevoree.framework.port")
    importPackages = importPackages ++ List("org.slf4j")
    importPackages = importPackages ++ List("scala,scala.actors")
    importPackages = importPackages ++ List("scala.collection.immutable")
    importPackages = importPackages ++ List("scala.collection.mutable")
    importPackages = importPackages ++ List("scala.reflect")
    importPackages = importPackages ++ List("scala.runtime")
    importPackages = importPackages ++ List("javax.servlet.http")
    importPackages = importPackages ++ List("javax.servlet")
    mf.getMainAttributes.put(new Attributes.Name("Import-Package"), importPackages.mkString(","))

    mf.getMainAttributes.put(new Attributes.Name("DynamicImport-Package"), "*")


    var exportPackages = List(project.getGroupId + "." + project.getArtifactId)
    exportPackages = exportPackages ++ List(project.getGroupId + "." + project.getArtifactId+".kevgen.JavaSENode")
    mf.getMainAttributes.put(new Attributes.Name("Export-Package"), exportPackages.mkString(","))

    val fos = new FileOutputStream(outDir.getAbsolutePath + File.separator + "META-INF" + File.separator + "MANIFEST.MF");
    mf.write(fos)

    //CLEAR SF files
    val metaInfDir = new File(outDir.getAbsolutePath + File.separator + "META-INF")
    metaInfDir.listFiles().foreach{ f =>
      if(f.getName.toLowerCase.endsWith(".sf")|| f.getName.toLowerCase.endsWith(".rsa")){
        f.delete()
      }
    }
    
    
  }

}