package xyz.jadonfowler.asmifier

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.ASMifier
import org.objectweb.asm.util.TraceClassVisitor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.PrintWriter
import javax.tools.ToolProvider

fun main(args: Array<String>) {
    Vertx.vertx().deployVerticle(ASMVerticle())
}

class ASMVerticle() : AbstractVerticle() {

    val get_html = File("static/get.html").readLines().joinToString("\n")
    val post_html = File("static/post.html").readLines().joinToString("\n")

    fun createRouter(): Router {
        val router = Router.router(vertx)

        // Used for forms
        router.route().handler(BodyHandler.create())

        router.get("/").handler { r ->
            r.response().end(get_html)
        }

        router.post("/").handler { r ->
            // Get values from form
            val className = r.request().getParam("className").replace('.', '/')
            val code = r.request().getParam("code")

            // Save code to file
            val codeFile = File("bin/$className.java")
            val classFile = File("bin/$className.class")
            if (!codeFile.exists()) {
                codeFile.parentFile.mkdirs()
                codeFile.createNewFile()
            }
            codeFile.printWriter().use { out -> out.write(code) }

            // Compile code
            val javac = ToolProvider.getSystemJavaCompiler()
            val outputStream = ByteArrayOutputStream()
            val errorStream = ByteArrayOutputStream()
            val rc = javac.run(null, outputStream, errorStream, "bin/$className.java", "-d", "bin/")

            var output: String

            if (rc == 0) {
                // ASMifier the class file`
                if (classFile.exists()) {
                    val cr = ClassReader(classFile.inputStream())
                    val asmStream = ByteArrayOutputStream()
                    cr.accept(TraceClassVisitor(null, ASMifier(), PrintWriter(asmStream)), 2)
                    var depth = 0
                    output = asmStream.toString().split("\n").map {
                        if (it.trim().endsWith("}")) depth--
                        val s = " ".repeat(depth * 4) + it
                        if (it.trim().endsWith("{")) depth++
                        s
                    }.joinToString("\n")
                    output = output.replace("<", "&lt")
                    output = output.replace(">", "&gt")
                } else output = "Couldn't find ${classFile.name}"
            } else {
                // javac returned with an error, return the output & error streams
                output = outputStream.toString() + "\n\n" + errorStream.toString()
            }

            // Remove files
            codeFile.delete()
            File("bin/$className.class").delete()

            // Build response
            val response = post_html
                    .replace("\$CLASS_NAME\$", className)
                    .replace("\$INPUT\$", code)
                    .replace("\$OUTPUT\$", output)

            r.response().end(response)
        }

        return router
    }

    override fun start(fut: Future<Void>) {
        val router = createRouter()
        val portValue = System.getenv("PORT")
        val port = if (portValue.isNullOrEmpty()) 8080 else portValue.toInt()
        vertx
                .createHttpServer()
                .requestHandler({ r -> router.accept(r) })
                .listen(port) { result ->
                    if (result.succeeded()) fut.complete()
                    else fut.fail(result.cause())
                }
        println("Server started: http://localhost:$port/")
    }

}
