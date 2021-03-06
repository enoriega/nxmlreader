package ai.lum.nxmlreader.standoff

import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.JsonDSL._
import ai.lum.common.Interval


sealed trait Tree {

  private[standoff] var _parent: Option[Tree] = None
  def parent: Option[Tree] = _parent

  def label: String
  def text: String
  def interval: Interval
  def children: List[Tree]
  def attributes: Map[String, String]
  def copy(): Tree
  def getTerminals(i: Interval): List[Terminal]
  def toJson: JObject

  def getTerminals(i: Int): List[Terminal] = {
    getTerminals(Interval.singleton(i))
  }

  def getTerminals(start: Int, end: Int): List[Terminal] = {
    getTerminals(Interval.open(start, end))
  }

  def getTerminals(): List[Terminal] = {
    getTerminals(interval)
  }

  def root: Tree = parent match {
    case None => this
    case Some(p) => p.root
  }

  def ancestors: List[Tree] = parent match {
    case None => Nil
    case Some(p) => p :: p.ancestors
  }

  def path: String = ancestors.map(_.label).mkString(" ")

  def printJson: String = pretty(render(this.toJson))

}

class NonTerminal(
    val label: String,
    val children: List[Tree],
    val attributes: Map[String, String]
) extends Tree {

  require(children.nonEmpty, "children list is empty")
  require(children.forall(_.parent.isEmpty), "children already have a parent")

  // set children's parent to self
  children.foreach(_._parent = Some(this))

  val interval: Interval = Interval.union(children.map(_.interval))

  def text: String = children.map(_.text).mkString

  def copy(): Tree = new NonTerminal(label, children.map(_.copy()), attributes)

  def getTerminals(i: Interval): List[Terminal] = {
    if (i intersects interval) {
      for {
        c <- children
        t <- c.getTerminals(i)
      } yield t
    } else {
      Nil
    }
  }

  def toJson: JObject = {
    // Create a dictionary to be rendered as JSON
    ("label" -> this.label) ~
    ("attributes" -> this.attributes) ~
    ("children" -> this.children.map(_.toJson))
  }

}

class Terminal(
    val label: String,
    val text: String,
    val interval: Interval
) extends Tree {

  val children: List[Tree] = Nil
  val attributes: Map[String, String] = Map()

  def copy(): Tree = new Terminal(label, text, interval)

  def getTerminals(i: Interval): List[Terminal] = {
    if (i intersects interval) List(this) else Nil
  }

  def toJson: JObject = {
    // Create a dictionary to be rendered as JSON
    ("label" -> this.label) ~
    ("text" -> this.text) ~
    ("start" -> this.interval.start) ~
    ("end" -> this.interval.end)
  }

}

// Companion object to keep the json deserialization code
object Tree {

  implicit lazy val formats = DefaultFormats

  def parseJson(txt:String):Tree = {

    def parseHelper(elem:JObject):Tree ={
      // Figure out if this isn't a terminal object
      if(elem.obj.exists(f => f._1 == "children")){
        // This is an internal node in the AST
        new NonTerminal(
          (elem \ "label").extract[String],
          (elem \ "children").extract[List[JObject]].map(parseHelper),
          (elem \ "attributes").extract[Map[String, String]]
        )
      }
      else{
        // This is a terminal in the AST
        new Terminal(
          (elem \ "label").extract[String],
          (elem \ "text").extract[String],
          Interval.open((elem \ "start").extract[Int], (elem \ "end").extract[Int])
        )
      }
    }

    val json = parse(txt).asInstanceOf[JObject]
    parseHelper(json)
  }

  def readJson(path:String):Tree = {
    val source = io.Source.fromFile(path)
    val json = source.getLines.mkString("\n")
    source.close
    parseJson(json)
  }

}
