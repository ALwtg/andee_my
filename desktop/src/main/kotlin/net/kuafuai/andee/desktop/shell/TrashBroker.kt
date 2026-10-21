package net.kuafuai.andee.desktop.shell

import net.kuafuai.andee.util.Log
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors

/**
 * Moves a sandboxed command's `rm` targets to the Trash on its behalf.
 *
 * The sandbox forbids the command from deleting files in a work folder — on
 * purpose, so `/bin/rm`, `find -delete` and a script's `os.remove` all fail.
 * The Trash move is itself an unlink of the original, so it has to be done by
 * Andee, outside the sandbox. The `rm` shim sends the absolute paths here, one
 * per line; this side checks every one of them again — inside a work folder,
 * not a credential path, and under the bulk limit — and only then moves them.
 * A command cannot talk this into deleting anything it could not name.
 */
class TrashBroker(private val workDirs: () -> List<File>, private val trashTool: () -> File) {

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "trash").apply { isDaemon = true } }
    private val sock = File(File(ShellTools.ShellRuntime.dir, "sock").apply { mkdirs() }, "trash.sock")

    val path: String get() = sock.path

    /** A line in the conversation for each move, so the user knows where to look. */
    @Volatile var onTrashed: (Int) -> Unit = {}

    fun start(): TrashBroker {
        sock.delete()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(sock.toPath()))
        sock.setReadable(true, true); sock.setWritable(true, true)
        pool.execute {
            while (server.isOpen) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { serve(c) }
            }
        }
        return this
    }

    /**
     * One path per line, then an empty line. Reading to end-of-stream instead
     * deadlocks: `nc` keeps its sending side open while it waits for the reply.
     */
    private fun serve(c: SocketChannel) = c.use {
        val r = Channels.newInputStream(c).bufferedReader()
        val lines = generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        val reply = runCatching { move(lines) }.getOrElse { "error: ${it.message}" }
        Channels.newOutputStream(c).apply { write((reply + "\n").toByteArray()); flush() }
    }

    internal fun move(paths: List<String>): String {
        if (paths.isEmpty()) return "ok 0"
        val roots = workDirs().map { it.canonicalFile }
        val files = paths.map { File(it).let { f -> if (f.isAbsolute) f else null } ?: return "error: not an absolute path: $it" }
        for (f in files) {
            val real = f.canonicalFile
            if (roots.none { real.path.startsWith(it.path + File.separator) }) {
                return "error: ${f.path} is outside the work folders; nothing was moved"
            }
            if (SandboxProfile.SECRET_PATHS.any { real.path.startsWith(it) }) return "error: ${f.path} is protected"
            if (!f.exists() && !java.nio.file.Files.isSymbolicLink(f.toPath())) return "error: ${f.path}: No such file or directory"
        }
        val count = files.sumOf { f -> f.walkTopDown().count() }
        val bytes = files.sumOf { f -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        if (count > TrashShims.MAX_FILES || bytes > TrashShims.MAX_BYTES) {
            return "error: refusing to delete $count files (${bytes / 1_048_576} MB) in one go — ask the user first, " +
                "then delete in smaller steps"
        }
        val p = ProcessBuilder(listOf(trashTool().path) + files.map { it.path }).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() != 0) return "error: $out"
        Log.i("Trash", "moved ${files.size} item(s) to the Trash")
        onTrashed(files.size)
        return "ok ${files.size}"
    }
}
