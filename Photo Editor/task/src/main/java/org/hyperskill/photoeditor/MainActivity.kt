package org.hyperskill.photoeditor

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.hyperskill.photoeditor.databinding.ActivityMainBinding
import kotlin.math.pow


private const val REQUEST_WRITE_PERMISSION = 0

private const val GAMMA_STEP_SIZE = 0.2f
private const val GAMMA_VALUE_FROM = 0.2f

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var originalBitmap: Bitmap
    private lateinit var resolver: ContentResolver

    private var currentBrightness = 0
    private var currentContrast = 0
    private var currentSaturation = 0
    private var currentGamma = 1.0

    private val imagePickResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val photoUri = result?.data?.data ?: return@registerForActivityResult
                binding.ivPhoto.setImageURI(photoUri)
                saveOriginal()
            }
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_PERMISSION && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            binding.btnSave.callOnClick()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindViews()

        //do not change this line
        binding.ivPhoto.setImageBitmap(createBitmap())

        saveOriginal()

        resolver = applicationContext.contentResolver
    }

    private fun saveOriginal() {
        originalBitmap = binding.ivPhoto.drawable.toBitmap()

        binding.slBrightness.value = 0f
        binding.slContrast.value = 0f
        binding.slSaturation.value = 0f
        binding.slGamma.value = 1f
    }

    private fun bindViews() {
        binding.btnGallery.setOnClickListener {
            val intent = Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )
            imagePickResultLauncher.launch(intent)
        }

        binding.btnSave.setOnClickListener {


//            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                saveImageToGallery()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_WRITE_PERMISSION
                )
            }
