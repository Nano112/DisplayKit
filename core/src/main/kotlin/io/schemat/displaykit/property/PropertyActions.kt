package io.schemat.displaykit.property

import io.schemat.displaykit.action.ActionHandler
import io.schemat.displaykit.action.ActionIcon
import io.schemat.displaykit.action.ActionPage
import io.schemat.displaykit.action.ActionSpec

/** Render a property sheet through any action-menu backend. */
@JvmOverloads
fun PropertySheetModel.actionPage(
    id: String,
    title: String? = null,
    icon: (PropertyField) -> ActionIcon = { ActionIcon.Default }
): ActionPage = ActionPage(
    id = id,
    title = title,
    actions = fields.flatMap { field ->
        buildList {
            field.previousLabel?.let { label ->
                add(ActionSpec(
                    id = "${field.id}.previous",
                    label = label,
                    icon = icon(field),
                    onInvoke = ActionHandler { field.previous() }
                ))
            }
            add(ActionSpec(
                id = "${field.id}.next",
                label = field.nextLabel,
                icon = icon(field),
                onInvoke = ActionHandler { field.next() }
            ))
        }
    }
)
