/*
 * Copyright (c) 2009 Matthew Hildebrand <matt.hildebrand@gmail.com>
 * Copyright (C) 2009, Progress Software Corporation and/or its
 * subsidiaries or affiliates.  All rights reserved.
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.fusesource.scalate.haml

import org.fusesoruce.scalate.haml._
import java.util.regex.Pattern
import java.net.URI
import org.fusesource.scalate._

/**
 * Generates a scala class given a HAML document
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class HamlCodeGenerator extends CodeGenerator
{

  private class SourceBuilder {

    var indent_level=0
    var code = ""

    def p(): this.type = p("")
    def p(line:String): this.type = {
      for( i <-0 until indent_level ) {
        code += "  ";
      }
      code += line+"\n";
      this
    }

    def i[T](op: => T):T = { indent_level += 1; val rc=op; indent_level-=1; rc }

    def out(line:String) {
      p("context.out.write(\"\"\""+line+"\"\"\"+\"\\n\");");
    }

    def generate(packageName:String, className:String, statements:List[Statement], args:List[TemplateArg]):Unit = {

      p("/** NOTE this file is autogenerated by ScalaTE : see http://scalate.fusesource.org/ */")
      if (packageName != "") {
        p("package "+packageName)
      }
      p
      p("import org.fusesource.scalate.{Template, TemplateContext}");
      p
      p("class " + className + " extends Template {");
      i{
        p
        p("def renderTemplateImpl(context: TemplateContext"+parameters(args)+"): Unit = {");
        i{

          // Handle importing each arg.
          if (!args.isEmpty) {
            args.foreach(arg=>{
              if( arg.importMembers ) {
                p("{")
                indent_level+=1;
                p("import " + arg.name + "._;")
              }
            })
          }

          generate(statements)

          if (!args.isEmpty) {
            args.foreach(arg=>{
              if( arg.importMembers ) {
                indent_level-=1;
                p("}")
              }
            })
          }
          p("context.completed");
        }
        p("}");
        p

        p("def renderTemplate(context: TemplateContext, args:Any*): Unit = {")
        i{
          if (!args.isEmpty) {
//            params.foreach(param=>{
//            p(param.valueCode)
//            })
//            p("renderTemplateImpl(context, " + params.map {_.name}.mkString(", ") + ")")
          } else {
            p("renderTemplateImpl(context)")
          }
        }
        p("}")

        p
      }
      p("}");

    }

    def generate(statements:List[Statement]):Unit = {
      statements.foreach(statement=>{
        generate(statement)
      })
    }

    def generate(statement:Statement):Unit = {
      statement match {
        case s:EvaluatedText=> generate(s)
        case s:LiteralText=> generate(s)
        case s:Element=> generate(s)
        case s:HamlComment=> {}
        case s:HtmlComment=> generate(s)
        case s:Executed=> generate(s)
        case s:Filter=> generate(s)
      }
    }

    def generate(statement:EvaluatedText):Unit = {
      // TODO: this need better escape encoding
      p("{"+statement.code+"}")
    }
    def generate(statement:LiteralText):Unit = {
      // TODO: this need better escape encoding
      out(statement.text)
    }
    def generate(statement:HtmlComment):Unit = {
      out("<!--"+statement.text.getOrElse("")+"-->")
    }
    def generate(statement:Executed):Unit = {
      statement match {
        case Executed(Some(code), List()) => {
          p(code)
        }
        case Executed(Some(code), list) => {
          p(code + " {")
          i {
            generate(list)
          }
          p("}")
        }
        case Executed(None,List())=> {}
      }
    }

    def generate(statement:Element):Unit = {
      val tag = statement.tag.getOrElse("div");
      out("<"+tag+attributes(statement.attributes)+">")
      statement match {
        case Element(_,_,None, List(), _, _) => {}
        case Element(_,_,Some(text), List(), _, _) => {
          generate(text)
        }
        case Element(_,_,None, list, _, _) => {
          generate(list)
        }
        case _ => throw new IllegalArgumentException("Syntax error on line "+statement.pos.line+": Illegal nesting: content can't be both given on the same line as html element and nested within it.");
      }
      out("</"+tag+">")
    }

    def generate(statement:Filter):Unit = {
      throw new UnsupportedOperationException("filters not yet implemented.");
    }

    def parameters(params: List[Any]) = {
      ""
    }
//    def parameters(params: List[AttributeFragment]) = {
//      if (params.isEmpty) {
//        ""
//      } else {
//        params.map(_.methodArgumentCode).mkString(", ", ", ", "")
//      }
//    }

    def attributes(entries: List[(Any,Any)]) = {
      val (entries_class, entries_rest) = entries.partition{x=>{ x._1 match { case "class" => true; case _=> false} } }
      var map = Map( entries_rest: _* )

      if( !entries_class.isEmpty ) {
        val value = entries_class.map(x=>x._2).mkString(" ")
        map += "class"->value
      }
      map.foldLeft(""){ case (r,e)=>r+" "+eval(e._1)+"=\""+eval(e._2)+"\""}
    }

    def eval(expression:Any) = {
      expression match {
        case s:String=>s
        case _=> throw new UnsupportedOperationException("don't know how to eval: "+expression);
      }
    }

  }


  override def generate(engine:TemplateEngine, uri:String, args:List[TemplateArg]): Code = {

    val hamlSource = engine.resourceLoader.load(uri)
    val (packageName, className) = extractPackageAndClassNames(uri)
    val statements = HamlParser.parse(hamlSource)
    val builder = new SourceBuilder()
    builder.generate(packageName, className, statements, args)

    Code(this.className(uri, args), builder.code, Set())

  }

  override def className(uri: String, args:List[TemplateArg]): String = {
    // Determine the package and class name to use for the generated class
    val (packageName, cn) = extractPackageAndClassNames(uri)

    // Build the complete class name (including the package name, if any)
    if (packageName == null || packageName.length == 0)
      cn
    else
      packageName + "." + cn
  }

  private def extractPackageAndClassNames(uri: String): (String, String) = {
    val normalizedURI = new URI(uri).normalize
    val SPLIT_ON_LAST_SLASH_REGEX = Pattern.compile("^(.*)/([^/]*)$")
    val matcher = SPLIT_ON_LAST_SLASH_REGEX.matcher(normalizedURI.toString)
    if (matcher.matches == false) throw new TemplateException("Internal error: unparseable URI [" + uri + "]")
    val packageName = matcher.group(1).replaceAll("[^A-Za-z0-9_/]", "_").replaceAll("/", ".").replaceFirst("^\\.", "")
    val cn = "_haml_" + matcher.group(2).replace('.', '_')
    (packageName, cn)
  }


}
