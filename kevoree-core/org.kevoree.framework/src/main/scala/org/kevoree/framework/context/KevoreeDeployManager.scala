package org.kevoree.framework.context

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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */


import org.osgi.framework.Bundle;
import org.osgi.service.packageadmin.PackageAdmin
import actors.DaemonActor


object KevoreeDeployManager extends DaemonActor {

  var bundle: Bundle = null
  def setBundle(b: Bundle) {
    bundle = b
    val sr = bundle.getBundleContext.getServiceReference(classOf[PackageAdmin].getName)
    servicePackageAdmin = Some(bundle.getBundleContext.getService(sr).asInstanceOf[PackageAdmin])
  }

  def getBundleContext = bundle.getBundleContext;

  private var private_bundleMapping: List[KevoreeOSGiBundle] = List[KevoreeOSGiBundle]();
  var servicePackageAdmin: Option[PackageAdmin] = null
  def getServicePackageAdmin: PackageAdmin = {
    servicePackageAdmin.get
  }


  /*
    Garbage unsed mapping
  */
  def garbage(): Unit = {
     this !? GARBAGE()
  }

  case class GARBAGE()


  def bundleMapping: List[KevoreeOSGiBundle] = {
    (this !? GET_MAPPINGS()).asInstanceOf[List[KevoreeOSGiBundle]]
  }

  case class GET_MAPPINGS()

  def addMapping(newMap: KevoreeOSGiBundle) {
    this !? ADD_MAPPING(newMap)
  }

  case class ADD_MAPPING(newMap: KevoreeOSGiBundle)

  def removeMapping(newMap: KevoreeOSGiBundle) {
    this !? REMOVE_MAPPING(newMap)
  }

  case class REMOVE_MAPPING(oldMap: KevoreeOSGiBundle)

  def act() {
    loop {
      react {
        case GARBAGE() => {
          private_bundleMapping.foreach {
            mapping =>
              if (bundle != null) {
                if (bundle.getState == Bundle.UNINSTALLED) {
                  private_bundleMapping = private_bundleMapping.filter(mp => mp != mapping)
                }
              } else {
                private_bundleMapping = private_bundleMapping.filter(mp => mp != mapping)
              }
          }
          reply(true)
        }
        case GET_MAPPINGS() => {
          reply(private_bundleMapping)
        }
        case ADD_MAPPING(newMap) => {
          private_bundleMapping = private_bundleMapping ++ List(newMap)
          reply(true)
        }
        case REMOVE_MAPPING(oldMap) => {
          private_bundleMapping = private_bundleMapping.filter(p => p != oldMap)
          reply(true)
        }
      }
    }
  }

  start()

}

