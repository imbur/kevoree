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

import org.kevoree.ContainerRoot
import org.kevoree.framework.annotation.processor.LocalUtility
import org.kevoree.framework.annotation.processor.PostAptChecker
import org.kevoree.tools.annotation.generator.{ThreadingMapping, KevoreeGenerator}

import org.kevoree.framework._
import scala.collection.JavaConversions._
import javax.annotation.processing.RoundEnvironment
import javax.tools.Diagnostic.Kind
import org.kevoree.annotation._
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import java.util.HashSet

class KevoreeAnnotationProcessor() extends javax.annotation.processing.AbstractProcessor {

  var options: java.util.Map[String, Object] = _

  def getOptions = options

  def setOptions(s: java.util.Map[String, Object]) = {
    options = s
  }

  lazy val env = processingEnv

  override def getSupportedOptions: java.util.Set[String] = {
    import scala.collection.JavaConversions._
    Set[String]()
  }

  override def getSupportedAnnotationTypes: java.util.Set[String] = {
    val stype = new java.util.HashSet[String]
    stype.add(classOf[org.kevoree.annotation.ChannelType].getName)
    stype.add(classOf[org.kevoree.annotation.ComponentType].getName)
    stype.add(classOf[org.kevoree.annotation.Port].getName)
    stype.add(classOf[org.kevoree.annotation.ProvidedPort].getName)
    stype.add(classOf[org.kevoree.annotation.Provides].getName)
    stype.add(classOf[org.kevoree.annotation.Requires].getName)
    stype.add(classOf[org.kevoree.annotation.RequiredPort].getName)
    stype.add(classOf[org.kevoree.annotation.Start].getName)
    stype.add(classOf[org.kevoree.annotation.Stop].getName)
    stype.add(classOf[org.kevoree.annotation.Ports].getName)
    stype.add(classOf[org.kevoree.annotation.ThirdParties].getName)
    stype.add(classOf[org.kevoree.annotation.ThirdParty].getName)
    stype.add(classOf[org.kevoree.annotation.DictionaryAttribute].getName)
    stype.add(classOf[org.kevoree.annotation.DictionaryType].getName)
    stype.add(classOf[org.kevoree.annotation.Library].getName)
    stype.add(classOf[org.kevoree.annotation.GroupType].getName)
    stype.add(classOf[org.kevoree.annotation.NodeType].getName)
    stype.add(classOf[org.kevoree.annotation.Slot].getName)
    stype.add(classOf[org.kevoree.annotation.SlotPort].getName)
    return stype
  }

  override def getSupportedSourceVersion: SourceVersion = {
    return SourceVersion.latest
  }

  private val threadProtectionAsked = new HashSet[String]


