package io.layertrace.app.io

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.layertrace.app.editor.WorkspaceMode
import io.layertrace.core.model.MeasureUnit
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Persistent user preferences: workspace mode, measurement unit and recent files. */
class Prefs(private val context: Context) {
    data class Recent(val uri: String, val name: String)
    data class Snapshot(val workspace: WorkspaceMode, val unit: MeasureUnit, val recent: List<Recent>)

    private val kWorkspace = stringPreferencesKey("workspace")
    private val kUnit = stringPreferencesKey("unit")
    private val kRecent = stringPreferencesKey("recent")

    suspend fun load(): Snapshot {
        val p = context.dataStore.data.first()
        return Snapshot(
            workspace = p[kWorkspace]?.let { runCatching { WorkspaceMode.valueOf(it) }.getOrNull() } ?: WorkspaceMode.KeyboardMouse,
            unit = p[kUnit]?.let { runCatching { MeasureUnit.valueOf(it) }.getOrNull() } ?: MeasureUnit.Millimeters,
            recent = decode(p[kRecent]),
        )
    }

    suspend fun setWorkspace(m: WorkspaceMode) { context.dataStore.edit { it[kWorkspace] = m.name } }
    suspend fun setUnit(u: MeasureUnit) { context.dataStore.edit { it[kUnit] = u.name } }

    suspend fun addRecent(uri: String, name: String): List<Recent> {
        var out = emptyList<Recent>()
        context.dataStore.edit { p ->
            out = (listOf(Recent(uri, name)) + decode(p[kRecent]).filterNot { it.uri == uri }).take(12)
            p[kRecent] = encode(out)
        }
        return out
    }

    suspend fun removeRecent(uri: String): List<Recent> {
        var out = emptyList<Recent>()
        context.dataStore.edit { p ->
            out = decode(p[kRecent]).filterNot { it.uri == uri }
            p[kRecent] = encode(out)
        }
        return out
    }

    private fun encode(list: List<Recent>) = list.joinToString("\n") { "${it.uri}\t${it.name.replace('\t', ' ').replace('\n', ' ')}" }
    private fun decode(s: String?): List<Recent> = s?.split('\n')?.mapNotNull { line ->
        val i = line.indexOf('\t')
        if (i <= 0) null else Recent(line.substring(0, i), line.substring(i + 1))
    } ?: emptyList()
}
