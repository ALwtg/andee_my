package net.kuafuai.andee.ui.ball

/** The look's name in the interface language. Lives here because the look's own data is shared with the desktop. */
fun net.kuafuai.andee.ui.ball.BallLook.label(ctx: android.content.Context): String = when (name) {
    "andee" -> net.kuafuai.andee.i18n.AppLocale.str(ctx, net.kuafuai.andee.R.string.ball_look_andee)
    "cream" -> net.kuafuai.andee.i18n.AppLocale.str(ctx, net.kuafuai.andee.R.string.ball_look_cream)
    "bajie" -> net.kuafuai.andee.i18n.AppLocale.str(ctx, net.kuafuai.andee.R.string.ball_look_bajie)
    "rabbit" -> net.kuafuai.andee.i18n.AppLocale.str(ctx, net.kuafuai.andee.R.string.ball_look_rabbit)
    "bear" -> net.kuafuai.andee.i18n.AppLocale.str(ctx, net.kuafuai.andee.R.string.ball_look_bear)
    "imp" -> net.kuafuai.andee.i18n.AppLocale.str(ctx, net.kuafuai.andee.R.string.ball_look_imp)
    else -> name
}