  def process(annotations: java.util.Set[_ <: TypeElement], roundEnv: RoundEnvironment): Boolean = {

    if (annotations.size() == 0) {
      return true
    }

    val root = LocalUtility.kevoreeFactory.createContainerRoot
    LocalUtility.root = root
    /* Look for reserved thread protection */
    roundEnv.getElementsAnnotatedWith(classOf[org.kevoree.annotation.ReservedThread]).foreach {
      typeDecl =>
        threadProtectionAsked.add(typeDecl.getSimpleName.toString)
    }
    roundEnv.getElementsAnnotatedWith(classOf[org.kevoree.annotation.ComponentType]).foreach {
      typeDecl =>
        processComponentType(typeDecl.getAnnotation(classOf[org.kevoree.annotation.ComponentType]), typeDecl.asInstanceOf[TypeElement], root)
    }
    roundEnv.getElementsAnnotatedWith(classOf[org.kevoree.annotation.ChannelType]).foreach {
      typeDecl =>
        processChannelType(typeDecl.getAnnotation(classOf[org.kevoree.annotation.ChannelType]), typeDecl.asInstanceOf[TypeElement], root)
    }

    // KevoreeXmiHelper.save(LocalUtility.generateLibURI(options) + ".beforeGTdebug", root);


    roundEnv.getElementsAnnotatedWith(classOf[org.kevoree.annotation.GroupType]).foreach {
      typeDecl =>
        processGroupType(typeDecl.getAnnotation(classOf[org.kevoree.annotation.GroupType]), typeDecl.asInstanceOf[TypeElement], root)
    }

    roundEnv.getElementsAnnotatedWith(classOf[org.kevoree.annotation.NodeType]).foreach {
      typeDecl =>
        processNodeType(typeDecl.getAnnotation(classOf[org.kevoree.annotation.NodeType]), typeDecl.asInstanceOf[TypeElement], root)
    }

    //KevoreeXmiHelper.save(LocalUtility.generateLibURI(options) + ".beforeCheckerdebug", root);


    //POST APT PROCESS CHECKER
    val checker: PostAptChecker = new PostAptChecker(root, env)
    val errorsInChecker = !checker.check

    // use libraries definition on pom (if there is no library definition on typeDefinition)

    if (options.containsKey("libraries") && options.get("libraries") != null) {
      val libraries = options.get("libraries").asInstanceOf[java.util.List[String]]
      root.getTypeDefinitions.foreach {
        typeDefinition =>
          if (root.getLibraries.find(library => library.getSubTypes.contains(typeDefinition)).isEmpty) {
            libraries.foreach {
              libraryName =>
                root.getLibraries.find({
                  lib => lib.getName == libraryName
                }) match {
                  case Some(lib) => lib.addSubTypes(typeDefinition)
                  case None => {
                    val newlib = LocalUtility.kevoreeFactory.createTypeLibrary
                    newlib.setName(libraryName)
                    newlib.addSubTypes(typeDefinition)
                    root.addLibraries(newlib)
                  }
                }
            }
          }
      }
    }


    KevoreeXmiHelper.instance$.save(LocalUtility.generateLibURI(options) + ".debug", root)

    if (!errorsInChecker) {
      //TODO SEPARATE MAVEN PLUGIN
      val nodeTypeNames = options.get("nodeTypeNames")
      val nodeTypeNameList: List[String] = nodeTypeNames.toString.split(",").filter(r => r != null && r != "").toList
      nodeTypeNameList.foreach {
        targetNodeName =>
          KevoreeGenerator.generatePort(root, env.getFiler, targetNodeName)
        //KevoreeFactoryGenerator.generateFactory(root, env.getFiler, targetNodeName)
        //KevoreeActivatorGenerator.generateActivator(root, env.getFiler, targetNodeName)
      }
      env.getMessager.printMessage(Kind.OTHER, "Save Kevoree library")
      KevoreeXmiHelper.instance$.save(LocalUtility.generateLibURI(options), root)
      true
    } else {
      false
    }
  }

  private def hasTooManyTypes(typeDecl: TypeElement): Boolean = {
    var nbTypes = 0
    if (typeDecl.getAnnotation(classOf[org.kevoree.annotation.GroupType]) != null) {
      nbTypes += 1
    }
    if (typeDecl.getAnnotation(classOf[org.kevoree.annotation.ChannelType]) != null) {
      nbTypes += 1
    }
    if (typeDecl.getAnnotation(classOf[org.kevoree.annotation.ComponentType]) != null) {
      nbTypes += 1
    }
    if (typeDecl.getAnnotation(classOf[org.kevoree.annotation.NodeType]) != null) {
      nbTypes += 1
    }
    nbTypes != 1
  }


