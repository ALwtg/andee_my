package net.kuafuai.andee.golden

import net.kuafuai.andee.brain.LocalPrompt
import net.kuafuai.andee.net.ToolSchemas
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Byte-for-byte snapshots of the model-facing text, taken before the brain was
 * split into a shared module. The prompt and the tool descriptions are the
 * biggest cached prefix of every request and the model's whole instruction
 * manual; a refactor that changes one character of either changes behaviour,
 * and nothing else would notice. Regenerate deliberately with
 * `-Dgolden.update=true` and review the diff.
 */
class GoldenTextTest {

    private val dir = File("src/test/resources/golden")
    private val update = System.getProperty("golden.update") == "true"

    private fun check(name: String, actual: String) {
        val f = File(dir, name)
        if (update || !f.exists()) {
            dir.mkdirs()
            f.writeText(actual)
            return
        }
        assertEquals("$name drifted — regenerate only if the change is intended", f.readText(), actual)
    }

    @Test
    fun `system prompt`() = check("local_prompt.txt", LocalPrompt.TEXT)

    @Test
    fun `system prompt with persona`() =
        check("local_prompt_persona.txt", LocalPrompt.withPersona("PERSONA-SENTINEL"))

    @Test
    fun `tool schemas`() = check("tool_schemas.json", ToolSchemas.all().toString(2))

    @Test
    fun `hub tool schemas`() = check("tool_schemas_hub.json", ToolSchemas.forHub().toString(2))
}
