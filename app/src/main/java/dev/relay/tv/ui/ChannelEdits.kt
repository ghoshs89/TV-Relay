package dev.relay.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Channel
import dev.relay.tv.data.ChannelCustom
import dev.relay.tv.data.ChannelGroup
import java.util.UUID
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add

/** Channel renames and the user's own groups, stored per device. */
object ChannelEdits {
    const val GROUP = "grp:"

    private val app get() = RelayApp.instance
    private var custom: ChannelCustom
        get() = app.store.channelCustom
        set(v) {
            app.store.channelCustom = v
            app.catalog.applyCustom()
        }

    fun rename(channelId: Int, name: String) {
        val c = custom
        custom = c.copy(renames = if (name.isBlank()) c.renames - channelId else c.renames + (channelId to name.trim()))
        AppLog.i("Channels", if (name.isBlank()) "Reset name of $channelId" else "Renamed $channelId")
    }

    fun isRenamed(channelId: Int) = custom.renames.containsKey(channelId)

    fun createGroup(name: String, firstChannel: Int?): ChannelGroup {
        val g = ChannelGroup(UUID.randomUUID().toString().take(8), name.trim(), listOfNotNull(firstChannel))
        custom = custom.copy(groups = custom.groups + g)
        return g
    }

    fun toggle(groupId: String, channelId: Int) = updateGroup(groupId) { g ->
        g.copy(channelIds = if (channelId in g.channelIds) g.channelIds - channelId else g.channelIds + channelId)
    }

    fun move(groupId: String, channelId: Int, delta: Int) = updateGroup(groupId) { g ->
        val ids = g.channelIds.toMutableList()
        val i = ids.indexOf(channelId)
        if (i < 0) return@updateGroup g
        val j = (i + delta).coerceIn(0, ids.lastIndex)
        if (i != j) {
            ids.removeAt(i)
            ids.add(j, channelId)
        }
        g.copy(channelIds = ids)
    }

    fun renameGroup(groupId: String, name: String) = updateGroup(groupId) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun deleteGroup(groupId: String) {
        custom = custom.copy(groups = custom.groups.filterNot { it.id == groupId })
    }

    private fun updateGroup(groupId: String, change: (ChannelGroup) -> ChannelGroup) {
        custom = custom.copy(groups = custom.groups.map { if (it.id == groupId) change(it) else it })
    }
}

@Composable
fun ChannelOptionsDialog(ch: Channel, groupId: String?, onDismiss: () -> Unit) {
    val store = RelayApp.instance.store
    store.version.intValue
    var renaming by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    val groups = store.channelCustom.groups
    val inGroup = groups.firstOrNull { it.id == groupId }

    TvDialog(onDismiss, width = 560.dp) {
        Text(ch.name, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Rename", { renaming = true }, focusRequester = first)
            if (ChannelEdits.isRenamed(ch.id)) PillButton("Original name", { ChannelEdits.rename(ch.id, "") })
        }
        if (inGroup != null) {
            Text("In ${inGroup.name}", color = Palette.dim, fontSize = Type.small)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Move up", { ChannelEdits.move(inGroup.id, ch.id, -1) })
                PillButton("Move down", { ChannelEdits.move(inGroup.id, ch.id, +1) })
                PillButton("Remove", {
                    ChannelEdits.toggle(inGroup.id, ch.id)
                    onDismiss()
                }, danger = true)
            }
        }
        Text("Your groups", color = Palette.dim, fontSize = Type.small)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(groups) { g ->
                val member = ch.id in g.channelIds
                ListRow(
                    g.name,
                    { ChannelEdits.toggle(g.id, ch.id) },
                    selected = member,
                    icon = if (member) Icons.Filled.Check else Icons.Filled.Add,
                    trailing = "${g.channelIds.size}",
                )
            }
            item { ListRow("New group…", { newGroup = true }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { PillButton("Done", onDismiss) }
    }

    if (renaming) {
        TextEditDialog(
            "Rename channel", ch.name, { renaming = false },
            onSave = {
                ChannelEdits.rename(ch.id, it)
                renaming = false
            },
            hint = "Leave empty to go back to the provider's name.",
        )
    }
    if (newGroup) {
        TextEditDialog(
            "New group", "", { newGroup = false },
            onSave = {
                if (it.isNotBlank()) ChannelEdits.createGroup(it, ch.id)
                newGroup = false
            },
            hint = "e.g. News, Kids, Sports. This channel is added to it.",
        )
    }
}

/** Settings → Channel groups → one group. */
@Composable
fun GroupManageDialog(group: ChannelGroup, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    TvDialog(onDismiss) {
        Text(group.name, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text("${group.channelIds.size} channels. Reorder them from Live TV → this group → a channel → Options.", color = Palette.dim, fontSize = Type.small)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Rename", { renaming = true }, focusRequester = first)
                PillButton(if (confirmDelete) "Tap again to delete" else "Delete group", {
                    if (confirmDelete) {
                        ChannelEdits.deleteGroup(group.id)
                        onDismiss()
                    } else {
                        confirmDelete = true
                    }
                }, danger = true)
                PillButton("Close", onDismiss)
            }
        }
    }
    if (renaming) {
        TextEditDialog(
            "Rename group", group.name, { renaming = false },
            onSave = {
                ChannelEdits.renameGroup(group.id, it)
                renaming = false
            },
        )
    }
}
