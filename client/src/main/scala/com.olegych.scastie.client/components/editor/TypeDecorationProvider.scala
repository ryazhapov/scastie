package com.olegych.scastie.client.components.editor

import com.olegych.scastie.api
import com.olegych.scastie.api.Html
import com.olegych.scastie.api.Value
import japgolly.scalajs.react._
import org.scalajs.dom
import org.scalajs.dom.HTMLElement
import typings.codemirrorState.mod._
import typings.codemirrorView.mod._
import scala.concurrent.duration._
import scala.collection.mutable.ListBuffer

import scalajs.js
import hooks.Hooks.UseStateF
import typings.codemirrorView.codemirrorViewBooleans
import typings.codemirrorState.codemirrorStateBooleans
import com.olegych.scastie.client.AttachedDoms
import com.olegych.scastie.api.AttachedDom

object TypeDecorationProvider {

  class AttachedDomDecoration(uuid: String, attachedDoms: AttachedDoms) extends WidgetType {
    override def toDOM(view: EditorView): HTMLElement = {
        val wrap = dom.document.createElement("div")
        wrap.setAttribute("aria-hidden", "true")
        wrap.setAttribute("class", "cm-domWidget")
        attachedDoms.get(uuid).map(wrap.append(_))
        wrap.domAsHtml
    }
  }

  class TypeDecoration(value: String, typeName: String) extends WidgetType {
    override def toDOM(view: EditorView): HTMLElement = {
        val wrap = dom.document.createElement("span")
        wrap.setAttribute("aria-hidden", "true")
        wrap.setAttribute("class", "cm-linewidget")
        val textBody = dom.document.createElement("pre")
        textBody.setAttribute("class", "inline")
        val value = dom.document.createElement("span")
        value.setAttribute("class", "cm-variable")
        value.innerText = s"$value: "
        val typ = dom.document.createElement("span")
        typ.setAttribute("class", "cm-type")
        typ.innerText = typeName
        textBody.append(value, typ)
        wrap.append(textBody)
        wrap.domAsHtml
    }
  }

  class HTMLDecoration(html: String) extends WidgetType {
    override def toDOM(view: EditorView): HTMLElement = {
        val wrap = dom.document.createElement("pre")
        wrap.setAttribute("aria-hidden", "true")
        wrap.setAttribute("class", "cm-linewidget")
        wrap.innerHTML = html
        wrap.domAsHtml
    }
  }


  def createDecorations(instrumentations: Set[api.Instrumentation], attachedDoms: AttachedDoms, maxPosititon: Int): DecorationSet = {
    val deco = instrumentations
      .filter(_.position.end < maxPosititon)
      .map { instrumentation =>
        {
          val decoration = Decoration.widget(WidgetDecorationSpec( instrumentation.render match {
            case AttachedDom(uuid, _) => new AttachedDomDecoration(uuid, attachedDoms)
            case Html(a, folded) => new HTMLDecoration(a)
            case Value(value, className) => new TypeDecoration(value, className)
          }).setBlock(!instrumentation.render.isInstanceOf[Value]).setSide(1))

          decoration.mapMode = MapMode.TrackBefore
          decoration.range(instrumentation.position.end).asInstanceOf[Range[Decoration]]
        }
      }
      .toSeq
    val x: js.Array[Range[Decoration]] = js.Array(deco: _*)
    Decoration.set(x, true)
  }

  val addTypeDecorations = StateEffect.define[DecorationSet]()
  val filterTypeDecorations = StateEffect.define[DecorationSet]()

  // This method is a workaround for
  private def updateDecorationPositions(previousValue: DecorationSet, transaction: Transaction): DecorationSet = {
    val newNewlines: ListBuffer[Int] = ListBuffer.empty
    val decorationsToReAdd: ListBuffer[Range[Decoration]] = ListBuffer.empty
    var value = previousValue
    transaction.changes.iterChanges((_, _, fromB, toB, _) => {
      transaction.newDoc.sliceString(fromB, toB).lastOption.foreach {
        case '\n' => newNewlines.addAll(List(fromB.toInt))
        case _ =>
      }
    })

    val iter = previousValue.iter()
    while (iter.value != null) {
      if (newNewlines.contains(iter.from))
        decorationsToReAdd.addOne(iter.value.asInstanceOf[Decoration].range(iter.from).asInstanceOf[Range[Decoration]])
      iter.next()
    }

    val newValues = previousValue
      .update(new js.Object {
        var filter = filterFunction(newNewlines.toList)
      }.asInstanceOf[RangeSetUpdate[DecorationSet]])
      .map(transaction.changes)

    if (decorationsToReAdd.isEmpty)
      newValues
    else
      newValues.update(new js.Object {
        var add = js.Array(decorationsToReAdd.toSeq: _*)
      }.asInstanceOf[RangeSetUpdate[DecorationSet]])
  }

  def updateState(previousValue: DecorationSet, transaction: Transaction): DecorationSet = {
    val (addEffects, filterEffects) = transaction.effects
      .filter(effect => { effect.is(addTypeDecorations) | effect.is(filterTypeDecorations) })
      .partition(_.is(addTypeDecorations))

    addEffects.headOption match {
      case Some(stateEffect) => {
        val decorationSet = stateEffect.value.asInstanceOf[DecorationSet]
        if (decorationSet.size > 0) decorationSet else Decoration.none
      }
      case _ =>
        updateDecorationPositions(previousValue, transaction)
    }
  }

  def filterFunction(ignoredRanges: List[Int]): js.Function2[Double, Double, Boolean] = { (from, to) =>
    !ignoredRanges.contains(from)
  }

  def updateTypeDecorations(
      editorView: UseStateF[CallbackTo, EditorView],
      prevProps: Option[Editor],
      props: Editor
  ): AsyncCallback[Unit] =
    Callback {
      val decorations = createDecorations(props.instrumentations, props.attachedDoms, editorView.value.state.doc.length.toInt + 1)
      val addTypesEffect = addTypeDecorations.of(decorations)
      val changes = new js.Object {
        var desc = new js.Object {
          var length = prevProps.map(_.code.length).getOrElse(0)
          var newLength = props.code.length
          var empty = newLength == length
        }.asInstanceOf[ChangeDesc]
      }.asInstanceOf[ChangeSpec]

      editorView.value.dispatch(
        TransactionSpec()
          .setChanges(changes)
          .setEffects(addTypesEffect.asInstanceOf[StateEffect[Any]])
      )
    }.when_(
        prevProps.isDefined &&
          (props.instrumentations != prevProps.get.instrumentations)
      ).async

  def stateFieldSpec(props: Editor) =
    StateFieldSpec[DecorationSet](
      create = _ => createDecorations(props.instrumentations, props.attachedDoms, props.code.length),
      update = updateState,
    ).setProvide(v => EditorView.decorations.from(v))

  def apply(props: Editor): Extension = StateField.define(stateFieldSpec(props)).extension
}
