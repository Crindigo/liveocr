package com.crindigo.liveocr

import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.vision.v1.*
import com.google.protobuf.ByteString
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Properties

fun main(args: Array<String>)
{
    val properties = Properties()
    properties.load(File("./liveocr.properties").reader())

    // directory where screenshot files are saved. on mac, the desktop.
    val inputPath = properties.getProperty("screenshotPath")
    // directory where files should be moved to.
    val outputPath = properties.getProperty("outputPath")
    // path to the google service account json file.
    val keyFile = properties.getProperty("keyFile")

    val ocr = LiveOCR(inputPath, outputPath, keyFile)

    while ( true ) {
        Thread.sleep(10000)
        println("Yawn...")
    }
}

class LiveOCR(private val screenshotPath: String,
              private val outputPath: String,
              keyFile: String)
{
    private val background: ExecutorService = Executors.newSingleThreadExecutor()
    private val client: ImageAnnotatorClient

    init {
        background.submit { registerWatcher() }

        val settings = ImageAnnotatorSettings.newBuilder()
        settings.credentialsProvider = FixedCredentialsProvider.create(
                ServiceAccountCredentials.fromStream(java.io.FileInputStream(keyFile)))

        client = ImageAnnotatorClient.create(settings.build())
    }

    private fun runOCR(path: Path)
    {
        val data = Files.readAllBytes(path)
        val imageBytes = ByteString.copyFrom(data)
        val image = Image.newBuilder().setContent(imageBytes).build()
        val feature = Feature.newBuilder().setType(Feature.Type.TEXT_DETECTION).build()
        val request = AnnotateImageRequest.newBuilder().setImage(image).addFeatures(feature).build()

        val response = client.batchAnnotateImages(listOf(request))
        val resp = response.getResponses(0)

        if ( resp.textAnnotationsCount > 0 ) {
            val text = resp.textAnnotationsList.sortedByDescending { it.description.length }.first().description
            println(text)
        } else {
            println("No text found?")
        }
    }

    private fun registerWatcher()
    {
        // Screen Shot 2018-02-12 at 9.39.04 PM.png
        val regex = Regex("Screen Shot \\d{4}-\\d{2}-\\d{2} at \\d{1,2}\\.\\d{2}\\.\\d{2} (AM|PM)\\.png")

        val fs = FileSystems.getDefault()
        val cwd = fs.getPath(screenshotPath)
        val target = fs.getPath(outputPath)

        FileSystems.getDefault().newWatchService().use { watchSvc ->
            cwd.register(watchSvc, StandardWatchEventKinds.ENTRY_CREATE)
            while (true) {
                val wk = watchSvc.take()
                wk.pollEvents()
                        .map {
                            // we only register "ENTRY_MODIFY" so the context is always a Path.
                            it.context() as Path
                        }
                        .filter { it.fileName.toString().matches(regex) }
                        .forEach {
                            val moveFrom = cwd.resolve(it.fileName)
                            val moveTo = target.resolve(it.fileName)
                            println("Moving: $moveFrom -> $moveTo")
                            try {
                                Files.move(moveFrom, moveTo)
                                runOCR(moveTo)
                            } catch ( e: Exception ) {
                                e.printStackTrace()
                            }
                        }
                // reset the key
                val valid = wk.reset()
                if (!valid) {
                    println("Key has been unregistered")
                }
            }
        }
    }
}