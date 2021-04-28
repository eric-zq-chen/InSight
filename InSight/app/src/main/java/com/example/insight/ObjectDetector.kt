package com.example.insight

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks.call
import org.tensorflow.lite.Interpreter
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ObjectDetector(private val context: Context) {
    // Add a TF Lite interpreter as a field.
    private var interpreter: Interpreter? = null
    var isInitialized = false
        private set

    /** Executor to run inference task in the background. */
    private val executorService: ExecutorService = Executors.newCachedThreadPool()

    private lateinit var labels: List<String>
    var numClass: Int = 0
        private set
    private val labelOffset = 1
    private var inputImageWidth: Int = 0 // will be inferred from TF Lite model.
    private var inputImageHeight: Int = 0 // will be inferred from TF Lite model.
    private var modelInputSize: Int = 0 // will be inferred from TF Lite model.
    private lateinit var outputLocations: Array<Array<FloatArray>>
    // outputClasses: array of shape [Batchsize, NUM_DETECTIONS]
    // contains the classes of detected boxes
    private lateinit var outputClasses: Array<FloatArray>
    // outputScores: array of shape [Batchsize, NUM_DETECTIONS]
    // contains the scores of detected boxes
    private lateinit var outputScores: Array<FloatArray>
    // numDetections: array of shape [Batchsize]
    // contains the number of detected boxes
    private lateinit var numDetections: FloatArray

    // An async version of initializeInterpreter(), not used in the app
    fun initialize(): Task<Void> {
        return call(
            executorService,
            Callable<Void> {
                initializeInterpreter()
                null
            }
        )
    }

    @Throws(IOException::class)
    fun initializeInterpreter() {
        //  Load the TF Lite model from file and initialize an interpreter.
        val assetManager = context.assets
        val model = loadModelFile(assetManager, "detect.tflite")

        //  Read the model input shape from model file.
        var interpreter = Interpreter(model)
        val inputShape = interpreter.getInputTensor(0).shape()
        inputImageWidth = inputShape[1]
        inputImageHeight = inputShape[2]
        modelInputSize = FLOAT_TYPE_SIZE * inputImageWidth * inputImageHeight * PIXEL_SIZE

        // Finish interpreter initialization
        this.interpreter = interpreter


        isInitialized = true
        Log.d(TAG, "Initialized TFLite interpreter.")

        // Read label file
        var labelsInput: InputStream? = null
        labelsInput = assetManager.open("labelmap.txt")
        var br: BufferedReader? = null
        br = BufferedReader(InputStreamReader(labelsInput))
        labels = br.readLines()
        br.close()

        numClass = labels.size - labelOffset

    }

    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, filename: String): ByteBuffer {
        val fileDescriptor = assetManager.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun classify(bitmap: Bitmap): List<Recognition> {
        check(isInitialized) { "TF Lite Interpreter is not initialized yet." }

        // Add code to run inference with TF Lite.
        // Preprocessing: resize the input image to match the model input shape.
        val resizedImage = Bitmap.createScaledBitmap(
            bitmap,
            inputImageWidth,
            inputImageHeight,
            true
        )
        val byteBuffer = convertBitmapToByteBuffer(resizedImage)

        // Define an array to store the model output.

        outputLocations = Array(
            1
        ) {
            Array(
                10
            ) { FloatArray(4) }
        }
        outputClasses = Array(
            1
        ) { FloatArray(10) }
        outputScores = Array(
            1
        ) { FloatArray(10) }
        numDetections = FloatArray(1)

        val inputArray = arrayOf<Any>(byteBuffer)
        val outputMap: MutableMap<Int, Any> =
            HashMap()
        outputMap[0] = outputLocations
        outputMap[1] = outputClasses
        outputMap[2] = outputScores
        outputMap[3] = numDetections

        // Run inference with the input data.
        interpreter?.runForMultipleInputsOutputs(inputArray, outputMap)
        // Show the best detections.
        // after scaling them back to the input size.
        val recognitions = ArrayList<Recognition>(10)
        for (i in 0 until 10) {
            val detection = RectF(
                outputLocations[0][i][1] * inputImageWidth,
                outputLocations[0][i][0] * inputImageHeight,
                outputLocations[0][i][3] * inputImageWidth,
                outputLocations[0][i][2] * inputImageHeight
            )
            // SSD Mobilenet V1 Model assumes class 0 is background class
            // in label file and class labels start from 1 to number_of_classes+1,
            // while outputClasses correspond to class index from 0 to number_of_classes

            recognitions.add(
                Recognition(
                    "" + i,
                    labels[outputClasses[0][i].toInt() + labelOffset],
                    outputClasses[0][i].toInt(),
                    outputScores[0][i],
                    detection
                )
            )
        }

        return recognitions
    }

    fun classifyAsync(bitmap: Bitmap): Task<List<Recognition>> {
        return call(executorService, Callable<List<Recognition>> { classify(bitmap) })
    }

    fun close() {
        call(
            executorService,
            Callable<String> {
                //  close the TF Lite interpreter here
                interpreter?.close()

                Log.d(TAG, "Closed TFLite interpreter.")
                null
            }
        )
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(modelInputSize)
        byteBuffer.order(ByteOrder.nativeOrder())
        byteBuffer.rewind()

        val pixels = IntArray(inputImageWidth * inputImageHeight)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixelValue in pixels) {
            val r = (pixelValue shr 16 and 0xFF).toByte()
            val g = (pixelValue shr 8 and 0xFF).toByte()
            val b = (pixelValue and 0xFF).toByte()

            byteBuffer.put(r)
            byteBuffer.put(g)
            byteBuffer.put(b)
        }

        return byteBuffer
    }

    companion object {
        private const val TAG = "Classifier"

        private const val FLOAT_TYPE_SIZE = 1
        private const val PIXEL_SIZE = 3

        private const val OUTPUT_CLASSES_COUNT = 80
    }

    class Recognition(
        /**
         * A unique identifier for what has been recognized. Specific to the class, not the instance of
         * the object.
         */
        val id: String?, /** The id number of the 10 object detected, sorted by their confidence.  */
        val title: String?, /** Display name for the recognition.  */
        val classNum: Int?, /** The class number correspond in the label file.  */
        val confidence: Float?, /** confidence score of the object detected.  */
        private var location: RectF? /** Location of the edges of the bounding box, within the input space.  */
    ) {

        fun getLocation(): RectF {
            return RectF(location)
        }

        fun setLocation(location: RectF?) {
            this.location = location
        }

        override fun toString(): String {
            var resultString = ""
            if (id != null) {
                resultString += "[$id] "
            }
            if (title != null) {
                resultString += "$title "
            }
            if (confidence != null) {
                resultString += String.format("(%.1f%%) ", confidence * 100.0f)
            }
            if (location != null) {
                resultString += location.toString() + " "
            }
            return resultString.trim { it <= ' ' }
        }

    }
}

