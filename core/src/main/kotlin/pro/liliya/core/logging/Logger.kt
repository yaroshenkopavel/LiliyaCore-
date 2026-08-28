package pro.liliya.core.logging

interface Logger {
    fun trace(marker: String, message: String)
    fun debug(marker: String, message: String)
    fun info(marker: String, message: String)
    fun warn(marker: String, message: String)
    fun error(marker: String, message: String, throwable: Throwable? = null)
    fun fatal(marker: String, message: String, throwable: Throwable? = null)
}