  def processNodeType(nodeTypeAnnotation: org.kevoree.annotation.NodeType, typeDecl: TypeElement, root: ContainerRoot) = {
    // check if there are multiple annotation definition
    if (hasTooManyTypes(typeDecl)) {
      env.getMessager.printMessage(Kind.ERROR, typeDecl.getQualifiedName + " has multiple annotation type which is forbidden")
      throw new Exception(typeDecl.getQualifiedName + " has multiple annotation type which is forbidden")
    }

    //Checks that the root AbstractNodeType is present in hierarchy.
    //val superTypeChecker = new SuperTypeValidationVisitor(classOf[AbstractNodeType].getName)
    //typeDecl.accept(superTypeChecker, typeDecl)

    var isAbstract = false
    typeDecl.getModifiers.find(mod => mod.equals(javax.lang.model.element.Modifier.ABSTRACT)) match {
      case Some(s) => {
        isAbstract = true
        //env.getMessager.printMessage(Kind.WARNING, "NodeType bean ignored  " + typeDecl.getQualifiedName + ", reason=Declared as @NodeType but is actually ABSTRACT. Should be either concrete or @NodeFragment.")
      }
      case None =>
    }

    //if (superTypeChecker.result) {
    val nodeTypeName = typeDecl.getSimpleName
    val nodeType: org.kevoree.NodeType = root.findByPath("typeDefinitions[" + nodeTypeName + "]", classOf[org.kevoree.NodeType]) match {
      case found: org.kevoree.NodeType => found
      case null => {
        val nodeType = LocalUtility.kevoreeFactory.createNodeType
        nodeType.setName(nodeTypeName.toString)
        root.addTypeDefinitions(nodeType)
        nodeType
      }
    }

    if (!isAbstract) {
      nodeType.setBean(typeDecl.getQualifiedName.toString)
      nodeType.setFactoryBean(typeDecl.getQualifiedName + "Factory")
      nodeType.setAbstract(false)
    } else {
      nodeType.setAbstract(true)
    }

    //RUN VISITOR
    typeDecl.accept(NodeTypeVisitor(nodeType, env, this), typeDecl)
    // } else {
    //  env.getMessager.printMessage(Kind.WARNING, "NodeType ignored " + typeDecl.getQualifiedName + " , reason=Must extend " + classOf[AbstractNodeType].getName)
    //}
  }


  def processGroupType(groupTypeAnnotation: GroupType, typeDecl: TypeElement, root: ContainerRoot) = {
    // check if there are multiple annotation definition
    if (hasTooManyTypes(typeDecl)) {
      env.getMessager.printMessage(Kind.ERROR, typeDecl.getQualifiedName + " has multiple annotation type which is forbidden")
      throw new Exception(typeDecl.getQualifiedName + " has multiple annotation type which is forbidden")
    }
    //Checks that the root AbstractGroupType is present in hierarchy.
    //    val superTypeChecker = new SuperTypeValidationVisitor(classOf[AbstractGroupType].getName)
    //    typeDecl.accept(superTypeChecker, typeDecl)

    var isAbstract = false
    typeDecl.getModifiers.find(mod => mod.equals(javax.lang.model.element.Modifier.ABSTRACT)) match {
      case Some(s) => {
        isAbstract = true
        //        env.getMessager.printMessage(Kind.WARNING, "GroupType ignored " + typeDecl.getQualifiedName + ", reason=Declared as @GroupType but is actually ABSTRACT. Should be either concrete or @GroupFragment.")
      }
      case None =>
    }

    //    if (superTypeChecker.result && !isAbstract) {
    val groupName = typeDecl.getSimpleName
    val groupType: org.kevoree.GroupType = root.findByPath("typeDefinitions[" + groupName + "]", classOf[org.kevoree.GroupType]) match {
      case null => {
        val groupType = LocalUtility.kevoreeFactory.createGroupType
        groupType.setName(groupName.toString)
        root.addTypeDefinitions(groupType)
        groupType
      }
      case td: org.kevoree.GroupType => {
        td
      }
    }

    if (!isAbstract) {
      groupType.setBean(typeDecl.getQualifiedName.toString)
      groupType.setFactoryBean(typeDecl.getQualifiedName + "Factory")
      groupType.setAbstract(false)
    } else {
      groupType.setAbstract(true)
    }

    //RUN VISITOR
    typeDecl.accept(GroupTypeVisitor(groupType, env, this), typeDecl)
    /*} else {
      env.getMessager.printMessage(Kind.WARNING, "GroupType ignored " + typeDecl.getQualifiedName + " , reason=Must extend " + classOf[AbstractGroupType].getName)
    }*/
  }


