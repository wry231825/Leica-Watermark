package com.app.leica

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.text.SimpleDateFormat
import java.util.Locale

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
    var deviceName by remember { mutableStateOf("LEICA Q (Typ 116)") }
    var lensModel by remember { mutableStateOf("") }
    var photoParams by remember { mutableStateOf("28mm   F 1.7   S 1/1000   ISO 250") }
    var photoDate by remember { mutableStateOf("Feb 23, 2025 at 17:50") }
    var userRotationAngle by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf("Ready. Please select a photo.") }
    var isProcessing by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            userRotationAngle = 0
            statusMessage = "Photo selected. Extracting EXIF..."
            readExifInfo(context, uri) { model, lens, params, date ->
                deviceName = model
                lensModel = lens
                photoParams = params
                photoDate = date
                statusMessage = "EXIF loaded. You can edit the text below."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(250.dp).padding(bottom = 5.dp),
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

        OutlinedTextField(value = deviceName, onValueChange = { deviceName = it }, label = { Text("Camera Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = lensModel, onValueChange = { lensModel = it }, label = { Text("Lens Model (Optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = photoParams, onValueChange = { photoParams = it }, label = { Text("Parameters") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = photoDate, onValueChange = { photoDate = it }, label = { Text("Date & Time") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        
        Text(text = statusMessage, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))

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
                        statusMessage = "Generating..."
                        scope.launch {
                            val success = processAndSaveImage(context, selectedImageUri!!, deviceName, lensModel, photoParams, photoDate, userRotationAngle)
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

fun readExifInfo(context: Context, uri: Uri, onResult: (String, String, String, String) -> Unit) {
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            var model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "LEICA Q (Typ 116)"
            model = model.uppercase(Locale.ROOT)
            
            val lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL) ?: ""

            val focalParts = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.split("/")
            val focal = if (focalParts != null && focalParts.size == 2) {
                (focalParts[0].toDouble() / focalParts[1].toDouble()).toInt().toString()
            } else "28"

            val fNum = exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: "1.7"
            val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: "250"
            
            val exposureStr = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            val exposure = exposureStr?.let {
                val d = it.toDoubleOrNull()
                if (d != null && d < 1) "1/${(1/d).toInt()}" else it
            } ?: "1/1000"
            
            val params = "${focal}mm   F $fNum   S $exposure   ISO $iso"
            
            val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            var formattedDate = "Feb 23, 2025 at 17:50"
            
            if (!rawDate.isNullOrEmpty()) {
                try {
                    val parser = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    val formatter = SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.US)
                    val dateObj = parser.parse(rawDate)
                    if (dateObj != null) {
                        formattedDate = formatter.format(dateObj)
                    }
                } catch (e: Exception) {
                }
            }
            
            onResult(model, lens, params, formattedDate)
        }
    } catch (e: Exception) {
        onResult("LEICA Q (Typ 116)", "", "28mm   F 1.7   S 1/1000   ISO 250", "Feb 23, 2025 at 17:50")
    }
}

suspend fun processAndSaveImage(
    context: Context, uri: Uri, deviceText: String, lensText: String, paramText: String, dateText: String, userRotation: Int
): Boolean = withContext(Dispatchers.IO) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext false
        val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return@withContext false
        
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

        val borderHeight = (width * 0.125f).toInt()
        val newHeight = height + borderHeight
        val padding = width * 0.035f 

        val resultBitmap = Bitmap.createBitmap(width, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(rotatedBitmap, 0f, 0f, null)

        // =========================================
        // 核心修改：分别加载 Light 和 Regular 字体
        // =========================================
        val typeLight = try { ResourcesCompat.getFont(context, R.font.font_light) } catch (e: Exception) { Typeface.DEFAULT }
        val typeRegular = try { ResourcesCompat.getFont(context, R.font.font_regular) } catch (e: Exception) { Typeface.DEFAULT_BOLD }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.isFakeBoldText = false // 关闭之前的假粗体，使用真字体

        val logoSize = (borderHeight * 0.55f).toInt()
        val logoX = padding
        val logoY = height + (borderHeight - logoSize) / 2f
        val logoDrawable = ContextCompat.getDrawable(context, R.drawable.logo)
        logoDrawable?.let {
            it.setBounds(logoX.toInt(), logoY.toInt(), (logoX + logoSize).toInt(), (logoY + logoSize).toInt())
            it.draw(canvas)
        }

        val leftTextX = logoX + logoSize + (padding * 0.6f)
        textPaint.textAlign = Paint.Align.LEFT
        
        // 1.1 "CAPTURED WITH" (使用 Regular，小字)
        textPaint.typeface = typeRegular
        textPaint.color = Color.parseColor("#999999")
        textPaint.textSize = borderHeight * 0.12f
        val capturedY = height + borderHeight * 0.4f
        canvas.drawText("CAPTURED WITH", leftTextX, capturedY, textPaint)

        // 1.2 相机型号 (使用 Regular，大字)
        textPaint.typeface = typeRegular
        textPaint.color = Color.parseColor("#111111")
        textPaint.textSize = borderHeight * 0.22f
        val modelY = height + borderHeight * 0.68f
        canvas.drawText(deviceText, leftTextX, modelY, textPaint)

        val rightTextX = width - padding
        textPaint.textAlign = Paint.Align.RIGHT
        val hasLens = lensText.isNotBlank()
        
        // 2.1 拍摄参数 (使用 Regular，黑色)
        textPaint.typeface = typeRegular
        textPaint.color = Color.parseColor("#222222")
        textPaint.textSize = borderHeight * 0.17f
        val paramsY = if (hasLens) height + borderHeight * 0.35f else height + borderHeight * 0.42f
        canvas.drawText(paramText, rightTextX, paramsY, textPaint)

        // 2.2 镜头型号 (使用 Light，灰色)
        if (hasLens) {
            textPaint.typeface = typeLight // 切换到细字体
            textPaint.color = Color.parseColor("#888888")
            textPaint.textSize = borderHeight * 0.15f
            val lensY = height + borderHeight * 0.58f
            canvas.drawText(lensText.uppercase(Locale.ROOT), rightTextX, lensY, textPaint)
        }

        // 2.3 拍摄时间 (使用 Light，灰色)
        textPaint.typeface = typeLight // 保持细字体
        textPaint.color = Color.parseColor("#888888")
        textPaint.textSize = borderHeight * 0.15f
        val dateY = if (hasLens) height + borderHeight * 0.81f else height + borderHeight * 0.68f
        canvas.drawText(dateText, rightTextX, dateY, textPaint)

        val filename = "LeicaFOTOS_${System.currentTimeMillis()}.jpg"
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
            originalBitmap.recycle()
            return@withContext true
        }
        return@withContext false
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext false
    }
}
