package jp.example.mykeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Switch

class MainActivity : Activity() {
    private val prefs by lazy {
        getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        layout.addView(TextView(this).apply {
            text = "MyKeyboard"
            textSize = 26f
        })

        layout.addView(TextView(this).apply {
            text = "キーボードの高さ"
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        })

        val minHeight = 220
        val maxHeight = 440
        val currentHeight = prefs.getInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP)
            .coerceIn(minHeight, maxHeight)

        val heightLabel = TextView(this).apply {
            textSize = 16f
            text = "高さ：${currentHeight} dp"
        }
        layout.addView(heightLabel)

        layout.addView(SeekBar(this).apply {
            max = maxHeight - minHeight
            progress = currentHeight - minHeight
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val height = minHeight + progress
                    heightLabel.text = "高さ：${height} dp"
                    prefs.edit().putInt(KEY_HEIGHT_DP, height).apply()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })

        layout.addView(TextView(this).apply {
            text = "設定した高さは、次にキーボードを表示したときに反映されます。"
            textSize = 13f
            setPadding(0, 0, 0, pad)
        })

        layout.addView(TextView(this).apply {
            text = "フリック感度（判定距離）"
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        })
        val flickValue = prefs.getInt(KEY_FLICK_THRESHOLD_DP, DEFAULT_FLICK_THRESHOLD_DP).coerceIn(8, 60)
        val flickLabel = TextView(this).apply { text = "判定距離：${flickValue} dp" }
        layout.addView(flickLabel)
        layout.addView(SeekBar(this).apply {
            max = 52
            progress = flickValue - 8
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 8 + progress
                    flickLabel.text = "判定距離：${value} dp"
                    prefs.edit().putInt(KEY_FLICK_THRESHOLD_DP, value).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })

        layout.addView(TextView(this).apply {
            text = "長押し感度（反応するまでの時間）"
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        })
        val longValue = prefs.getInt(KEY_LONG_PRESS_MS, DEFAULT_LONG_PRESS_MS).coerceIn(250, 1000)
        val longLabel = TextView(this).apply { text = "長押し：${longValue} ms" }
        layout.addView(longLabel)
        layout.addView(SeekBar(this).apply {
            max = 750
            progress = longValue - 250
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 250 + progress
                    longLabel.text = "長押し：${value} ms"
                    prefs.edit().putInt(KEY_LONG_PRESS_MS, value).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })

        layout.addView(Switch(this).apply {
            text = "入力学習を有効にする"
            textSize = 16f
            isChecked = prefs.getBoolean(KEY_LEARNING_ENABLED, DEFAULT_LEARNING_ENABLED)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(KEY_LEARNING_ENABLED, enabled).apply()
            }
        })

        layout.addView(TextView(this).apply {
            text = "学習をOFFにすると、Mozcの履歴学習とユーザー辞書を使わない変換になります。"
            textSize = 13f
            setPadding(0, pad / 2, 0, pad)
        })

        layout.addView(Button(this).apply {
            text = "単語登録"
            setOnClickListener {
                val form = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, 0)
                }
                val readingInput = EditText(this@MainActivity).apply {
                    hint = "よみ（ひらがな）"
                    setSingleLine(true)
                }
                val wordInput = EditText(this@MainActivity).apply {
                    hint = "登録する単語"
                    setSingleLine(true)
                }
                form.addView(readingInput)
                form.addView(wordInput)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Mozcユーザー辞書に登録")
                    .setView(form)
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("登録") { _, _ ->
                        val reading = readingInput.text.toString().trim()
                        val word = wordInput.text.toString().trim()
                        if (reading.isEmpty() || word.isEmpty()) {
                            android.widget.Toast.makeText(this@MainActivity, "よみと単語を入力してください", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val saved = jp.example.mykeyboard.mozc.MozcSession(this@MainActivity).addUserWord(reading, word)
                            android.widget.Toast.makeText(this@MainActivity, if (saved) "単語を登録しました" else "登録できませんでした。Mozcの起動状態を確認してください", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    .show()
            }
        })

        layout.addView(Button(this).apply {
            text = "キーボード設定を開く"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        layout.addView(Button(this).apply {
            text = "キーボードを選択"
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        })

        setContentView(layout)
    }

    companion object {
        const val KEY_HEIGHT_DP = "keyboard_height_dp"
        const val DEFAULT_HEIGHT_DP = 320
        const val KEY_FLICK_THRESHOLD_DP = "flick_threshold_dp"
        const val DEFAULT_FLICK_THRESHOLD_DP = 28
        const val KEY_LONG_PRESS_MS = "long_press_ms"
        const val DEFAULT_LONG_PRESS_MS = 480
        const val KEY_LEARNING_ENABLED = "learning_enabled"
        const val DEFAULT_LEARNING_ENABLED = true
    }
}
