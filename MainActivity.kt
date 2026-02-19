package com.app.leica

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.exifinterface.media.ExifInterface
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WatermarkScreen()
                }
            }
        }
    }
}

@Composable
fun WatermarkScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var deviceName by remember { mutableStateOf("Leica Q2") }
    var photoParams by remember { mutableStateOf("28mm f/1.7 1/1000 ISO100") }
    var userRotationAngle by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf("Ready. Please select a photo.") }
    var isProcessing by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            userRotationAngle = 0 // Reset rotation on new photo
            statusMessage = "Photo selected. Check EXIF."
            readExifInfo(context, uri) { d, p ->
                deviceName = d
                photoParams = p
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Preview Area
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selectedImageUri != null) {
                Image(
                    painter = rememberAsyncImagePainter(selectedImageUri),
                    contentDescription = "Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("No Image Selected")
            }
        }

        // Exif Edit Area
        OutlinedTextField(value = deviceName, onValueChange = { deviceName = it }, label = { Text("Camera Model") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = photoParams, onValueChange = { photoParams = it }, label = { Text("Parameters") }, modifier = Modifier.fillMaxWidth())
        
        Text(text = statusMessage, color = MaterialTheme.colorScheme.primary)

        // Buttons
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { 
                Text("Select") 
            }
            
            Button(onClick = { userRotationAngle = (userRotationAngle + 90) % 360 }, enabled = selectedImageUri != null) { 
                Text("Rotate") 
            }

            Button(
                onClick = {
                    if (selectedImageUri != null && !isProcessing) {
                        isProcessing = true
                        statusMessage = "Processing..."
                        scope.launch {
                            val success = processAndSaveImage(context, selectedImageUri!!, deviceName, photoParams, userRotationAngle)
                            statusMessage = if (success) "Saved successfully to Gallery!" else "Error processing image."
                            isProcessing = false
                        }
                    }
                },
                enabled = selectedImageUri != null
            ) { Text(if (isProcessing) "..." else "Generate") }
        }
    }
}

fun readExifInfo(context: Context, uri: Uri, onResult: (String, String) -> Unit) {
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Leica Q2"
            
            // Format Focal Length
            val focalParts = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.split("/")
            val focal = if (focalParts != null && focalParts.size == 2) {
                (focalParts[0].toDouble() / focalParts[1].toDouble()).toInt().toString() + "mm"
            } else "28mm"

            val fNum = exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: "1.7"
            val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: "100"
            
            val exposureStr = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            val exposure = exposureStr?.let {
                val d = it.toDoubleOrNull()
                if (d != null && d < 1) "1/${(1/d).toInt()}" else it
            } ?: "1/1000"
            
            val params = "$focal f/$fNum $exposure ISO$iso"
            onResult(model, params)
        }
    } catch (e: Exception) {
        onResult("Leica Q2", "28mm f/1.7 1/1000 ISO100")
    }
}

suspend fun processAndSaveImage(context: Context, uri: Uri, deviceText: String, paramText: String, userRotation: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext false
        val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return@withContext false
        
        // 1. Handle Rotation (Exif + User input)
        var exifRotation = 0
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            exifRotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
        
        val totalRotation = (exifRotation + userRotation) % 360
        val matrix = Matrix()
        if (totalRotation != 0) matrix.postRotate(totalRotation.toFloat())
        
        val rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
        
        val width = rotatedBitmap.width
        val height = rotatedBitmap.height

        // 2. Calculate Proportions
        val borderHeight = (width * 0.12f).toInt()
        val newHeight = height + borderHeight
        val padding = width * 0.04f

        val resultBitmap = Bitmap.createBitmap(width, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(rotatedBitmap, 0f, 0f, null)

        // 3. Prepare Custom Font (Only used for Watermark)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            try {
                typeface = ResourcesCompat.getFont(context, R.font.font)
            } catch (e: Exception) {
                // Fallback handled silently
            }
        }

        // 4. Draw Logo
        val logoSize = (borderHeight * 0.55f).toInt()
        val logoX = padding
        val logoY = height + (borderHeight - logoSize) / 2f
        val logoDrawable = ContextCompat.getDrawable(context, R.drawable.logo)
        logoDrawable?.let {
            it.setBounds(logoX.toInt(), logoY.toInt(), (logoX + logoSize).toInt(), (logoY + logoSize).toInt())
            it.draw(canvas)
        }

        // 5. Draw Model Text
        val textX = logoX + logoSize + (padding * 0.5f)
        val contentCenterY = height + borderHeight / 2f
        textPaint.textSize = borderHeight * 0.22f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(deviceText, textX, contentCenterY + (textPaint.textSize / 3), textPaint)

        // 6. Draw Parameter Text
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.DKGRAY
        textPaint.textSize = borderHeight * 0.18f
        canvas.drawText(paramText, width - padding, contentCenterY + (textPaint.textSize / 3), textPaint)

        // Save
        val filename = "LeicaMaker_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeicaMaker")
        }

        val resolver = context.contentResolver
        val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (outUri != null) {
            resolver.openOutputStream(outUri)?.use { stream ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            return@withContext true
        }
        return@withContext false
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext false
    }
}
