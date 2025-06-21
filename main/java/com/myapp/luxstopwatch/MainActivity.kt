package com.example.luxstopwatch

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.myapp.luxstopwatch.R
import java.io.File
import java.io.FileWriter
import java.util.*
import kotlin.concurrent.fixedRateTimer
import android.os.PowerManager

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var currentLux: Float = 0f
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var stopwatchTextView: TextView
    private lateinit var luxTextView: TextView
    private lateinit var minLuxInput: EditText
    private lateinit var startStopButton: Button
    private lateinit var exportButton: Button
    private lateinit var listView: ListView

    private var isMeasuring = false
    private var measurementTimer: Timer? = null
    private var stopwatchHandler: Handler? = null
    private var stopwatchRunnable: Runnable? = null
    private var stopwatchStartTime: Long = 0

    private var minLuxThreshold = 0f
    private val measurementData = mutableListOf<Pair<Long, Float>>()
    private lateinit var adapter: ArrayAdapter<String>

    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        stopwatchTextView = findViewById(R.id.stopwatchTextView)
        luxTextView = findViewById(R.id.luxTextView)
        minLuxInput = findViewById(R.id.minLuxInput)
        startStopButton = findViewById(R.id.startStopButton)
        exportButton = findViewById(R.id.exportButton)
        listView = findViewById(R.id.measurementList)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        startStopButton.setOnClickListener { toggleMeasurement() }
        exportButton.setOnClickListener { exportCSV() }
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopMeasurement()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            currentLux = event.values[0]
            luxTextView.text = "💡 Lux: %.2f".format(currentLux)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun toggleMeasurement() {
        if (isMeasuring) stopMeasurement() else startMeasurement()
    }

    private fun startMeasurement() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "LuxStopwatch::WakeLock")
        wakeLock?.acquire()

        val minLuxStr = minLuxInput.text.toString()
        minLuxThreshold = minLuxStr.toFloatOrNull() ?: run {
            Toast.makeText(this, "Ungültiger Minimalwert", Toast.LENGTH_SHORT).show()
            return
        }

        stopwatchStartTime = System.currentTimeMillis()
        measurementData.clear()
        adapter.clear()
        stopwatchTextView.visibility = TextView.VISIBLE

        // Timer zum Messen alle 60 Sekunden
        measurementTimer = fixedRateTimer(initialDelay = 0, period = 60_000) {
            val elapsedSeconds = (System.currentTimeMillis() - stopwatchStartTime) / 1000
            val elapsedMinutes = elapsedSeconds / 60
            measurementData.add(elapsedMinutes to currentLux)
            uiHandler.post {
                adapter.add("⏱ $elapsedMinutes Min • 💡 %.2f Lux".format(currentLux))
                adapter.notifyDataSetChanged()
                listView.smoothScrollToPosition(adapter.count - 1)
            }

            if (currentLux <= minLuxThreshold) {
                uiHandler.post {
                    playBeep()
                    Toast.makeText(this@MainActivity, "Minimalwert erreicht", Toast.LENGTH_SHORT).show()
                    stopMeasurement()
                }
            }
        }

        // Stoppuhr läuft sekündlich
        stopwatchHandler = Handler(Looper.getMainLooper())
        stopwatchRunnable = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - stopwatchStartTime) / 1000
                val minutes = elapsed / 60
                val seconds = elapsed % 60
                stopwatchTextView.text = "⏱ Laufzeit: %d:%02d".format(minutes, seconds)
                stopwatchHandler?.postDelayed(this, 1000)
            }
        }
        stopwatchHandler?.post(stopwatchRunnable!!)

        isMeasuring = true
        startStopButton.text = "Stop"
    }

    private fun stopMeasurement() {
        measurementTimer?.cancel()
        stopwatchHandler?.removeCallbacks(stopwatchRunnable!!)
        stopwatchTextView.visibility = TextView.GONE

        isMeasuring = false
        startStopButton.text = "Start"
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

    }

    private fun playBeep() {
        val mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
        mediaPlayer.setOnCompletionListener { it.release() }
        mediaPlayer.start()
    }

    private fun exportCSV() {
        if (measurementData.isEmpty()) {
            Toast.makeText(this, "Keine Messdaten vorhanden", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = System.currentTimeMillis()
        val csvFile = File(getExternalFilesDir(null), "lux_measurements_$timestamp.csv")
        val writer = FileWriter(csvFile)
        writer.append("Zeit (Minuten),Lux\n")
        measurementData.forEach {
            writer.append("${it.first},${it.second}\n")
        }
        writer.flush()
        writer.close()

        val uri = FileProvider.getUriForFile(this, "$packageName.provider", csvFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Lux-Messung")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "CSV senden über..."))
    }
}
