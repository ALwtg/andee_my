package net.kuafuai.andee.desktop.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The real thing, on this Mac: a sandboxed shell, the rm shim, the broker and
 * the Trash. Skipped where sandbox-exec is not available.
 */
class SandboxIntegrationTest {

    private fun sh(profile: String, cwd: File, env: Map<String, String>, cmd: String): Pair<Int, String> {
        val prof = File.createTempFile("andee", ".sb").apply { writeText(profile); deleteOnExit() }
        val pb = ProcessBuilder("/usr/bin/sandbox-exec", "-f", prof.path, "/bin/zsh", "-f", "-c", cmd)
            .directory(cwd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test
    fun `rm goes to the Trash through the broker, bin rm is stopped, outside paths are refused`() {
        assumeTrue(File("/usr/bin/sandbox-exec").exists())
        val home = System.getProperty("user.home")
        val work = Files.createTempDirectory(File(home).toPath(), "andee-it-").toFile()
        val tool = File(System.getProperty("andee.trashTool") ?: "build/native/andee-trash")
        assumeTrue("trash helper built", tool.canExecute())
        try {
            File(work, "a.txt").writeText("a")
            File(work, "b.txt").writeText("b")
            val broker = TrashBroker({ listOf(work) }, { tool }).start()
            val shims = TrashShims.install(Files.createTempDirectory("andee-shim").toFile(), broker.path)
            val tmp = Files.createTempDirectory("andee-tmp").toFile()
            val profile = SandboxProfile.build(listOf(work.path), emptyList(), tmp.path)
            val env = mapOf("PATH" to "${shims.path}:/usr/bin:/bin", "HOME" to home)

            val (rc1, out1) = sh(profile, work, env, "rm a.txt")
            assertEquals(out1, 0, rc1)
            assertFalse("a.txt moved away", File(work, "a.txt").exists())

            val (rc2, _) = sh(profile, work, env, "/bin/rm b.txt")
            assertTrue("/bin/rm must fail in the sandbox", rc2 != 0)
            assertTrue(File(work, "b.txt").exists())

            val (rc3, out3) = sh(profile, work, env, "rm $home/.zshrc")
            assertTrue(rc3 != 0)
            assertTrue(out3, out3.contains("outside the work folders"))
        } finally {
            work.deleteRecursively()
        }
    }
}