  def processChannelType(channelTypeAnnotation: org.kevoree.annotation.ChannelType, typeDecl: TypeElement, root: ContainerRoot) = {
    // check if there are multiple annotation definition
    if (hasTooManyTypes(typeDecl)) {
      env.getMessager.printMessage(Kind.ERROR, typeDecl.getQualifiedName + " has multiple annotation type which is forbidden")
      throw new Exception(typeDecl.getQualifiedName + " has multiple annotation type which is forbidden")
    }

    ThreadingMapping.getMappings.put((typeDecl.getSimpleName.toString, typeDecl.getSimpleName.toString), channelTypeAnnotation.theadStrategy())

    //Checks that the root KevoreeChannelFragment is present in hierarchy.
    //    val superTypeChecker = new SuperTypeValidationVisitor(classOf[AbstractChannelFragment].getName)
    //    typeDecl.accept(superTypeChecker, typeDecl)

    var isAbstract = false
    typeDecl.getModifiers.find(mod => mod.equals(javax.lang.model.element.Modifier.ABSTRACT)) match {
      case Some(s) => {
        isAbstract = true
        //        env.getMessager.printMessage(Kind.WARNING, "ChannelType ignored " + typeDecl.getQualifiedName + ", reason=Declared as @ChannelFragment but is actually ABSTRACT")
      }
      case None =>
    }

    //    if (superTypeChecker.result && !isAbstract) {
    val channelName = typeDecl.getSimpleName
    val channelType: org.kevoree.ChannelType = root.findByPath("typeDefinitions[" + channelName + "]", classOf[org.kevoree.ChannelType]) match {
      case null => {
        val channelType = LocalUtility.kevoreeFactory.createChannelType
        channelType.setName(channelName.toString)
        root.addTypeDefinitions(channelType)
        channelType
      }
      case td: org.kevoree.ChannelType => {
        td
      }
    }

    if (!isAbstract) {
      channelType.setBean(typeDecl.getQualifiedName.toString)
      channelType.setFactoryBean(typeDecl.getQualifiedName + "Factory")
      channelType.setAbstract(false)
    } else {
      channelType.setAbstract(true)
    }

    //RUN VISITOR
    typeDecl.accept(ChannelTypeFragmentVisitor(channelType, env, this), typeDecl)
    /*} else {
       env.getMessager.printMessage(Kind.WARNING, "ChannelFragment ignored " + typeDecl.getQualifiedName + " , reason=Must extend " + classOf[AbstractChannelFragment].getName)
     }*/
  }

  def processComponentType(componentTypeAnnotation: org.kevoree.annotation.ComponentType, typeDecl: TypeElement, root: ContainerRoot) = {
    // check if there are multiple annotation definition
    if (hasTooManyTypes(typeDecl)) {
      env.getMessager.printMessage(Kind.ERROR, typeDecl.getQualifiedName + " has multiple annotation type which is forbidden")
      throw new Exception(typeDecl.getQualifiedName + " has multiple annotation type which is forbidden")
    }

    //Checks that the root AbstractComponentType is present in hierarchy.
    //    val superTypeChecker = new SuperTypeValidationVisitor(classOf[AbstractComponentType].getName)
    //    typeDecl.accept(superTypeChecker, typeDecl)

    //Prints a warning
    /*if (!superTypeChecker.result) {
      env.getMessager.printMessage(Kind.WARNING, "ComponentType ignored " + typeDecl.getQualifiedName + " , reason=Must extend " + classOf[AbstractComponentType].getName)
    }*/

    //Checks the Class is not Abstract
    var isAbstract = false
    typeDecl.getModifiers.find(mod => mod.equals(javax.lang.model.element.Modifier.ABSTRACT)) match {
      case Some(s) => {
        isAbstract = true
        //        env.getMessager.printMessage(Kind.WARNING, "ComponentType ignored " + typeDecl.getQualifiedName + ", reason=Declared as @ComponentType but is actually ABSTRACT. Should be either concrete or @ComponentFragment.")
      }
      case None =>
    }

    //    if (superTypeChecker.result && !isAbstract) {
    val componentName = typeDecl.getSimpleName
    val componentType: org.kevoree.ComponentType = root.findByPath("typeDefinitions[" + componentName + "]", classOf[org.kevoree.ComponentType]) match {
      case null => {
        val componentType = LocalUtility.kevoreeFactory.createComponentType
        componentType.setName(componentName.toString)
        root.addTypeDefinitions(componentType)
        componentType
      }
      case td: org.kevoree.ComponentType => {
        td
      }
    }

    if (!isAbstract) {
      componentType.setBean(typeDecl.getQualifiedName.toString)
      componentType.setFactoryBean(typeDecl.getQualifiedName + "Factory")
      componentType.setAbstract(false)
    } else {
      componentType.setAbstract(true)
    }

    //RUN VISITOR
    val cvisitor = ComponentDefinitionVisitor(componentType, env, this)
    typeDecl.accept(cvisitor, typeDecl)
    cvisitor.doAnnotationPostProcess(componentType)

    //    }
  }
}
