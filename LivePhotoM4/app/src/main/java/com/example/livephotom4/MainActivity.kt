package com.example.livephotom4

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live Photo per POCO M4 Pro 4G.
 * Alla pressione dello scatto registra ~3 secondi di video e scatta una foto
 * a meta' clip. Salva JPEG (Pictures/LivePhotoM4) + MP4 (Movies/LivePhotoM4)
 * con lo stesso nome.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var shutter: Button
    private lateinit var status: TextView

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val handler = Handler(Looper.getMainLooper())

    // POCO M4 Pro 4G = codename "fleur". Il Redmi Note 11S usa lo stesso codename,
    // quindi si controlla anche il modello (2201117PG globale, 2201117PI India).
    private fun isSupportedDevice(): Boolean {
        val deviceOk = Build.DEVICE.equals("fleur", ignoreCase = true)
        val modelOk = Build.MODEL.uppercase(Locale.ROOT) in setOf("2201117PG", "2201117PI")
        return deviceOk && modelOk
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Toast.makeText(this, "Serve il permesso fotocamera", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isSupportedDevice()) {
            Toast.makeText(
                this,
                "App disponibile solo su POCO M4 Pro 4G",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        buildUi()

        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this)
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            text = "Pronto"
            gravity = Gravity.CENTER
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { topMargin = 96 }
        )

        shutter = Button(this).apply {
            text = "LIVE"
            setOnClickListener { captureLivePhoto() }
        }
        root.addView(
            shutter,
            FrameLayout.LayoutParams(260, 260, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = 120 }
        )

        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    videoCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Errore fotocamera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureLivePhoto() {
        val video = videoCapture ?: return
        val image = imageCapture ?: return
        if (recording != null) return

        shutter.isEnabled = false
        status.text = "Registro..."

        val name = "LIVE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val videoValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/LivePhotoM4")
        }
        val videoOptions = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(videoValues).build()

        var pending = video.output.prepareRecording(this, videoOptions)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                shutter.isEnabled = true
                status.text = if (event.hasError()) "Errore video" else "Salvata: $name"
            }
        }

        // Foto a meta' clip (1,5 s)
        handler.postDelayed({ takePhoto(image, name) }, 1500)
        // Fine clip a 3 s
        handler.postDelayed({ recording?.stop() }, 3000)
    }

    private fun takePhoto(image: ImageCapture, name: String) {
        val photoValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LivePhotoM4")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues
        ).build()

        image.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {}
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity, "Errore foto: ${exc.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recording?.stop()
        super.onDestroy()
    }
}
