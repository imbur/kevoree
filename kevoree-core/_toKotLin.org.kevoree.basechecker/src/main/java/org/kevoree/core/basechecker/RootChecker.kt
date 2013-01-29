/**
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE, Version 3, 29 June 2007;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kevoree.core.basechecker

import org.slf4j.LoggerFactory
import java.util
import org.kevoree.api.service.core.checker.CheckerViolation
import java.util.ArrayList


class RootChecker : CheckerService {

  private val logger = LoggerFactory.getLogger(this.getClass)
  
  var subcheckers: List[CheckerService] = List(new KevoreeVersionChecker, new ComponentCycleChecker, /*new NodeCycleChecker,*/ new NameChecker,
                                                new PortChecker, new NodeChecker, new BindingChecker, new BoundsChecker, new IdChecker, new DictionaryOptionalChecker, new NodeContainerChecker, new DictionaryNetworkPortChecker)

  def check (model: ContainerRoot): java.util.List[CheckerViolation]{

var result = ArrayList<CheckerViolation>()

    val beginTime = System.currentTimeMillis()
    subcheckers.foreach({
      sub =>   try {
        result.addAll(sub.check(model))
      } catch {
        case _ @ e => {
          logger.error("Exception during checking", e)
          val violation: CheckerViolation = new CheckerViolation
          violation.setMessage("Checker fatal exception "+sub.getClass.getSimpleName+"-"+e.getMessage)
          violation.setTargetObjects(new util.ArrayList())
          result.add(violation)
        }
      }
    })
    logger.debug("Model checked in "+(System.currentTimeMillis()-beginTime))
    result
  }

}