//            } else {
//                saveImageToGallery()
//            }
        }

        binding.slBrightness.addOnChangeListener { _, value, _ ->
            currentBrightness = value.toInt()
            applyFilters()
        }

        binding.slContrast.addOnChangeListener { _, value, _ ->
            currentContrast = value.toInt()
            applyFilters()
        }

        binding.slSaturation.addOnChangeListener { _, value, _ ->
            currentSaturation = value.toInt()
            applyFilters()
        }

        binding.slGamma.apply {
            stepSize = GAMMA_STEP_SIZE
            valueFrom = GAMMA_VALUE_FROM
        }.addOnChangeListener { _, value, _ ->
            currentGamma = value.toDouble()
            applyFilters()
        }
    }

    private fun getAvgBright(brightnessBitmap: Bitmap, width: Int, height: Int): Int {
        val totalPixels = width * height

        var sumBright: Long = 0

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = brightnessBitmap.getPixel(x, y)

                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)

                sumBright += (red + green + blue) / 3
            }
        }

        return (sumBright / totalPixels).toInt()
    }

    private var lastJob: Job? = null

    @OptIn(DelicateCoroutinesApi::class)
    private fun applyFilters() {
        lastJob?.cancel()

        lastJob = GlobalScope.launch(Dispatchers.Default) {
            var resultBitmap = originalBitmap

            if (currentBrightness != 0) {
                resultBitmap = applyBrightness(resultBitmap, currentBrightness)
            }

            if (currentContrast != 0) {
                resultBitmap = applyContrast(resultBitmap, currentContrast)
            }

            if (currentSaturation != 0) {
                resultBitmap = applySaturation(resultBitmap, currentSaturation)
            }

            if (currentGamma != 1.0) {
                resultBitmap = applyGamma(resultBitmap, currentGamma)
            }

            ensureActive()

            runOnUiThread {
                binding.ivPhoto.setImageBitmap(resultBitmap)
            }
        }
    }


    private fun hasPermission(permission: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } else {
            PermissionChecker.checkSelfPermission(
                this,
                permission
            ) == PermissionChecker.PERMISSION_GRANTED
        }
    }

    private fun saveImageToGallery() {
//        lifecycleScope.launch(Dispatchers.IO) {
        val bitmap = binding.ivPhoto.drawable.toBitmap()
        val filename = "IMG_${System.currentTimeMillis()}.jpeg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val contentResolver = contentResolver
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        uri?.let {
            contentResolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
//                runOnUiThread {
            Toast.makeText(this@MainActivity, "Image saved!", Toast.LENGTH_SHORT).show()
//                }
        } ?: run {
//                runOnUiThread {
            Toast.makeText(this@MainActivity, "Failed to save image", Toast.LENGTH_SHORT)
                .show()
//                }
        }
//        }
    }

    private fun applyBrightness(currentBitmap: Bitmap, brightness: Int): Bitmap {
        val width = currentBitmap.width
        val height = currentBitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = currentBitmap.getPixel(x, y)

                val alpha = Color.alpha(pixel)
                var red = Color.red(pixel) + brightness
                var green = Color.green(pixel) + brightness
                var blue = Color.blue(pixel) + brightness

                red = red.coerceIn(0, 255)
                green = green.coerceIn(0, 255)
                blue = blue.coerceIn(0, 255)

                val newPixel = Color.argb(alpha, red, green, blue)
                output.setPixel(x, y, newPixel)
            }
        }

        return output
    }

    private fun applyContrast(bitmap: Bitmap, contrast: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        // Calculate avg brightness AFTER brightness filter has been applied
        val avgBright = getAvgBright(bitmap, width, height)

        // Correct formula for alpha (contrast factor)
        val contrastFactor = (255.0 + contrast) / (255.0 - contrast)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)

                val alpha = Color.alpha(pixel)
                var red = ((contrastFactor * (Color.red(pixel) - avgBright)) + avgBright).toInt()
                var green =
                    ((contrastFactor * (Color.green(pixel) - avgBright)) + avgBright).toInt()
                var blue = ((contrastFactor * (Color.blue(pixel) - avgBright)) + avgBright).toInt()

                red = red.coerceIn(0, 255)
                green = green.coerceIn(0, 255)
                blue = blue.coerceIn(0, 255)

                val newPixel = Color.argb(alpha, red, green, blue)
                output.setPixel(x, y, newPixel)
            }
        }

        return output
    }

    private fun applySaturation(bitmap: Bitmap, saturation: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        val saturationFactor = (255.0 + saturation) / (255.0 - saturation)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)

                val alpha = Color.alpha(pixel)

                var red = Color.red(pixel)
                var green = Color.green(pixel)
                var blue = Color.blue(pixel)

                val rgbAvg: Int = (red + green + blue) / 3

                red = (saturationFactor * (red - rgbAvg) + rgbAvg).toInt()
                green = (saturationFactor * (green - rgbAvg) + rgbAvg).toInt()
                blue = (saturationFactor * (blue - rgbAvg) + rgbAvg).toInt()

                red = red.coerceIn(0, 255)
                green = green.coerceIn(0, 255)
                blue = blue.coerceIn(0, 255)

                val newPixel = Color.argb(alpha, red, green, blue)
                output.setPixel(x, y, newPixel)
            }
        }

        return output
    }

    private fun applyGamma(bitmap: Bitmap, gamma: Double): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {

                val pixel = bitmap.getPixel(x, y)

                val alpha = Color.alpha(pixel)

                var red = ((Color.red(pixel) / 255.0).pow(gamma) * 255).toInt()
                var green = ((Color.green(pixel) / 255.0).pow(gamma) * 255).toInt()
                var blue = ((Color.blue(pixel) / 255.0).pow(gamma) * 255).toInt()

                red = red.coerceIn(0, 255)
                green = green.coerceIn(0, 255)
                blue = blue.coerceIn(0, 255)

                val newPixel = Color.argb(alpha, red, green, blue)
                output.setPixel(x, y, newPixel)
            }
        }
        return output
    }

    // do not change this function
    fun createBitmap(): Bitmap {
        val width = 200
        val height = 100
        val pixels = IntArray(width * height)
        // get pixel array from source

        var R: Int
        var G: Int
        var B: Int
        var index: Int

        for (y in 0 until height) {
            for (x in 0 until width) {
                // get current index in 2D-matrix
                index = y * width + x
                // get color
                R = x % 100 + 40
                G = y % 100 + 80
                B = (x + y) % 100 + 120

                pixels[index] = Color.rgb(R, G, B)
            }
        }

        Thread.currentThread()
        // output bitmap
        val bitmapOut = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmapOut.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmapOut
    }
}