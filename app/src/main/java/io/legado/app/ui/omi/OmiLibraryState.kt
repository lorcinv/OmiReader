package io.legado.app.ui.omi

import android.content.Context
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Virtual folders never move or delete the source document. Stored with the app preferences for backup. */
class OmiLibraryState(private val context: Context) {
    data class Folder(val id: String, var name: String, var parent: String = "", var shelf: String = "")
    data class Tag(val id: String, var name: String)
    val folders = mutableListOf<Folder>()
    val tags = mutableListOf<Tag>()
    val bookFolders = mutableMapOf<String, String>()
    val bookTags = mutableMapOf<String, MutableSet<String>>()
    val shelfFolders = mutableMapOf<String, String>()
    val order = mutableMapOf<String, MutableList<String>>()
    var columns = 2
    var list = false
    private val prefs get() = context.defaultSharedPreferences
    init {
        val json = runCatching { JSONObject(prefs.getString("omiLibraryV2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        json.optJSONArray("folders")?.objects()?.forEach { folders += Folder(it.getString("id"), it.getString("name"), it.optString("parent"), it.optString("shelf")) }
        json.optJSONArray("tags")?.objects()?.forEach { tags += Tag(it.getString("id"), it.getString("name")) }
        json.optJSONObject("bookFolders")?.let { o -> o.keys().forEach { bookFolders[it] = o.getString(it) } }
        json.optJSONObject("shelfFolders")?.let { o -> o.keys().forEach { shelfFolders[it] = o.getString(it) } }
        json.optJSONObject("bookTags")?.let { o -> o.keys().forEach { bookTags[it] = o.getJSONArray(it).strings().toMutableSet() } }
        json.optJSONObject("order")?.let { o -> o.keys().forEach { order[it] = o.getJSONArray(it).strings().toMutableList() } }
        columns = json.optInt("columns", 2).coerceIn(2, 6)
        list = json.optBoolean("list")
    }
    fun save() {
        val json = JSONObject().put("columns", columns).put("list", list)
            .put("folders", JSONArray(folders.map { JSONObject().put("id", it.id).put("name", it.name).put("parent", it.parent).put("shelf", it.shelf) }))
            .put("tags", JSONArray(tags.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("bookFolders", JSONObject(bookFolders.toMap()))
            .put("shelfFolders", JSONObject(shelfFolders.toMap()))
        val tagObject = JSONObject(); bookTags.forEach { (key, value) -> tagObject.put(key, JSONArray(value.toList())) }
        val orderObject = JSONObject(); order.forEach { (key, value) -> orderObject.put(key, JSONArray(value)) }
        json.put("bookTags", tagObject).put("order", orderObject)
        prefs.edit().putString("omiLibraryV2", json.toString()).apply()
    }
    fun migrateGroups(groups: List<io.legado.app.data.entities.BookGroup>, books: List<io.legado.app.data.entities.Book>) {
        if (prefs.getBoolean("omiGroupsMigrated", false)) return
        groups.filter { it.groupId > 0 }.forEach { group ->
            val tag = tags.find { it.name == group.groupName } ?: Tag("legacy:${group.groupId}", group.groupName).also { tags += it }
            books.filter { it.group and group.groupId != 0L }.forEach { book -> bookTags.getOrPut(book.bookUrl) { mutableSetOf() }.add(tag.id) }
        }
        save()
        prefs.edit().putBoolean("omiGroupsMigrated", true).apply()
    }
    fun addFolder(name: String, parent: String, shelf: String = ""): Folder? {
        val clean = name.trim().take(80)
        if (clean.isBlank() || folders.any { it.parent == parent && it.shelf == shelf && it.name == clean }) return null
        return Folder(UUID.randomUUID().toString(), clean, parent, shelf).also { folders += it; save() }
    }
    fun addTag(name: String): Tag? {
        val clean = name.trim().removePrefix("#").take(40)
        if (clean.isBlank() || tags.any { it.name == clean }) return null
        return Tag(UUID.randomUUID().toString(), clean).also { tags += it; save() }
    }
    fun removeFolder(folder: Folder) {
        folders.filter { it.parent == folder.id }.forEach { it.parent = folder.parent }
        bookFolders.filterValues { it == folder.id }.keys.forEach { bookFolders[it] = folder.parent }
        shelfFolders.filterValues { it == folder.id }.keys.forEach { shelfFolders[it] = folder.parent }
        folders.remove(folder); order.remove(folder.id); save()
    }
    fun removeTag(tag: Tag) { tags.remove(tag); bookTags.values.forEach { it.remove(tag.id) }; save() }
    fun folderFor(bookUrl: String, shelf: String = "") = if (shelf.isEmpty()) bookFolders[bookUrl].orEmpty() else shelfFolders["$shelf|$bookUrl"].orEmpty()
    fun setFolder(bookUrl: String, folder: String, shelf: String = "") {
        if (shelf.isEmpty()) bookFolders[bookUrl] = folder else shelfFolders["$shelf|$bookUrl"] = folder
        save()
    }
    fun toggleTag(bookUrl: String, tag: String) {
        val selected = bookTags.getOrPut(bookUrl) { mutableSetOf() }
        if (!selected.add(tag)) selected.remove(tag)
        save()
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
