package net.kuafuai.andee.desktop.shell

import java.io.File

/**
 * `rm`, `rmdir` and `unlink` that move to the Trash instead, put first on the
 * command's PATH. They hand the absolute paths to Andee's [TrashBroker] over a
 * local socket, because the sandbox does not let the command delete work files
 * itself — not even to move them to the Trash.
 *
 * What they reach is the ordinary `rm x` a model types; `/bin/rm`, `find
 * -delete` or a script's own delete go past them and are stopped by the
 * sandbox's delete rule, which is the real guard. The broker re-checks every
 * path and the bulk limit, so the shim decides nothing on its own.
 */
object TrashShims {

    const val MAX_FILES = 50
    const val MAX_BYTES = 200L * 1024 * 1024

    /** Write the shims into [dir] and return it. [brokerSocket] is the [TrashBroker]'s socket. */
    fun install(dir: File, brokerSocket: String): File {
        dir.mkdirs()
        val body = """
            |#!/bin/sh
            |# Andee: deletions go to the Trash so they can be undone.
            |list=""
            |for a in "${'$'}@"; do
            |  case "${'$'}a" in
            |    --|-*) continue ;;
            |  esac
            |  case "${'$'}a" in
            |    /*) p="${'$'}a" ;;
            |    *) p="${'$'}PWD/${'$'}a" ;;
            |  esac
            |  list="${'$'}list${'$'}p
            |"
            |done
            |[ -z "${'$'}list" ] && exit 0
            |reply=${'$'}(printf '%s\n' "${'$'}list" | /usr/bin/nc -U "$brokerSocket" 2>&1)
            |case "${'$'}reply" in
            |  ok*) exit 0 ;;
            |  error:*) echo "rm: ${'$'}{reply#error: }" >&2; exit 1 ;;
            |  *) echo "rm: Andee did not answer (${'$'}reply); nothing was deleted" >&2; exit 1 ;;
            |esac
            |""".trimMargin()
        for (name in listOf("rm", "rmdir", "unlink")) {
            val f = File(dir, name)
            if (!f.exists() || f.readText() != body) {
                f.writeText(body)
                f.setExecutable(true)
            }
        }
        return dir
    }
}
