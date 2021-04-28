package com.example.insight

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Camera
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import android.widget.Toast
import android.speech.tts.TextToSpeech
import android.media.Image
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.TextView
import com.google.ar.core.*
import java.util.*
import java.io.*
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.HitTestResult
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import kotlin.math.sqrt

private const val DEBUG_TAG = "Gestures"

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, Scene.OnUpdateListener {

    private var tts: TextToSpeech? = null
    private var PERMISSIONS_CAMERA = 1
    private var PERMISSIONS_EXTERNAL_STORAGE = 2
    private val objectDetector = ObjectDetector(this)
    private val VOICE_RECOGNITION_REQUEST_CODE = 10
    private var appState: String = "EXPLORE"
    private val MIN_OPENGL_VERSION: Double = 3.0
    private var currentAnchor: Anchor? = null
    private var currentAnchorNode: AnchorNode? = null
    private var distanceMeters: Float? = null
    private lateinit var cubeRenderable: ModelRenderable
    private var arFragment: ArFragment? = null
    private var imageStore: Image? = null
    private var ba: ByteArray? = null
    private val inputImageWidth = 300
    private val inputImageHeight = 300
    private lateinit var rect : Rect
    private val kMaxChannelValue = 262143
    private var rgbBytes: IntArray? = null
    private var frameToCropTransform: Matrix? = null
    private val cropSize: Int = 300
    private lateinit var rgbFrameBitmap : Bitmap
    private val croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
    private val CONFIDENCE_THRESHOLD = 0.5
    private lateinit var classCounter: IntArray
    private val STABLE_FRAME_NUM = 10
    private var spoken : Boolean = true
    private var objectLookup: String = ""
    private var initCheck : Boolean = true
    private lateinit var mDetector: GestureDetectorCompat
    private lateinit var tvDistance: TextView
    private lateinit var labels: List<String>
    private var startVoiceInput: Boolean = false
    private var calibrationPrompted: Boolean = false
    private var hitTestSuccess: Boolean = false
    private var relevantResult: List<ObjectDetector.Recognition>? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // gesture detector, that overrides the AR Core gesture capture
        mDetector = GestureDetectorCompat(this, MyGestureListener())

        // load the TF lite model, and the class labels
        val assetManager = this.assets
        var labelsInput = assetManager.open("labelmap.txt")
        var br = BufferedReader(InputStreamReader(labelsInput))
        labels = br.readLines()
        br.close()

        // initialise text to speech
        initTTS(this)

//        if (ContextCompat.checkSelfPermission(this,
//                Manifest.permission.CAMERA)
//            != PackageManager.PERMISSION_GRANTED) {
//
//            // Permission is not granted
//            // Should we show an explanation?
//            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
//                    Manifest.permission.CAMERA)) {
//                // Show an explanation to the user *asynchronously* -- don't block
//                // this thread waiting for the user's response! After the user
//                // sees the explanation, try again to request the permission.
//            } else {
//                // No explanation needed, we can request the permission.
//                ActivityCompat.requestPermissions(this@MainActivity,
//                    arrayOf(Manifest.permission.CAMERA),
//                    PERMISSIONS_CAMERA)
//
//                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
//                // app-defined int constant. The callback method gets the
//                // result of the request.
//            }
//
//        } else {
//            // Permission has already been granted
//            mCamera = getCameraInstance()
//        }

//        if (ContextCompat.checkSelfPermission(this,
//                Manifest.permission.WRITE_EXTERNAL_STORAGE)
//            != PackageManager.PERMISSION_GRANTED) {
//            if (ActivityCompat.shouldShowRequestPermissionRationale(
//                    this,
//                    Manifest.permission.WRITE_EXTERNAL_STORAGE
//                )
//            ) {
//                // Show an explanation to the user *asynchronously* -- don't block
//                // this thread waiting for the user's response! After the user
//                // sees the explanation, try again to request the permission.
//            } else {
//                // No explanation needed, we can request the permission.
//                ActivityCompat.requestPermissions(
//                    this@MainActivity,
//                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
//                    PERMISSIONS_EXTERNAL_STORAGE
//                )
//
//                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
//                // app-defined int constant. The callback method gets the
//                // result of the request.
//            }
//        }

        // Initialise object detector
        objectDetector.initializeInterpreter()
        classCounter = IntArray(objectDetector.numClass)

        // Initialise AR
        arFragment = supportFragmentManager.findFragmentById(R.id.ux_fragment) as ArFragment?
        arFragment!!.getArSceneView().scene.addOnUpdateListener(this)
        // override the default AR scene gesture listener with our own gesture listener mDetector
        arFragment!!.arSceneView.scene.addOnPeekTouchListener(this::handleOnTouch)

        tvDistance = findViewById(R.id.tvDistance)

        init3DModel()
    }

    fun initTTS(context: Context) {
        tts = TextToSpeech(context, null)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(s: String) {
            }

            override fun onDone(s: String) {
                // A check that is used to prevent spoken lines from overlapping
                spoken = true
            }

            override fun onError(s: String) {
            }
        })

    }

    override fun onInit(status: Int) {

        // Set the language if TTS initialises
        if (status == TextToSpeech.SUCCESS) {
            // set UK English as language for Text To Speech (TTS)
            val result = tts!!.setLanguage(Locale.UK)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS","The Language specified is not supported!")
            }

        } else {
            Log.e("TTS", "Initilization Failed!")
        }

    }

    public override fun onDestroy() {
        // Shutdown Text To Speech (TTS)
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }

        // Close object detector
        if (objectDetector != null) {
            objectDetector.close()
        }

        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_CAMERA -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
