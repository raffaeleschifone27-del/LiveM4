package com.example.livephotom4

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
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
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live Photo per POCO M4 Pro 4G.
 * Allo scatto registra ~3 secondi di video e scatta una foto a meta' clip,
 * poi unisce tutto in un'unica Motion Photo (JPEG con video MP4 incorporato)
 * salvata in Pictures/LivePhotoM4.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var shutter: Button
    private lateinit var status: TextView

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val handler = Handler(Looper.getMainLooper())

    private var baseName = ""
    private var photoUri: Uri? = null
    private var videoUri: Uri? = null
    private var photoDone = false
    private var videoDone = false

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

        baseName = "LIVE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        photoUri = null
        videoUri = null
        photoDone = false
        videoDone = false

        val videoValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, baseName)
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
                videoUri = if (event.hasError()) null else event.outputResults.outputUri
                videoDone = true
                maybeMerge()
            }
        }

        // Foto a meta' clip (1,5 s)
        handler.postDelayed({ takePhoto(image) }, 1500)
        // Fine clip a 3 s
        handler.postDelayed({ recording?.stop() }, 3000)
    }

    private fun takePhoto(image: ImageCapture) {
        val photoValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, baseName)
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
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    photoUri = output.savedUri
                    photoDone = true
                    maybeMerge()
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity, "Errore foto: ${exc.message}", Toast.LENGTH_LONG
                    ).show()
                    photoUri = null
                    photoDone = true
                    maybeMerge()
                }
            }
        )
    }

    /** Quando foto e video sono entrambi pronti li unisce in una Motion Photo. */
    private fun maybeMerge() {
        if (!photoDone || !videoDone) return

        val photo = photoUri
        val video = videoUri
        if (photo == null || video == null) {
            status.text = "Salvato (file separati)"
            shutter.isEnabled = true
            return
        }

        status.text = "Unisco foto e video..."
        val name = baseName
        Thread {
            var ok = true
            try {
                mergeMotionPhoto(photo, video, name)
            } catch (e: Exception) {
                ok = false
            }
            runOnUiThread {
                status.text = if (ok) "Live Photo salvata" else "Errore unione, file separati"
                shutter.isEnabled = true
            }
        }.start()
    }

    private fun mergeMotionPhoto(photo: Uri, video: Uri, name: String) {
        val jpeg = contentResolver.openInputStream(photo)!!.use { it.readBytes() }
        val mp4 = contentResolver.openInputStream(video)!!.use { it.readBytes() }

        val xmp = buildXmp(mp4.size).toByteArray(Charsets.UTF_8)
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val segLen = 2 + header.size + xmp.size
        require(segLen <= 65535) { "XMP troppo grande" }

        val insertAt = findInsertPosition(jpeg)

        val out = ByteArrayOutputStream(jpeg.size + mp4.size + segLen + 4)
        out.write(jpeg, 0, insertAt)
        out.write(0xFF)
        out.write(0xE1)
        out.write(segLen shr 8)
        out.write(segLen and 0xFF)
        out.write(header)
        out.write(xmp)
        out.write(jpeg, insertAt, jpeg.size - insertAt)
        out.write(mp4)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MVIMG_" + name.removePrefix("LIVE_"))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LivePhotoM4")
        }
        val merged = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        contentResolver.openOutputStream(merged)!!.use { it.write(out.toByteArray()) }

        // Rimuove i due file separati
        contentResolver.delete(photo, null, null)
        contentResolver.delete(video, null, null)
    }

    /** Posizione dopo gli header APP0/APP1 (EXIF) dove inserire l'XMP. */
    private fun findInsertPosition(jpeg: ByteArray): Int {
        var pos = 2 // dopo SOI (FFD8)
        while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker != 0xE0 && marker != 0xE1) break
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            pos += 2 + len
        }
        return pos
    }

    private fun buildXmp(videoSize: Int): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="1500000" GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="$videoSize" GCamera:MicroVideoPresentationTimestampUs="1500000"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoSize" Item:Padding="0"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recording?.stop()
        super.onDestroy()
    }
}
