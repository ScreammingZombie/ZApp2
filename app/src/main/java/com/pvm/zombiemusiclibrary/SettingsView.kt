package com.pvm.zombiemusiclibrary

import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Permissions, save / share and the battery tips for Xiaomi. */
class SettingsView(private val act: MainActivity, private val pal: Palette) : ScrollView(act) {

    private val box = LinearLayout(act)
    private val permText = TextView(act)
    private val permButton = ZButton(act, pal)

    init {
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(24))
        addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        title("Permisos")
        body("Para mostrar tu música la app necesita leer los archivos de audio. Para mostrar los controles en la pantalla bloqueada necesita poder mostrar notificaciones. No graba nada ni usa el micrófono.")
        permText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        permText.setTextColor(pal.fore)
        permText.setPadding(0, act.dp(6), 0, act.dp(6))
        box.addView(permText)
        permButton.text = "Dar permisos"
        permButton.accent = true
        permButton.setOnClickListener { act.askPermissions() }
        box.addView(permButton, lp(44))
        button("Abrir ajustes de la app", false) { act.openAppSettings() }

        title("Guardar y compartir")
        body("El archivo .zml es el mismo que usa el programa de la PC. Exportá la biblioteca del teléfono para verla en la PC. Para ver una biblioteca guardada usá el botón \"Cargar biblioteca\" de la pantalla principal.")
        button("Exportar biblioteca del teléfono (.zml)", true) { act.exportZml() }
        button("Exportar lista de bandas y discos (txt) - biblioteca", false) { act.exportTxt(false) }
        button("Exportar lista de bandas y discos (txt) - catálogo", false) { act.exportTxt(true) }

        title("Que no se corte la música (Xiaomi)")
        body("Los Xiaomi cierran las apps en segundo plano para ahorrar batería. Para que la música siga con la pantalla apagada:\n\n" +
            "1. Aceptá \"sin restricciones de batería\" (botón de abajo).\n" +
            "2. Ajustes > Aplicaciones > Administrar aplicaciones > Zombie Music Library > Ahorro de batería > Sin restricciones.\n" +
            "3. En esa misma pantalla, activá \"Inicio automático\".\n" +
            "4. Abrí la pantalla de apps recientes, mantené apretada la tarjeta de la app y tocá el candado para fijarla.")
        button("Pedir \"sin restricciones de batería\"", true) { act.askIgnoreBattery() }
        button("Abrir ajustes de batería", false) { act.openBatterySettings() }

        title("Acerca de")
        body("Zombie Music Library  -  by P.V.M.\nVersión 2.0")

        refreshPermissions()
    }

    private fun lp(h: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, act.dp(h))
        p.topMargin = act.dp(8)
        return p
    }

    private fun title(s: String) {
        val t = TextView(act)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        t.setTextColor(pal.accent)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, act.dp(22), 0, act.dp(4))
        box.addView(t)
    }

    private fun body(s: String) {
        val t = TextView(act)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.setTextColor(pal.dim)
        box.addView(t)
    }

    private fun button(text: String, accent: Boolean, click: () -> Unit) {
        val b = ZButton(act, pal)
        b.text = text
        b.accent = accent
        b.textSizeSp = 13f
        b.setOnClickListener { click() }
        box.addView(b, lp(44))
    }

    fun refreshPermissions() {
        val music = act.hasMediaPermission()
        val notif = act.hasNotificationPermission()
        permText.text = "Música:  " + (if (music) "permitido" else "FALTA el permiso") + "\nNotificaciones:  " + (if (notif) "permitido" else "falta el permiso")
        permButton.visibility = if (music && notif) View.GONE else View.VISIBLE
    }
}