//                    mCamera = getCameraInstance()

                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
//                    Toast.makeText(this@MainActivity, "1", Toast.LENGTH_LONG).show()
//                    ActivityCompat.requestPermissions(this,
//                        arrayOf(Manifest.permission.CAMERA),
//                        MY_PERMISSIONS_REQUEST_READ_CONTACTS)
//
//                    Toast.makeText(this@MainActivity, "Hello", Toast.LENGTH_LONG).show()
                }
                return
            }

            PERMISSIONS_EXTERNAL_STORAGE -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
//                    mCamera = getCameraInstance()

                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
//                    Toast.makeText(this@MainActivity, "1", Toast.LENGTH_LONG).show()
//                    ActivityCompat.requestPermissions(this,
//                        arrayOf(Manifest.permission.CAMERA),
//                        MY_PERMISSIONS_REQUEST_READ_CONTACTS)
//
//                    Toast.makeText(this@MainActivity, "Hello", Toast.LENGTH_LONG).show()
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
        }
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        mDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    fun handleOnTouch (hitTestResult : HitTestResult, motionEvent : MotionEvent) {
        arFragment!!.onPeekTouch(hitTestResult,motionEvent)

        if (hitTestResult.node != null) {
            return
        }

        mDetector.onTouchEvent(motionEvent)
    }

    private inner class MyGestureListener : GestureDetector.SimpleOnGestureListener() {

        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100

        override fun onDown(event: MotionEvent): Boolean {
            Log.d(DEBUG_TAG, "onDown: $event")
            return true
        }

        override fun onFling(
            e1: MotionEvent,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            Log.d(DEBUG_TAG, "onFling: $e1 $e2")
            val result = false

            // uses the difference between coordinates to determine whether the swipe was left or right
            try {
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            onSwipeRight()
                        } else {
                            onSwipeLeft()
                        }
                        return true
                    }
                } else {
                    // onTouch(e);
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }

            return result
        }

        override fun onSingleTapConfirmed(event: MotionEvent?): Boolean {

            Log.d(DEBUG_TAG, "onSingleTapUp: $event")
            if (appState == "FIND"){
                if (currentAnchor != null && distanceMeters != null){
                    val distanceRounded = (distanceMeters!!*100).toInt().toString()
                    var sentence = ""
                    if (relevantResult.isNullOrEmpty()){
                        sentence = "the $objectLookup is not in view of the camera and is $distanceRounded centimeters away"
                    } else {
                        sentence = "the $objectLookup is in view of the camera and is $distanceRounded centimeters away"
                    }
                    // converts the distance value from meters to centimeters

                    voiceLine(sentence, isHold = true)

                } else if (relevantResult.isNullOrEmpty()){

                    voiceLine("Object is not in view of camera", isHold = true)

                } else if (currentAnchor == null){

                    voiceLine("Surface is not calibrated, please gently wave your device around", isHold = true)
                }

            }
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            Log.d(DEBUG_TAG, "onDoubleTap: $event")
            if (appState == "FIND")

                voiceLine("Please say the name of the new item after the beep.")
                startVoiceInput = true
                clearAnchor()

            return true
        }

    }

    private fun onSwipeRight() {
        //DEBUG TESTING//
//        val view = this.window.decorView.rootView
//        Snackbar.make(view, "Swiped Right", Snackbar.LENGTH_LONG).setAction("Action", null).show()
//        tts!!.speak("Swiped Right", TextToSpeech.QUEUE_FLUSH, null,"")
//
//        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
//        if (Build.VERSION.SDK_INT >= 26) {
//            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
//        } else {
//            vibrator.vibrate(200)
//        }
        //DEBUG ENDS//

        if (appState == "FIND"){
            startExplore()
        }

        Toast.makeText(this, appState, Toast.LENGTH_SHORT).show()
    }

    private fun onSwipeLeft() {
        //DEBUG TESTING//
//        val view = this.window.decorView.rootView
//        Snackbar.make(view, "Swiped Left", Snackbar.LENGTH_LONG).setAction("Action", null).show()
//        tts!!.speak("Swiped Left", TextToSpeech.QUEUE_FLUSH, null,"")
//
//        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
//        if (Build.VERSION.SDK_INT >= 26) {
//            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
//            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
//
//            val mVibratePattern : LongArray = longArrayOf(0, 400, 200, 400)
//            vibrator.vibrate(mVibratePattern, -1)
//
//        } else {
//            vibrator.vibrate(100)
//            vibrator.vibrate(100)
//        }
        //DEBUG ENDS//
        if (appState == "EXPLORE"){
            startFind()
        }

        Toast.makeText(this, appState, Toast.LENGTH_SHORT).show()

    }

    private fun startExplore(){
        appState = "EXPLORE"
        voiceLine("I am now exploring. I will try to identify every objects that I can see.")
    }

    private fun startFind() {
        appState = "FIND"
        voiceLine("I am now finding a specific object, please say the name of the object after the beep.", isHold = true)
        startVoiceInput = true
    }

    private fun voiceLine(str: String, pause: Long = 1000, isHold: Boolean = false){
        // Function to voice string out using a tts object
        if (isHold){
            while (!spoken){

            }
        }

        if (spoken) {
            spoken = false
            tts!!.speak(str, TextToSpeech.QUEUE_FLUSH, null, "")
            tts!!.playSilentUtterance(pause, TextToSpeech.QUEUE_ADD, "")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(200)
            }
        }
    }

    private fun getSpeechInput() {
        // Starting a Google voice input session
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

        if (intent.resolveActivity(getPackageManager()) != null) {

            while(!spoken){

            }

            startActivityForResult(intent, VOICE_RECOGNITION_REQUEST_CODE)

        } else {

            Toast.makeText(this, "Your Device Don't Support Speech Input", Toast.LENGTH_SHORT).show()

        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            VOICE_RECOGNITION_REQUEST_CODE->{
                if (resultCode == RESULT_OK && data != null) {

                    // Handling voice input
                    val result: ArrayList<String> = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    objectLookup = result[0].filter{it.isLetter()} //drop anything that is not a letter
                    objectLookup = objectLookup.toLowerCase()
                    clearAnchor()

                    //Check if voice input is in the detection list
                    if (labels.contains(objectLookup)){

                        voiceLine("I am now looking for $objectLookup")

                    } else {

                        voiceLine("Object $objectLookup is not currently supported, please request another object after the beep")
                        startVoiceInput = true

                    }

                } else {

                    voiceLine("Sorry, I didn't get that. Please repeat")
                    startVoiceInput = true
                }
            }

        }
    }

    fun checkIsSupportedDeviceOrFinish(activity: Activity): Boolean {

        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configurationInfo = activityManager.deviceConfigurationInfo
        val vers = configurationInfo.glEsVersion as Double

        if (vers < MIN_OPENGL_VERSION) {
            Log.e(TAG, "Sceneform requires OpenGL ES 3.0 later");
            Toast.makeText(activity, "Sceneform requires OpenGL ES 3.0 or late  r", Toast.LENGTH_LONG)
                .show()
            activity.finish()

            return false
        }

        return true
    }

    private fun init3DModel() {
        // Create a 3D model for AR anchor.
        val vector3: Vector3 = Vector3(0.05f, 0.01f, 0.01f)

        MaterialFactory.makeTransparentWithColor(
            this,
            com.google.ar.sceneform.rendering.Color(Color.RED)
        )
            .thenAccept { material ->
                val vector3 = Vector3(0.05f, 0.01f, 0.01f)
                cubeRenderable = ShapeFactory.makeCube(vector3, Vector3.zero(), material)
                cubeRenderable.isShadowCaster = false
                cubeRenderable.isShadowReceiver = false
            }

    }

    private fun clearAnchor() {
        //Clear AR anchor
        currentAnchor = null

        if (currentAnchorNode != null) {
            arFragment!!.getArSceneView().getScene().removeChild(currentAnchorNode)
            currentAnchorNode!!.getAnchor()!!.detach()
            currentAnchorNode!!.setParent(null)
            currentAnchorNode = null
        }

        distanceMeters = null
    }

    private fun setAnchor(x: Float, y: Float): Boolean{
        // Set AR anchor
        if (cubeRenderable != null){
            var frame: Frame? = arFragment!!.arSceneView.arFrame
            val hitTest = frame!!.hitTest(x,y)

            if (hitTest.isNotEmpty()){
                var anchor: Anchor = hitTest.get(0).createAnchor()
                val anchorNode = AnchorNode(anchor)

                anchorNode.setParent(arFragment!!.getArSceneView().getScene())

                clearAnchor()

                currentAnchor = anchor
                currentAnchorNode = anchorNode

                val node = TransformableNode(arFragment!!.getTransformationSystem())

                node.setRenderable(cubeRenderable)
                node.setParent(anchorNode)
                arFragment!!.getArSceneView().getScene().addOnUpdateListener(this)
                arFragment!!.getArSceneView().getScene().addChild(anchorNode)
                node.select()

                return true
            } else {
                return false
            }
        }
        return true
    }

    override fun onUpdate(p0: FrameTime?) {
        // Operation we need to do on every frames
        val frame = arFragment!!.getArSceneView().arFrame

        try {
            imageStore = frame!!.acquireCameraImage() // Retrieve the image this frame
            val image_height = imageStore!!.getHeight()
            val image_width = imageStore!!.getWidth()

            // Operation that only needs to be done once at the start
            if (initCheck) {
                rect = Rect(0, 0, image_width, image_height)
                rgbFrameBitmap =  Bitmap.createBitmap(image_width, image_height, Bitmap.Config.ARGB_8888)
                if(rgbBytes == null){
                    rgbBytes = IntArray(image_width * image_height)
                }
                frameToCropTransform = getTransformationMatrix(
                    image_width,
                    image_height,
                    cropSize,
                    cropSize,
                    90,
                    false
                )
                initCheck = false
            }

            // Converting YUV420 ByteArray to NV21 ByteArray
            ba = YUV_420_888toNV21(imageStore!!)
            imageStore!!.close()

            // DEBUG ONLY
//            img = YuvImage(ba, ImageFormat.NV21, image_width, image_height, null)
//            val out = ByteArrayOutputStream(3 * image_width * image_height)
//            val rect = Rect(0, 0, image_width, image_height)
//            img!!.compressToJpeg(rect, 100, out)
//            val ba2 = out.toByteArray()
//            bitmap = BitmapFactory.decodeByteArray(ba2, 0, ba2.size)
            //DEBUG ENDS

            // Converting NV21 to ARGB ByteArray
            rgbBytes?.let{rgbBytes ->
                convertYUV420SPToARGB8888(ba!!, image_width, image_height, rgbBytes)
            }
            //Converting ByteArray into Bitmap
            rgbFrameBitmap!!.setPixels(rgbBytes,
                0,
                image_width,
                0,
                0,
                image_width,
                image_height
            )

            //Rotate and scale the bitmap
            val canvas = Canvas(croppedBitmap)
            frameToCropTransform?.let{frameToCropTransform ->
                canvas.drawBitmap(rgbFrameBitmap,frameToCropTransform,null)
            }

            // Passing the processed image into the classifier and saving the result
            val result = objectDetector.classify(croppedBitmap)
            // Only results over the confidence threshold are saved
            val confidentResult = result.dropLastWhile{it.confidence!! < CONFIDENCE_THRESHOLD}
            var stableResult = ""
            var newClassCounter = IntArray(objectDetector.numClass)

            for (item in confidentResult){
                // Counting up the number of consecutive frames an object has consistently been in
                newClassCounter[item.classNum!!] = classCounter[item.classNum!!] + 1
                if (newClassCounter[item.classNum!!] >= STABLE_FRAME_NUM){
                    // Only objects that have been in a number of consecutive frames are considered
                    if (!stableResult.contains(item.title.toString(),true)){
                        stableResult = stableResult + item.title + " "
                    }

                }
            }

            classCounter = newClassCounter

            if(appState == "EXPLORE"){
                if (stableResult != "") {

                    // Voicing out all the items identified in explore mode
                    voiceLine(stableResult)

                }

            } else if (appState == "FIND"){

                if (startVoiceInput && spoken) {
                    // Check if we need to restart Google speech input
                    // Make sure no line is playing when starting speech input to prevent audio feedback
                    getSpeechInput()
                    startVoiceInput = false
                }

                relevantResult = null
                if (stableResult.contains(objectLookup, true) and objectLookup.isNotBlank()){
                    // Result is only relevant when it matches with the user request.
                    relevantResult = confidentResult.dropLastWhile { !it.title!!.contains(objectLookup) }

                    // Setting AR Anchor to the bottom of the object
                    if (currentAnchorNode == null) {
                        val vw = findViewById<View>(android.R.id.content)
                        val x = relevantResult!![0].getLocation().centerX() / inputImageWidth * vw.width
                        val y = relevantResult!![0].getLocation().bottom / inputImageHeight * vw.height
                        if (y/vw.height<0.9){
                            // Check if bottom of the object is in frame
                            hitTestSuccess = setAnchor(x,y)
                            // Prompting user to calibrate surface when a hitTest failed
                            if (!hitTestSuccess) {
                                voiceLine("Object is found, please gently wave your device around to calibrate the surface.", 5000)
                                calibrationPrompted = true
                            }
                        } else {
                            // Prompting user to move back if the bottom of the object is out of frame.
                            voiceLine("Object is out of frame, please move the camera back slightly. Please beware of your surrounding.")
                            }
                        if(hitTestSuccess) {
                            voiceLine("Object is found")
                        }
                    }

//                    print("Eric's dodgy method")
                }

            }



        } catch (e: NotYetAvailableException) {
            Log.d(TAG, e.toString())
        }

        // Update distance each frame if an anchor is already set
        if (currentAnchorNode != null) {
            var objectPose: Pose = currentAnchor!!.getPose()
            var cameraPose: Pose = frame!!.getCamera()!!.getPose()

            var dx = objectPose.tx() - cameraPose.tx()
            var dy = objectPose.ty() - cameraPose.ty()
            var dz = objectPose.tz() - cameraPose.tz()

            ///Compute the straight-line distance.
            distanceMeters = sqrt(dx * dx + dy * dy + dz * dz)
            tvDistance.setText("Distance from camera: " + distanceMeters + " metres")

            // Play calibration message once when the surface is successfully calibrated
            if (calibrationPrompted && spoken) {
                voiceLine("Surface successfully calibrated.")
                calibrationPrompted  = false
            }

        }
    }

    companion object {
        // log tag
        private const val TAG = "MainActivity"
    }

    // Image Format Conversion
    private fun YUV_420_888toNV21(image: Image): ByteArray {
        val nv21: ByteArray
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        nv21 = ByteArray(ySize + uSize + vSize)

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    // Image Format Conversion
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
        r = if (r > kMaxChannelValue) kMaxChannelValue else if (r < 0) 0 else r
        g = if (g > kMaxChannelValue) kMaxChannelValue else if (g < 0) 0 else g
        b = if (b > kMaxChannelValue) kMaxChannelValue else if (b < 0) 0 else b
        return -0x1000000 or (r shl 6 and 0xff0000) or (g shr 2 and 0xff00) or (b shr 10 and 0xff)
    }

    // Image Format Conversion
    fun convertYUV420SPToARGB8888(input: ByteArray, width: Int, height: Int, output: IntArray) {
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


}
