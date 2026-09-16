package dev.anriudh.spotifyduo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var result: TextView
    private lateinit var whoAnirudh: RadioButton
    private lateinit var whoDivya: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlField = findViewById(R.id.baseUrl)
        tokenField = findViewById(R.id.token)
        result = findViewById(R.id.result)
        whoAnirudh = findViewById(R.id.whoAnirudh)
        whoDivya = findViewById(R.id.whoDivya)

        urlField.setText(baseUrl.ifBlank { DEFAULT_BASE_URL })
        tokenField.setText(deviceToken)
        when (selfId) {
            "anirudh" -> whoAnirudh.isChecked = true
            "divya" -> whoDivya.isChecked = true
        }

        findViewById<Button>(R.id.link).setOnClickListener { openLogin() }
        findViewById<Button>(R.id.save).setOnClickListener { saveAndTest() }
        findViewById<Button>(R.id.alarms).setOnClickListener { openExactAlarmSettings() }
        findViewById<Button>(R.id.battery).setOnClickListener { openAppSettings() }
    }

    private fun selectedWho(): String? = when {
        whoAnirudh.isChecked -> "anirudh"
        whoDivya.isChecked -> "divya"
        else -> null
    }

    private fun persistFields() {
        baseUrl = urlField.text.toString().ifBlank { DEFAULT_BASE_URL }
        deviceToken = tokenField.text.toString()
        selectedWho()?.let { selfId = it }
    }

    private fun openLogin() {
        val who = selectedWho()
        if (who == null) {
            result.text = getString(R.string.pick_who_first)
            return
        }
        persistFields()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl/auth/login?u=$who")))
    }

    private fun saveAndTest() {
        persistFields()
        if (deviceToken.isBlank()) {
            result.text = getString(R.string.paste_token_first)
            return
        }
        result.text = getString(R.string.checking)

        // HttpURLConnection on the main thread throws NetworkOnMainThreadException.
        Thread {
            val state = StateRepository.refresh(this, forced = true)
            val text = describe(state)
            PlaybackWidget.renderAll(this, state)
            runOnUiThread {
                result.text = text
                RefreshScheduler.ensureScheduled(this)
            }
        }.start()
    }

    private fun describe(state: DuoState?): String {
        if (state == null) return "Failed: ${lastError ?: "unknown error"}"
        val lines = state.users.joinToString("\n") { u ->
            when {
                u.needsLogin -> "${u.displayName}: not signed in to Spotify yet"
                u.isPlaying -> "${u.displayName}: playing ${u.trackName} — ${u.artistName}"
                u.hasTrack -> "${u.displayName}: last played ${u.trackName} — ${u.artistName}"
                else -> "${u.displayName}: nothing recorded yet"
            }
        }
        return "$lines\n\nClock offset vs server: ${state.clockSkewMs} ms"
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
            )
        } else {
            result.text = getString(R.string.alarms_not_needed)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
    }
}
