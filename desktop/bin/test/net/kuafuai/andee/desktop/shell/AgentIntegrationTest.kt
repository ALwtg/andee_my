package net.kuafuai.andee.desktop.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The agent against the real OpenSSH client: `ssh-add -L` lists the key and
 * `ssh-keygen -Y sign` gets a signature that `ssh-keygen -Y verify` accepts.
 * No network, no server — just the agent protocol and Ed25519 being right.
 */
class AgentIntegrationTest {

    private fun run(env: Map<String, String>, vararg cmd: String, input: String? = null): Pair<Int, String> {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test
    fun `ssh-add lists the key and a signature verifies`() {
        assumeTrue(File("/usr/bin/ssh-add").exists())
        val dir = Files.createTempDirectory("andee-agent").toFile()
        try {
            System.setProperty("andee.agentTest", dir.path)
            val key = AndeeAgent.testKey("andee-test")
            val sock = AndeeAgent.startForTest(File(dir, "a.sock"), listOf(key))
            val env = mapOf("SSH_AUTH_SOCK" to sock)

            val (rc, listed) = run(env, "/usr/bin/ssh-add", "-L")
            assertEquals(listed, 0, rc)
            assertEquals(key.openSsh(), listed.trim())

            val pub = File(dir, "k.pub").apply { writeText(key.openSsh() + "\n") }
            val msg = File(dir, "m.txt").apply { writeText("hello") }
            val (rs, sOut) = run(env, "/usr/bin/ssh-keygen", "-Y", "sign", "-f", pub.path, "-n", "file", msg.path)
            assertEquals(sOut, 0, rs)
            val allowed = File(dir, "allowed").apply { writeText("me ${key.openSsh()}\n") }
            val (rv, vOut) = run(
                env, "/usr/bin/ssh-keygen", "-Y", "verify", "-f", allowed.path, "-I", "me", "-n", "file",
                "-s", "${msg.path}.sig", input = "hello",
            )
            assertEquals(vOut, 0, rv)
            assertTrue(vOut.contains("Good"))
        } finally {
            AndeeAgent.stopForTest()
            dir.deleteRecursively()
        }
    }
}
