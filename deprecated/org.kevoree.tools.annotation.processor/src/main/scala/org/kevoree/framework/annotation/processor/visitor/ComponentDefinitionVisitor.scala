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

package org.kevoree.framework.annotation.processor.visitor

import sub._
import org.kevoree.{NamedElement, TypeDefinition, ComponentType}
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.util.SimpleElementVisitor6
import javax.lang.model.element._
import scala.collection.JavaConversions._

case class ComponentDefinitionVisitor(componentType: ComponentType, _env: ProcessingEnvironment, _rootVisitor: KevoreeAnnotationProcessor)
  extends SimpleElementVisitor6[Any, Element]
  with ProvidedPortProcessor
  with RequiredPortProcessor
  with PortMappingProcessor
  with SlotProcessor
//  with TypeDefinitionProcessor
  with CommonProcessor {

  var typeDefinition: TypeDefinition = componentType
  var elementVisitor: ElementVisitor[Any, Element] = this
  var env: ProcessingEnvironment = _env
  var rootVisitor: KevoreeAnnotationProcessor = _rootVisitor
  var annotationType: Class[_ <: java.lang.annotation.Annotation] = classOf[org.kevoree.annotation.ComponentType]
  var typeDefinitionType : Class[_ <: TypeDefinition] = classOf[ComponentType]

  override def visitType(p1: TypeElement, p2: Element): Any = {

    super[CommonProcessor].commonProcess(p1)

    processProvidedPort(componentType, p1, env)
    processRequiredPort(componentType, p1, env)
    processSlot(componentType, p1, env)
    p1.getEnclosedElements.foreach {
      method => {
        method.getKind match {
          case ElementKind.METHOD => {
            processPortMapping(componentType, method.asInstanceOf[ExecutableElement], env)
          }
          case _ =>
        }
      }
    }
  }
}
