package io.legado.app.ui.association

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.viewModels
import androidx.core.os.postDelayed
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.canRead
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.readUri
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

class FileAssociationActivity :
    VMBaseActivity<ActivityTranslucenceBinding, FileAssociationViewModel>() {

    private val omiPickBook get() = intent.getBooleanExtra("omiPickBook", false)
    private var pickerLaunched = false
    private var copyPickedBook = false
    private val bookPicker = registerForActivityResult(OpenDocument()) { uri ->
        if (uri == null) {
            finish()
        } else {
            lifecycleScope.launch {
                runCatching {
                    val name = withContext(IO) { FileDoc.fromUri(uri, false).name }
                    require(name.matches(Regex(".*\\.(txt|epub|mobi|azw3|docx|md|markdown)", RegexOption.IGNORE_CASE))) {
                        getString(R.string.omi_formats)
                    }
                    copyPickedBook = runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        false
                    }.getOrDefault(true)
                    intent.data = uri
                    viewModel.dispatchIntent(uri)
                }.onFailure {
                    if (it is CancellationException) throw it
                    viewModel.errorLive.value = it.localizedMessage ?: getString(R.string.omi_open_error)
                }
            }
        }
    }

    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        intent.data?.let { uri ->
            it.uri?.let { treeUri ->
                AppConfig.defaultBookTreeUri = treeUri.toString()
                importBook(treeUri, uri)
            } ?: let {
                val storageHelp = String(assets.open("storageHelp.md").readBytes())
                toastOnUi(storageHelp)
                importBook(null, uri)
            }
        }
    }

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)

    override val viewModel by viewModels<FileAssociationViewModel>()

    private val handler by lazy {
        buildMainHandler()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pickerLaunched = savedInstanceState?.getBoolean("omiPickerLaunched") ?: false
        copyPickedBook = savedInstanceState?.getBoolean("omiCopyPickedBook") ?: false
        if (omiPickBook && intent.data == null) {
            savedInstanceState?.getString("omiPickedBookUri")?.let { intent.data = Uri.parse(it) }
        }
        binding.rotateLoading.visible()
        viewModel.importBookLiveData.observe(this) { uri ->
            importBook(uri)
        }
        viewModel.onLineImportLive.observe(this) {
            startActivity<OnLineImportActivity> {
                data = it
            }
            finish()
        }
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(ImportBookSourceDialog(it.second, true))
                "rssSource" -> showDialogFragment(ImportRssSourceDialog(it.second, true))
                "replaceRule" -> showDialogFragment(ImportReplaceRuleDialog(it.second, true))
                "httpTts" -> showDialogFragment(ImportHttpTtsDialog(it.second, true))
                "theme" -> showDialogFragment(ImportThemeDialog(it.second, true))
                "txtRule" -> showDialogFragment(ImportTxtTocRuleDialog(it.second, true))
                "dictRule" -> showDialogFragment(ImportDictRuleDialog(it.second, true))
            }
        }
        viewModel.errorLive.observe(this) {
            binding.rotateLoading.gone()
            toastOnUi(it)
            handler.postDelayed(2000) {
                finish()
            }
        }
        viewModel.openBookLiveData.observe(this) {
            binding.rotateLoading.gone()
            startActivityForBook(it)
            finish()
        }
        viewModel.notSupportedLiveData.observe(this) { data ->
            binding.rotateLoading.gone()
            alert(
                title = appCtx.getString(R.string.draw),
                message = appCtx.getString(R.string.file_not_supported, data.second)
            ) {
                yesButton {
                    importBook(data.first)
                }
                noButton {
                    finish()
                }
                onCancelled {
                    finish()
                }
            }
        }
        if (omiPickBook && intent.data == null) {
            if (!pickerLaunched) {
                pickerLaunched = true
                bookPicker.launch(arrayOf(
                    "text/plain", "application/epub+zip",
                    "application/x-mobipocket-ebook", "application/mobi",
                    "application/azw3", "application/x-mobi8-ebook", "application/vnd.amazon.ebook",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "text/markdown", "text/x-markdown", "application/octet-stream"
                ))
            }
            return
        }
        // The retained result already resumes an import after an activity recreation.
        if (omiPickBook && (viewModel.importBookLiveData.value != null || viewModel.openBookLiveData.value != null)) return
        intent.data?.let { data ->
            if (data.isContentScheme() && data.canRead()) {
                viewModel.dispatchIntent(data)
            } else {
                PermissionsCompat.Builder()
                    .addPermissions(*Permissions.Group.STORAGE)
                    .rationale(R.string.tip_perm_request_storage)
                    .onGranted {
                        viewModel.dispatchIntent(data)
                    }.onDenied {
                        toastOnUi("请求存储权限失败。")
                        handler.postDelayed(2000) {
                            finish()
                        }
                    }.request()
            }
        } ?: finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("omiPickerLaunched", pickerLaunched)
        outState.putBoolean("omiCopyPickedBook", copyPickedBook)
        outState.putString("omiPickedBookUri", intent.data?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun importBook(uri: Uri) {
        if (omiPickBook) {
            val privateTree = if (copyPickedBook) {
                File(filesDir, "omi-books/${MD5Utils.md5Encode(uri.toString())}").apply {
                    mkdirs()
                }.let { Uri.fromFile(it) }
            } else null
            importBook(privateTree, uri)
            return
        }
        if (uri.isContentScheme()) {
            val treeUriStr = AppConfig.defaultBookTreeUri
            if (treeUriStr.isNullOrEmpty()) {
                localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                    mode = HandleFileContract.DIR_SYS
                }
            } else {
                importBook(Uri.parse(treeUriStr), uri)
            }
        } else {
            importBook(null, uri)
        }
    }

    private fun importBook(treeUri: Uri?, uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    if (treeUri == null) {
                        viewModel.importBook(uri) { ensureActive() }
                    } else if (treeUri.isContentScheme()) {
                        val treeDoc =
                            DocumentFile.fromTreeUri(this@FileAssociationActivity, treeUri)
                        if (!treeDoc!!.checkWrite()) {
                            throw InvalidBooksDirException(
                                "请重新设置书籍保存位置\nPermission Denial"
                            )
                        }
                        readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            var doc = treeDoc.findFile(name)
                            if (doc == null || fileDoc.lastModified > doc.lastModified()) {
                                if (doc == null) {
                                    doc = treeDoc.createFile(FileUtils.getMimeType(name), name)
                                        ?: throw InvalidBooksDirException(
                                            "请重新设置书籍保存位置\nPermission Denial"
                                        )
                                }
                                contentResolver.openOutputStream(doc.uri)!!.use { oStream ->
                                    inputStream.copyTo(oStream)
                                    oStream.flush()
                                }
                            }
                            viewModel.importBook(doc.uri) { ensureActive() }
                        }
                    } else {
                        val treeFile = File(treeUri.path ?: treeUri.toString())
                        if (!treeFile.checkWrite()) {
                            throw InvalidBooksDirException(
                                "请重新设置书籍保存位置\nPermission Denial"
                            )
                        }
                        readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            val file = treeFile.getFile(name)
                            if (!file.exists() || fileDoc.lastModified > file.lastModified()) {
                                FileOutputStream(file).use { oStream ->
                                    inputStream.copyTo(oStream)
                                    oStream.flush()
                                }
                            }
                            viewModel.importBook(Uri.fromFile(file)) { ensureActive() }
                        }
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                if (it is InvalidBooksDirException && !omiPickBook) {
                    localBookTreeSelect.launch {
                        title = getString(R.string.select_book_folder)
                        mode = HandleFileContract.DIR_SYS
                    }
                } else {
                    val msg = "导入书籍失败\n${it.localizedMessage}"
                    AppLog.put(msg, it)
                    toastOnUi(msg)
                    handler.postDelayed(2000) {
                        finish()
                    }
                }
            }
        }
    }

}
