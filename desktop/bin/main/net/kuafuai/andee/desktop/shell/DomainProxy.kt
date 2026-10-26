package net.kuafuai.andee.desktop.shell

import net.kuafuai.andee.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The only way out to the network for a sandboxed command: an HTTP proxy on
 * localhost that lets through the domains on the allow list and asks about the
 * rest. Plain HTTP requests and HTTPS CONNECT tunnels are both handled; the
 * proxy never sees inside TLS, it only decides by host name.
 *
 * Commands find it through HTTP_PROXY / HTTPS_PROXY / ALL_PROXY. A program that
 * ignores those cannot reach the network at all, because the sandbox allows
 * outbound connections to localhost only.
 */
class DomainProxy(
    /** Asked once per new host; true lets it through for this session. Called off the UI thread. */
    private val ask: (host: String) -> Boolean,
) {
    private val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "proxy").apply { isDaemon = true } }
    private val decided = ConcurrentHashMap<String, Boolean>()

    val port: Int get() = server.localPort

    /** Registries and code hosts a build needs, including the mirrors used in China. */
    private val defaults = listOf(
        "registry.npmjs.org", "registry.npmmirror.com", "registry.yarnpkg.com", "pypi.org", "files.pythonhosted.org",
        "pypi.tuna.tsinghua.edu.cn", "mirrors.aliyun.com", "maven.aliyun.com", "repo.maven.apache.org",
        "repo1.maven.org", "plugins.gradle.org", "services.gradle.org", "dl.google.com", "maven.google.com",
        "crates.io", "static.crates.io", "index.crates.io", "proxy.golang.org", "goproxy.cn", "sum.golang.org",
        "github.com", "api.github.com", "codeload.github.com", "objects.githubusercontent.com",
        "raw.githubusercontent.com", "gitlab.com", "gitee.com", "rubygems.org", "formulae.brew.sh", "ghcr.io",
    )

    init {
        pool.execute { acceptLoop() }
    }

    fun allowed(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (defaults.any { h == it || h.endsWith(".$it") }) return true
        return decided.getOrPut(h) { runCatching { ask(h) }.getOrDefault(false) }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val c = runCatching { server.accept() }.getOrNull() ?: continue
            pool.execute { handle(c) }
        }
    }

    private fun handle(client: Socket) = client.use {
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val firstLine = head.substringBefore("\r\n")
        val parts = firstLine.split(" ")
        if (parts.size < 3) return
        val (method, target) = parts[0] to parts[1]
        val (host, port) = if (method.equals("CONNECT", true)) {
            target.substringBeforeLast(':') to (target.substringAfterLast(':').toIntOrNull() ?: 443)
        } else {
            val u = runCatching { java.net.URI(target) }.getOrNull() ?: return
            (u.host ?: return) to (if (u.port > 0) u.port else 80)
        }
        if (!allowed(host)) {
            Log.i(TAG, "blocked $host")
            val msg = "Andee blocked $host: the user has not allowed this domain."
            client.getOutputStream().write(
                "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nContent-Length: ${msg.length}\r\nConnection: close\r\n\r\n$msg"
                    .toByteArray(),
            )
            return
        }
        val upstream = runCatching { Socket().apply { connect(InetSocketAddress(host, port), 15_000) } }.getOrElse {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        upstream.use { up ->
            if (method.equals("CONNECT", true)) {
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            } else {
                // Rewrite an absolute-form request line to origin-form for the server.
                val path = runCatching { java.net.URI(target).rawPath.ifEmpty { "/" } + (java.net.URI(target).rawQuery?.let { "?$it" } ?: "") }
                    .getOrDefault("/")
                up.getOutputStream().write((head.replaceFirst(target, path)).toByteArray(Charsets.ISO_8859_1))
            }
            val t = Thread { pipe(input, up.getOutputStream()) }.apply { isDaemon = true; start() }
            pipe(up.getInputStream(), client.getOutputStream())
            t.join(1000)
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 64 * 1024) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        runCatching {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        }
        runCatching { to.close() }
    }

    fun close() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private companion object {
        const val TAG = "Proxy"
    }
}
