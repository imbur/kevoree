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
package org.kevoree.tools.marShellTransform


import ast._
import util.parsing.combinator.syntactical.StandardTokenParsers

import collection.JavaConversions._



/**
 * Created by jed
 * User: jedartois@gmail.com
 * Date: 28/03/12
 * Time: 16:57
 *  */

class ParserPush extends StandardTokenParsers{

  lexical.delimiters ++= List("/",":",",","=","{","}","node=")


  def operation  =numericLit ^^ {
    case s => s
  }

  def value  =numericLit ^^ {
    case s => s
  }

  def instanceID  =ident ^^ {
    case s => s
  }


  def nodeName  =ident ^^ {
    case s => s
  }


  def portID  =ident ^^ {
    case s => s
  }
  def chID = ident ^^ {
    case s => s
  }
  
  def typeIDB = numericLit ^^ {
    case s => s
  }

  def portIDB   =numericLit ^^ {
      case s => s
    }

  // 3:T1
  def  parseIDPredicate: Parser[IDPredicate]   = operation ~ ":" ~ instanceID ^^  {
    case     d  ~ _ ~ t =>  new IDPredicate(d.toInt,t)
  }

  // 0=100, ...
  def  parsePropertiesPredicate: Parser[PropertiePredicate]   = operation ~ "=" ~ value ^^  {
    case     d  ~ _ ~ t =>  new PropertiePredicate(d.toInt,t.toInt)
  }

  def parseUDI: Parser[Adaptation] =
     parseIDPredicate ~ ":" ~ rep1sep (parsePropertiesPredicate, ",") ^^  {
      case  a  ~ _ ~ b =>    new UDI(a,b)
    }

  //1:T1:0:0=50000
  def parseAIN: Parser[Adaptation] =
       parseIDPredicate ~ ":" ~  typeIDB ~ ":" ~ rep1sep (parsePropertiesPredicate, ",") ^^  {
      case  a  ~ _ ~ b  ~ _ ~ c =>    new AIN(a,b.toInt,c)
    }

   //1:1
   def parseRIN : Parser[Adaptation] =   value ~ ":" ~ value ^^{
     case a   ~ _  ~ b => new RIN(a.toInt,b.toInt)
   }

  //3:T1: S1:0$
  def parseABI: Parser[Adaptation] =
       parseIDPredicate ~ ":" ~  chID  ~ ":" ~ portIDB  ^^  {
      case  a  ~ _ ~ b  ~ _ ~ c   =>    new ABI(a,b,c.toInt)
    }

  def parseRBI  : Parser[Adaptation] =    parseIDPredicate ~ ":" ~  chID   ~ ":" ~  portIDB  ^^ {
    case  a  ~ _ ~ b  ~ _ ~ c   =>    new RBI(a,b.toInt,c.toInt)

  }
  //  global
  def requestParse: Parser[Adaptations] =  "{" ~ nodeName ~ "/" ~ rep1sep(( parseABI | parseAIN | parseUDI | parseRIN ), "/") ~ opt("/") ~ "}"   ^^
    {
      case _ ~ nodename ~ _  ~ mylist ~ _ ~ _ => new  Adaptations(nodename,mylist)
    }

  def  parseAdaptations(chaine : String) :Adaptations = {

    requestParse(new lexical.Scanner(chaine)) match {
      case Success(_rules, _) => _rules
      case Failure(msg,_) => throw new Exception("Bad syntax: "+msg)
      case Error(msg, _) =>  throw new Exception("Bad syntax: "+msg)
    }


  }

}