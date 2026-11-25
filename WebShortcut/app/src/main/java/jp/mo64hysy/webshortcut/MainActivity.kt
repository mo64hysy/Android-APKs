package jp.mo64hysy.webshortcut

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var nameField: EditText
    private lateinit var urlField: EditText
    private lateinit var status: TextView
    private var selectedIcon = ""
    private var selectedScript = ""
    private var editingId: String? = null
    private val pickIcon = 10
    private val pickScript = 11
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderEntries()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 16)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply {
            text = "Web Shortcut"
            textSize = 26f
            setPadding(0, 0, 0, 8)
        })
        content.addView(TextView(this).apply {
            text = "URL・名前・アイコンを登録して、ホーム画面から独立したウィンドウで開きます。"
            textSize = 14f
        })
        nameField = field("ホーム画面に表示する名前").also { content.addView(it) }
        urlField = field("https://example.com").also { content.addView(it) }
        val iconButton = Button(this).apply {
            text = "アイコン画像を選択"
            setOnClickListener { chooseFile("image/*", pickIcon) }
        }
        content.addView(iconButton)
        val scriptButton = Button(this).apply {
            text = "UserScript（.user.js）を選択（任意）"
            setOnClickListener { chooseFile("*/*", pickScript) }
        }
        content.addView(scriptButton)
        content.addView(Button(this).apply {
            text = "保存してホーム画面に追加"
            setOnClickListener { saveEntry() }
        })
        content.addView(Button(this).apply {
            text = "入力欄をクリア"
            setOnClickListener { clearForm() }
        })
        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, 8, 0, 12)
        }
        content.addView(status)
        content.addView(TextView(this).apply {
            text = "登録済みサイト"
            textSize = 20f
            setPadding(0, 14, 0, 8)
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        // Keep app controls inside the safe area around system bars and display cutouts.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                left = safe.left
                top = safe.top
                right = safe.right
                bottom = safe.bottom
            } else {
                left = insets.systemWindowInsetLeft
                top = insets.systemWindowInsetTop
                right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(20 + left, 18 + top, 20 + right, 16 + bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 16f
        setSingleLine(true)
        setPadding(12, 10, 12, 10)
    }

    private fun chooseFile(type: String, request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = type
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("Legacy picker callback for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            if (requestCode == pickIcon) {
                selectedIcon = AppStore.copyUri(this, uri, "icon_${UUID.randomUUID()}")
                status.text = "アイコン画像を選択しました"
            } else if (requestCode == pickScript) {
                selectedScript = AppStore.copyUri(this, uri, "script_${UUID.randomUUID()}.user.js")
                status.text = "UserScriptを選択しました"
            }
        } catch (e: Exception) {
            Toast.makeText(this, "ファイルを読み込めません: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveEntry() {
        val name = nameField.text.toString().trim()
        var url = urlField.text.toString().trim()
        if (name.isBlank() || url.isBlank()) {
            Toast.makeText(this, "名前とURLを入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) url = "https://$url"
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.host.isNullOrBlank() || (uri?.scheme != "https" && uri?.scheme != "http")) {
            Toast.makeText(this, "有効なURLを入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        val id = editingId ?: UUID.randomUUID().toString()
        val old = AppStore.find(this, id)
        val entry = WebEntry(id, name, url, selectedIcon.ifBlank { old?.iconPath ?: "" },
            selectedScript.ifBlank { old?.scriptPath ?: "" })
        AppStore.save(this, entry)
        pinShortcut(entry)
        status.text = "「$name」を保存しました。ホーム画面への追加を確認してください。"
        clearForm()
        renderEntries()
    }

    private fun pinShortcut(entry: WebEntry) {
        val manager = getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) {
            Toast.makeText(this, "このランチャーはショートカット追加に対応していません", Toast.LENGTH_LONG).show()
            return
        }
        val launch = Intent(this, WebActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("webshortcut://entry/${entry.id}")
            putExtra(WebActivity.EXTRA_ID, entry.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val builder = ShortcutInfo.Builder(this, entry.id)
            .setShortLabel(entry.name.take(25))
            .setLongLabel(entry.name)
            .setIntent(launch)
        val bitmap = if (entry.iconPath.isNotBlank()) BitmapFactory.decodeFile(entry.iconPath) else null
        if (bitmap != null) builder.setIcon(Icon.createWithBitmap(bitmap))
        else builder.setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_view))
        manager.requestPinShortcut(builder.build(), null)
    }

    private fun renderEntries() {
        listBox.removeAllViews()
        val entries = AppStore.all(this)
        if (entries.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "まだ登録されていません。" })
        }
        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 10, 10, 10)
            }
            row.addView(TextView(this).apply {
                text = entry.name
                textSize = 17f
            })
            row.addView(TextView(this).apply {
                text = entry.url + if (entry.scriptPath.isNotBlank()) "\nUserScriptあり" else ""
                textSize = 12f
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this@MainActivity).apply {
                text = "編集・再追加"
                setOnClickListener {
                    editingId = entry.id
                    nameField.setText(entry.name)
                    urlField.setText(entry.url)
                    selectedIcon = entry.iconPath
                    selectedScript = entry.scriptPath
                    pinShortcut(entry)
                    status.text = "編集後に保存すると設定が更新されます。"
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(Button(this@MainActivity).apply {
                text = "削除"
                setOnClickListener {
                    AppStore.delete(this@MainActivity, entry.id)
                    getSystemService(ShortcutManager::class.java).disableShortcuts(
                        listOf(entry.id), "このサイトは削除されました")
                    renderEntries()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(actions)
            listBox.addView(row)
            listBox.addView(View(this).apply { setBackgroundColor(0x22000000) },
                LinearLayout.LayoutParams(-1, 1))
        }
    }

    private fun clearForm() {
        editingId = null
        nameField.setText("")
        urlField.setText("")
        selectedIcon = ""
        selectedScript = ""
    }
}
