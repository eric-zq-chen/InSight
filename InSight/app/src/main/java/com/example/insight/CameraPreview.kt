package com.example.insight

import android.content.Context
import android.graphics.*
import android.hardware.Camera
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.constraintlayout.widget.Constraints.TAG
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import android.speech.tts.UtteranceProgressListener
import android.icu.lang.UCharacter.GraphemeClusterBreak.T
import android.os.Vibrator
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService


/** A basic Camera preview class */
class CameraPreview(
    context: Context,
    private val mCamera: Camera,
    private val objectDetector: ObjectDetector
) : SurfaceView(context), SurfaceHolder.Callback {

    private val mHolder: SurfaceHolder = holder.apply {
        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        addCallback(this@CameraPreview)
        // deprecated setting, but required on Android versions prior to 3.0
        setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
//        initTTS(context)
//        tts2!!.speak("Start", TextToSpeech.QUEUE_FLUSH, null,"")
    }
    private var mCallback: Camera.PreviewCallback? = null
    protected var previewWidth = mCamera.parameters.previewSize.width
    protected var previewHeight = mCamera.parameters.previewSize.height
    private val channelSize = 3
    private val inputImageWidth = 300
    private val inputImageHeight = 300
    protected val rect = Rect(0, 0, previewWidth, previewHeight)
    private val kMaxChannelValue = 262143
    private var rgbBytes: IntArray? = null
    private var frameToCropTransform: Matrix? = null
    private val cropSize: Int = 300
    private val rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
    private val croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
    private var tts2: TextToSpeech? = null
    private val objects: MutableList<String> =  mutableListOf("apple", "banana", "carrot", "date", "eggplant")
    private var speakDebug: Boolean = true
    private val CONFIDENCE_THRESHOLD = 0.5
    private val numClass = objectDetector.numClass
    private var classCounter = IntArray(numClass)
    private val STABLE_FRAME_NUM = 10
    private var spoken : Boolean = true
    private var state: String = "EXPLORE"
    private var objectLookup: String = ""

    fun initTTS(context: Context) {
        tts2 = TextToSpeech(context, null)

        tts2?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(s: String) {
            }

            override fun onDone(s: String) {
                spoken = true
            }

            override fun onError(s: String) {
            }
        })

    }


    override fun surfaceCreated(holder: SurfaceHolder) {
        // The Surface has been created, now tell the camera where to draw the preview.
        holder.addCallback(this)
        initTTS(context)
        mCamera.apply {
            try {
                setPreviewDisplay(holder)
                startPreview()
                setPreviewCallback(CameraPreviewCallback())
            } catch (e: IOException) {
                Log.d(TAG, "Error setting camera preview: ${e.message}")
            }
        }



    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // empty. Take care of releasing the Camera preview in your activity.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        // If your preview can change or rotate, take care of those events here.
        // Make sure to stop the preview before resizing or reformatting it.
        if (mHolder.surface == null) {
            // preview surface does not exist
            return
        }

        // stop preview before making changes
        try {
            mCamera.stopPreview()
        } catch (e: Exception) {
            // ignore: tried to stop a non-existent preview
        }

        // set preview size and make any resize, rotate or
        // reformatting changes here

        // start preview with new settings
        mCamera.apply {
            try {
                setPreviewDisplay(mHolder)
                startPreview()
                setPreviewCallback(CameraPreviewCallback())
            } catch (e: Exception) {
                Log.d(TAG, "Error starting camera preview: ${e.message}")
            }
        }
    }

    private inner class CameraPreviewCallback : Camera.PreviewCallback {

        override fun onPreviewFrame(data: ByteArray, camera: Camera) {

            if(rgbBytes == null){
                rgbBytes = IntArray(previewWidth * previewHeight)
            }
            if (camera.parameters.previewFormat === ImageFormat.NV21) {
                //**DEBUG ONLY**
//                previewWidth = camera.parameters.previewSize.width
//                previewHeight = camera.parameters.previewSize.height
//                val rect = Rect(0, 0, previewWidth, previewHeight)
//                val img =
//                    YuvImage(data, ImageFormat.NV21, previewWidth, previewHeight, null)
//                var outStream: OutputStream? = null
//                val file = File(context.getExternalFilesDir("Environment.DIRECTORY_PICTURES").toString() + "/a.jpg")
//                try {
//                    outStream = FileOutputStream(file)
//                    img.compressToJpeg(rect, 100, outStream)
//                    outStream.flush()
//                    outStream.close()
//                } catch (e: FileNotFoundException) {
//                    e.printStackTrace()
//                } catch (e: IOException) {
//                    e.printStackTrace()
//                }
                //**END**

//                val img =
//                    YuvImage(data, ImageFormat.NV21, previewWidth, previewHeight, null)
//                var out = ByteArrayOutputStream(channelSize * previewWidth * previewHeight)
//                img.compressToJpeg(rect, 100, out)
//                var ba: ByteArray = out.toByteArray()
//                var bitmap: Bitmap = BitmapFactory.decodeByteArray(ba,0,ba.size)
//                bitmap = Bitmap.createScaledBitmap(bitmap,inputImageWidth,inputImageHeight,true)
//                var buffer = ByteBuffer.allocate(bitmap.byteCount)
//                bitmap.copyPixelsToBuffer(buffer)
//

                frameToCropTransform = getTransformationMatrix(
                    previewWidth,
                    previewHeight,
                    cropSize,
                    cropSize,
                    90,
                    false
                )
                rgbBytes?.let{rgbBytes ->
                    convertYUV420SPToARGB8888(data, previewWidth, previewHeight, rgbBytes)
                }
                rgbFrameBitmap!!.setPixels(rgbBytes,
                    0,
                    previewWidth,
                    0,
                    0,
                    previewWidth,
                    previewHeight
                )
                val canvas = Canvas(croppedBitmap)
                frameToCropTransform?.let{frameToCropTransform ->
                    canvas.drawBitmap(rgbFrameBitmap,frameToCropTransform,null)
                }

                val result = objectDetector.classify(croppedBitmap)
                val confidentResult = result.dropLastWhile{it.confidence!! < CONFIDENCE_THRESHOLD}
                var stableResult = ""
                var newClassCounter = IntArray(numClass)

                for (item in confidentResult){
                    newClassCounter[item.classNum!!] = classCounter[item.classNum!!] + 1
                    if (newClassCounter[item.classNum!!] >= STABLE_FRAME_NUM){
                        if (!stableResult.contains(item.title.toString(),true)){
                            stableResult = stableResult + item.title + " "
                        }

                    }
                }

                classCounter = newClassCounter

                if(state == "EXPLORE"){
                    if (spoken and (stableResult != "")) {
                        spoken = false
                        tts2!!.speak(stableResult, TextToSpeech.QUEUE_FLUSH, null,"")
                        tts2!!.playSilentUtterance(1000, TextToSpeech.QUEUE_ADD,"" )
                    }
                } else if (state == "FIND"){
                    //TODO: search if object in frame
                    if (stableResult.contains(objectLookup, true) and objectLookup.isNotBlank()){

                        if (spoken and (stableResult != "")) {
                            spoken = false
                            tts2!!.speak("found", TextToSpeech.QUEUE_FLUSH, null,"")
                            tts2!!.playSilentUtterance(1000, TextToSpeech.QUEUE_ADD,"" )
                        }
                    }
                }


                print("")

            }

        }

    }

    fun convertYUV420SPToARGB8888(
        input: ByteArray,
        width: Int,
        height: Int,
        output: IntArray
    ) {
        val frameSize = width * height
        var j = 0
        var yp = 0
        while (j < height) {
            var uvp = frameSize + (j shr 1) * width
            var u = 0
            var v = 0
            var i = 0
            while (i < width) {
                val y = 0xff and input[yp].toInt()
                if (i and 1 == 0) {
                    v = 0xff and input[uvp++].toInt()
                    u = 0xff and input[uvp++].toInt()
                }
                output[yp] = YUV2RGB(y, u, v)
                i++
                yp++
            }
            j++
        }
    }

    private fun YUV2RGB(yin: Int, uin: Int, vin: Int): Int { // Adjust and check YUV values
        var y = yin
        var u = uin
        var v = vin
        y = if (y - 16 < 0) 0 else y - 16
        u -= 128
        v -= 128
        // This is the floating point equivalent. We do the conversion in integer
// because some Android devices do not have floating point in hardware.
// nR = (int)(1.164 * nY + 2.018 * nU);
// nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
// nB = (int)(1.164 * nY + 1.596 * nV);
        val y1192 = 1192 * y
        var r = y1192 + 1634 * v
        var g = y1192 - 833 * v - 400 * u
        var b = y1192 + 2066 * u
        // Clipping RGB values to be inside boundaries [ 0 , kMaxChannelValue ]
        r =
            if (r > kMaxChannelValue) kMaxChannelValue else if (r < 0) 0 else r
        g =
            if (g > kMaxChannelValue) kMaxChannelValue else if (g < 0) 0 else g
        b =
            if (b > kMaxChannelValue) kMaxChannelValue else if (b < 0) 0 else b
        return -0x1000000 or (r shl 6 and 0xff0000) or (g shr 2 and 0xff00) or (b shr 10 and 0xff)
    }

    fun getTransformationMatrix(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        applyRotation: Int,
        maintainAspectRatio: Boolean
    ): Matrix? {
        val matrix = Matrix()
        if (applyRotation != 0) {
            if (applyRotation % 90 != 0) {

            }
            // Translate so center of image is at origin.
            matrix.postTranslate(-srcWidth / 2.0f, -srcHeight / 2.0f)
            // Rotate around origin.
            matrix.postRotate(applyRotation.toFloat())
        }
        // Account for the already applied rotation, if any, and then determine how
// much scaling is needed for each axis.
        val transpose = (Math.abs(applyRotation) + 90) % 180 == 0
        val inWidth = if (transpose) srcHeight else srcWidth
        val inHeight = if (transpose) srcWidth else srcHeight
        // Apply scaling if necessary.
        if (inWidth != dstWidth || inHeight != dstHeight) {
            val scaleFactorX = dstWidth / inWidth.toFloat()
            val scaleFactorY = dstHeight / inHeight.toFloat()
            if (maintainAspectRatio) { // Scale by minimum factor so that dst is filled completely while
// maintaining the aspect ratio. Some image may fall off the edge.
                val scaleFactor = Math.max(scaleFactorX, scaleFactorY)
                matrix.postScale(scaleFactor, scaleFactor)
            } else { // Scale exactly to fill dst from src.
                matrix.postScale(scaleFactorX, scaleFactorY)
            }
        }
        if (applyRotation != 0) { // Translate back from origin centered reference to destination frame.
            matrix.postTranslate(dstWidth / 2.0f, dstHeight / 2.0f)
        }
        return matrix
    }

    fun setState(s: String) {
        state = s
    }

    fun setObjectLookup(o: String) {
        objectLookup = o
    }









}


