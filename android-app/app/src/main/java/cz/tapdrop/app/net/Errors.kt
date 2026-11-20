package cz.tapdrop.app.net

enum class ErrorKind { WIFI_OFF, NOT_FOUND, BAD_TOKEN, NOT_PAIRED, WRONG_TAG, OTHER }

/** Chyba, kterou umíme uživateli srozumitelně vysvětlit česky. */
class TapError(val kind: ErrorKind, detail: String? = null) : Exception(detail ?: defaultText(kind)) {
    val title: String get() = defaultText(kind)

    companion object {
        fun defaultText(k: ErrorKind) = when (k) {
            ErrorKind.WIFI_OFF -> "WiFi vypnuta"
            ErrorKind.NOT_FOUND -> "Notebook nenalezen"
            ErrorKind.BAD_TOKEN -> "Špatný token"
            ErrorKind.NOT_PAIRED -> "Telefon není spárován"
            ErrorKind.WRONG_TAG -> "Tag patří jinému notebooku"
            ErrorKind.OTHER -> "Přenos se nezdařil"
        }

        fun hint(k: ErrorKind) = when (k) {
            ErrorKind.WIFI_OFF -> "Zapni WiFi a připoj se do stejné sítě jako notebook."
            ErrorKind.NOT_FOUND -> "Zkontroluj, že na notebooku běží TapDrop a že jste na stejné WiFi."
            ErrorKind.BAD_TOKEN -> "Spáruj telefon s notebookem znovu (token se mohl změnit)."
            ErrorKind.NOT_PAIRED -> "Nejdřív spáruj telefon s notebookem."
            ErrorKind.WRONG_TAG -> "Tento tag patří k jinému notebooku, než se kterým jsi spárovaný."
            ErrorKind.OTHER -> "Zkus to prosím znovu."
        }
    }
}

fun czFiles(n: Int) = when {
    n == 1 -> "1 soubor"
    n in 2..4 -> "$n soubory"
    else -> "$n souborů"
}

fun humanSize(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("kB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return String.format(java.util.Locale("cs"), "%.1f %s", v, u[i])
}
